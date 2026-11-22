#!/usr/bin/env bash
set -euo pipefail

# Fetches pinned upstream rafaelkenedy/genma-recomp sources and applies the Android ARM64/Vulkan patch.
UPSTREAM_REPO="https://github.com/rafaelkenedy/genma-recomp.git"
UPSTREAM_COMMIT="33fee19ef4aec490b8970d4472fadd6c4bc99aa2"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "==> Fetching upstream rafaelkenedy/genma-recomp @ ${UPSTREAM_COMMIT:0:12}..."
git init -q "$TMP_DIR"
git -C "$TMP_DIR" remote add origin "$UPSTREAM_REPO"
git -C "$TMP_DIR" fetch --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$TMP_DIR" checkout -q FETCH_HEAD

echo "==> Copying upstream cmake/, config/, sdk/, src/, and tools/..."
cp -a "$TMP_DIR/cmake" "$REPO_ROOT/"
cp -a "$TMP_DIR/config" "$REPO_ROOT/"
cp -a "$TMP_DIR/CMakePresets.json" "$REPO_ROOT/"
cp -a "$TMP_DIR/src" "$REPO_ROOT/"
cp -a "$TMP_DIR/tools/xdvdfs.py" "$REPO_ROOT/tools/"
cp -a "$TMP_DIR/tools/test_"* "$REPO_ROOT/tools/"

mkdir -p "$REPO_ROOT/sdk"
# Preserve Android-specific files already present in sdk/runtime/
if command -v rsync >/dev/null 2>&1; then
  rsync -a --exclude='runtime/xdk/fiber_android.h' --exclude='runtime/core/touch_overlay.*' "$TMP_DIR/sdk/" "$REPO_ROOT/sdk/"
else
  cp -a "$TMP_DIR/sdk/." "$REPO_ROOT/sdk/"
fi

echo "==> Applying patches/genma-android.patch..."
(cd "$REPO_ROOT" && git apply --check patches/genma-android.patch && git apply patches/genma-android.patch)
echo "[OK] Upstream rafaelkenedy/genma-recomp synced and patched for Android ARM64."
