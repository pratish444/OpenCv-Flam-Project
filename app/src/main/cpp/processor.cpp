#include <opencv2/opencv.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>

#define LOG_TAG "Processor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * processor.cpp - OpenCV image processing pipeline.
 * 
 * Responsibilities:
 * - Convert NV21 YUV to RGBA using OpenCV
 * - Apply image processing effects (grayscale, Canny edge detection)
 * - Optimize performance by reusing cv::Mat objects
 * 
 * Performance considerations:
 * - Reuse static cv::Mat buffers to avoid repeated allocation
 * - Use cv::Mat wrapping pointers (no copy) where possible
 * - NV21 format: Y plane + interleaved VU (width*height + width*height/2 bytes)
 * - OpenCV conversion: COLOR_YUV2RGBA_NV21 (efficient native conversion)
 * 
 * Processing modes:
 * 1. Passthrough: YUV -> RGBA only
 * 2. Grayscale: YUV -> RGBA -> Gray -> RGBA (4-channel for texture compatibility)
 * 3. Canny edges: YUV -> RGBA -> Gray -> Canny -> RGBA
 * 
 * Change PROCESSING_MODE to switch effects.
 */

// Processing mode selection
enum ProcessingMode {
    MODE_PASSTHROUGH = 0,  // No processing, just convert YUV to RGBA
    MODE_GRAYSCALE = 1,    // Grayscale effect
    MODE_CANNY = 2         // Canny edge detection
};

// Set desired processing mode here
static const ProcessingMode PROCESSING_MODE = MODE_CANNY;

// Reusable cv::Mat buffers (thread-local storage for GL thread safety)
// These are allocated once and reused to avoid repeated allocations
thread_local static cv::Mat yuvMat;
thread_local static cv::Mat rgbaMat;
thread_local static cv::Mat grayMat;
thread_local static cv::Mat edgesMat;
thread_local static bool initialized = false;

/**
 * Initialize reusable cv::Mat buffers.
 * 
 * Called once per thread on first frame.
 * Preallocates matrices to avoid allocation overhead on each frame.
 */
static void initializeBuffers(int width, int height) {
    if (!initialized) {
        LOGI("Initializing OpenCV buffers: %dx%d", width, height);

        // Preallocate matrices
        // NV21 format: height + height/2 rows, 1 channel (Y + UV interleaved)
        yuvMat = cv::Mat(height + height / 2, width, CV_8UC1);
        rgbaMat = cv::Mat(height, width, CV_8UC4);
        grayMat = cv::Mat(height, width, CV_8UC1);
        edgesMat = cv::Mat(height, width, CV_8UC1);

        initialized = true;
        LOGI("OpenCV buffers initialized");
    }
}

/**
 * Process camera frame: NV21 YUV -> RGBA with optional effects.
 * 
 * Called from GL thread (via renderer.cpp).
 * 
 * @param nv21Data Input NV21 YUV data from camera
 * @param width Frame width
 * @param height Frame height
 * @param rgbaOut Output RGBA buffer (must be preallocated: width*height*4 bytes)
 * 
 * NV21 format layout:
 * - Bytes 0 to (width*height-1): Y plane (luminance)
 * - Bytes (width*height) to end: VU plane (interleaved V and U, width*height/2 bytes)
 * 
 * Performance optimization:
 * - Wraps input/output pointers in cv::Mat (no copy)
 * - Reuses preallocated intermediate buffers
 * - cvtColor uses optimized SIMD implementations when available
 */
