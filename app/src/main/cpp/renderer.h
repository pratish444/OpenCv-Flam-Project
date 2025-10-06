#ifndef RENDERER_H
#define RENDERER_H

#include <cstdint>
#include <memory>

// Forward declare implementation structure
struct RendererImpl;

/**
 * Renderer class declaration.
 * Implementation is in renderer.cpp using PIMPL pattern.
 */
class Renderer {
public:
    Renderer(int previewWidth, int previewHeight);
    ~Renderer();

    void onSurfaceCreated();
    void onSurfaceChanged(int width, int height);
    void onCameraFrame(const uint8_t* nv21Data, int width, int height);
    void onDrawFrame();

private:
    RendererImpl* impl_;
};

#endif // RENDERER_H