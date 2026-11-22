# Panduan Port Android ARM64 — Genma Onimusha

Port ini menggabungkan upstream [`rafaelkenedy/genma-recomp`](https://github.com/rafaelkenedy/genma-recomp) dengan pola *game loader*, SDL3 activity, dan *in-process* glslang Vulkan pipeline dari [`RanggaCasper/Black-Android`](https://github.com/RanggaCasper/Black-Android).

## 1. Cara Memuat Game di Android

1. Pasang APK dari **GitHub Releases**.
2. Buka **Genma Onimusha**.
3. Pada dialog awal, pilih salah satu:
   - **Allow access**: aktifkan *All Files Access* (Android 11+) atau izin *Storage* (Android 10), lalu salin `.iso` (Genma Onimusha USA) atau `default.xbe` + folder datanya ke:
     - `/storage/emulated/0/GenmaOnimusha`
     - atau folder `GenmaOnimusha` di root SD card / USB OTG
   - **Import game**: pilih berkas `.iso` langsung dari *Downloads*, USB, atau penyimpanan cloud tanpa memindahkan file secara manual. `GameImporter` memvalidasi header Xbox XDVDFS (`MICROSOFT*XBOX*MEDIA`) dan menyalin secara atomik (`.part` → `.iso`).
   - **Folder privat (tanpa All Files Access)**: `Android/data/com.genmarecomp.android/files/assets`.
4. `app::FindGame()` di lapisan native memindai folder game tiap 1 detik secara otomatis dan langsung menjalankan game begitu berkas ditemukan.

## 2. Build Otomatis via GitHub Actions (Standalone APK)

1. Enkripsi `default.xbe` legal (USA Redump) sekali saja:
   ```bash
   XBE_KEY="kata-sandi-kuat-anda" bash tools/encrypt_xbe_for_ci.sh assets/default.xbe
   git add ci/default.xbe.enc && git commit -m "ci: update encrypted default.xbe.enc" && git push
   ```
   > **Catatan Keamanan:** Sangat disarankan menggunakan repositori **private** apabila menyertakan `ci/default.xbe.enc`.
2. Tambahkan GitHub Actions Secret `XBE_KEY` (dan opsional `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASS`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASS`).
3. Jalankan workflow **Build & Release Android APK (ARM64)** atau push tag `v*-android*`.

## 3. Build Lokal di PC (Linux)

```bash
export ANDROID_HOME=/path/to/android-sdk
bash tools/build_android_pc.sh /path/to/GenmaOnimusha.iso
adb install -r android/app/build/outputs/apk/release/app-release.apk
```
