#pragma once
// Android virtual touch overlay + Bluetooth/USB-C controller arbiter for Genma Onimusha.

#include <array>
#include <cstdint>

namespace input_android {

enum class TouchKind : uint8_t {
    Stick = 0,
    DPad  = 1,
    Button = 2,
    Menu = 3,
};

struct TouchControlDef {
    const char* id;
    const char* label;
    float normX;
    float normY;
    float normSize;
    TouchKind kind;
};

struct VirtualPadState {
    int16_t leftStickX = 0;
    int16_t leftStickY = 0;
    uint32_t buttons = 0; // Matches Xbox XINPUT_GAMEPAD bitmask
    bool dpadUp = false;
    bool dpadDown = false;
    bool dpadLeft = false;
    bool dpadRight = false;
    bool menuToggleRequested = false;
};

struct OverlaySettings {
    bool enabled = true;
    bool autoHideWhenGamepadConnected = true;
    bool modernAnalogControls = true;
    float opacity = 0.68f;
    float deadzone = 0.14f;
};

void InitOverlay(const OverlaySettings& settings);
void SetPhysicalGamepadConnected(bool connected);
bool ShouldRenderOverlay();
void OnTouchDown(int pointerId, float normX, float normY);
void OnTouchMove(int pointerId, float normX, float normY);
void OnTouchUp(int pointerId);
VirtualPadState PollVirtualPad();
const std::array<TouchControlDef, 8>& GetLayout();

} // namespace input_android
