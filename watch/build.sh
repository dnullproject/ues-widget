#!/usr/bin/env bash
# Build <app>/bin/<prefix>-<device>.prg for each device (default: all in manifest.xml).
# App: $APP_DIR, default watch (-> ues-bb); APP_DIR=watch-note -> ues-note.
# Needs `monkeyc` on PATH and the device files in ~/.Garmin/ConnectIQ/Devices.
# Signing key: $CIQ_DEVELOPER_KEY_B64 (base64 DER) if set, else a throwaway key (the .prg works for sideloading).
set -euo pipefail
cd "$(dirname "$0")/../${APP_DIR:-watch}"
case "$(basename "$PWD")" in watch-note) prefix=ues-note ;; *) prefix=ues-bb ;; esac
out="$PWD/bin"
# Build from a temp copy so the stamp (and a baked key) never touch the checkout.
src=$(mktemp -d); cp -r . "$src"; trap 'rm -rf "$src"' EXIT
# Build stamp shown small at the bottom of the app: git short sha + UTC build time.
echo "const BUILD = \"$(git rev-parse --short HEAD 2>/dev/null || echo dev)$(git diff --quiet 2>/dev/null || echo +) $(date -u +%m%d-%H%M)\";" > "$src/source/BuildInfo.mc"
# Sideloaded apps get no settings from the phone, so a personal build bakes $UES_API_KEY in (never in CI: the repo is public).
if [ -n "${UES_API_KEY:-}" ]; then
  python3 - "$src/resources/properties.xml" <<'PY'
import sys, xml.sax.saxutils as x, os
p = sys.argv[1]; s = open(p).read()
open(p, "w").write(s.replace('<property id="apiKey" type="string"></property>',
    '<property id="apiKey" type="string">' + x.escape(os.environ["UES_API_KEY"]) + '</property>'))
PY
fi
cd "$src"
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
  monkeyc -d "$d" -f monkey.jungle -o "$out/$prefix-$d.prg" -y "$key" -w
done
rm -f "$key"
