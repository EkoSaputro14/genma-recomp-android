# Root Cause Analysis — Genma Recomp Android 16:9 Widescreen / Fullscreen Display Distortion

> **Target Repository**: [`EkoSaputro14/genma-recomp-android`](https://github.com/EkoSaputro14/genma-recomp-android)  
> **Reference Research**: [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android) (MIT License, read-only architectural comparison)  
> **Baseline Audited**: `v0.8.14-android` (`d6116ead58a6c839ebf799c77935edb6c9d3f1e8`)

---

## 1. Executive Summary

Although `v0.8.14-android` advertised `16:9` widescreen and `Full Screen` modes alongside an opt-in `Zoom Crop` toggle, forensic inspection of the Android Java lifecycle, SDL3 host wrapper, SDK settings bridge, and Vulkan presentation stage revealed **six independent root causes (RC1–RC6)** causing horizontal/vertical squashing, unintended black bars around notches or ultrawide displays, stale swapchain extents, and silent configuration drift.

---

## 2. Game Architecture Constraint: *Genma Onimusha* (4:3 Pre-Rendered) vs. *Black* (3D FPS)

Before fixing presentation, it is critical to distinguish why *Black-Android* can expand camera FOV while *Genma Onimusha* cannot use pure FOV widening across whole scenes:

| Architectural Dimension | `RanggaCasper/Black-Android` | `genma-recomp-android` (*Genma Onimusha*) |
|---|---|---|
| Scene Composition | Full real-time 3D world + anchored 2D HUD quads | **3D characters composited over pre-rendered 2D `640×480` `.bgs` room backgrounds** (`src/common/game_info.cpp:27`) with pixel-aligned Z-masks |
| Aspect Control in Game Binary | `src/game/hud.cpp:41–51` sets `kDisplayWidescreen=1`, widens `kDisplayViewWidth` (`16:9` / dynamic screen aspect), updates `kDisplayAspect`, and adjusts `kHudSafeX` | `src/common/game_info.cpp:27–28` declares `.aspect = app::Aspect::Ratio4x3` ("640x480 pre-rendered backgrounds, never drawn widescreen") and `.overscan = 100.0f / 30` (3.33% black console border per edge) |
| Effect of Forcing 16:9 3D FOV | Expands peripheral 3D geometry cleanly | Breaks 2D background-to-3D geometry alignment, camera frustum bounds, and depth masks; 2D backgrounds remain `4:3` (`640×480`) |
| Swapchain Drawing Pipeline | Fullscreen graphics render pass (`DrawFrame` letterboxed to `game::Aspect()` which dynamically matches widescreen) | Offscreen Xbox render target (`640×480 × scale_`) -> optional gamma pass -> `vkCmdBlitImage` into `VkSwapchainKHR` |

Because a fixed `4:3` image (`1.3333`) cannot simultaneously fill a `16:9` (`1.7778`) or `20:9` (`2.2222`) rectangle, keep every top/bottom pixel, **and** preserve 1:1 square pixel proportions, widening the guest image horizontally across the swapchain inevitably introduces **+33.3% to +66.7% horizontal stretching** unless the `4:3` image is cropped proportionally along the vertical axis (`pan-and-scan`) or letterboxed/pillarboxed (`Native 4:3`).

---

## 3. Forensic Root Causes Found in `v0.8.14-android`

### RC1 — Non-Uniform Horizontal Stretching in `Vulkan::Present` & UI Defaults
- **Source**:
  - `patches/02-v0.8.14-widescreen-hotplug.patch:109` -> `sdk/runtime/gpu/vulkan.cpp:2809–2847`
  - `android/app/src/main/java/com/genmarecomp/android/GenmaActivity.java:1024–1031`
- **Mechanism**:
  1. In `GenmaActivity.java:1024–1025`, the primary **16:9 (`aspectChoice = 20`)** and **Full Screen (`aspectChoice = 30`)** options encoded `fillNoStretch = false`.
  2. In `vulkan.cpp:2827`, vertical aspect matching was gated behind:
     ```cpp
     const bool zoom_crop_wide = host::FillNoStretch() && (aspect == 0.0f || app::Widescreen());
     ```
  3. Because `host::FillNoStretch()` evaluated to `false`, `sx0..sx1` and `sy0..sy1` remained **`598×448` (`4:3`)** (after `3.33%` overscan crop) while `dx0..dx1` and `dy0..dy1` were computed as **`16:9` (`1920×1080`)** or **full swapchain (`2400×1080 = 20:9`)**.
  4. `vkCmdBlitImage` then non-uniformly stretched a `598×448` source texture into `1920×1080` (`scaleX = 3.2107`, `scaleY = 2.4107`, distortion ratio **`1.332x` / +33.2% wide**) or `2400×1080` (`scaleX = 4.0134`, `scaleY = 2.4107`, distortion ratio **`1.665x` / +66.5% wide**).
  5. Additionally, on `20:9` devices (`2400×1080`), selecting `16:9` computed `w = lround(1080 * 16 / 9) = 1920`, leaving `240px` black bars on the left and right edges *while still stretching the 4:3 game image inside 1920×1080*.

### RC2 — Missing Display Cutout / Notch Fullscreen & Late System Bar Hiding
- **Source**:
  - `android/app/src/main/res/values/styles.xml:1–6`
  - `android/app/src/main/java/com/genmarecomp/android/GenmaActivity.java:121, 1267, 1274`
- **Mechanism**:
  1. `styles.xml` did not specify `<item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>`. On Android 10–15 devices with punch-hole cameras or display notches (`18:9`, `19.5:9`, `20:9`), the OS defaults to `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT`, letterboxing the entire SDL window away from the cutout strip in landscape mode and permanently shrinking `SDL_GetWindowSizeInPixels()`.
  2. `GenmaActivity.onCreate()` did not configure `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (API 30+) / `SHORT_EDGES` or `setDecorFitsSystemWindows(false)`, and `hideSystemBars()` was omitted from `onCreate()` and `onResume()` (called only in `onWindowFocusChanged(true)`), allowing initial surface creation before navigation/status bar insets cleared.
  3. `android:configChanges` in `AndroidManifest.xml` omitted `density`, risking unnecessary Activity restarts when system display size or cutout insets adjust.

### RC3 — Stale `g_size = 1280×720`, Unscaled Portrait `Desktop()`, Locked `Widescreen()` Cache, and Swapchain Lag
- **Source**:
  - `native/main.cpp:36, 98–103, 411–414`
  - `sdk/runtime/app/settings.cpp:125–160`
  - `sdk/runtime/gpu/vulkan.cpp:2539–2548, 2588–2589, 2692–2694`
- **Mechanism**:
  1. `native/main.cpp:36` initialized `g_size` to hardcoded `1280×720`, and `RefreshWindowSize()` relied solely on `SDL_GetWindowSizeInPixels` updates arriving via `Dispatch` or loop end (`line 525`).
  2. `Vulkan::Resize()` (`vulkan.cpp:2540`) read cached `app::LinuxWindowSize()` (`g_size`) instead of querying live `host::WindowPixels(w, h)` (with `caps.currentExtent` fallback), unlike `Black-Android` (`native/vulkan.cpp:1457–1463, 3132–3137`). If the render thread executed `Resize()` before SDL delivered the true landscape window size, Vulkan built a `1280×720` swapchain that Android SurfaceFlinger stretched over the entire screen.
  3. `settings.cpp:136–137` (`app::Desktop()`) returned logical `SDL_GetDesktopDisplayMode()` coordinates **without** multiplying by `m->pixel_density` and **without** landscape normalization (`max, min`), unlike `Black-Android` (`native/main.cpp:83–93` `ScreenMode()`).
  4. `settings.cpp:152–159` (`app::Widescreen()`) cached `wide` in a static `std::atomic<bool>` keyed on `(s.width << 32) | s.height` (`0, 0` on Android), locking stale results indefinitely if evaluated during initialization.

### RC4 — Independent Axis Clamping Hazard in `Vulkan::ScaledExtent`
- **Source**: `sdk/runtime/gpu/vulkan.cpp:972–977`
- **Mechanism**:
  `ScaledExtent(gw, gh)` clamped `lround(gw * scale_)` and `lround(gh * scale_)` independently to `max_image_dim_`. On GPUs with smaller `maxImageDimension2D` caps where width hits `max_image_dim_` before height (or non-4:3 guest surfaces), independent axis clamping alters the internal render target's aspect ratio instead of scaling down uniformly.

### RC5 — Settings Persistence Desync & Incomplete Vertical Framing UX
- **Source**:
  - `GenmaActivity.java:65–66, 124–136, 951–968, 1045–1068`
  - `native/main.cpp:351–353`
- **Mechanism**:
  1. `fill_no_stretch` and `wide_pan_pct` were stored only in Android `SharedPreferences`, whereas `s.aspect` lived in `settings.toml`. Updates or fallbacks could fall out of sync across Activity restarts and native `app::Load()`.
  2. Vertical Framing only offered `25/50/70/90` (omitting exact `0% Top` and `100% Bottom`) and was disabled for primary `16:9` / `Full Screen` modes.

### RC6 — Lack of `[DISPLAY]` / `[RENDER]` / `[VULKAN]` Pipeline Telemetry
- **Source**: `native/main.cpp`, `sdk/runtime/gpu/vulkan.cpp`
- **Mechanism**:
  Logs did not report the end-to-end chain (`Physical Display -> SDL Window -> Output -> Internal Render Target -> Viewport/Scissor -> Swapchain Extent -> Blit Source/Dest -> Distortion Ratio`), making device-specific layout or swapchain bugs difficult to diagnose from `genma.log`.

---

## 4. Pipeline Trace: Before (`v0.8.14`) vs. Target (`v0.8.15`)

```text
BEFORE (v0.8.14-android, when user picks "16:9" or "Full Screen"):
  Physical Display (2400x1080, cutout letterboxed by OS -> e.g. 2280x1080)
    -> g_size initially 1280x720 -> Resize() lags behind live ANativeWindow
    -> Guest Render Target: 640x480 * scale (4:3 = 1.3333)
    -> Vulkan::Present(): fillNoStretch == false
         -> src_rect = 598x448 (4:3 = 1.3348)
         -> dst_rect = 1920x1080 (16:9) or 2280x1080 (~19:9)
         -> vkCmdBlitImage stretches 4:3 -> 16:9 / 19:9 (+33% to +58% horizontal squash!)

AFTER (v0.8.15-android):
  Physical Display (2400x1080, cutoutMode=ALWAYS/shortEdges, Edge-to-Edge Immersive)
    -> SDL_Window -> host::ScreenMode() & live host::WindowPixels() (2400x1080)
    -> Resize() creates VkSwapchainKHR matching live 2400x1080 immediately
    -> Guest Render Target: ComputeScaledExtent(640, 480, scale_, max_image_dim_) (exact 4:3)
    -> Vulkan::Present() -> display_math::ComputePresentPlan(...)
         - Native 4:3:           1440x1080 centered (0% crop, distortion = 1.000)
         - 16:9 Widescreen:      1920x1080 centered (~25% vertical framing, distortion = 1.000)
         - Fullscreen No-Stretch: 2400x1080 full screen (0 black bars, framed cleanly, distortion = 1.000)
```
