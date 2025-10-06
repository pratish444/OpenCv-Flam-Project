#include <jni.h>
#include <android/log.h>
#include <memory>
#include <cstring>
#include <exception>
#include "renderer.h"

#define LOG_TAG "NativeLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jint previewWidth,
        jint previewHeight) {

    LOGI("=== nativeInit START: %dx%d ===", previewWidth, previewHeight);

    try {
        LOGI("Creating Renderer...");
        Renderer* renderer = new Renderer(previewWidth, previewHeight);
        LOGI("Renderer created successfully, handle: %p", renderer);
        return reinterpret_cast<jlong>(renderer);
    } catch (const std::exception& e) {
        LOGE("nativeInit failed with exception: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("nativeInit failed with unknown exception");
        return 0;
    }
}

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

JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnSurfaceCreated(
        JNIEnv* env,
        jobject /* this */,
        jlong handle) {

    LOGI("=== nativeOnSurfaceCreated called ===");

    if (handle == 0) {
        LOGE("nativeOnSurfaceCreated: invalid handle");
        return;
    }
    auto* renderer = reinterpret_cast<Renderer*>(handle);

    try {
        renderer->onSurfaceCreated();
        LOGI("=== nativeOnSurfaceCreated complete ===");
    } catch (const std::exception& e) {
        LOGE("onSurfaceCreated failed: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnSurfaceChanged(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jint width,
        jint height) {

    LOGI("=== nativeOnSurfaceChanged: %dx%d ===", width, height);

    if (handle == 0) {
        LOGE("nativeOnSurfaceChanged: invalid handle");
        return;
    }
    auto* renderer = reinterpret_cast<Renderer*>(handle);

    try {
        renderer->onSurfaceChanged(width, height);
        LOGI("=== nativeOnSurfaceChanged complete ===");
    } catch (const std::exception& e) {
        LOGE("onSurfaceChanged failed: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnCameraFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jbyteArray data,
        jint width,
        jint height) {

    // Log EVERY frame to see if we're getting them
    static int frameCount = 0;
    frameCount++;

    // Log every single frame for debugging
    LOGI(">>> Camera frame %d: %dx%d <<<", frameCount, width, height);

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

    jsize arrayLength = env->GetArrayLength(data);
    LOGI("Frame data size: %d bytes (expected: %d)", arrayLength, width * height * 3 / 2);

    try {
        renderer->onCameraFrame(reinterpret_cast<uint8_t*>(dataPtr), width, height);
        LOGI("Frame %d processed successfully", frameCount);
    } catch (const std::exception& e) {
        LOGE("onCameraFrame failed: %s", e.what());
    }

    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_opencvflam_GLSurfaceNativeView_nativeOnDrawFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong handle) {

    static int drawCount = 0;
    if (++drawCount % 30 == 0) {
        LOGI("=== onDrawFrame %d ===", drawCount);
    }

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