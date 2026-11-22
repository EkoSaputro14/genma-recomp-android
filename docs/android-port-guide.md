# Panduan Porting Android ARM64 — Genma Onimusha

Dokumen ini menjelaskan arsitektur port Android (API 29+, `arm64-v8a`, Vulkan 1.0-fallback) untuk `rafaelkenedy/genma-recomp`.

## 1. Prinsip Legal (Asset-Free)
Rilis APK di GitHub **tidak memuat aset maupun kode biner game Capcom**. Anda wajib menggunakan salinan legal *Genma Onimusha (USA)* (`default.xbe` SHA-1: `08bfe432eb55877b5af2dd401b713743c5b751cd`).

## 2. Alur Build Rekompilasi PC -> APK ARM64
1. Pasang **Android NDK 27.2.12479018**, **CMake 3.25+**, **Clang (C++20)**, dan **Gradle 8.10+** di PC Linux / WSL2 / macOS.
2. Letakkan `default.xbe` beserta folder game hasil ekstrak di direktori `assets/`.
3. Jalankan skrip cross-compile:
   ```bash
   export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
   chmod +x tools/build_android_pc.sh
   ./tools/build_android_pc.sh assets/default.xbe
   ```
4. Pasang APK hasil kompilasi ke perangkat Android via ADB:
   ```bash
   adb install -r android/app/build/outputs/apk/release/app-release.apk
   ```

## 3. Memasang HD Texture Pack (~9,9 GB)
- Ekstrak `GenmaOnimusha-HD-textures.zip` ke penyimpanan internal perangkat Anda (misalnya `Documents/GenmaOnimusha/textures/`).
- Buka aplikasi di Android, ketuk **Pilih HD Texture Pack**, lalu pilih folder tersebut melalui Storage Access Framework (SAF).
- Batas cache RAM mobile diatur ke **512 MB** untuk mencegah OOM pada perangkat 4–8 GB RAM.

## 4. Ringkasan Komponen Port Android
- `sdk/runtime/xdk/fiber_android.h`: Context switcher ARM64 (AArch64) menggantikan `<ucontext.h>`.
- `sdk/runtime/kernel/memory_android.cpp`: Reservasi virtual 4 GiB + dual alias fisik (`SYS_memfd_create` & `ASharedMemory_create`).
- `sdk/runtime/gpu/vulkan_android.h`: Penjaga kapabilitas Vulkan 1.0+ fallback untuk GPU Adreno/Mali.
- `sdk/runtime/core/touch_overlay.cpp`: Overlay sentuh multi-touch + auto-hide saat controller Bluetooth/USB-C aktif.
- `.github/workflows/build-android.yml`: Pipeline CI otomatis untuk build & publikasi APK ke GitHub Releases.
