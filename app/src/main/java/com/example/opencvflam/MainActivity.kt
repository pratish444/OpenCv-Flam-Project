package com.example.opencvflam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * MainActivity - Entry point of the OpenCV Camera app.
 *
 * Responsibilities:
 * - Request CAMERA permission at runtime (required for Android 6.0+)
 * - Initialize GLSurfaceNativeView (OpenGL rendering surface)
 * - Initialize CameraController (Camera2 API wrapper)
 * - Update FPS and resolution overlays on UI thread
 *
 * Threading model:
 * - UI thread: handles permission, lifecycle, UI updates
 * - Camera thread: managed by CameraController (background handler)
 * - GL thread: managed by GLSurfaceView, calls native render methods
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceNativeView
    private lateinit var cameraController: CameraController
    private lateinit var tvFps: TextView
    private lateinit var tvResolution: TextView
    private lateinit var tvPermissionHint: TextView

    private var frameCount = 0
    private var lastFpsUpdateTime = 0L

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100

        // Load native library
        init {
            System.loadLibrary("native-lib")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        glSurfaceView = findViewById(R.id.gl_surface_view)
        tvFps = findViewById(R.id.tv_fps)
        tvResolution = findViewById(R.id.tv_resolution)
        tvPermissionHint = findViewById(R.id.tv_permissions_hint)

        // Check camera permission
        if (hasCameraPermission()) {
            initializeCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        tvPermissionHint.visibility = TextView.VISIBLE
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tvPermissionHint.visibility = TextView.GONE
                initializeCamera()
            } else {
                tvPermissionHint.text = "Camera permission denied. Cannot start camera."
            }
        }
    }

    /**
     * Initialize camera and connect it to the native renderer.
     *
     * Flow:
     * 1. GLSurfaceNativeView sets up OpenGL context and creates native renderer
     * 2. CameraController opens camera and starts streaming YUV frames
     * 3. Each camera frame is passed to native code via JNI
     * 4. Native code processes with OpenCV and renders with OpenGL
     */
    private fun initializeCamera() {
        // Initialize GL surface first (creates native renderer)
        glSurfaceView.initialize(
            previewWidth = 1280,  // Can adjust: 640x480 for better performance
            previewHeight = 720,
            onFrameRendered = { onFrameRendered() }
        )

        // Initialize camera controller
        cameraController = CameraController(
            context = this,
            previewWidth = 1280,
            previewHeight = 720,
            onFrameAvailable = { data, width, height ->
                // Pass camera frame to native code (called on camera thread)
                glSurfaceView.onCameraFrame(data, width, height)
            }
        )

        // Update resolution display
        runOnUiThread {
            tvResolution.text = "Res: 1280 x 720"
        }
    }

    /**
     * Called after each frame is rendered (from GL thread via callback).
     * Updates FPS counter every ~1 second.
     */
    private fun onFrameRendered() {
        frameCount++

        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFpsUpdateTime

        // Update FPS every second
        if (elapsed >= 1000) {
            val fps = (frameCount * 1000.0 / elapsed).toInt()
            runOnUiThread {
                tvFps.text = "FPS: $fps"
            }
            frameCount = 0
            lastFpsUpdateTime = currentTime
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        if (hasCameraPermission() && ::cameraController.isInitialized) {
            cameraController.startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraController.isInitialized) {
            cameraController.stopCamera()
        }
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraController.isInitialized) {
            cameraController.release()
        }
        glSurfaceView.release()
    }
}