#pragma once
// Shared Android host interface: SDL3 window, app foreground/surface lifecycle, and OEM loose controller bridge.
#include <cstdint>
#include <string>

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
// Physical landscape display dimensions and pixel density detected from SDL3 display mode.
void PhysicalDisplay(int& w, int& h, float& density);
// Landscape output resolution: live window pixels if available, else physical display pixels (Black-Android ScreenMode pattern).
void ScreenMode(uint32_t& w, uint32_t& h);
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
// Display presentation strategy (0 = Native 4:3, 1 = 16:9 Widescreen No-Stretch, 2 = Fullscreen No-Stretch Adaptive).
uint32_t DisplayMode();
void SetDisplayMode(uint32_t mode);
// Backward-compatible helpers; FillNoStretch() is true for DisplayMode() != 0.
bool FillNoStretch();
void SetFillNoStretch(bool fill);
// Vertical framing position [0.0 = Top, 0.25 = HUD-Safe, 0.50 = Center, 0.70 = Subtitle-Safe, 1.0 = Bottom].
float WideVerticalPan();
void SetWideVerticalPan(float pan);
// In-game Bahasa Indonesia localization (subtitles, dialogues, items, menus).
bool IndonesianGame();
void SetIndonesianGame(bool enabled);
void OnXbeLoaded();
std::string GameDir();
void SetGameDir(const std::string& dir);

}  // namespace host
