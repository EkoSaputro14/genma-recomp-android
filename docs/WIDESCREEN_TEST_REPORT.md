# Verification & Test Report — Genma Recomp Android 16:9 Widescreen / True Fullscreen (`v0.8.15-android`)

> **Target Repository**: [`EkoSaputro14/genma-recomp-android`](https://github.com/EkoSaputro14/genma-recomp-android)  
> **Verified Commit**: `18587647924eff8f1380a2707153911f06a4ba1a` ([PR #7](https://github.com/EkoSaputro14/genma-recomp-android/pull/7))  
> **Release Tag**: [`v0.8.15-android`](https://github.com/EkoSaputro14/genma-recomp-android/releases/tag/v0.8.15-android)

---

## 1. CI Build & Release Verification (Verified)

| Artifact / Pipeline Check | Status | Details |
|---|---|---|
| GitHub Actions CI Run (`ubuntu-24.04`) | **PASSED** | Run [#45401077378](https://github.com/EkoSaputro14/genma-recomp-android/actions/runs/45401077378) — synced pinned upstream `rafaelkenedy/genma-recomp`, applied all 3 sequential patches (`genma-android.patch`, `02-v0.8.14-widescreen-hotplug.patch`, `03-v0.8.15-widescreen-display-pipeline.patch`), recompiled `default.xbe` to C++20, built ARM64 `libmain.so` (NDK `27.2.12479018`) & Gradle Release APK |
| Release Binary (`arm64-v8a`) | **PUBLISHED** | `GenmaOnimusha-v0.8.15-android-arm64-v8a.apk` (`15,768,676` bytes) |
| Release Checksum (`SHA256SUMS`) | **VERIFIED** | `b51aeb0b8ebf854445b7c19827394b9d610370c5d90593d50592106fb96b0767` |
| Upstream Clean Sequential Patch Check | **PASSED** | `git apply --check` across all 3 patches on upstream `33fee19ef4aec490b8970d4472fadd6c4bc99aa2`; SHA-256 byte-for-byte identical for `sdk/runtime/app/settings.cpp` and `sdk/runtime/gpu/vulkan.cpp` |
| NDK ARM64 C++20 Syntax Check (`-fsyntax-only`) | **PASSED** | `aarch64-linux-android29-clang++ -std=c++20` (`settings.cpp`, `vulkan.cpp`, `main.cpp`, `display_math.h`) — 0 errors |
| Android Java Compilation + JNI Parity | **PASSED** | `javac` against `android-34/android.jar` + SDL3 `release-3.4.16` Java sources (68 `.class` files, 0 errors); 10/10 JNI declarations matched in `native/main.cpp` |

---

## 2. Physical Hardware Verification Status (Honest Reporting)

| Check Category | Status | Scope & Evidence |
|---|---|---|
| Physical Android Device (`adb devices -l`) | **NOT TESTED ON DEVICE** | `192.168.1.9:5555` (`SH_04L`) remained `offline` throughout verification; no online Android handset was attached for live Vulkan/SurfaceFlinger inspection, touch overlay visual ROI capture, or FMV playback screenshot capture |
| Mathematical & Pipeline Logic Simulation | **VERIFIED (740 checks)** | Executed C++20 test suite [`tools/test_widescreen_math.cpp`](../tools/test_widescreen_math.cpp) compiled with `clang++ -std=c++20 -Wall -Wextra -Werror`, directly calling the production `display_math::ComputePresentPlan`, `ComputeScaledExtent`, and `NormalizeLandscape` headers shared by `sdk/runtime/gpu/vulkan.cpp` |

---

## 3. Automated Verification Matrix (`tools/test_widescreen_math.cpp` — 740 Checks Passed)

Observed pixel distortion across all tested aspect ratios (`4:3`, `16:9`, `18:9`, `19.5:9`, `20:9`, `21:9`), scales (`0.75x`–`3x`), and framings (`0%`–`100%`) stayed within **`0.9989 .. 1.0020`** (`|scale_x / scale_y - 1.0| <= 0.0020`):

| Test Configuration | Swapchain / Output | Verified Behavior (`Scale 1.00x–3.00x`, Subtitle-Safe 70% except framing checks) | Observed Distortion Ratio | Status |
|---|---|---|---|---|
| **Native 4:3** | `1440×1080` (`4:3`), `1920×1080` (`16:9`), `2400×1080` (`20:9`) | Preserves exact `4:3` (`1.3333`) inside swapchain (centered `240..1680` on 16:9; `480..1920` on 20:9), `0.0%` vertical crop | `0.9998 – 1.0003` | PASS (Simulated) |
| **16:9 Widescreen (No-Stretch)** | `1920×1080`, `2560×1440` (`16:9`) | Fills `16:9` edge-to-edge `(0,0)-(W,H)` with `25.0%` vertical crop and `1:1` square pixels | `0.9989 – 1.0006` | PASS (Simulated) |
| **18:9 Adaptive (`2160×1080`)** | `Fullscreen No-Stretch` | Fills `(0,0)-(2160,1080)` (zero black bars), `33.3%–33.4%` vertical crop | `1.0000` | PASS (Simulated) |
| **19.5:9 Adaptive (`2340×1080`)** | `Fullscreen No-Stretch` | Fills `(0,0)-(2340,1080)` (zero black bars), `38.4%–38.5%` vertical crop | `0.9999 – 1.0011` | PASS (Simulated) |
| **20:9 Adaptive (`2400×1080`, `3200×1440`)** | `Fullscreen No-Stretch` | Fills `(0,0)-(2400,1080)` / `(3200,1440)` (zero black bars), `39.9%–40.1%` vertical crop | `0.9994 – 1.0020` | PASS (Simulated) |
| **21:9 Adaptive (`2520×1080`)** | `Fullscreen No-Stretch` | Fills `(0,0)-(2520,1080)` (zero black bars), `42.9%` vertical crop | `0.9989 – 1.0006` | PASS (Simulated) |
| **Vertical Framing Top (`0%`)** | `2400×1080` (`2x` `1280×960`) | Shifts `src_y` strictly to upper overscan edge: `[32..704]` (`16:9`) / `[32..569]` (`20:9`) | `0.9994 – 1.0006` | PASS (Simulated) |
| **Vertical Framing HUD-Safe (`25%`)** | `2400×1080` (`2x` `1280×960`) | Monotonically shifts lower: `[88..760]` (`16:9`) / `[122..659]` (`20:9`) | `0.9994 – 1.0006` | PASS (Simulated) |
| **Vertical Framing Center (`50%`)** | `2400×1080` (`2x` `1280×960`) | Centers crop window symmetrically: `[144..816]` (`16:9`) / `[212..749]` (`20:9`) | `0.9994 – 1.0006` | PASS (Simulated) |
| **Vertical Framing Subtitle-Safe (`70%`)** | `2400×1080` (`2x` `1280×960`) | Biases toward lower subtitles while sparing top HUD: `[189..861]` (`16:9`) / `[283..820]` (`20:9`) | `0.9994 – 1.0006` | PASS (Simulated) |
| **Vertical Framing Bottom (`100%`)** | `2400×1080` (`2x` `1280×960`) | Aligns strictly to bottom overscan edge: `[256..928]` (`16:9`) / `[391..928]` (`20:9`) | `0.9994 – 1.0006` | PASS (Simulated) |
| **Resolution `0.75x` / `1x` / `1.5x` / `2x` / `2.5x` / `3x`** | Internal RT (`480×360` .. `1920×1440`) | Preserves `4:3` internal aspect ratio independently of output swapchain (`|rt_aspect - 4/3| < 0.01`); `max_image_dim=1024` clamp produces exact `1024×768` (`4:3`) | `0.9989 – 1.0020` | PASS (Simulated) |
| **Portrait -> Landscape & Overscan `0% / 3.33% / 5%`** | `1080×2400 -> 2400×1080` | `NormalizeLandscape()` normalizes `{2400, 1080}`; `RefreshWindowSize()` ignores transient `w < h` portrait surfaces; zero black-matte leakage at `0%`–`5%` overscan | `0.9989 – 1.0020` | PASS (Simulated) |

---

## 4. Before (`v0.8.14`) vs. After (`v0.8.15`) Observations & Remaining Limitations

### Improvements Verified in Code & Build
1. **Before (`v0.8.14`)**: `16:9` and `Full Screen` modes stretched `598×448` (`4:3`) into `16:9` (`1.332x` wide) or `20:9` (`1.665x` wide) because `fillNoStretch` was `false`; `16:9` on `20:9` phones also left 240px black sidebars; display cutouts clipped the landscape surface by default.
2. **After (`v0.8.15`)**:
   - All 3 display strategies enforce uniform `1:1` pixel proportions (`0.9989–1.0020`) via `display_math::ComputePresentPlan`.
   - `windowLayoutInDisplayCutoutMode=shortEdges` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + `hideSystemBars()` on `onCreate`/`onResume`/`onWindowFocusChanged` eliminate OS cutout letterboxing and navigation-bar shrink.
   - `Vulkan::Resize()` queries live `host::WindowPixels()` directly (no stale `1280×720` `g_size` lock).
   - `[DISPLAY]`, `[RENDER]`, and `[VULKAN]` state-change logs diagnose physical size, SDL window, internal target, swapchain, viewport, scissor, blit rects, and distortion ratio.

### Remaining Limitations & On-Device Checks Required
1. **Pre-Rendered 2D Background Constraint**: Because *Genma Onimusha* renders 3D actors over `640×480` 2D `.bgs` paintings (`src/common/game_info.cpp:27`), `Fullscreen No-Stretch` on ultra-wide `20:9` screens achieves `1:1` distortion-free fill by vertically cropping `~40%` (controlled by the 5 Vertical Framing presets), whereas `16:9 Widescreen` caps vertical crop at `~25%`, and `Native 4:3` retains `100%` vertical height cleanly with side pillarboxes.
2. **On-Device Runtime Verification Pending**: Physical testing on ARM64 hardware (notch phones `19.5:9`/`20:9`, Mali/Immortalis & Adreno GPUs, live mode switching, pause/resume background transitions, 16:9 FMV subtitles, and touch control visual overlap checks with `genma.log` capture) must still be performed on-device once the `v0.8.15-android` APK is installed.
