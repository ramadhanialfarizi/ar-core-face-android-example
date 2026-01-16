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

/**
 * This activity demonstrates ARCore Augmented Faces API using only a glasses object.
 * The glasses follow the user's nose tip with proper positioning and scale.
 */
public class AugmentedFacesActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

  private static final String TAG = AugmentedFacesActivity.class.getSimpleName();

  // OpenGL surface view for rendering the AR content
  private GLSurfaceView surfaceView;

  // Flag to indicate whether ARCore installation was requested
  private boolean installRequested;

  // ARCore session
  private Session session;

  // Helpers for UI messages, rotation handling, and face tracking
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  // Background renderer for the camera feed
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

  // 3D glasses object to overlay on nose tip
  private final ObjectRenderer noseObject = new ObjectRenderer();

  // Matrix to store nose pose
  private final float[] noseMatrix = new float[16];

  // Default color array (used if needed)
  private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

  /** Called when the activity is first created */
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // Initialize GLSurfaceView
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(this);

    surfaceView.setPreserveEGLContextOnPause(true);  // Keep GL context across pause/resume
    surfaceView.setEGLContextClientVersion(2);       // OpenGL ES 2.0
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Use alpha channel for transparency
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY); // Constant rendering
    surfaceView.setWillNotDraw(false);

    installRequested = false; // ARCore install not requested yet
  }

  /** Called when the activity resumes */
  @Override
  protected void onResume() {
    super.onResume();

    // Create ARCore session if null
    if (session == null) {
      try {
        // Request ARCore installation if not installed
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // Request camera permission if not granted
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create session with front-facing camera
        session = new Session(this, EnumSet.noneOf(Session.Feature.class));
        CameraConfigFilter cameraConfigFilter = new CameraConfigFilter(session);
        cameraConfigFilter.setFacingDirection(CameraConfig.FacingDirection.FRONT);
        List<CameraConfig> cameraConfigs = session.getSupportedCameraConfigs(cameraConfigFilter);
        if (!cameraConfigs.isEmpty()) {
          session.setCameraConfig(cameraConfigs.get(0));
        }

        configureSession(); // Configure Augmented Faces

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

    // Resume AR session
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    // Resume GLSurfaceView and rotation helper
    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  /** Called when the activity is paused */
  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      displayRotationHelper.onPause(); // Stop rotation updates
      surfaceView.onPause();           // Pause GL rendering
      session.pause();                 // Pause AR session
    }
  }

  /** Called when the OpenGL surface is created */
  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f); // Set background color

    try {
      // Initialize background renderer
      backgroundRenderer.createOnGlThread(this);

      // Initialize 3D glasses model
      noseObject.createOnGlThread(this, "models/glasses2.obj", "models/glasses2.png");
      noseObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f);
      noseObject.setBlendMode(ObjectRenderer.BlendMode.Shadow); // Solid glasses (no transparency)

    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  /** Called when the OpenGL surface changes size */
  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height); // Adjust for rotation
    GLES20.glViewport(0, 0, width, height);               // Set viewport
  }

  /** Called for every frame to render AR content */
  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear color and depth buffers
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) return;

    // Update session with any display rotation changes
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      // Set camera texture for background
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Get latest AR frame
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Get projection and view matrices from camera
      float[] projectionMatrix = new float[16];
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);

      float[] viewMatrix = new float[16];
      camera.getViewMatrix(viewMatrix, 0);

      // Compute lighting for shading
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // Draw camera background
      backgroundRenderer.draw(frame);

      // Keep screen awake while face is tracked
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // Get all detected faces
      Collection<AugmentedFace> faces = session.getAllTrackables(AugmentedFace.class);
      for (AugmentedFace face : faces) {
        if (face.getTrackingState() != TrackingState.TRACKING) continue;

        // --- Draw glasses on nose tip ---
        Pose nosePose = face.getRegionPose(AugmentedFace.RegionType.NOSE_TIP);
        nosePose.toMatrix(noseMatrix, 0); // Convert pose to matrix

        // Apply local translation offset to position glasses correctly
        float[] offsetMatrix = new float[16];
        Matrix.setIdentityM(offsetMatrix, 0);
        Matrix.translateM(offsetMatrix, 0,
                0.001f,   // X offset (left/right)
                0.02f,    // Y offset (up)
                -0.07f    // Z offset (forward/back)
        );

        // Combine nose pose and offset
        float[] finalGlassesMatrix = new float[16];
        Matrix.multiplyMM(finalGlassesMatrix, 0, noseMatrix, 0, offsetMatrix, 0);

        float scaleFactorGlasses = 0.09f;

        // Enable depth testing and disable blending for solid glasses
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        // Update and draw glasses
        noseObject.updateModelMatrix(finalGlassesMatrix, scaleFactorGlasses);
        noseObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, DEFAULT_COLOR);
      }
    } catch (Throwable t) {
      Log.e(TAG, "Exception on the OpenGL thread", t);
    } finally {
      GLES20.glDepthMask(true); // Ensure depth mask restored
    }
  }

  /** Configure ARCore session to use Augmented Faces */
  private void configureSession() {
    Config config = new Config(session);
    config.setAugmentedFaceMode(Config.AugmentedFaceMode.MESH3D);
    session.configure(config);
  }
}
