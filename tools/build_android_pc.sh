#!/usr/bin/env bash
set -euo pipefail

# Genma Onimusha — PC Recompile to Android ARM64 APK Builder (Black-Android unified libmain.so pipeline)
# Usage: bash tools/build_android_pc.sh [/path/to/default.xbe | /path/to/game.iso]

INPUT_PATH="${1:-assets/default.xbe}"
EXPECTED_SHA1="08bfe432eb55877b5af2dd401b713743c5b751cd"

if [[ ! -d "sdk/tools/recomp" || ! -f "config/game.toml" ]]; then
  bash tools/sync_upstream.sh
fi

mkdir -p assets generated/game

if [[ ! -f "$INPUT_PATH" ]]; then
  echo "[ERROR] File default.xbe / .iso tidak ditemukan di: $INPUT_PATH"
  echo "Salin default.xbe (Genma Onimusha USA) ke assets/default.xbe atau kirim path .xbe/.iso sebagai argumen."
  exit 1
fi

lower="${INPUT_PATH,,}"
if [[ "$lower" == *.iso || "$lower" == *.xiso ]]; then
  echo "==> Mengekstrak default.xbe dari disc image: $INPUT_PATH"
  python3 -I tools/xdvdfs.py "$INPUT_PATH" default.xbe assets/default.xbe
else
  if [[ "$(realpath "$INPUT_PATH")" != "$(realpath assets/default.xbe 2>/dev/null || echo assets/default.xbe)" ]]; then
    cp "$INPUT_PATH" assets/default.xbe
  fi
fi

ACTUAL_SHA1=$(sha1sum assets/default.xbe | awk '{print $1}')
if [[ "$ACTUAL_SHA1" != "$EXPECTED_SHA1" ]]; then
  echo "[WARN] SHA-1 XBE ($ACTUAL_SHA1) berbeda dari USA Redump ($EXPECTED_SHA1)."
fi

echo "==> 1/3 Membangun xbe_recomp & xbe_symbols native di host PC..."
cmake --preset linux-core
cmake --build --preset linux-core --target xbe_recomp xbe_symbols --parallel "$(nproc)"

echo "==> 2/3 Merekompilasi assets/default.xbe menjadi 32 partisi C++20..."
./out/build/linux-core/xbe_recomp config/game.toml --xdk sdk/xdk

echo "==> 3/3 Membangun APK ARM64 (libSDL3.so + libmain.so) via Gradle..."
(cd android && gradle assembleRelease --stacktrace)

echo "[SELESAI] APK siap dipasang: android/app/build/outputs/apk/release/"
