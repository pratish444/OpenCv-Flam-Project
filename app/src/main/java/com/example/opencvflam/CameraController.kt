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

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Store actual camera resolution (may differ from requested)
    private var actualWidth = previewWidth
    private var actualHeight = previewHeight
    private lateinit var nv21Buffer: ByteArray

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

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

    fun startCamera() {
        // IMPORTANT: Stop any existing camera session first
        stopCamera()

        // Small delay to ensure cleanup completes
        cameraHandler?.postDelayed({
            openCamera()
        }, 100)
    }

    private fun openCamera() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                Log.e(tag, "No back-facing camera found")
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)

            Log.d(tag, "Supported camera resolutions:")
            sizes?.forEach { size ->
                Log.d(tag, "  ${size.width}x${size.height}")
            }

            // Find closest supported size
            val targetSize = sizes?.minByOrNull { size ->
                Math.abs(size.width * size.height - (previewWidth * previewHeight))
            } ?: android.util.Size(640, 480)

            // Store actual dimensions
            actualWidth = targetSize.width
            actualHeight = targetSize.height

            // Create buffer with ACTUAL dimensions
            nv21Buffer = ByteArray(actualWidth * actualHeight * 3 / 2)

            Log.d(tag, "Using camera resolution: ${actualWidth}x${actualHeight}")

            // Create ImageReader with actual resolution
            imageReader = ImageReader.newInstance(
                actualWidth,
                actualHeight,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener(imageAvailableListener, cameraHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)

            Log.d(tag, "Creating ImageReader with ${actualWidth}x${actualHeight}")

            imageReader = ImageReader.newInstance(
                actualWidth,
                actualHeight,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                Log.d(tag, "ImageReader created successfully")
                Log.d(tag, "Setting onImageAvailableListener...")
                setOnImageAvailableListener(imageAvailableListener, cameraHandler)
                Log.d(tag, "Listener set, handler: $cameraHandler")
            }

            Log.d(tag, "Opening camera...")
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)

        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(tag, "Camera permission not granted", e)
        }
    }

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

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        Log.d(tag, "Creating capture session...")
        Log.d(tag, "ImageReader surface: ${reader.surface}")
        Log.d(tag, "ImageReader surface valid: ${reader.surface.isValid}")

        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(tag, "Capture session configured!")
                        captureSession = session
                        startRepeatingRequest(session, reader.surface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(tag, "Capture session configuration FAILED!")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(tag, "Exception creating capture session", e)
        }
    }
    private fun startRepeatingRequest(session: CameraCaptureSession, surface: Surface) {
        val camera = cameraDevice ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
            Log.d(tag, "Started repeating capture request")

        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to start repeating request", e)
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        Log.d(tag, "!!! IMAGE AVAILABLE CALLBACK FIRED !!!")

        val image = reader.acquireLatestImage()
        if (image == null) {
            Log.e(tag, "acquireLatestImage returned NULL")
            return@OnImageAvailableListener
        }

        Log.d(tag, "Got image: ${image.width}x${image.height}, format: ${image.format}")

        try {
            yuv420ToNV21(image, nv21Buffer)
            Log.d(tag, "YUV conversion complete, calling onFrameAvailable")
            onFrameAvailable(nv21Buffer, actualWidth, actualHeight)
            Log.d(tag, "onFrameAvailable called successfully")
        } catch (e: Exception) {
            Log.e(tag, "Exception in imageAvailableListener", e)
        } finally {
            image.close()
        }
    }
    private fun yuv420ToNV21(image: android.media.Image, out: ByteArray) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var pos = 0

        // Copy Y plane
        if (yPlane.pixelStride == 1) {
            val ySize = yBuffer.remaining()
            yBuffer.get(out, 0, ySize)
            pos = ySize
        } else {
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            for (row in 0 until actualHeight) {
                for (col in 0 until actualWidth) {
                    out[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                }
            }
        }

        // Copy UV planes
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (row in 0 until actualHeight / 2) {
            for (col in 0 until actualWidth / 2) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                out[pos++] = vBuffer.get(uvIndex)
                out[pos++] = uBuffer.get(uvIndex)
            }
        }
    }

    fun stopCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to stop camera", e)
        }
    }

    fun release() {
        stopCamera()
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }
}