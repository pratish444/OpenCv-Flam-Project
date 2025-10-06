package com.example.opencvflam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * CameraController - Manages Camera2 API for capturing YUV frames.
 *
 * Key design decisions:
 * - Uses Camera2 API (not deprecated Camera1)
 * - ImageReader with YUV_420_888 format for efficient processing
 * - Dedicated background thread for camera operations
 * - Converts YUV planes to NV21 format (Y plane + interleaved VU)
 * - Reuses byte array to minimize GC pressure
 *
 * Threading:
 * - All camera operations run on background "CameraThread"
 * - Frame callback delivers data on camera thread (caller must handle threading)
 */
class CameraController(
    private val context: Context,
    private val previewWidth: Int,
    private val previewHeight: Int,
    private val onFrameAvailable: (ByteArray, Int, Int) -> Unit
) {

    private val tag = "CameraController"

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    // Background thread for camera operations
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Reusable buffer for YUV data (avoid allocations each frame)
    // NV21 format: Y plane (width*height) + VU plane (width*height/2)
    private val nv21Buffer = ByteArray(previewWidth * previewHeight * 3 / 2)

    init {
        startBackgroundThread()
    }

    /**
     * Start dedicated background thread for camera operations.
     */
    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    /**
     * Stop background thread.
     */
    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "Thread interrupted", e)
        }
    }

    /**
     * Open camera and start preview session.
     *
     * Flow:
     * 1. Open back-facing camera
     * 2. Create ImageReader for YUV_420_888 frames
     * 3. Create capture session with ImageReader surface
     * 4. Start repeating request for continuous frames
     */
    fun startCamera() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            // Find back-facing camera
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                Log.e(tag, "No back-facing camera found")
                return
            }

            // Create ImageReader for YUV frames
            // maxImages=2: double buffering for smooth capture
            imageReader = ImageReader.newInstance(
                previewWidth,
                previewHeight,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener(imageAvailableListener, cameraHandler)
            }

            // Open camera device
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)

        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(tag, "Camera permission not granted", e)
        }
    }

    /**
     * Camera device state callback.
     */
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(tag, "Camera opened")
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(tag, "Camera disconnected")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(tag, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    /**
     * Create capture session and start repeating request.
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            // Create capture session with ImageReader surface
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingRequest(session, reader.surface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(tag, "Capture session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to create capture session", e)
        }
    }

    /**
     * Start repeating capture request for continuous preview.
     */
    private fun startRepeatingRequest(session: CameraCaptureSession, surface: Surface) {
        val camera = cameraDevice ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)

                // Set auto-focus and auto-exposure
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // Start repeating request (continuous capture)
            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
            Log.d(tag, "Started repeating capture request")

        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to start repeating request", e)
        }
    }

    /**
     * ImageReader callback - called when new frame is available.
     *
     * YUV_420_888 format handling:
     * - 3 planes: Y (luminance), U (Cb), V (Cr)
     * - We convert to NV21: Y plane followed by interleaved VU
     * - This is a common format that OpenCV can process efficiently
     *
     * Performance note:
     * - Reuses nv21Buffer to avoid allocations
     * - Direct ByteBuffer access for fast copy
     */
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        try {
            // Convert YUV_420_888 to NV21
            yuv420ToNV21(image, nv21Buffer)

            // Pass to native code (this is called on camera thread)
            onFrameAvailable(nv21Buffer, previewWidth, previewHeight)

        } finally {
            image.close()
        }
    }

    /**
     * Convert YUV_420_888 Image to NV21 byte array.
     *
     * NV21 layout:
     * - Y plane: width * height bytes
     * - VU plane: width * height / 2 bytes (interleaved V and U)
     *
     * YUV_420_888 can have padding/stride, so we must copy row by row.
     */
    private fun yuv420ToNV21(image: android.media.Image, out: ByteArray) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        var pos = 0

        // Copy Y plane (luminance)
        if (yPlane.pixelStride == 1) {
            // No padding, direct copy
            yBuffer.get(out, 0, ySize)
            pos = ySize
        } else {
            // Has stride, copy row by row
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            for (row in 0 until previewHeight) {
                for (col in 0 until previewWidth) {
                    out[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                }
            }
        }

        // Copy UV planes (chrominance) - interleave V and U for NV21
        // NV21 format: YYYYYYY... VUVUVU...
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (row in 0 until previewHeight / 2) {
            for (col in 0 until previewWidth / 2) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                out[pos++] = vBuffer.get(uvIndex)  // V first (NV21)
                out[pos++] = uBuffer.get(uvIndex)  // U second
            }
        }
    }

    /**
     * Stop camera preview.
     */
    fun stopCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to stop camera", e)
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopCamera()

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        stopBackgroundThread()
    }
}