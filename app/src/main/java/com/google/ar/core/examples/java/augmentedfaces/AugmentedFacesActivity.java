package com.google.ar.core.examples.java.augmentedfaces;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.*;
import com.google.ar.core.examples.java.common.helpers.*;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.exceptions.*;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class AugmentedFacesActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = AugmentedFacesActivity.class.getSimpleName();

  private GLSurfaceView surfaceView;
  private boolean installRequested;
  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer noseObject = new ObjectRenderer();
  private final float[] noseMatrix = new float[16];
  private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(this);

    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    installRequested = false;
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        session = new Session(this, EnumSet.noneOf(Session.Feature.class));
        CameraConfigFilter cameraConfigFilter = new CameraConfigFilter(session);
        cameraConfigFilter.setFacingDirection(CameraConfig.FacingDirection.FRONT);
        List<CameraConfig> cameraConfigs = session.getSupportedCameraConfigs(cameraConfigFilter);
        if (!cameraConfigs.isEmpty()) {
          session.setCameraConfig(cameraConfigs.get(0));
        }

        configureSession();

      } catch (UnavailableArcoreNotInstalledException
               | UnavailableUserDeclinedInstallationException e) {
        messageSnackbarHelper.showError(this, "Please install ARCore");
        Log.e(TAG, "Exception creating session", e);
        return;
      } catch (UnavailableApkTooOldException e) {
        messageSnackbarHelper.showError(this, "Please update ARCore");
        return;
      } catch (UnavailableSdkTooOldException e) {
        messageSnackbarHelper.showError(this, "Please update this app");
        return;
      } catch (UnavailableDeviceNotCompatibleException e) {
        messageSnackbarHelper.showError(this, "This device does not support AR");
        return;
      } catch (Exception e) {
        messageSnackbarHelper.showError(this, "Failed to create AR session");
        Log.e(TAG, "Exception creating session", e);
        return;
      }
    }

    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    try {
      backgroundRenderer.createOnGlThread(this);
      noseObject.createOnGlThread(this, "models/glasses2.obj", "models/glasses2.png");
      noseObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f);
      noseObject.setBlendMode(ObjectRenderer.BlendMode.Shadow); // solid glasses
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) return;

    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      float[] projectionMatrix = new float[16];
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);

      float[] viewMatrix = new float[16];
      camera.getViewMatrix(viewMatrix, 0);

      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      backgroundRenderer.draw(frame);
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      Collection<AugmentedFace> faces = session.getAllTrackables(AugmentedFace.class);
      for (AugmentedFace face : faces) {
        if (face.getTrackingState() != TrackingState.TRACKING) continue;

        // --- Draw glasses ---
        Pose nosePose = face.getRegionPose(AugmentedFace.RegionType.NOSE_TIP);
        nosePose.toMatrix(noseMatrix, 0);

        float[] offsetMatrix = new float[16];
        Matrix.setIdentityM(offsetMatrix, 0);
        Matrix.translateM(offsetMatrix, 0,
                0.001f,   // X
                0.02f,    // Y
                -0.07f    // Z
        );

        float[] finalGlassesMatrix = new float[16];
        Matrix.multiplyMM(finalGlassesMatrix, 0, noseMatrix, 0, offsetMatrix, 0);

        float scaleFactorGlasses = 0.09f;

        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        noseObject.updateModelMatrix(finalGlassesMatrix, scaleFactorGlasses);
        noseObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, DEFAULT_COLOR);
      }
    } catch (Throwable t) {
      Log.e(TAG, "Exception on the OpenGL thread", t);
    } finally {
      GLES20.glDepthMask(true);
    }
  }

  private void configureSession() {
    Config config = new Config(session);
    config.setAugmentedFaceMode(Config.AugmentedFaceMode.MESH3D);
    session.configure(config);
  }
}
