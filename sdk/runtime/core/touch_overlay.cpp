#include "touch_overlay.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>

namespace input_android {

namespace {

constexpr uint32_t kBtnA     = 0x1000; // Soul Absorb / Confirm
constexpr uint32_t kBtnX     = 0x4000; // Attack
constexpr uint32_t kBtnY     = 0x8000; // Oni Magic
constexpr uint32_t kBtnLB    = 0x0100; // Guard / Dodge
constexpr uint32_t kBtnStart = 0x0010; // Pause / Inventory

struct ActiveTouch {
    bool active = false;
    int pointerId = -1;
    int controlIndex = -1;
    float x = 0.0f;
    float y = 0.0f;
};

std::mutex g_mutex;
OverlaySettings g_settings{};
bool g_physicalGamepadConnected = false;
bool g_pendingMenuToggle = false;
std::array<ActiveTouch, 10> g_pointers{};

const std::array<TouchControlDef, 8> kDefaultLayout = {{
    {"left_stick", "L-STICK", 0.140f, 0.680f, 0.190f, TouchKind::Stick},
    {"dpad", "D-PAD", 0.270f, 0.360f, 0.140f, TouchKind::DPad},
    {"btn_attack", "X", 0.780f, 0.680f, 0.110f, TouchKind::Button},
    {"btn_absorb", "A", 0.870f, 0.780f, 0.110f, TouchKind::Button},
    {"btn_magic", "Y", 0.870f, 0.560f, 0.105f, TouchKind::Button},
    {"btn_guard", "LB", 0.740f, 0.480f, 0.105f, TouchKind::Button},
    {"btn_start", "START", 0.540f, 0.140f, 0.080f, TouchKind::Button},
    {"btn_imgui", "MENU", 0.450f, 0.140f, 0.080f, TouchKind::Menu},
}};

int HitTestControl(float nx, float ny) {
    int best = -1;
    float bestDist = 1e9f;
    for (size_t i = 0; i < kDefaultLayout.size(); ++i) {
        const auto& c = kDefaultLayout[i];
        const float dx = nx - c.normX;
        const float dy = ny - c.normY;
        const float dist = std::hypot(dx, dy);
        const float radius = c.normSize * 0.65f;
        if (dist <= radius && dist < bestDist) {
            bestDist = dist;
            best = static_cast<int>(i);
        }
    }
    return best;
}

} // namespace

void InitOverlay(const OverlaySettings& settings) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_settings = settings;
    g_pointers = {};
    g_pendingMenuToggle = false;
}

void SetPhysicalGamepadConnected(bool connected) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_physicalGamepadConnected = connected;
}

bool ShouldRenderOverlay() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_settings.enabled) return false;
    if (g_settings.autoHideWhenGamepadConnected && g_physicalGamepadConnected) return false;
    return true;
}

void OnTouchDown(int pointerId, float normX, float normY) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_settings.enabled) return;
    const int hit = HitTestControl(normX, normY);
    if (hit < 0) return;

    if (kDefaultLayout[static_cast<size_t>(hit)].kind == TouchKind::Menu) {
        g_pendingMenuToggle = true;
        return;
    }

    for (auto& slot : g_pointers) {
        if (!slot.active || slot.pointerId == pointerId) {
            slot.active = true;
            slot.pointerId = pointerId;
            slot.controlIndex = hit;
            slot.x = normX;
            slot.y = normY;
            break;
        }
    }
}

void OnTouchMove(int pointerId, float normX, float normY) {
    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto& slot : g_pointers) {
        if (slot.active && slot.pointerId == pointerId) {
            slot.x = normX;
            slot.y = normY;
            break;
        }
    }
}

void OnTouchUp(int pointerId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto& slot : g_pointers) {
        if (slot.active && slot.pointerId == pointerId) {
            slot.active = false;
            slot.pointerId = -1;
            slot.controlIndex = -1;
            break;
        }
    }
}

VirtualPadState PollVirtualPad() {
    std::lock_guard<std::mutex> lock(g_mutex);
    VirtualPadState state{};
    state.menuToggleRequested = g_pendingMenuToggle;
    g_pendingMenuToggle = false;

    for (const auto& slot : g_pointers) {
        if (!slot.active || slot.controlIndex < 0) continue;
        const auto& c = kDefaultLayout[static_cast<size_t>(slot.controlIndex)];
        if (c.kind == TouchKind::Stick) {
            const float radius = std::max(0.03f, c.normSize * 0.5f);
            float dx = (slot.x - c.normX) / radius;
            float dy = (slot.y - c.normY) / radius;
            const float mag = std::hypot(dx, dy);
            if (mag > 1.0f) {
                dx /= mag;
                dy /= mag;
            }
            if (mag >= g_settings.deadzone) {
                state.leftStickX = static_cast<int16_t>(std::clamp(dx, -1.0f, 1.0f) * 32767.0f);
                state.leftStickY = static_cast<int16_t>(std::clamp(-dy, -1.0f, 1.0f) * 32767.0f);
            }
        } else if (c.kind == TouchKind::DPad) {
            const float dx = slot.x - c.normX;
            const float dy = slot.y - c.normY;
            if (std::fabs(dx) > 0.015f || std::fabs(dy) > 0.015f) {
                if (std::fabs(dx) > std::fabs(dy)) {
                    state.dpadLeft = dx < 0.0f;
                    state.dpadRight = dx > 0.0f;
                } else {
                    state.dpadUp = dy < 0.0f;
                    state.dpadDown = dy > 0.0f;
                }
            }
        } else if (c.kind == TouchKind::Button) {
            if (std::strcmp(c.id, "btn_attack") == 0) state.buttons |= kBtnX;
            else if (std::strcmp(c.id, "btn_absorb") == 0) state.buttons |= kBtnA;
            else if (std::strcmp(c.id, "btn_magic") == 0) state.buttons |= kBtnY;
            else if (std::strcmp(c.id, "btn_guard") == 0) state.buttons |= kBtnLB;
            else if (std::strcmp(c.id, "btn_start") == 0) state.buttons |= kBtnStart;
        }
    }
    return state;
}

const std::array<TouchControlDef, 8>& GetLayout() {
    return kDefaultLayout;
}

} // namespace input_android
