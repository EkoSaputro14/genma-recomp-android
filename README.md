# Genma Onimusha — Android ARM64 Port

[![Build & Release Android APK (ARM64)](https://github.com/EkoSaputro14/genma-recomp-android/actions/workflows/build-android.yml/badge.svg)](https://github.com/EkoSaputro14/genma-recomp-android/actions/workflows/build-android.yml)
[![Latest Release](https://img.shields.io/github/v/release/EkoSaputro14/genma-recomp-android?label=Release&color=blue)](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest)
[![Platform](https://img.shields.io/badge/Android-10%2B%20(ARM64%20%7C%20Vulkan%201.1)-3DDC84?logo=android&logoColor=white)](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest)
[![GitHub Stars](https://img.shields.io/github/stars/EkoSaputro14/genma-recomp-android?style=social)](https://github.com/EkoSaputro14/genma-recomp-android/stargazers)

> **Experimental native ARM64 static recompilation port of *Genma Onimusha* (Original Xbox) for Android powered by SDL3 + Vulkan.**
>
> Always download the newest APK from [**Latest Release**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest). Help test compatibility across **MediaTek (Mali / Immortalis)**, **Samsung Exynos**, **Google Tensor**, and **Qualcomm Snapdragon (Adreno)** devices! If this project interests you, consider giving it a **Star** ⭐ and submitting a [Device Test Report](https://github.com/EkoSaputro14/genma-recomp-android/issues/new?template=device_test_report.yml).

---

## Highlights & Android Architecture

All features below are included in the [**Latest Release**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest):

- **Static Recompilation (Not an Emulator)**: Runs natively compiled ARM64 C++20 code generated from `default.xbe` via [`rafaelkenedy/genma-recomp`](https://github.com/rafaelkenedy/genma-recomp), paired with an XDK/kernel runtime and Vulkan GPU backend.
- **Dedicated Async Vulkan Render Thread**: Decouples Vulkan command encoding/presentation from x86 CPU game simulation threads, paired with `copy_cache_` snapshot reuse and integer depth upload optimizations.
- **ARM Mali / Immortalis / Exynos Compatibility**:
  - Automatic fallback from `VK_FORMAT_D24_UNORM_S8_UINT` to `VK_FORMAT_D32_SFLOAT_S8_UINT` when a GPU driver does not support packed 24-bit depth-stencil sampling/attachments (common on ARM Mali, Immortalis, and Exynos).
  - Non-sRGB swapchain preference (`VK_FORMAT_B8G8R8A8_UNORM` -> `VK_FORMAT_R8G8B8A8_UNORM`) before falling back to driver default, avoiding washed-out double-gamma presentation.
  - CPU DXT1/DXT3/DXT5 texture decode fallback when hardware BC texture compression is unavailable (`vulkan: BC textures unavailable`).
- **Built-in Touch Controls + Hot-Plug Gamepad Support**: Multi-touch virtual Xbox overlay (`Stick`, `D-Pad`, `A/B/X/Y`, `LB/LT/RB/RT`, `BACK/START`, `PAD` visibility toggle, gear icon `⚙` settings trigger, live **10%–100% opacity** & **button shape editor**, plus **community skin/layout importer** for **RetroArch `.zip`/`.cfg`**, **Winlator `.icp`**, and **Genma `.json`/`.zip`**) alongside instant in-game Bluetooth/USB physical controller hot-plug detection (`SDL3` + `InputManager`).
- **True Immersive Fullscreen & Zero-Stretch Widescreen**:
  - Edge-to-edge display cutout rendering (`shortEdges` / `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + `hideSystemBars()` on create/resume/focus) and live physical Vulkan swapchain sizing (`host::WindowPixels` + `host::ScreenMode`).
  - Three zero-distortion display strategies sharing `native/display_math.h`: **`4:3 Native` (Default)**, **`16:9 Widescreen (No-Stretch, 1:1 Pixels)`**, and **`Fullscreen No-Stretch (Adaptive)`** supporting dynamic landscape ratios (`16:9`, `18:9`, `19.5:9`, `20:9+`) with zero non-uniform stretching (`scaleX/scaleY ≈ 1.000`).
  - Five active **Vertical Framing** presets (`Top 0%`, `HUD-Safe 25%`, `Center 50%`, `Subtitle-Safe 70%`, `Bottom 100%`) plus aspect-invariant upscaling (`0.75x–3x`) and `[DISPLAY]` / `[RENDER]` / `[VULKAN]` pipeline diagnostics.
  - Engineering docs: [Root Cause Analysis](docs/WIDESCREEN_ROOT_CAUSE_ANALYSIS.md) · [Technical Implementation](docs/WIDESCREEN_IMPLEMENTATION.md) · [Verification Report](docs/WIDESCREEN_TEST_REPORT.md).
- **Bahasa Indonesia Localization**: Switch between **English (Default)** and **Bahasa Indonesia** for the Android app UI, in-game dialogue/cutscene subtitles, item names/descriptions, choices (`0xF0 <arg>`-safe), and **all 19 in-game image-based documents** (*Yuki's Letter*, *Instructions A/B/C*, *Sougen's Note*, *Journals #1–#6*, *Blue/Red/Demon King Books* rendered on-the-fly into 4bpp `OIM ` overlays without touching Ogre puzzle cipher keys or your `.iso`), plus layered `lang/id/` and `mods/` VFS overrides.

---

## Quick Start (English)

1. **Download the APK**
   - Grab the newest `GenmaOnimusha-v*-android-arm64-v8a.apk` from [**GitHub Latest Release**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) (verify with `SHA256SUMS` if desired).
   - Requirements: **Android 10+ (API 29+)**, **ARM64 (`arm64-v8a`)**, **Vulkan 1.1+**.
2. **Prepare Your Own Game Copy** *(No copyrighted assets included)*
   - Use your own legal dump of *Genma Onimusha* (Original Xbox): either a disc image (`.iso` / `.xiso`) or extracted `default.xbe` with game asset folders.
   - Put the game files in one of these locations:
     - `/sdcard/GenmaOnimusha/` (recommended — grant *All files access* when prompted, or use the in-app **ISO Importer** picker), **or**
     - `/sdcard/Android/data/com.genmarecomp.android/files/assets/`, **or**
     - `GenmaOnimusha/` at the root of an SD card / USB OTG drive.
3. **Launch & Play**
   - Once the game image or `default.xbe` is detected, booting begins automatically.
   - Tap `PAD` at the top-center of the screen to show/hide the translucent virtual Xbox controller overlay, or pair a Bluetooth/USB controller.

---

## Device Compatibility Matrix (Call for Testers!)

We are actively collecting test reports on the [**Latest Release**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) — **Mali-G52 is verified playable at 60 FPS**, and we welcome more tests on **MediaTek Dimensity/Helio (Mali & Immortalis)**, **Samsung Exynos**, **Google Tensor**, and **Qualcomm Snapdragon (Adreno)** devices!

| Device Model | SoC / GPU | Android Version | Download | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| Xiaomi POCO X3 NFC (`M2007J20CG`) | Snapdragon 732G (`SM7150`) / Adreno 618 | Android 12 (API 31) | [Latest Release](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) | **Playable (60 FPS)** | Verified up to `2x (1280×960)` + `8x` Aniso & full `2400×1080` cutout swapchain with CPU BC decode fallback |
| Xiaomi Redmi 14C (`2409BRN2CY`) | MediaTek Helio G81 Ultra (`MT6769`) / Mali-G52 MC2 | Android 16 (API 36) | [Latest Release](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) | **Playable (Smooth 60 FPS)** | Verified via [Issue #1](https://github.com/EkoSaputro14/genma-recomp-android/issues/1) (`@NoviceHunter95`): Mali WAW/subpass fix resolved black screen; smooth 60 FPS gameplay reported (`1640×720`, `fmt=37`, CPU BC decode fallback) |
| MediaTek Dimensity / Helio (Other Mali / Immortalis) | Mali / Immortalis | Android 10–16 | [Latest Release](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) | **Partially Tested — More Testers Needed!** | Mali-G52 verified; still collecting `D32_SFLOAT_S8_UINT` & `R8G8B8A8_UNORM` reports on newer Dimensity/Immortalis GPUs |
| Samsung Galaxy (Exynos) | Mali / Xclipse | Android 10–15 | [Latest Release](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) | **Untested — Testers Needed!** | Needs `genma.log` & visual feedback |
| Google Pixel (Tensor) | Mali / Immortalis | Android 12–15 | [Latest Release](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) | **Untested — Testers Needed!** | Needs `genma.log` & visual feedback |
| Snapdragon 6xx / 7xx / 8xx (Adreno) | Adreno 6xx / 7xx | Android 10–15 | [Latest Release](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) | **Partially Tested** | Report FPS & scene performance on newer Adreno GPUs |

👉 **Tested on your phone?** Please open a [**Device Test Report**](https://github.com/EkoSaputro14/genma-recomp-android/issues/new?template=device_test_report.yml) with your SoC/GPU model and `genma.log`!

---

## How to Collect `genma.log` for Bug Reports

Every run writes a diagnostic log automatically on Android:

- **Log path on device**:
  `/sdcard/Android/data/com.genmarecomp.android/files/logs/genma.log`
- You can retrieve it using:
  - **File manager** with `Android/data` access (e.g., ZArchiver / Solid Explorer / MT Manager), or
  - **PC via ADB**:
    ```bash
    adb pull /sdcard/Android/data/com.genmarecomp.android/files/logs/genma.log .
    ```
- Key lines helpful for diagnosis include `Device:`, `CPU:`, `vulkan: depth format ...`, `vulkan: swapchain ...`, and the `fps=...` performance lines.

---

## Panduan Singkat (Bahasa Indonesia)

Porting native ARM64 berbasis static recompilation untuk game Original Xbox ***Genma Onimusha*** di Android (SDL3 + Vulkan). Ini bukan emulator berat — kode game dikompilasi ke native ARM64 (`libmain.so`).

1. **Unduh APK Terbaru**: Ambil `GenmaOnimusha-v*-android-arm64-v8a.apk` langsung dari halaman [**Rilis Terbaru (Latest Release)**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) (wajib Android 10+ ARM64 dengan Vulkan 1.1+).
2. **Siapkan File Game Milik Pribadi**: Letakkan file `.iso` / `.xiso` atau folder berisi `default.xbe` ke `/sdcard/GenmaOnimusha/` atau `/sdcard/Android/data/com.genmarecomp.android/files/assets/` (atau pilih lewat menu import ISO di aplikasi).
3. **Bantu Testing Multi-Device**: [**Rilis Terbaru**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) sudah mencakup arsitektur **True Immersive Cutout Fullscreen & Zero-Stretch Widescreen** (`4:3 Native`, `16:9 Widescreen No-Stretch`, & `Fullscreen No-Stretch Adaptive` di `16:9`–`20:9+` dengan 5 preset *Vertical Framing* `Top`/`HUD-Safe`/`Center`/`Subtitle-Safe`/`Bottom`), ikon **gerigi (`⚙`)**, kontrol sentuh interaktif (**transparansi 10%–100%**, **bentuk tombol**, & **impor skin/layout komunitas RetroArch `.zip`/`.cfg`, Winlator `.icp`, `.json`**), upscaling `0.75x–3x` bebas distorsi, *hot-plug* controller Bluetooth/USB langsung saat *in-game*, serta dukungan **Bahasa Indonesia (Menu APK + Subtitle, Teks, & 19 Dokumen Dalam Game)** lewat tombol `⚙ -> Bahasa / Language`. Fallback `VK_FORMAT_D32_SFLOAT_S8_UINT` & swapchain non-sRGB tetap aktif agar GPU **MediaTek (Mali/Immortalis)** dan **Samsung Exynos** berjalan lancar. Dokumen teknis: [Root Cause](docs/WIDESCREEN_ROOT_CAUSE_ANALYSIS.md) · [Implementasi](docs/WIDESCREEN_IMPLEMENTATION.md) · [Laporan Uji](docs/WIDESCREEN_TEST_REPORT.md).
4. **Kirim Laporan & Log**: Ambil log dari `/sdcard/Android/data/com.genmarecomp.android/files/logs/genma.log` lalu kirim lewat template [**Device Test Report**](https://github.com/EkoSaputro14/genma-recomp-android/issues/new?template=device_test_report.yml). Jangan lupa kasih ⭐ **Star** jika proyek ini bermanfaat!

---

## Credits & Acknowledgments

- **Upstream Recompiler & Runtime**: [`rafaelkenedy/genma-recomp`](https://github.com/rafaelkenedy/genma-recomp) (pinned commit `33fee19ef4aec490b8970d4472fadd6c4bc99aa2` in [`tools/sync_upstream.sh`](tools/sync_upstream.sh)).
- **Android Unified Build / SDL3 / Vulkan Shader Bridge Architecture**: Inspired by [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android).
- **Dependencies**: SDL3 (`release-3.4.16`), Khronos `glslang` (`16.0.0`), `toml++` (`v3.4.0`), Android NDK (`27.2.12479018`).

## Legal Disclaimer

This repository contains **source code, build scripts, and Android compatibility patches only**. It does **not** distribute copyrighted game binaries, Xbox BIOS images, keys, or disc assets. Users must supply their own legally obtained copy of *Genma Onimusha* to run the application.
