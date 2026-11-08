#!/usr/bin/env bash
# Build watch/bin/ues-bb-<device>.prg for each device (default: all in manifest.xml).
# Needs `monkeyc` on PATH and the device files in ~/.Garmin/ConnectIQ/Devices.
# Signing key: $CIQ_DEVELOPER_KEY_B64 (base64 DER) if set, else a throwaway key (the .prg works for sideloading).
set -euo pipefail
cd "$(dirname "$0")"
out="$PWD/bin"
# Sideloaded apps get no settings from the phone, so a personal build bakes $UES_API_KEY in (never in CI: the repo is public).
if [ -n "${UES_API_KEY:-}" ]; then
  src=$(mktemp -d); cp -r . "$src"; trap 'rm -rf "$src"' EXIT
  python3 - "$src/resources/properties.xml" <<'PY'
import sys, xml.sax.saxutils as x, os
p = sys.argv[1]; s = open(p).read()
open(p, "w").write(s.replace('<property id="apiKey" type="string"></property>',
    '<property id="apiKey" type="string">' + x.escape(os.environ["UES_API_KEY"]) + '</property>'))
PY
  cd "$src"
fi
devices=("$@")
[ ${#devices[@]} -gt 0 ] || mapfile -t devices < <(grep -o 'product id="[^"]*"' manifest.xml | cut -d'"' -f2)
key=$(mktemp --suffix=.der)
if [ -n "${CIQ_DEVELOPER_KEY_B64:-}" ]; then
  echo "$CIQ_DEVELOPER_KEY_B64" | base64 -d > "$key"
else
  openssl genrsa 4096 2>/dev/null | openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -out "$key"
fi
mkdir -p "$out"
for d in "${devices[@]}"; do
  monkeyc -d "$d" -f monkey.jungle -o "$out/ues-bb-$d.prg" -y "$key" -w
done
rm -f "$key"
