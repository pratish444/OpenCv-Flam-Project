package com.example.opencvflam

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

/**
 * GLSurfaceNativeView - Custom GLSurfaceView that bridges camera frames to native OpenGL renderer.
 *
 * Architecture:
 * - Wraps GLSurfaceView with a custom Renderer
 * - Manages native renderer lifecycle (init, destroy)
 * - Thread-safe frame buffer for passing camera data to GL thread
 * - Uses RENDERMODE_WHEN_DIRTY + requestRender() to sync with camera frame rate
 *
 * Threading model:
 * - Camera thread: calls onCameraFrame() with YUV data
 * - GL thread: calls onDrawFrame() to process and render
 * - Synchronization: ReentrantLock protects shared frame buffer
 */
class GLSurfaceNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val tag = "GLSurfaceNativeView"

    // Native renderer handle (pointer to C++ object)
    private var nativeHandle: Long = 0

    // Frame buffer for thread-safe data passing
    private val frameLock = ReentrantLock()
    private var frameBuffer: ByteArray? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private val hasNewFrame = AtomicBoolean(false)

    // Callback for frame rendered (for FPS counting)
    private var onFrameRenderedCallback: (() -> Unit)? = null

    /**
     * Initialize GL surface and native renderer.
     *
     * @param previewWidth Camera preview width
     * @param previewHeight Camera preview height
     * @param onFrameRendered Callback invoked after each frame is rendered
     */
    fun initialize(
        previewWidth: Int,
        previewHeight: Int,
        onFrameRendered: () -> Unit
    ) {
        Log.d(tag, "Initializing GLSurfaceView: ${previewWidth}x${previewHeight}")

        this.onFrameRenderedCallback = onFrameRendered

        // Allocate frame buffer (camera thread will write, GL thread will read)
        // NV21 format: width * height * 3/2 bytes
        frameBuffer = ByteArray(previewWidth * previewHeight * 3 / 2)
        frameWidth = previewWidth
        frameHeight = previewHeight

        // Configure GLSurfaceView
        setEGLContextClientVersion(2)  // OpenGL ES 2.0
        setRenderer(NativeRenderer())
        renderMode = RENDERMODE_WHEN_DIRTY  // Render only when requestRender() is called

        // Initialize native renderer (creates C++ object)
        nativeHandle = nativeInit(previewWidth, previewHeight)
        Log.d(tag, "Native handle: $nativeHandle")
    }

    /**
     * Called by CameraController when new frame is available (camera thread).
     *
     * Thread safety:
     * - Copies data into frameBuffer under lock
     * - Sets hasNewFrame flag
     * - Calls requestRender() to trigger GL thread processing
     *
     * @param data NV21 YUV data from camera
     * @param width Frame width
     * @param height Frame height
     */
    fun onCameraFrame(data: ByteArray, width: Int, height: Int) {
        frameLock.withLock {
            // Copy camera data to frame buffer
            if (frameBuffer != null && frameBuffer!!.size >= data.size) {
                System.arraycopy(data, 0, frameBuffer!!, 0, data.size)
                hasNewFrame.set(true)
            }
        }

        // Request render on GL thread
        requestRender()
    }

    /**
     * Release native resources.
     */
    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0
        }
    }

    /**
     * Custom Renderer that delegates to native code.
     */
    private inner class NativeRenderer : Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Log.d(tag, "onSurfaceCreated")
            if (nativeHandle != 0L) {
                nativeOnSurfaceCreated(nativeHandle)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            Log.d(tag, "onSurfaceChanged: ${width}x${height}")
            if (nativeHandle != 0L) {
                nativeOnSurfaceChanged(nativeHandle, width, height)
            }
        }

        /**
         * Called on GL thread to render frame.
         *
         * Flow:
         * 1. Check if new camera frame is available
         * 2. If yes, pass frame data to native code (under lock)
         * 3. Native code processes with OpenCV and renders with OpenGL
         * 4. Invoke frame rendered callback for FPS counting
         */
        override fun onDrawFrame(gl: GL10?) {
            if (nativeHandle == 0L) return

            // Pass camera frame to native if available
            if (hasNewFrame.get()) {
                frameLock.withLock {
                    if (frameBuffer != null && hasNewFrame.get()) {
                        // Pass frame data to native (JNI call)
                        nativeOnCameraFrame(
                            nativeHandle,
                            frameBuffer!!,
                            frameWidth,
                            frameHeight
                        )
                        hasNewFrame.set(false)
                    }
                }
            }

            // Render frame (native code processes OpenCV + renders OpenGL)
            nativeOnDrawFrame(nativeHandle)

            // Notify frame rendered for FPS counting
            onFrameRenderedCallback?.invoke()
        }
    }

    // ========== JNI Native Methods ==========

    /**
     * Initialize native renderer.
     * @return Native handle (pointer to C++ Renderer object)
     */
    private external fun nativeInit(previewWidth: Int, previewHeight: Int): Long

    /**
     * Release native renderer.
     */
    private external fun nativeRelease(handle: Long)

    /**
     * Called when OpenGL surface is created (GL thread).
     */
    private external fun nativeOnSurfaceCreated(handle: Long)

    /**
     * Called when OpenGL surface size changes (GL thread).
     */
    private external fun nativeOnSurfaceChanged(handle: Long, width: Int, height: Int)

    /**
     * Called to render frame (GL thread).
     */
    private external fun nativeOnDrawFrame(handle: Long)

    /**
     * Pass camera frame to native code (GL thread).
     * Native code will process with OpenCV and upload to texture.
     */
    private external fun nativeOnCameraFrame(
        handle: Long,
        data: ByteArray,
        width: Int,
        height: Int
    )
}
