# Technical Implementation — True Widescreen, Immersive Fullscreen, & Zero-Stretch Vulkan Pipeline (`v0.8.15-android`)

> **Target Repository**: [`EkoSaputro14/genma-recomp-android`](https://github.com/EkoSaputro14/genma-recomp-android)  
> **Reference Design**: [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android) (MIT License — studied and adapted with proper attribution; Genma rendering engine and static recompilation architecture preserved intact)

---

## 1. Architecture Overview

The display pipeline now enforces a **single mathematical invariant** from Android window creation through Vulkan swapchain presentation:

$$\text{distortion\_ratio} = \frac{\text{scale}_x}{\text{scale}_y} \approx 1.000 \quad \text{(zero non-uniform stretching across all modes and scales)}$$

Changes are cleanly layered so upstream-synced `sdk/` modifications reside exclusively in `patches/03-v0.8.15-widescreen-display-pipeline.patch` without mutating prior patches (`patches/genma-android.patch`, `patches/02-v0.8.14-widescreen-hotplug.patch`).

---

## 2. Adopted Techniques & Attribution (`Black-Android` MIT Reference)

Per the MIT License of [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android), the following display and Vulkan lifecycle techniques were studied and adapted for *Genma Onimusha*'s `4:3` offscreen blit + `.bgs` architecture:

| Reference Technique | `Black-Android` Implementation | Genma Adaptation (`v0.8.15-android`) |
|---|---|---|
| Physical display detection & landscape normalization | `native/main.cpp:83–93` (`ScreenMode()` multiplies `SDL_GetCurrentDisplayMode` by `pixel_density` and normalizes `{max(w,h), min(w,h)}`) | `native/main.cpp` (`DetectPhysicalDisplay()`, `host::PhysicalDisplay()`, `host::ScreenMode()`) + `display_math::NormalizeLandscape()` + `sdk/runtime/app/settings.cpp` (`Desktop()`, `Widescreen()`) |
| Live pixel-size window query inside Vulkan | `native/vulkan.cpp:1457–1463, 3132–3137` (`host::WindowPixels(w, h)` inside `CreateSwapchain` & `AcquireImage` with `caps.currentExtent` fallback) | `sdk/runtime/gpu/vulkan.cpp` (`Vulkan::Resize()` queries live `host::WindowPixels(win_w, win_h)` directly instead of stale `app::LinuxWindowSize()` / `1280×720`) |
| Surface epoch & bounded swapchain acquire | `native/vulkan.cpp:3124–3152` (`SurfaceEpoch()` recreation + `500ms` bounded `vkAcquireNextImageKHR` timeout handling `VK_TIMEOUT`) | `sdk/runtime/gpu/vulkan.cpp` (`RecreateSurface()` + `500ms` Android acquire timeout gracefully skipping transient unready frames instead of aborting) |
| Widescreen mode detection without stale cache | `native/main.cpp:83–93` + `src/common/aspect.h` | `sdk/runtime/app/settings.cpp` (`Widescreen()` & `FrameAspect()` evaluate live `host::ScreenMode()` and `host::DisplayMode()`, removing the stuck `(0,0)` static cache) |
| Render scaling invariance | `native/vulkan.cpp` scaled target allocation + `DrawFrame` aspect preservation | `native/display_math.h` (`ComputeScaledExtent()` clamps using a single uniform `eff_scale` bounded by `max_image_dim_`, preserving `4:3` across `0.75x–3x`) |
| Edge-to-edge fullscreen presentation | `BlackActivity.java` + `styles.xml` landscape immersive handling | `styles.xml` (`shortEdges` cutout mode) + `GenmaActivity.java` (`configureTrueFullscreen()` with API 30+ `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + `setDecorFitsSystemWindows(false)`, `hideSystemBars()` in `onCreate`/`onResume`/`onWindowFocusChanged`) |

**Key Engine Difference Preserved**: Unlike *Black* (real-time 3D camera FOV expansion in `src/game/hooks.cpp` / `hud.cpp`), *Genma Onimusha* composites 3D characters over pre-rendered 2D `640×480` `.bgs` room backgrounds (`src/common/game_info.cpp:27`). Therefore, Genma preserves `4:3` guest geometry internally and achieves undistorted widescreen at presentation via `display_math::ComputePresentPlan()`.

---

## 3. Mathematical Formulation (`native/display_math.h`)

### A. Uniform Internal Upscaling (`ComputeScaledExtent`)

Given guest surface $(g_w, g_h)$, requested multiplier $s \in [0.75, 3.0]$ (`0.75x, 1x, 1.5x, 2x, 2.5x, 3x`), and GPU limit $M = \texttt{maxImageDimension2D}$:

$$s_{\text{eff}} = \min\!\left(\text{clamp}(s, 0.25, 4.0),\; \frac{M}{g_w},\; \frac{M}{g_h}\right)$$
$$(t_w, t_h) = \left(\text{round}(g_w \cdot s_{\text{eff}}),\; \text{round}(g_h \cdot s_{\text{eff}})\right)$$

Internal render targets, viewports (`vkCmdSetViewport`), scissors (`vkCmdSetScissor`), and gamma passes operate in $(t_w, t_h)$ space at exact `4:3` proportions, completely independent of swapchain resolution $(W_s, H_s)$.

### B. Presentation Rects (`ComputePresentPlan`)

1. **Symmetric Overscan Matte Removal** (default `overscan_pct = 3.33%`, matching Genma's `1/30` console black frame):
   $$c_x = \text{round}(src\_w \cdot \tfrac{\text{overscan}}{100}), \quad c_y = \text{round}(src\_h \cdot \tfrac{\text{overscan}}{100})$$
   $$s_w = src\_w - 2c_x, \quad s_h = src\_h - 2c_y, \quad A_{\text{src}} = \frac{s_w}{s_h} \approx \frac{4}{3}$$

2. **Destination Output Rectangle Inside Swapchain $(W_s, H_s)$**:
   - **`DisplayMode::Native4x3` (`0`)**: fits $A_{\text{src}}$ (`4:3`) centered inside $(W_s, H_s)$; `cropped_vertical = false`, `100%` of original frame visible.
   - **`DisplayMode::Widescreen16x9` (`1`)**: fits $A_{\text{target}} = 16/9 \approx 1.7778$ centered inside $(W_s, H_s)$. Fills `16:9` displays completely and caps vertical crop to `~25%` even on `19.5:9`–`21:9` phones.
   - **`DisplayMode::FullscreenNoStretch` (`2`)**: $d_x = 0, d_y = 0, d_w = W_s, d_h = H_s$. Occupies **100%** of the dynamic physical swapchain (`16:9`, `18:9`, `19.5:9`, `20:9`, `21:9`, etc.) with **zero black bars**.

3. **Zero-Stretch Source Aspect Matching + Vertical Framing**:
   When `mode != Native4x3` and $A_{\text{dst}} = \frac{d_w}{d_h} > A_{\text{src}}$:
   $$vis\_h = \text{clamp}\!\left(\text{round}\!\left(\frac{s_w}{A_{\text{dst}}}\right),\; 1,\; s_h\right)$$
   $$sy_0 = c_y + \text{round}((s_h - vis\_h) \cdot \text{pan}), \quad sy_1 = sy_0 + vis\_h$$
   where $\text{pan} \in [0.0, 1.0]$ selects:
   - `0.00` — **Top** (`0%`, keeps top border/status area intact)
   - `0.25` — **HUD-Safe** (`25%`)
   - `0.50` — **Center** (`50%`, symmetric / `16:9` FMVs)
   - `0.70` — **Subtitle-Safe** (`70%`, default for widescreen modes)
   - `1.00` — **Bottom** (`100%`, full lower dialogue/journal subtitle area)

   Resulting pixel scales:
   $$\text{scale}_x = \frac{d_w}{sx_1 - sx_0}, \quad \text{scale}_y = \frac{d_h}{vis\_h} \implies \frac{\text{scale}_x}{\text{scale}_y} = 1.000 \pm 0.005$$

---

## 4. Modified Components

- `native/display_math.h`: Header-only C++20 display geometry, aspect math, uniform upscaling, and vertical framing helpers shared by `vulkan.cpp` and unit tests.
- `native/host.h` & `native/main.cpp`:
  - Removed hardcoded `1280×720` `g_size` default (`0` until live surface attaches).
  - Added `DetectPhysicalDisplay()`, `host::PhysicalDisplay()`, `host::ScreenMode()`, `host::DisplayMode()`, and `host::SetDisplayMode()`.
  - Guarded `RefreshWindowSize()` against zero and transient portrait (`w < h`) sizes.
  - Synchronized `display_mode` cleanly between Android `SharedPreferences` (`GenmaActivity`), JNI (`nativeSetDisplayMode`, `nativeGetGraphicsSettings`, `nativeApplyGraphicsSettings`), and `settings.toml` (`app::Settings::aspect`).
- `android/app/src/main/res/values/styles.xml`, `AndroidManifest.xml`, `GenmaActivity.java`:
  - Enabled cutout edge-to-edge rendering (`shortEdges` / `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + `setDecorFitsSystemWindows(false)`).
  - Applied `hideSystemBars()` in `onCreate()`, `onResume()`, and `onWindowFocusChanged(true)`.
  - Exposed the 3 distortion-free **Display / Widescreen Modes** (`Native 4:3` default, `16:9 Widescreen No-Stretch`, `Fullscreen No-Stretch Adaptive`) and all **5 Vertical Framing** presets (`Top 0%`, `HUD-Safe 25%`, `Center 50%`, `Subtitle-Safe 70%`, `Bottom 100%`).
- `sdk/runtime/app/settings.cpp` & `sdk/runtime/gpu/vulkan.cpp` (via `patches/03-v0.8.15-widescreen-display-pipeline.patch`):
  - Fixed `Desktop()`, `Widescreen()`, and `FrameAspect()` for Android physical pixels & display modes.
  - Updated `Vulkan::ScaledExtent`, `Vulkan::RecreateSurface`, `Vulkan::Resize` (live `host::WindowPixels`), and `Vulkan::Present` (`ComputePresentPlan` + state-change-only `[DISPLAY]`, `[RENDER]`, `[VULKAN]` diagnostics).
  - Preserved all Mali/Immortalis compatibility fallbacks (`D32_SFLOAT_S8_UINT`, non-sRGB swapchain preference, CPU DXT fallback, WAW clear barrier).

---

## 5. Structured Diagnostic Logs (`genma.log` / `logcat`)

Emitted once on startup and on every surface recreate, swapchain resize, or settings change:

```text
[DISPLAY] physical=2400x1080 (density=3.00) sdl_window=2400x1080 output=2400x1080 display_aspect=2.2222 mode=Fullscreen No-Stretch (Adaptive)
[RENDER] internal_guest=640x480 render_target=1280x960 render_scale=2.00x game_aspect=1.3333 output_aspect=2.2222 framing=Subtitle-Safe (70%) (pan=0.70, vcrop=40.0%)
[VULKAN] swapchain=2400x1080 viewport=1280x960 scissor=(0,0 1280x960) src_blit=(43,190)-(1237,848) dst_blit=(0,0)-(2400,1080) scale_xy=(2.0101,1.9878) distortion=1.0112 surface_recreates=0
```
