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

echo "==> 2/4 Merekompilasi default.xbe menjadi partisi C++20..."
mkdir -p assets generated/game
if [[ "$(realpath "$XBE_PATH")" != "$(realpath assets/default.xbe 2>/dev/null || echo assets/default.xbe)" ]]; then
  cp "$XBE_PATH" assets/default.xbe
fi
./out/build/linux-core/xbe_recomp config/game.toml --xdk sdk/xdk

echo "==> 3/4 Cross-compiling libgenma_game.so untuk arm64-v8a (API 29)..."
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++"
mkdir -p build-arm64-obj android/app/src/main/jniLibs/arm64-v8a

compile_unit() {
  local src="$1"
  local obj="build-arm64-obj/$(echo "$src" | tr '/.' '__').o"
  "$TOOLCHAIN" -c -fPIC -std=c++20 -O2 -fno-strict-aliasing \
    -Wno-unused-label -Wno-parentheses-equality -Wno-unused-variable -Wno-unused-function \
    -Igenerated/game -Isdk/runtime -Isrc -Isrc/game \
    "$src" -o "$obj"
}
export TOOLCHAIN
export -f compile_unit

printf '%s\n' generated/game/*.cpp src/game/modern_controls.cpp src/game/background_streaming.cpp src/game/math_flags.cpp \
  | xargs -P "$(nproc)" -I{} bash -c 'compile_unit "$1"' _ {}

"$TOOLCHAIN" -shared -Wl,--allow-shlib-undefined build-arm64-obj/*.o \
  -o android/app/src/main/jniLibs/arm64-v8a/libgenma_game.so

echo "==> 4/4 Mengemas APK ARM64 dengan Gradle..."
(cd android && gradle assembleRelease)

echo "[SELESAI] APK siap dipasang: android/app/build/outputs/apk/release/"
