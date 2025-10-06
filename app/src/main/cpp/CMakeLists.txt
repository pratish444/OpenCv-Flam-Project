cmake_minimum_required(VERSION 3.18.1)

project("opencv-camera")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fexceptions -frtti")

# Important: Use shared C++ library
set(ANDROID_STL c++_shared)

# ========== 16KB Page Alignment Fix ==========
# Required for Android 15+ devices
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ========== OpenCV Configuration ==========
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/../../../OpenCV-android-sdk/sdk/native/jni)

include_directories(${OpenCV_DIR}/include)

add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../../../OpenCV-android-sdk/sdk/native/libs/${ANDROID_ABI}/libopencv_java4.so)

# ========== Native Library Configuration ==========
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles2-lib GLESv2)

add_library(native-lib SHARED
        native-lib.cpp
        renderer.cpp
        processor.cpp
)

target_link_libraries(native-lib
        opencv_java4
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${gles2-lib}
)