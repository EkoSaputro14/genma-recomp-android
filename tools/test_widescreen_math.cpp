// Automated C++20 verification suite for Genma Recomp Android display/widescreen/upscaling math.
// Covers: Native 4:3, 16:9, 18:9, 19.5:9, 20:9, 21:9, Top/HUD-Safe/Center/Subtitle-Safe/Bottom framing,
// resolution upscaling (0.75x..3x), max_image_dim clamping invariance, overscan, mode switching, and resize transitions.

#include "display_math.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

using display_math::ComputePresentPlan;
using display_math::ComputeScaledExtent;
using display_math::DisplayMode;
using display_math::DisplayModeName;
using display_math::FramingName;
using display_math::FramingPan;
using display_math::NormalizeLandscape;
using display_math::PresentPlan;
using display_math::VerticalFraming;

namespace {

constexpr float kGenmaOverscan = 100.0f / 30.0f;

void AssertNear(float a, float b, float tol, const char* msg) {
    if (std::fabs(a - b) > tol) {
        std::fprintf(stderr, "FAIL [%s]: got %.6f expected %.6f (tol=%.6f)\n", msg, a, b, tol);
        std::abort();
    }
}

void CheckInvariants(const PresentPlan& p, uint32_t src_w, uint32_t src_h, uint32_t swap_w, uint32_t swap_h) {
    assert(p.sx0 >= 0 && p.sy0 >= 0);
    assert(p.sx1 > p.sx0 && p.sx1 <= int(src_w));
    assert(p.sy1 > p.sy0 && p.sy1 <= int(src_h));
    assert(p.dx0 >= 0 && p.dy0 >= 0);
    assert(p.dx1 > p.dx0 && p.dx1 <= int(swap_w));
    assert(p.dy1 > p.dy0 && p.dy1 <= int(swap_h));
    // Core anti-squash guarantee: pixel aspect ratio scale_x / scale_y must be ~1.0 in ALL modes!
    AssertNear(p.distortion_ratio, 1.0f, 0.01f, "zero-distortion pixel ratio");
}

}  // namespace

