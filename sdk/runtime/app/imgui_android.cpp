// Touch-friendly ImGui menu profile replacing the desktop F1 shortcut on Android.

#include <cstdint>

namespace app_android {

struct TouchMenuSettings {
    float dpiScale = 1.55f;
    float minTouchTargetPx = 48.0f;
    bool open = false;

    // Graphics & port fixes synced with Genma Onimusha settings.toml
    int renderScalePercent = 100;       // 50..150% on mobile GPUs
    int fpsCap = 60;                    // 30 / 60 / Unlimited
    bool keep4x3Aspect = true;          // Pre-rendered 640x480 backgrounds
    bool cropTvOverscan = true;         // 3.33% per edge
    bool preciseDepth = true;
    bool stablePointSampling = true;
    bool hdTexturesEnabled = true;
    bool modernAnalogControls = true;
    float touchOverlayOpacity = 0.68f;
    bool vulkan10SafeMode = true;
};

static TouchMenuSettings g_menu{};

TouchMenuSettings& GetTouchMenuSettings() {
    return g_menu;
}

void ToggleTouchMenu() {
    g_menu.open = !g_menu.open;
}

bool IsTouchMenuOpen() {
    return g_menu.open;
}

} // namespace app_android
