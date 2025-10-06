package com.example.opencvflam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
    private var isInitialized = false  // ADD THIS FLAG

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100

        init {
            System.loadLibrary("native-lib")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.gl_surface_view)
        tvFps = findViewById(R.id.tv_fps)
        tvResolution = findViewById(R.id.tv_resolution)
        tvPermissionHint = findViewById(R.id.tv_permissions_hint)

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
                // ONLY initialize if not already done
                if (!isInitialized) {
                    initializeCamera()
                }
            } else {
                tvPermissionHint.text = "Camera permission denied. Cannot start camera."
            }
        }
    }

    private fun initializeCamera() {
        if (isInitialized) {
            Log.d("MainActivity", "Already initialized, skipping")
            return
        }

        isInitialized = true
        Log.d("MainActivity", "Initializing camera...")

        glSurfaceView.initialize(
            previewWidth = 640,
            previewHeight = 480,
            onFrameRendered = { onFrameRendered() }
        )

        cameraController = CameraController(
            context = this,
            previewWidth = 640,
            previewHeight = 480,
            onFrameAvailable = { data, width, height ->
                Log.d("MainActivity", "!!! FRAME RECEIVED: ${width}x${height} !!!")
                glSurfaceView.onCameraFrame(data, width, height)
            }
        )

        // DON'T start camera here - wait for onResume
        runOnUiThread {
            tvResolution.text = "Res: 640x480"
        }
    }

    private fun onFrameRendered() {
        frameCount++
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFpsUpdateTime

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

        // Start camera with delay if already initialized
        if (isInitialized) {
            glSurfaceView.postDelayed({
                if (hasCameraPermission() && ::cameraController.isInitialized) {
                    Log.d("MainActivity", "Starting camera...")
                    cameraController.startCamera()
                }
            }, 500)
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