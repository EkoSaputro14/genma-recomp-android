# Genma Onimusha — Android ARM64 Port

[![Build & Release Android APK (ARM64)](https://github.com/EkoSaputro14/genma-recomp-android/actions/workflows/build-android.yml/badge.svg)](https://github.com/EkoSaputro14/genma-recomp-android/actions/workflows/build-android.yml)
[![Latest Release](https://img.shields.io/github/v/release/EkoSaputro14/genma-recomp-android?label=Release&color=blue)](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest)
[![Platform](https://img.shields.io/badge/Android-10%2B%20(ARM64%20%7C%20Vulkan%201.1)-3DDC84?logo=android&logoColor=white)](https://github.com/EkoSaputro14/genma-recomp-android/releases)
[![GitHub Stars](https://img.shields.io/github/stars/EkoSaputro14/genma-recomp-android?style=social)](https://github.com/EkoSaputro14/genma-recomp-android/stargazers)

> **Experimental native ARM64 static recompilation port of *Genma Onimusha* (Original Xbox) for Android powered by SDL3 + Vulkan.**
>
> Help test compatibility across **MediaTek (Mali / Immortalis)**, **Samsung Exynos**, **Google Tensor**, and **Qualcomm Snapdragon (Adreno)** devices! If this project interests you, consider giving it a **Star** ⭐ and submitting a [Device Test Report](https://github.com/EkoSaputro14/genma-recomp-android/issues/new?template=device_test_report.yml).

---

## Highlights & Android Architecture

- **Static Recompilation (Not an Emulator)**: Runs natively compiled ARM64 C++20 code generated from `default.xbe` via [`rafaelkenedy/genma-recomp`](https://github.com/rafaelkenedy/genma-recomp), paired with an XDK/kernel runtime and Vulkan GPU backend.
- **Dedicated Async Vulkan Render Thread (`v0.8.0-android.7+`)**: Decouples Vulkan command encoding/presentation from x86 CPU game simulation threads, paired with `copy_cache_` snapshot reuse and integer depth upload optimizations.
- **ARM Mali / Immortalis / Exynos Compatibility (`v0.8.0-android.8`)**:
  - Automatic fallback from `VK_FORMAT_D24_UNORM_S8_UINT` to `VK_FORMAT_D32_SFLOAT_S8_UINT` when a GPU driver does not support packed 24-bit depth-stencil sampling/attachments (common on ARM Mali, Immortalis, and Exynos).
  - Non-sRGB swapchain preference (`VK_FORMAT_B8G8R8A8_UNORM` -> `VK_FORMAT_R8G8B8A8_UNORM`) before falling back to driver default, avoiding washed-out double-gamma presentation.
  - CPU DXT1/DXT3/DXT5 texture decode fallback when hardware BC texture compression is unavailable (`vulkan: BC textures unavailable`).
- **Built-in Touch Controls + Gamepad Support**: Multi-touch virtual Xbox overlay (`Stick`, `D-Pad`, `A/B/X/Y`, `LB/LT/RB/RT`, `BACK/START`, `PAD` visibility toggle, and interactive `CFG` drag/resize layout editor) plus SDL3 & Android OEM controller support.
- **16:9 / Full-Screen No-Stretch ("Tanpa Gepeng") + Upscaling (`v0.8.0-android.11`)**: Keeps exact 1:1 square pixels via configurable vertical framing (`Top / Center / Subtitle-Safe / Bottom`) alongside `0.75x–3x` internal resolution upscaling, while keeping `4:3 (1x Native)` untouched as default.
- **Bahasa Indonesia Localization (`v0.8.0-android.13`)**: Switch between **English (Default)** and **Bahasa Indonesia** for the Android app UI, in-game dialogue/cutscene subtitles, item names/descriptions, choices (`0xF0 <arg>`-safe), and **all 19 in-game image-based documents** (*Yuki's Letter*, *Instructions A/B/C*, *Sougen's Note*, *Journals #1–#6*, *Blue/Red/Demon King Books* rendered on-the-fly into 4bpp `OIM ` overlays without touching Ogre puzzle cipher keys or your `.iso`), plus layered `lang/id/` and `mods/` VFS overrides.

---

## Quick Start (English)

1. **Download the APK**
   - Grab the latest `GenmaOnimusha-v*-android-arm64-v8a.apk` from [**GitHub Releases**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) (verify with `SHA256SUMS` if desired).
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

We are actively collecting test reports — **especially on MediaTek Dimensity/Helio (Mali & Immortalis), Samsung Exynos, and Google Tensor** devices in `v0.8.0-android.8`+!

| Device Model | SoC / GPU | Android Version | APK Version | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| Xiaomi POCO X3 NFC (`M2007J20CG`) | Snapdragon 732G (`SM7150`) / Adreno 618 | Android 12 (API 31) | `v0.8.0-android.7` | Playable (up to 60 FPS) | Verified with CPU BC decode fallback; minor scene-dependent slowdowns remain |
| MediaTek Dimensity / Helio | Mali / Immortalis | Android 10–15 | `v0.8.0-android.8` | **Untested — Testers Needed!** | Needs verification for `D32_SFLOAT_S8_UINT` depth-stencil & `R8G8B8A8_UNORM` swapchain |
| Samsung Galaxy (Exynos) | Mali / Xclipse | Android 10–15 | `v0.8.0-android.8` | **Untested — Testers Needed!** | Needs `genma.log` & visual feedback |
| Google Pixel (Tensor) | Mali / Immortalis | Android 12–15 | `v0.8.0-android.8` | **Untested — Testers Needed!** | Needs `genma.log` & visual feedback |
| Snapdragon 6xx / 7xx / 8xx (Adreno) | Adreno 6xx / 7xx | Android 10–15 | `v0.8.0-android.8` | **Partially Tested** | Report FPS & scene performance on newer Adreno GPUs |

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

1. **Unduh APK**: Ambil `GenmaOnimusha-v*-android-arm64-v8a.apk` dari halaman [**Releases**](https://github.com/EkoSaputro14/genma-recomp-android/releases/latest) (wajib Android 10+ ARM64 dengan Vulkan 1.1+).
2. **Siapkan File Game Milik Pribadi**: Letakkan file `.iso` / `.xiso` atau folder berisi `default.xbe` ke `/sdcard/GenmaOnimusha/` atau `/sdcard/Android/data/com.genmarecomp.android/files/assets/` (atau pilih lewat menu import ISO di aplikasi).
3. **Bantu Testing Multi-Device**: Rilis terbaru dilengkapi kontrol sentuh interaktif (`CFG`), upscaling `0.75x–3x`, mode **16:9 / Layar Penuh Tanpa Gepeng**, serta dukungan **Bahasa Indonesia (Menu APK + Subtitle & Teks Dalam Game)** lewat tombol `CFG -> Language / Bahasa`. Fallback `VK_FORMAT_D32_SFLOAT_S8_UINT` & swapchain non-sRGB tetap aktif agar GPU **MediaTek (Mali/Immortalis)** dan **Samsung Exynos** berjalan lancar.
4. **Kirim Laporan & Log**: Ambil log dari `/sdcard/Android/data/com.genmarecomp.android/files/logs/genma.log` lalu kirim lewat template [**Device Test Report**](https://github.com/EkoSaputro14/genma-recomp-android/issues/new?template=device_test_report.yml). Jangan lupa kasih ⭐ **Star** jika proyek ini bermanfaat!

---

## Credits & Acknowledgments

- **Upstream Recompiler & Runtime**: [`rafaelkenedy/genma-recomp`](https://github.com/rafaelkenedy/genma-recomp) (pinned commit `33fee19ef4aec490b8970d4472fadd6c4bc99aa2` in [`tools/sync_upstream.sh`](tools/sync_upstream.sh)).
- **Android Unified Build / SDL3 / Vulkan Shader Bridge Architecture**: Inspired by [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android).
- **Dependencies**: SDL3 (`release-3.4.16`), Khronos `glslang` (`16.0.0`), `toml++` (`v3.4.0`), Android NDK (`27.2.12479018`).

## Legal Disclaimer

This repository contains **source code, build scripts, and Android compatibility patches only**. It does **not** distribute copyrighted game binaries, Xbox BIOS images, keys, or disc assets. Users must supply their own legally obtained copy of *Genma Onimusha* to run the application.
