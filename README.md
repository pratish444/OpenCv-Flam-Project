# OpenCV Real-Time Camera Processing for Android

A high-performance Android application that captures camera frames, processes them with OpenCV, and displays the results using OpenGL ES 2.0. This project demonstrates native C++ integration, Camera2 API usage, and real-time image processing on mobile devices.

![Android](https://img.shields.io/badge/Android-15+-green.svg)
![OpenCV](https://img.shields.io/badge/OpenCV-4.x-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple.svg)
![C++](https://img.shields.io/badge/C%2B%2B-17-orange.svg)

---

## 🎯 Features

- **Real-time Camera Processing**: Captures video frames at 30+ FPS using Android Camera2 API  
- **OpenCV Integration**: Processes frames with OpenCV's computer vision algorithms  
- **Native Performance**: Critical processing done in C++ using JNI for maximum speed  
- **OpenGL Rendering**: Hardware-accelerated display using OpenGL ES 2.0  
- **Multiple Effects**: Supports passthrough, grayscale, and Canny edge detection  
- **Optimized Pipeline**: Minimizes memory allocations and GC pressure for smooth performance  

---

## 📱 Screenshots

*(Add your app screenshots here showing the camera feed with different processing modes)*

---


---

## 🧩 Component Details

### **Kotlin Layer**
- **MainActivity**: Handles app lifecycle, permissions, and UI updates  
- **CameraController**: Manages Camera2 API for YUV frame capture  
- **GLSurfaceNativeView**: Custom GLSurfaceView that bridges camera frames to native code  

### **Native Layer (C++)**
- **Renderer**: OpenGL ES 2.0 rendering pipeline with shader management  
- **Processor**: OpenCV image processing (YUV→RGBA conversion, filters, edge detection)  
- **Native-lib**: JNI interface between Kotlin and C++  

---

## 🚀 Getting Started

### Prerequisites

1. **Android Studio**: Arctic Fox (2020.3.1) or later  
2. **Android SDK**: API 31+ (targeting API 36)  
3. **NDK**: r21+ (for C++ compilation)  
4. **CMake**: 3.18.1+ (for native build)  
5. **OpenCV Android SDK**: 4.x  

---

### OpenCV Setup

1. Download OpenCV Android SDK from [opencv.org](https://opencv.org/releases/)  
2. Extract the SDK to a location on your system  
3. Update `CMakeLists.txt` with the correct OpenCV path:

```cmake
set(OpenCV_DIR /path/to/OpenCV-android-sdk/sdk/native/jni)
```
### 📂 Project Structure
~~~
opencv-android-camera/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/                    # Native C++ code
│   │   │   │   ├── CMakeLists.txt      # Build configuration
│   │   │   │   ├── native-lib.cpp      # JNI interface
│   │   │   │   ├── renderer.cpp/.h     # OpenGL rendering
│   │   │   │   └── processor.cpp       # OpenCV processing
│   │   │   ├── java/com/example/opencvflam/
│   │   │   │   ├── MainActivity.kt     # Main activity
│   │   │   │   ├── CameraController.kt # Camera2 wrapper
│   │   │   │   └── GLSurfaceNativeView.kt  # OpenGL surface
│   │   │   ├── res/layout/activity_main.xml
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle
│   └── build.gradle
├── OpenCV-android-sdk/                 # Place OpenCV SDK here
└── README.md
~~~

### 📊 Performance Benchmarks

| Device      | Resolution | Mode        | FPS |
| ----------- | ---------- | ----------- | --- |
| Pixel 6 Pro | 640×480    | Passthrough | 60  |
| Pixel 6 Pro | 640×480    | Grayscale   | 48  |
| Pixel 6 Pro | 640×480    | Canny       | 32  |
| Samsung S21 | 1280×720   | Passthrough | 45  |
| OnePlus 9   | 640×480    | Canny       | 35  |

## 🐛 Troubleshooting

### 🖤 Black Screen / No Camera Feed

**Solutions:**
1. Check logcat for camera errors:  
   ```bash
   adb logcat | grep CameraController
2. Verify camera permission is granted in Settings

3. Restart the app after granting permission

4. Ensure no other app is currently using the camera

5. Try selecting a different camera resolution



   ### 🐢 Low FPS

**Try:**
1. Lower the camera resolution (e.g., **640×480**)
2. Switch to **MODE_PASSTHROUGH** for testing
3. Use a **Release build** instead of Debug
4. Check for **thermal throttling**
5. Close any **background apps** that may be using CPU or GPU resources

---

## 📖 Key Concepts

- **Camera2 API** – Modern Android camera control interface providing manual control and high-performance capture  
- **YUV_420_888** – Efficient YUV color format used by Android cameras; easily converted for OpenCV processing  
- **JNI (Java Native Interface)** – Bridge between Kotlin/Java and C++ for high-speed native code execution  
- **OpenGL ES 2.0** – Mobile graphics API used for hardware-accelerated rendering of camera frames






