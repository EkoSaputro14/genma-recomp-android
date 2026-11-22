#!/usr/bin/env bash
set -euo pipefail

# Enkripsi default.xbe untuk build Standalone APK otomatis di GitHub Actions (pola Black-Android)
# Penggunaan:
#   XBE_KEY="kata-sandi-kuat-anda" bash tools/encrypt_xbe_for_ci.sh /path/to/default.xbe
# Lalu tambahkan secret GitHub bernama XBE_KEY dengan nilai yang sama di Settings -> Secrets -> Actions.

XBE_INPUT="${1:-assets/default.xbe}"
if [[ ! -f "$XBE_INPUT" ]]; then
  echo "[ERROR] Berkas default.xbe tidak ditemukan: $XBE_INPUT"
  exit 1
fi

if [[ -z "${XBE_KEY:-}" ]]; then
  echo "[ERROR] Set environment variable XBE_KEY terlebih dahulu."
  exit 1
fi

mkdir -p ci
key_file=$(mktemp)
trap 'rm -f "$key_file"' EXIT
chmod 600 "$key_file"
printf '%s' "$XBE_KEY" > "$key_file"

openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
  -in "$XBE_INPUT" \
  -out ci/default.xbe.enc \
  -pass "file:$key_file"

echo "[OK] Terenkripsi di ci/default.xbe.enc"
echo "Commit ci/default.xbe.enc ke repo (disarankan repo private) dan simpan XBE_KEY di GitHub Actions Secrets."
