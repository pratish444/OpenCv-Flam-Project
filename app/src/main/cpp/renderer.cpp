#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <memory>
#include <string>
#include <cstring>

#define LOG_TAG "Renderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declaration of processor functions
extern void processFrame(const uint8_t* nv21Data, int width, int height, uint8_t* rgbaOut);

/**
 * renderer.cpp - OpenGL ES 2.0 renderer for displaying processed camera frames.
 * 
 * Architecture:
 * - Manages OpenGL resources: shaders, texture, VBO
 * - Renders full-screen textured quad
 * - Receives RGBA data from processor and uploads to texture
 * 
 * Performance optimizations:
 * - Creates texture once in onSurfaceCreated, reuses with glTexSubImage2D
 * - Uses VBO for vertex data (though draw call is minimal)
 * - Simple shaders with no complex computations
 * - glPixelStorei(GL_UNPACK_ALIGNMENT, 1) for potentially unaligned data
 */

class Renderer {
public:
    Renderer(int previewWidth, int previewHeight)
            : previewWidth_(previewWidth)
            , previewHeight_(previewHeight)
            , program_(0)
            , texture_(0)
            , vbo_(0)
            , hasFrame_(false) {

        // Allocate buffer for processed RGBA data
        // This buffer is filled by processor.cpp
        rgbaBuffer_ = new uint8_t[previewWidth * previewHeight * 4];
    }

    ~Renderer() {
        // Clean up OpenGL resources
        if (program_ != 0) {
            glDeleteProgram(program_);
        }
        if (texture_ != 0) {
            glDeleteTextures(1, &texture_);
        }
        if (vbo_ != 0) {
            glDeleteBuffers(1, &vbo_);
        }

        delete[] rgbaBuffer_;
    }

    /**
     * Initialize OpenGL resources.
     * 
     * Called from GL thread when surface is created.
     * 
     * Tasks:
     * - Compile vertex and fragment shaders
     * - Link shader program
     * - Create texture object
     * - Create VBO for fullscreen quad
     */
    void onSurfaceCreated() {
        LOGI("onSurfaceCreated");

        // Compile shaders
        GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
        GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);

        // Link program
        program_ = glCreateProgram();
        glAttachShader(program_, vertexShader);
        glAttachShader(program_, fragmentShader);
        glLinkProgram(program_);

        // Check link status
        GLint linkStatus = 0;
        glGetProgramiv(program_, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLchar log[512];
            glGetProgramInfoLog(program_, 512, nullptr, log);
            LOGE("Shader link failed: %s", log);
        }

        // Clean up shaders (no longer needed after linking)
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        // Get attribute/uniform locations
        positionLoc_ = glGetAttribLocation(program_, "a_position");
        texCoordLoc_ = glGetAttribLocation(program_, "a_texCoord");
        textureLoc_ = glGetUniformLocation(program_, "u_texture");

        // Create texture
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Allocate texture storage (will be filled later)
        // Using GL_RGBA and GL_UNSIGNED_BYTE for processed camera data
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, previewWidth_, previewHeight_,
                     0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

        // Create VBO for fullscreen quad
        // Vertex format: (x, y, u, v) - position and texture coordinates
        GLfloat quadVertices[] = {
                // Position (x,y)  // TexCoord (u,v)
                -1.0f, -1.0f,      0.0f, 1.0f,  // Bottom-left
                1.0f, -1.0f,      1.0f, 1.0f,  // Bottom-right
                -1.0f,  1.0f,      0.0f, 0.0f,  // Top-left
                1.0f,  1.0f,      1.0f, 0.0f   // Top-right
        };

        glGenBuffers(1, &vbo_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);

