package com.example.opencvflam

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

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
    private var hasValidFrame = false  // Changed: tracks if we have ANY frame data

    // Callback for frame rendered (for FPS counting)
    private var onFrameRenderedCallback: (() -> Unit)? = null

    fun initialize(
        previewWidth: Int,
        previewHeight: Int,
        onFrameRendered: () -> Unit
    ) {
        Log.d(tag, "Initializing GLSurfaceView: ${previewWidth}x${previewHeight}")

        this.onFrameRenderedCallback = onFrameRendered

        // Allocate frame buffer
        frameBuffer = ByteArray(previewWidth * previewHeight * 3 / 2)
        frameWidth = previewWidth
        frameHeight = previewHeight

        // Configure GLSurfaceView
        setEGLContextClientVersion(2)
        setRenderer(NativeRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY

        // Initialize native renderer
        nativeHandle = nativeInit(previewWidth, previewHeight)
        Log.d(tag, "Native handle: $nativeHandle")
    }

    fun onCameraFrame(data: ByteArray, width: Int, height: Int) {
        frameLock.withLock {
            // Copy camera data to frame buffer
            if (frameBuffer != null && frameBuffer!!.size >= data.size) {
                System.arraycopy(data, 0, frameBuffer!!, 0, data.size)
                hasValidFrame = true  // Mark that we have valid data
            }
        }
        // No need to call requestRender() - continuous mode handles it
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0
        }
    }

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

        override fun onDrawFrame(gl: GL10?) {
            if (nativeHandle == 0L) return

            // FIXED: Always upload frame if we have valid data
            frameLock.withLock {
                if (frameBuffer != null && hasValidFrame) {
                    nativeOnCameraFrame(
                        nativeHandle,
                        frameBuffer!!,
                        frameWidth,
                        frameHeight
                    )
                }
            }

            // Render the current frame
            nativeOnDrawFrame(nativeHandle)

            // Notify frame rendered for FPS counting
            onFrameRenderedCallback?.invoke()
        }
    }

    // ========== JNI Native Methods ==========
    private external fun nativeInit(previewWidth: Int, previewHeight: Int): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeOnSurfaceCreated(handle: Long)
    private external fun nativeOnSurfaceChanged(handle: Long, width: Int, height: Int)
    private external fun nativeOnDrawFrame(handle: Long)
    private external fun nativeOnCameraFrame(
        handle: Long,
        data: ByteArray,
        width: Int,
        height: Int
    )
}