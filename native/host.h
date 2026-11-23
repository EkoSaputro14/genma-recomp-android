#pragma once
// Shared Android host interface: SDL3 window, app foreground/surface lifecycle, and OEM loose controller bridge.
#include <cstdint>

struct SDL_Window;

namespace host {

SDL_Window* Window();
// True while the activity is in the foreground with a valid Vulkan surface.
bool Foreground();
// Incremented every time the activity returns to the foreground so Vulkan recreates its VkSurfaceKHR/swapchain.
uint32_t SurfaceEpoch();
// Blocks the render/game thread until the app returns to the foreground.
void WaitForeground();
// Current drawable surface dimensions in physical pixels.
void WindowPixels(int& w, int& h);
// Internal resolution multiplier relative to guest surface size (default 1.0f = 1x native 480p).
float RenderScale();
void SetRenderScale(float scale);
// Present blit filter: false = bilinear (default), true = nearest (sharp).
bool NearestPresent();
void SetNearestPresent(bool nearest);
// GPU capabilities reported by Vulkan::Init() for the Android settings UI.
void SetGpuCaps(float max_aniso, uint32_t max_image_dim);
float MaxAniso();
uint32_t MaxImageDim();

}  // namespace host
