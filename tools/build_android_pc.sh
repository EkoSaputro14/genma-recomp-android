#!/usr/bin/env bash
set -euo pipefail

# Genma Onimusha — PC Recompile to Android ARM64 APK Builder
# Target: Android 29+ (arm64-v8a), Vulkan 1.0-fallback
# Usage: ./tools/build_android_pc.sh /path/to/default.xbe

XBE_PATH="${1:-assets/default.xbe}"
EXPECTED_SHA1="08bfe432eb55877b5af2dd401b713743c5b751cd"

if [[ ! -f "$XBE_PATH" ]]; then
  echo "[ERROR] File default.xbe tidak ditemukan di: $XBE_PATH"
  echo "Salin default.xbe (Genma Onimusha USA) ke folder assets/ atau kirim path sebagai argumen."
  exit 1
fi

ACTUAL_SHA1=$(sha1sum "$XBE_PATH" | awk '{print $1}')
if [[ "$ACTUAL_SHA1" != "$EXPECTED_SHA1" ]]; then
  echo "[WARN] SHA-1 XBE ($ACTUAL_SHA1) berbeda dari USA Redump ($EXPECTED_SHA1)."
  echo "Hook kontrol modern & streaming kamera diuji pada versi USA retail."
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "[ERROR] Set ANDROID_NDK_HOME terlebih dahulu (contoh: $ANDROID_HOME/ndk/27.2.12479018)."
  exit 1
fi

echo "==> 1/4 Membangun xbe_recomp & xbe_symbols native di host PC..."
cmake --preset linux-core
cmake --build --preset linux-core --target xbe_recomp xbe_symbols --parallel "$(nproc)"

echo "==> 2/4 Merekompilasi default.xbe menjadi 32 partisi C++20..."
mkdir -p generated/game
./out/build/linux-core/xbe_recomp config/game.toml --xbe "$XBE_PATH" --out generated/game

echo "==> 3/4 Cross-compiling libgenma_game.so untuk arm64-v8a (API 29)..."
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++"
mkdir -p android/app/src/main/jniLibs/arm64-v8a

$TOOLCHAIN -shared -fPIC -std=c++20 -O3 \
  -Isdk/runtime -Isrc/game \
  generated/game/*.cpp \
  src/game/modern_controls.cpp \
  src/game/background_streaming.cpp \
  src/game/math_flags.cpp \
  -o android/app/src/main/jniLibs/arm64-v8a/libgenma_game.so

echo "==> 4/4 Mengemas APK ARM64 dengan Gradle..."
(cd android && gradle assembleRelease)

echo "[SELESAI] APK siap dipasang: android/app/build/outputs/apk/release/"