int main() {
    struct ScreenCase {
        const char* label;
        uint32_t w, h;
    };
    const ScreenCase screens[] = {
        {"4:3 (1440x1080)", 1440, 1080},
        {"16:9 (1920x1080)", 1920, 1080},
        {"16:9 (2560x1440)", 2560, 1440},
        {"18:9 (2160x1080)", 2160, 1080},
        {"19.5:9 (2340x1080)", 2340, 1080},
        {"20:9 (2400x1080)", 2400, 1080},
        {"20:9 (3200x1440)", 3200, 1440},
        {"21:9 (2520x1080)", 2520, 1080},
    };
    const float scales[] = {0.75f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f};
    const DisplayMode modes[] = {
        DisplayMode::Native4x3,
        DisplayMode::Widescreen16x9,
        DisplayMode::FullscreenNoStretch,
    };
    const VerticalFraming framings[] = {
        VerticalFraming::Top,
        VerticalFraming::HudSafe,
        VerticalFraming::Center,
        VerticalFraming::SubtitleSafe,
        VerticalFraming::Bottom,
    };

    std::printf("=== Genma Recomp Android Widescreen / Fullscreen / Upscale Verification Matrix ===\n");
    std::printf("%-18s | %-5s | %-28s | %-15s | %-15s | %-9s | %-5s\n",
                "Screen", "Scale", "Mode", "SrcRect", "DstRect", "DistRatio", "Crop%");
    std::printf("-------------------------------------------------------------------------------------------------------------\n");

    size_t total_checks = 0;

    // 1. Cross-product: screens x scales x modes x framings
    for (const auto& sc : screens) {
        const auto land = NormalizeLandscape(sc.h, sc.w);  // intentionally reversed input
        assert(land.width == sc.w && land.height == sc.h);

        for (float scale : scales) {
            const auto rt = ComputeScaledExtent(640, 480, scale, 4096);
            AssertNear(float(rt.width) / float(rt.height), 4.0f / 3.0f, 0.01f, "scaled RT 4:3 invariance");

            for (DisplayMode mode : modes) {
                for (VerticalFraming fr : framings) {
                    const float pan = FramingPan(fr);
                    const PresentPlan p = ComputePresentPlan(rt.width, rt.height, sc.w, sc.h, mode, kGenmaOverscan, pan);
                    CheckInvariants(p, rt.width, rt.height, sc.w, sc.h);

                    if (mode == DisplayMode::Native4x3) {
                        assert(!p.cropped_vertical && !p.cropped_horizontal);
                        AssertNear(p.dst_aspect, 4.0f / 3.0f, 0.01f, "Native 4:3 dst aspect");
                    } else if (mode == DisplayMode::Widescreen16x9) {
                        if (float(sc.w) / float(sc.h) >= 16.0f / 9.0f - 0.01f) {
                            AssertNear(p.dst_aspect, 16.0f / 9.0f, 0.01f, "16:9 dst aspect");
                            assert(p.cropped_vertical);
                            // 4:3 -> 16:9 vertical crop is ~25.0% even on 20:9+ displays
                            AssertNear(p.vertical_crop_pct, 25.0f, 1.5f, "16:9 25% vertical crop");
                        }
                    } else if (mode == DisplayMode::FullscreenNoStretch) {
                        // Fullscreen No-Stretch must occupy 100% of swapchain with ZERO black bars
                        assert(p.dx0 == 0 && p.dy0 == 0);
                        assert(p.dx1 == int(sc.w) && p.dy1 == int(sc.h));
                    }

                    if (fr == VerticalFraming::SubtitleSafe && (scale == 0.75f || scale == 1.0f || scale == 2.0f || scale == 3.0f)) {
                        std::printf("%-18s | %4.2fx | %-28s | (%3d,%3d)-(%4d,%4d) | (%3d,%3d)-(%4d,%4d) |   %.4f  | %4.1f%%\n",
                                    sc.label, scale, DisplayModeName(mode),
                                    p.sx0, p.sy0, p.sx1, p.sy1, p.dx0, p.dy0, p.dx1, p.dy1,
                                    p.distortion_ratio, p.vertical_crop_pct);
                    }
                    total_checks++;
                }
            }
        }
    }

    // 2. Vertical Framing monotonic position verification (Top < HUD-Safe < Center < Subtitle-Safe < Bottom)
    for (DisplayMode wide_mode : {DisplayMode::Widescreen16x9, DisplayMode::FullscreenNoStretch}) {
        const auto rt2x = ComputeScaledExtent(640, 480, 2.0f, 4096);  // 1280x960
        const uint32_t sw = 2400, sh = 1080;  // 20:9
        PresentPlan prev{};
        int expected_vis_h = -1;
        const int cy = int(std::lround(float(rt2x.height) * (kGenmaOverscan * 0.01f)));
        for (size_t i = 0; i < 5; i++) {
            const float pan = FramingPan(framings[i]);
            const PresentPlan p = ComputePresentPlan(rt2x.width, rt2x.height, sw, sh, wide_mode, kGenmaOverscan, pan);
            CheckInvariants(p, rt2x.width, rt2x.height, sw, sh);
            const int vis_h = p.sy1 - p.sy0;
            if (expected_vis_h < 0) expected_vis_h = vis_h;
            assert(vis_h == expected_vis_h);
            if (i == 0) {
                assert(p.sy0 == cy);  // Top aligns with upper overscan boundary
            } else {
                assert(p.sy0 > prev.sy0);
                assert(p.sy1 > prev.sy1);
            }
            if (i == 4) {
                assert(p.sy1 == int(rt2x.height) - cy);  // Bottom aligns with lower overscan boundary
            }
            std::printf("Framing Check [%s | %-18s]: src_y=[%d..%d] (h=%d)\n",
                        DisplayModeName(wide_mode), FramingName(pan), p.sy0, p.sy1, vis_h);
            prev = p;
            total_checks++;
        }
    }

    // 3. MaxImageDim uniform clamping stress test (prevents distortion on small maxImageDimension2D GPUs)
    {
        const auto clamped = ComputeScaledExtent(640, 480, 3.0f, 1024);
        assert(clamped.width == 1024 && clamped.height == 768);
        AssertNear(float(clamped.width) / float(clamped.height), 4.0f / 3.0f, 0.005f, "clamped max_dim 4:3");
        total_checks++;
    }

    // 4. Mode switch & surface resize sequence (Portrait -> Landscape -> Mode cycle -> Overscan 0%/3.33%/5%)
    {
        const auto s1 = NormalizeLandscape(1080, 2400);
        assert(s1.width == 2400 && s1.height == 1080);
        for (float ov : {0.0f, kGenmaOverscan, 5.0f}) {
            for (DisplayMode mode : modes) {
                const auto p = ComputePresentPlan(640, 480, s1.width, s1.height, mode, ov, 0.70f);
                CheckInvariants(p, 640, 480, s1.width, s1.height);
                total_checks++;
            }
        }
    }

    std::printf("\n[PASS] Verified %zu display/aspect/framing/upscale checks with max distortion <= 0.01.\n",
                total_checks);
    return 0;
}