        LOGI("OpenGL setup complete");
    }

    /**
     * Handle surface size change.
     * 
     * Called when screen orientation changes or view is resized.
     * Updates OpenGL viewport.
     */
    void onSurfaceChanged(int width, int height) {
        LOGI("onSurfaceChanged: %dx%d", width, height);
        screenWidth_ = width;
        screenHeight_ = height;
        glViewport(0, 0, width, height);
    }

    /**
     * Process camera frame with OpenCV and upload to texture.
     * 
     * Called from GL thread when new camera data arrives.
     * 
     * Flow:
     * 1. Receive NV21 YUV data
     * 2. Call processor (OpenCV) to convert and process to RGBA
     * 3. Upload RGBA to OpenGL texture using glTexSubImage2D
     * 
     * Performance note:
     * - glTexSubImage2D updates existing texture without reallocation
     * - Much faster than glTexImage2D for repeated updates
     * - GL_UNPACK_ALIGNMENT set to 1 for potentially unaligned data
     */
    void onCameraFrame(const uint8_t* nv21Data, int width, int height) {
        if (width != previewWidth_ || height != previewHeight_) {
            LOGE("Frame size mismatch: expected %dx%d, got %dx%d",
                 previewWidth_, previewHeight_, width, height);
            return;
        }

        // Process frame with OpenCV (YUV -> RGBA + effects)
        // Implemented in processor.cpp
        processFrame(nv21Data, width, height, rgbaBuffer_);

        // Upload to texture
        glBindTexture(GL_TEXTURE_2D, texture_);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);  // No alignment assumptions

        // glTexSubImage2D updates existing texture (faster than glTexImage2D)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, previewWidth_, previewHeight_,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgbaBuffer_);

        hasFrame_ = true;
    }

    /**
     * Render frame to screen.
     * 
     * Called every frame from GL thread.
     * 
     * Draws fullscreen textured quad with processed camera frame.
     */
    void onDrawFrame() {
        // Clear screen
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        // Skip if no frame received yet
        if (!hasFrame_) {
            return;
        }

        // Use shader program
        glUseProgram(program_);

        // Bind texture
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glUniform1i(textureLoc_, 0);

        // Bind VBO and set up vertex attributes
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);

        // Position attribute (x, y)
        glEnableVertexAttribArray(positionLoc_);
        glVertexAttribPointer(positionLoc_, 2, GL_FLOAT, GL_FALSE,
                              4 * sizeof(GLfloat), (void*)0);

        // Texture coordinate attribute (u, v)
        glEnableVertexAttribArray(texCoordLoc_);
        glVertexAttribPointer(texCoordLoc_, 2, GL_FLOAT, GL_FALSE,
                              4 * sizeof(GLfloat), (void*)(2 * sizeof(GLfloat)));

        // Draw fullscreen quad (2 triangles = 4 vertices as triangle strip)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Clean up
        glDisableVertexAttribArray(positionLoc_);
        glDisableVertexAttribArray(texCoordLoc_);
    }

private:
    /**
     * Compile shader from source.
     */
    GLuint compileShader(GLenum type, const char* source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);

        // Check compile status
        GLint compileStatus = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compileStatus);
        if (compileStatus != GL_TRUE) {
            GLchar log[512];
            glGetShaderInfoLog(shader, 512, nullptr, log);
            LOGE("Shader compilation failed: %s", log);
        }

        return shader;
    }

    // Simple vertex shader - pass through position and texture coordinates
    static constexpr const char* vertexShaderSource = R"(
        attribute vec4 a_position;
        attribute vec2 a_texCoord;
        varying vec2 v_texCoord;
        
        void main() {
            gl_Position = a_position;
            v_texCoord = a_texCoord;
        }
    )";

    // Simple fragment shader - sample texture
    static constexpr const char* fragmentShaderSource = R"(
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        
        void main() {
            gl_FragColor = texture2D(u_texture, v_texCoord);
        }
    )";

    int previewWidth_;
    int previewHeight_;
    int screenWidth_ = 0;
    int screenHeight_ = 0;

    GLuint program_;
    GLuint texture_;
    GLuint vbo_;

    GLint positionLoc_;
    GLint texCoordLoc_;
    GLint textureLoc_;

    uint8_t* rgbaBuffer_;  // Processed RGBA data
    bool hasFrame_;
};

/**
 * Factory function to create Renderer (called from native-lib.cpp).
 */
std::unique_ptr<Renderer> createRenderer(int previewWidth, int previewHeight) {
    return std::make_unique<Renderer>(previewWidth, previewHeight);
}