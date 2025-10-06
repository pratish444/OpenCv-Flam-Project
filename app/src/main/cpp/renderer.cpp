#include "renderer.h"
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

// Shader sources
static constexpr const char* vertexShaderSource = R"(
    attribute vec4 a_position;
    attribute vec2 a_texCoord;
    varying vec2 v_texCoord;

    void main() {
        gl_Position = a_position;
        v_texCoord = a_texCoord;
    }
)";

static constexpr const char* fragmentShaderSource = R"(
    precision mediump float;
    varying vec2 v_texCoord;
    uniform sampler2D u_texture;

    void main() {
        gl_FragColor = texture2D(u_texture, v_texCoord);
    }
)";

// Private helper function for shader compilation
static GLuint compileShader(GLenum type, const char* source) {
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

// Private implementation structure
struct RendererImpl {
    int previewWidth;
    int previewHeight;
    int screenWidth = 0;
    int screenHeight = 0;

    GLuint program = 0;
    GLuint texture = 0;
    GLuint vbo = 0;

    GLint positionLoc;
    GLint texCoordLoc;
    GLint textureLoc;

    uint8_t* rgbaBuffer = nullptr;
    bool hasFrame = false;
};

// Constructor
Renderer::Renderer(int previewWidth, int previewHeight) {
    impl_ = new RendererImpl();
    impl_->previewWidth = previewWidth;
    impl_->previewHeight = previewHeight;
    impl_->rgbaBuffer = new uint8_t[previewWidth * previewHeight * 4];

    LOGI("Renderer created: %dx%d", previewWidth, previewHeight);
}

// Destructor
Renderer::~Renderer() {
    if (impl_) {
        // Clean up OpenGL resources
        if (impl_->program != 0) {
            glDeleteProgram(impl_->program);
        }
        if (impl_->texture != 0) {
            glDeleteTextures(1, &impl_->texture);
        }
        if (impl_->vbo != 0) {
            glDeleteBuffers(1, &impl_->vbo);
        }

        delete[] impl_->rgbaBuffer;
        delete impl_;
    }

    LOGI("Renderer destroyed");
}

void Renderer::onSurfaceCreated() {
    LOGI("onSurfaceCreated");

    // Compile shaders
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);

    // Link program
    impl_->program = glCreateProgram();
    glAttachShader(impl_->program, vertexShader);
    glAttachShader(impl_->program, fragmentShader);
    glLinkProgram(impl_->program);

    // Check link status
    GLint linkStatus = 0;
    glGetProgramiv(impl_->program, GL_LINK_STATUS, &linkStatus);
    if (linkStatus != GL_TRUE) {
        GLchar log[512];
        glGetProgramInfoLog(impl_->program, 512, nullptr, log);
        LOGE("Shader link failed: %s", log);
    }

    // Clean up shaders
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    // Get attribute/uniform locations
    impl_->positionLoc = glGetAttribLocation(impl_->program, "a_position");
    impl_->texCoordLoc = glGetAttribLocation(impl_->program, "a_texCoord");
    impl_->textureLoc = glGetUniformLocation(impl_->program, "u_texture");

    // Create texture
    glGenTextures(1, &impl_->texture);
    glBindTexture(GL_TEXTURE_2D, impl_->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Allocate texture storage
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, impl_->previewWidth, impl_->previewHeight,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    // Create VBO for fullscreen quad
    GLfloat quadVertices[] = {
            // Position (x,y)  // TexCoord (u,v)
            -1.0f, -1.0f,      0.0f, 1.0f,  // Bottom-left
            1.0f, -1.0f,      1.0f, 1.0f,  // Bottom-right
            -1.0f,  1.0f,      0.0f, 0.0f,  // Top-left
            1.0f,  1.0f,      1.0f, 0.0f   // Top-right
    };

    glGenBuffers(1, &impl_->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, impl_->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);

    LOGI("OpenGL setup complete");
}

void Renderer::onSurfaceChanged(int width, int height) {
    LOGI("onSurfaceChanged: %dx%d", width, height);
    impl_->screenWidth = width;
    impl_->screenHeight = height;
    glViewport(0, 0, width, height);
}

void Renderer::onCameraFrame(const uint8_t* nv21Data, int width, int height) {
    if (width != impl_->previewWidth || height != impl_->previewHeight) {
        LOGE("Frame size mismatch: expected %dx%d, got %dx%d",
             impl_->previewWidth, impl_->previewHeight, width, height);
        return;
    }

    // Process frame with OpenCV
    processFrame(nv21Data, width, height, impl_->rgbaBuffer);

    // Upload to texture
    glBindTexture(GL_TEXTURE_2D, impl_->texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, impl_->previewWidth, impl_->previewHeight,
                    GL_RGBA, GL_UNSIGNED_BYTE, impl_->rgbaBuffer);

    impl_->hasFrame = true;  // Mark that we have valid frame data
}

void Renderer::onDrawFrame() {
    // Clear screen
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // FIXED: Only skip if we've NEVER received a frame
    // Once hasFrame is true, it stays true
    if (!impl_->hasFrame) {
        return;
    }

    // Use shader program
    glUseProgram(impl_->program);

    // Bind texture
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, impl_->texture);
    glUniform1i(impl_->textureLoc, 0);

    // Bind VBO and set up vertex attributes
    glBindBuffer(GL_ARRAY_BUFFER, impl_->vbo);

    // Position attribute
    glEnableVertexAttribArray(impl_->positionLoc);
    glVertexAttribPointer(impl_->positionLoc, 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), (void*)0);

    // Texture coordinate attribute
    glEnableVertexAttribArray(impl_->texCoordLoc);
    glVertexAttribPointer(impl_->texCoordLoc, 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), (void*)(2 * sizeof(GLfloat)));

    // Draw fullscreen quad
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Clean up
    glDisableVertexAttribArray(impl_->positionLoc);
    glDisableVertexAttribArray(impl_->texCoordLoc);
}