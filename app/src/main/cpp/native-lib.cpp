#include <jni.h>
#include <android/log.h>
#include <memory>
#include <cstring>
#include <exception>

// Forward declare Renderer and factory
class Renderer;
extern std::unique_ptr<Renderer> createRenderer(int previewWidth, int previewHeight);

#define LOG_TAG "NativeLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Initialize native renderer.
 */
JNIEXPORT jlong JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jint previewWidth,
        jint previewHeight) {

    LOGI("nativeInit: %dx%d", previewWidth, previewHeight);

    try {
        auto renderer = createRenderer(previewWidth, previewHeight);
        return reinterpret_cast<jlong>(renderer.release());
    } catch (const std::exception& e) {
        LOGE("nativeInit failed: %s", e.what());
        return 0;
    }
}

/**
 * Release renderer.
 */
JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeRelease(
        JNIEnv* env,
        jobject /* this */,
        jlong handle) {

    LOGI("nativeRelease");

    if (handle != 0) {
        auto* renderer = reinterpret_cast<Renderer*>(handle);
        delete renderer;
    }
}

/**
 * Surface created.
 */
JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnSurfaceCreated(
        JNIEnv* env,
        jobject /* this */,
        jlong handle) {

    if (handle == 0) {
        LOGE("nativeOnSurfaceCreated: invalid handle");
        return;
    }
    auto* renderer = reinterpret_cast<Renderer*>(handle);

    try {
        renderer->onSurfaceCreated();
    } catch (const std::exception& e) {
        LOGE("onSurfaceCreated failed: %s", e.what());
    }
}

/**
 * Surface changed.
 */
JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnSurfaceChanged(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jint width,
        jint height) {

    if (handle == 0) {
        LOGE("nativeOnSurfaceChanged: invalid handle");
        return;
    }
    auto* renderer = reinterpret_cast<Renderer*>(handle);

    try {
        renderer->onSurfaceChanged(width, height);
    } catch (const std::exception& e) {
        LOGE("onSurfaceChanged failed: %s", e.what());
    }
}

/**
 * Pass camera frame.
 */
JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnCameraFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jbyteArray data,
        jint width,
        jint height) {

    if (handle == 0) {
        LOGE("nativeOnCameraFrame: invalid handle");
        return;
    }

    auto* renderer = reinterpret_cast<Renderer*>(handle);

    jboolean isCopy = JNI_FALSE;
    jbyte* dataPtr = env->GetByteArrayElements(data, &isCopy);
    if (dataPtr == nullptr) {
        LOGE("nativeOnCameraFrame: failed to get byte array");
        return;
    }

    try {
        renderer->onCameraFrame(reinterpret_cast<uint8_t*>(dataPtr), width, height);
    } catch (const std::exception& e) {
        LOGE("onCameraFrame failed: %s", e.what());
    }

    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

/**
 * Draw frame.
 */
JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnDrawFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong handle) {

    if (handle == 0) {
        return;
    }
    auto* renderer = reinterpret_cast<Renderer*>(handle);

    try {
        renderer->onDrawFrame();
    } catch (const std::exception& e) {
        LOGE("onDrawFrame failed: %s", e.what());
    }
}

} // extern "C"