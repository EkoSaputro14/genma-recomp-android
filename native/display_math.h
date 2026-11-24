#pragma once
// Pure C++20 display geometry, aspect-ratio, overscan, vertical framing, and upscaling math
// for Genma Onimusha Android ARM64 (shared between sdk/runtime/gpu/vulkan.cpp and unit tests).

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace display_math {

enum class DisplayMode : uint32_t {
    Native4x3 = 0,
    Widescreen16x9 = 1,
    FullscreenNoStretch = 2,
};

enum class VerticalFraming : uint32_t {
    Top = 0,
    HudSafe = 1,
    Center = 2,
    SubtitleSafe = 3,
    Bottom = 4,
};

struct Extent {
    uint32_t width = 0;
    uint32_t height = 0;
};

struct PresentPlan {
    int sx0 = 0;
    int sy0 = 0;
    int sx1 = 0;
    int sy1 = 0;
    int dx0 = 0;
    int dy0 = 0;
    int dx1 = 0;
    int dy1 = 0;
    float src_active_aspect = 4.0f / 3.0f;
    float dst_aspect = 4.0f / 3.0f;
    float scale_x = 1.0f;
    float scale_y = 1.0f;
    float distortion_ratio = 1.0f;
    bool cropped_vertical = false;
    bool cropped_horizontal = false;
    float vertical_crop_pct = 0.0f;
};

constexpr float FramingPan(VerticalFraming f) {
    switch (f) {
        case VerticalFraming::Top: return 0.00f;
        case VerticalFraming::HudSafe: return 0.25f;
        case VerticalFraming::Center: return 0.50f;
        case VerticalFraming::SubtitleSafe: return 0.70f;
        case VerticalFraming::Bottom: return 1.00f;
    }
    return 0.70f;
}

constexpr const char* DisplayModeName(DisplayMode mode) {
    switch (mode) {
        case DisplayMode::Native4x3: return "Native 4:3";
        case DisplayMode::Widescreen16x9: return "16:9 Widescreen (No-Stretch)";
        case DisplayMode::FullscreenNoStretch: return "Fullscreen No-Stretch (Adaptive)";
    }
    return "Native 4:3";
}

inline const char* FramingName(float pan) {
    const float p = std::clamp(pan, 0.0f, 1.0f);
    if (std::fabs(p - 0.00f) < 0.03f) return "Top (0%)";
    if (std::fabs(p - 0.25f) < 0.03f) return "HUD-Safe (25%)";
    if (std::fabs(p - 0.50f) < 0.03f) return "Center (50%)";
    if (std::fabs(p - 0.70f) < 0.03f) return "Subtitle-Safe (70%)";
    if (std::fabs(p - 1.00f) < 0.03f) return "Bottom (100%)";
    return "Custom";
}

inline Extent NormalizeLandscape(uint32_t w, uint32_t h) {
    if (!w || !h) return {0, 0};
    return {std::max(w, h), std::min(w, h)};
}

// Scales guest render targets uniformly so max_dim clamping never distorts internal 4:3 aspect ratio.
inline Extent ComputeScaledExtent(uint32_t gw, uint32_t gh, float scale, uint32_t max_dim) {
    if (!gw || !gh || !max_dim) return {gw, gh};
    const float req_scale = std::clamp(scale, 0.25f, 4.0f);
    if (std::fabs(req_scale - 1.0f) <= 1e-4f && gw <= max_dim && gh <= max_dim) {
        return {gw, gh};
    }
    const float max_scale_w = float(max_dim) / float(gw);
    const float max_scale_h = float(max_dim) / float(gh);
    const float eff_scale = std::min(req_scale, std::min(max_scale_w, max_scale_h));
    const uint32_t out_w = std::clamp(uint32_t(std::lround(float(gw) * eff_scale)), 1u, max_dim);
    const uint32_t out_h = std::clamp(uint32_t(std::lround(float(gh) * eff_scale)), 1u, max_dim);
    return {out_w, out_h};
}

// Computes exact source & destination blit rects for Vulkan presentation.
// Enforces 1:1 uniform pixel aspect across all 3 DisplayModes (never stretches 4:3 into 16:9/20:9).
inline PresentPlan ComputePresentPlan(uint32_t src_w, uint32_t src_h, uint32_t swap_w, uint32_t swap_h,
                                      DisplayMode mode, float overscan_pct, float pan) {
    PresentPlan plan{};
    if (!src_w || !src_h || !swap_w || !swap_h) return plan;

    const float crop = std::clamp(overscan_pct, 0.0f, 15.0f) * 0.01f;
    const int cx = std::clamp(int(std::lround(float(src_w) * crop)), 0, int(src_w) / 4);
    const int cy = std::clamp(int(std::lround(float(src_h) * crop)), 0, int(src_h) / 4);
    plan.sx0 = cx;
    plan.sx1 = std::max(cx + 1, int(src_w) - cx);
    plan.sy0 = cy;
    plan.sy1 = std::max(cy + 1, int(src_h) - cy);

    const int sw = std::max(1, plan.sx1 - plan.sx0);
    const int sh = std::max(1, plan.sy1 - plan.sy0);
    plan.src_active_aspect = float(sw) / float(sh);

    int dw = int(swap_w);
    int dh = int(swap_h);
    const float swap_aspect = float(swap_w) / float(swap_h);

    if (mode == DisplayMode::Native4x3) {
        const float target_aspect = plan.src_active_aspect;
        if (swap_aspect > target_aspect) {
            dw = std::clamp(int(std::lround(float(dh) * target_aspect)), 1, int(swap_w));
        } else {
            dh = std::clamp(int(std::lround(float(dw) / target_aspect)), 1, int(swap_h));
        }
    } else if (mode == DisplayMode::Widescreen16x9) {
        constexpr float kAspect16x9 = 16.0f / 9.0f;
        if (swap_aspect > kAspect16x9) {
            dw = std::clamp(int(std::lround(float(dh) * kAspect16x9)), 1, int(swap_w));
        } else {
            dh = std::clamp(int(std::lround(float(dw) / kAspect16x9)), 1, int(swap_h));
        }
    }
    // DisplayMode::FullscreenNoStretch keeps full swap_w x swap_h with zero black bars.

    const int dx = (int(swap_w) - dw) / 2;
    const int dy = (int(swap_h) - dh) / 2;
    plan.dx0 = dx;
    plan.dy0 = dy;
    plan.dx1 = dx + dw;
    plan.dy1 = dy + dh;
    plan.dst_aspect = float(dw) / float(std::max(1, dh));

    // Match source crop aspect to destination aspect for non-4:3 modes so pixel proportions stay 1:1.
    if (mode != DisplayMode::Native4x3) {
        if (plan.dst_aspect > plan.src_active_aspect + 0.001f) {
            const int vis_h = std::clamp(int(std::lround(float(sw) / plan.dst_aspect)), 1, sh);
            const int slack = sh - vis_h;
            const float clamped_pan = std::clamp(pan, 0.0f, 1.0f);
            plan.sy0 += int(std::lround(float(slack) * clamped_pan));
            plan.sy1 = plan.sy0 + vis_h;
            plan.cropped_vertical = (vis_h < sh);
            plan.vertical_crop_pct = (sh > 0) ? (100.0f * float(sh - vis_h) / float(sh)) : 0.0f;
        } else if (plan.dst_aspect < plan.src_active_aspect - 0.001f) {
            const int vis_w = std::clamp(int(std::lround(float(sh) * plan.dst_aspect)), 1, sw);
            const int slack = sw - vis_w;
            plan.sx0 += slack / 2;
            plan.sx1 = plan.sx0 + vis_w;
            plan.cropped_horizontal = (vis_w < sw);
        }
    }

    const int final_sw = std::max(1, plan.sx1 - plan.sx0);
    const int final_sh = std::max(1, plan.sy1 - plan.sy0);
    plan.scale_x = float(dw) / float(final_sw);
    plan.scale_y = float(dh) / float(final_sh);
    plan.distortion_ratio = (plan.scale_y > 0.0f) ? (plan.scale_x / plan.scale_y) : 1.0f;
    return plan;
}

}  // namespace display_math
