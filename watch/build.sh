#!/usr/bin/env bash
# Build watch/bin/ues-bb-<device>.prg for each device (default: all in manifest.xml).
# Needs `monkeyc` on PATH and the device files in ~/.Garmin/ConnectIQ/Devices.
# Signing key: $CIQ_DEVELOPER_KEY_B64 (base64 DER) if set, else a throwaway key (the .prg works for sideloading).
set -euo pipefail
cd "$(dirname "$0")"
devices=("$@")
[ ${#devices[@]} -gt 0 ] || mapfile -t devices < <(grep -o 'product id="[^"]*"' manifest.xml | cut -d'"' -f2)
key=$(mktemp --suffix=.der)
trap 'rm -f "$key"' EXIT
if [ -n "${CIQ_DEVELOPER_KEY_B64:-}" ]; then
  echo "$CIQ_DEVELOPER_KEY_B64" | base64 -d > "$key"
else
  openssl genrsa 4096 2>/dev/null | openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -out "$key"
fi
mkdir -p bin
for d in "${devices[@]}"; do
  monkeyc -d "$d" -f monkey.jungle -o "bin/ues-bb-$d.prg" -y "$key" -w
done
