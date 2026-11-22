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

}  // namespace host
