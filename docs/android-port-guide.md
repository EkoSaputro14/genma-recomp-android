# Panduan Port Android ARM64 — Genma Onimusha

Dokumen ini menjelaskan cara memasang dan membangun port Android (API 29+, `arm64-v8a`, Vulkan 1.0-fallback) untuk `rafaelkenedy/genma-recomp`.

## 1. Mode Standalone APK (Langsung di HP Tanpa PC — Pola Black-Android)
Agar pemain di Android cukup memasang APK dan menyalin `.iso` tanpa perlu menjalankan compiler di PC:

1. **Siapkan repositori fork** dari `rafaelkenedy/genma-recomp` (disarankan **private** bila menyertakan berkas terenkripsi).
2. **Enkripsi `default.xbe` legal Anda sekali saja**:
   ```bash
   XBE_KEY="sandi-rahasia-anda" ./tools/encrypt_xbe_for_ci.sh assets/default.xbe
   git add ci/default.xbe.enc && git commit -m "ci: add encrypted xbe for standalone apk" && git push
   ```
3. **Tambahkan GitHub Actions Secret** bernama `XBE_KEY` di *Settings → Secrets and variables → Actions*.
4. **Jalankan workflow `Build & Release Android APK (ARM64)`** — server GitHub Actions akan mendekripsi XBE, menjalankan `xbe_recomp`, mengompilasi `libgenma_game.so` (`arm64-v8a`), membundelnya ke dalam APK, dan menerbitkannya di **GitHub Releases**.
5. **Pasang di HP Android**:
   - Unduh dan pasang APK dari **GitHub Releases**.
   - Ketuk **Izinkan Akses Storage** (*All Files Access* di Android 11+, atau *Storage* di Android 10).
   - Salin file `.iso` (atau ekstrak `default.xbe` + folder data game) ke `/storage/emulated/0/GenmaOnimusha`.
   - Opsional: ekstrak HD Texture Pack ke `/storage/emulated/0/GenmaOnimusha/textures/` (cache RAM mobile: **1152 MB**).

## 2. Mode Build Lokal di PC (Opsional)
```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
chmod +x tools/build_android_pc.sh
./tools/build_android_pc.sh assets/default.xbe
adb install -r android/app/build/outputs/apk/release/app-release.apk
```
