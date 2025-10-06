# CMakeLists.txt for OpenCV Camera native library
#
# This file configures the native C++ build for Android.
#
# Requirements:
# - CMake 3.18.1 or newer
# - NDK (e.g., 25.1.8937393)
# - OpenCV Android SDK (prebuilt libraries and headers)
#
# OpenCV Setup:
# Option 1: Place OpenCV SDK in project root
#   - Download OpenCV Android SDK from https://opencv.org/releases/
#   - Extract to <project>/opencv-android-sdk/
#   - Set OpenCV_DIR below to point to SDK
#
# Option 2: Place prebuilt .so files in jniLibs
#   - Copy libopencv_java4.so to app/src/main/jniLibs/<abi>/
#   - Copy OpenCV headers to app/src/main/cpp/include/opencv2/
#   - Adjust include_directories and library paths below

cmake_minimum_required(VERSION 3.18.1)

project("opencv-camera")

# Set C++ standard to C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags
# -O3: Maximum optimization for speed
# -fexceptions: Enable C++ exceptions
# -frtti: Enable Run-Time Type Information (required by OpenCV)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fexceptions -frtti")

# For debugging, you can add:
# set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -DDEBUG")

# ========== OpenCV Configuration ==========

# OPTION 1: Using OpenCV Android SDK
# Set OpenCV_DIR to your OpenCV SDK location
# Adjust this path to match your project structure
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/../../../opencv-android-sdk/sdk/native/jni)

# Include OpenCV headers
include_directories(${OpenCV_DIR}/include)

# Import prebuilt OpenCV library
# The actual .so file should be in opencv-android-sdk/sdk/native/libs/<ABI>/
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_java4.so)

# OPTION 2: Manual setup (if not using SDK structure)
# Uncomment and adjust these if you placed files manually:
#
# include_directories(${CMAKE_SOURCE_DIR}/include)
# add_library(opencv_java4 SHARED IMPORTED)
# set_target_properties(opencv_java4 PROPERTIES
#     IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_java4.so)

# ========== Native Library Configuration ==========

# Find Android system libraries
find_library(log-lib log)          # Logging
find_library(android-lib android)  # Android native API
find_library(egl-lib EGL)          # OpenGL EGL (context creation)
find_library(gles2-lib GLESv2)     # OpenGL ES 2.0

# Create native library from source files
add_library(native-lib SHARED
        native-lib.cpp      # JNI bridge
        renderer.cpp        # OpenGL ES renderer
        processor.cpp       # OpenCV processing
)

# Link libraries
target_link_libraries(native-lib
        opencv_java4        # OpenCV
        ${log-lib}          # Android logging
        ${android-lib}      # Android native API
        ${egl-lib}          # EGL
        ${gles2-lib}        # OpenGL ES 2.0
)

# ========== Build Configuration Notes ==========
#
# ABIs:
# - By default, builds for all ABIs specified in build.gradle (abiFilters)
# - Common ABIs: armeabi-v7a, arm64-v8a, x86, x86_64
# - For faster builds during development, limit to one ABI in build.gradle
#
# OpenCV Library:
# - libopencv_java4.so contains all OpenCV modules
# - Size: ~30-50 MB per ABI (can be reduced by custom OpenCV build)
# - Ensure .so files are present for all target ABIs
#
# Debugging:
# - If build fails with "opencv_java4 not found":
#   1. Check OpenCV_DIR path is correct
#   2. Verify libopencv_java4.so exists in jniLibs/<ABI>/
#   3. Check ANDROID_ABI matches your device/emulator
#
# - If runtime fails with "cannot locate symbol":
#   1. Ensure OpenCV library is packaged in APK (check APK analyzer)
#   2. Verify NDK version compatibility with OpenCV build
#   3. Check that -fexceptions and -frtti flags are set
#
# Performance:
# - -O3 flag enables aggressive optimization
# - For profiling, temporarily use -O0 -g flags
# - LTO (Link Time Optimization) can be added but increases build time:
#   set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -flto")
#   set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto")