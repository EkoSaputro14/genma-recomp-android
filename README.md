# genma-recomp-android

Port native Android ARM64 (API 29+, Vulkan) untuk **Genma Onimusha** (original Xbox), hasil adaptasi dari upstream [`rafaelkenedy/genma-recomp`](https://github.com/rafaelkenedy/genma-recomp) dengan mekanisme *game loader*, SDL3 activity, dan *in-process* glslang shader pipeline mengikuti arsitektur [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android).

> [!NOTE]
> Repositori ini tidak menyediakan aset game terbuka. Pengguna wajib menyediakan salinan legal *Genma Onimusha (USA)* sendiri (`.iso` atau `default.xbe` + folder data).

## Cara Memuat Game di Android (Pola Black-Android)

1. Pasang APK dari **GitHub Releases**.
2. Buka **Genma Onimusha** dan izinkan **All Files Access** (Android 11+) atau izin **Storage** (Android 10).
3. Pilih salah satu metode berikut:
   - **Import langsung dari aplikasi**: ketuk **Import game** pada dialog awal dan pilih berkas `.iso` dari *Downloads*, SD card, USB OTG, atau penyimpanan cloud. Aplikasi memvalidasi header XDVDFS (`MICROSOFT*XBOX*MEDIA`) dan menyalin secara atomik (`.part` → `.iso`).
   - **Salin manual**: letakkan `.iso` atau `default.xbe` beserta folder datanya di `/storage/emulated/0/GenmaOnimusha`, folder `GenmaOnimusha` di root SD card, atau `Android/data/com.genmarecomp.android/files/assets`.
4. Native `app::FindGame()` memindai folder game tiap detik secara otomatis—begitu file tersedia, game langsung *boot* tanpa perlu restart APK.

## Arsitektur Teknis

- **Unified Native Library (`libmain.so` + `libSDL3.so`)**: dibangun dari satu root [CMakeLists.txt](CMakeLists.txt) yang menyatukan `sdk/runtime` portable, hook game `src/`, host Android `native/`, dan 32 partisi hasil `xbe_recomp` (`generated/game/`).
- **Vulkan Mobile Compatibility**: kompilasi HLSL → SPIR-V berjalan *in-process* melalui `glslang` ([native/vk_shader.cpp](native/vk_shader.cpp)) dengan *disk cache* (menggantikan `posix_spawnp("dxc")`), dilengkapi CPU DXT1/3/5 decode fallback untuk GPU mobile tanpa `textureCompressionBC` dan penanganan *surface epoch* saat *background/foreground*.
- **ARM64 Bionic Fiber**: context switcher AAPCS64 BTI-safe ([sdk/runtime/xdk/fiber_android.h](sdk/runtime/xdk/fiber_android.h)) yang diaktifkan otomatis oleh `sdk/runtime/xdk/fiber.cpp` pada `#ifdef __ANDROID__`.
- **Input**: dukungan penuh gamepad Bluetooth/USB via SDL3, jembatan *loose OEM controller* (MIUI/virtualized pad), serta *touch overlay* virtual.

## Build Lokal & CI

Lihat panduan lengkap di [docs/android-port-guide.md](docs/android-port-guide.md).
