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
// Non-stretched widescreen controls ("16:9 / Full Screen Tanpa Gepeng"):
// FillNoStretch crops Aspect::Stretch (0) to the screen aspect at 1:1 square pixels;
// WideVerticalPan shifts the vertical crop window [0.0=top .. 1.0=bottom] (default 0.70=subtitle-safe).
bool FillNoStretch();
void SetFillNoStretch(bool fill);
float WideVerticalPan();
void SetWideVerticalPan(float pan);
// In-game Bahasa Indonesia localization (subtitles, dialogues, items, menus).
bool IndonesianGame();
void SetIndonesianGame(bool enabled);
void OnXbeLoaded();
std::string GameDir();
void SetGameDir(const std::string& dir);

}  // namespace host