void processFrame(const uint8_t* nv21Data, int width, int height, uint8_t* rgbaOut) {
    // Initialize buffers on first call
    initializeBuffers(width, height);

    try {
        // Wrap NV21 data in cv::Mat (no copy)
        // NV21 is stored as: height rows of Y + height/2 rows of interleaved VU
        cv::Mat yuvInput(height + height / 2, width, CV_8UC1, (void*)nv21Data);

        // Convert NV21 to RGBA
        // COLOR_YUV2RGBA_NV21: Y plane followed by VU interleaved
        cv::cvtColor(yuvInput, rgbaMat, cv::COLOR_YUV2RGBA_NV21);

        // Apply processing based on mode
        switch (PROCESSING_MODE) {
            case MODE_PASSTHROUGH:
                // No additional processing, rgbaMat is ready
                break;

            case MODE_GRAYSCALE: {
                // Convert to grayscale and back to RGBA (for 4-channel texture)
                cv::cvtColor(rgbaMat, grayMat, cv::COLOR_RGBA2GRAY);
                cv::cvtColor(grayMat, rgbaMat, cv::COLOR_GRAY2RGBA);
                break;
            }

            case MODE_CANNY: {
                // Canny edge detection
                // 1. Convert to grayscale
                cv::cvtColor(rgbaMat, grayMat, cv::COLOR_RGBA2GRAY);

                // 2. Apply Canny edge detector
                // Parameters: low threshold = 80, high threshold = 160
                // Lower thresholds = more edges, higher = fewer edges
                cv::Canny(grayMat, edgesMat, 80, 160);

                // 3. Convert back to RGBA (edges are white on black)
                cv::cvtColor(edgesMat, rgbaMat, cv::COLOR_GRAY2RGBA);
                break;
            }
        }

        // Copy result to output buffer
        // rgbaMat.data points to RGBA pixels (width*height*4 bytes)
        std::memcpy(rgbaOut, rgbaMat.data, width * height * 4);

    } catch (const cv::Exception& e) {
        LOGE("OpenCV exception: %s", e.what());
    } catch (const std::exception& e) {
        LOGE("Processing exception: %s", e.what());
    }
}

/**
 * Alternative processing functions (can be exposed via JNI if needed).
 * 
 * These demonstrate other OpenCV operations that could be useful:
 */

/**
 * Apply Gaussian blur (smoothing).
 */
void applyGaussianBlur(cv::Mat& input, cv::Mat& output, int kernelSize = 5) {
    cv::GaussianBlur(input, output, cv::Size(kernelSize, kernelSize), 0);
}

/**
 * Apply bilateral filter (edge-preserving smoothing).
 */
void applyBilateralFilter(cv::Mat& input, cv::Mat& output) {
    cv::bilateralFilter(input, output, 9, 75, 75);
}

/**
 * Adjust brightness and contrast.
 */
void adjustBrightnessContrast(cv::Mat& input, cv::Mat& output,
                              double alpha = 1.0, int beta = 0) {
    input.convertTo(output, -1, alpha, beta);
    // alpha: contrast (1.0 = no change, >1.0 = more contrast)
    // beta: brightness (0 = no change, positive = brighter)
}

/**
 * Detect faces using Haar cascade (requires loading cascade file).
 * Note: This is an example - would need cascade XML file loaded at init.
 */
/*
void detectFaces(cv::Mat& input, std::vector<cv::Rect>& faces) {
    static cv::CascadeClassifier faceCascade;
    static bool cascadeLoaded = false;
    
    if (!cascadeLoaded) {
        // Load Haar cascade from assets (path needs to be provided)
        std::string cascadePath = "/path/to/haarcascade_frontalface_default.xml";
        cascadeLoaded = faceCascade.load(cascadePath);
    }
    
    if (cascadeLoaded) {
        cv::Mat gray;
        cv::cvtColor(input, gray, cv::COLOR_RGBA2GRAY);
        faceCascade.detectMultiScale(gray, faces, 1.1, 3, 0, cv::Size(30, 30));
    }
}
*/

/**
 * Performance tips:
 * 
 * 1. Lower resolution for better FPS:
 *    - Change camera preview to 640x480 instead of 1280x720
 * 
 * 2. Reduce processing complexity:
 *    - MODE_PASSTHROUGH is fastest (just YUV conversion)
 *    - MODE_GRAYSCALE adds one extra conversion
 *    - MODE_CANNY adds grayscale + Canny (more expensive)
 * 
 * 3. Optimize Canny parameters:
 *    - Higher thresholds = fewer edges = faster
 *    - cv::Canny(input, output, 100, 200) vs (50, 150)
 * 
 * 4. Use cv::resize() to downsample before processing:
 *    cv::resize(input, smaller, cv::Size(width/2, height/2));
 *    // Process smaller image
 *    cv::resize(result, output, cv::Size(width, height));
 * 
 * 5. Skip frames if FPS too low:
 *    static int frameCounter = 0;
 *    if (++frameCounter % 2 == 0) return; // Process every other frame
 * 
 * 6. Profile with Android Profiler to find bottlenecks:
 *    - Is bottleneck in OpenCV processing?
 *    - Is bottleneck in glTexSubImage2D upload?
 *    - Is bottleneck in camera pipeline?
 */