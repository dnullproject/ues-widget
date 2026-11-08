# UES Body Battery watch app

Connect IQ widget for the Garmin Fenix 8 AMOLED (43 / 47 / 51 mm, plus Fenix 8 Pro 47 mm). Garmin Connect only syncs the
watch to the cloud a few times a day, so UES sees a stale Body Battery. This app reads the newest Body Battery sample on
the watch every 5 minutes and POSTs it straight to UES:

```
POST <baseUrl>/api/body-battery        header X-API-Key: <apiKey>
{"level": 72, "timestamp": 1731070000, "source": "watch"}
```

A sample is sent once; if its timestamp equals the last successfully sent one, nothing is posted. The glance and the
widget view show the last sent level, its time (HH:mm) and the last HTTP status (201 = ok, 401 = bad key, 400 = bad input,
negative = Garmin error, e.g. -104 = phone not connected: the watch sends requests through the phone's Bluetooth).

## Settings

- `baseUrl`, default `https://memory-ues.kudu-skate.ts.net` (the phone needs Tailscale; Garmin Connect makes the request)
- `apiKey`, no default (never committed)

A sideloaded app gets no settings from the phone, so the personal build bakes the key in:
`just watch-prg` (repo root) reads `UES_API_KEY` from 1Password via fnox and writes `watch/bin/ues-bb-<device>.prg`.
Never attach such a build to a release or CI artifact: this repo is public. CI builds keyless, only to prove it compiles.
A Connect IQ store (beta) install does get phone settings, so a keyless build works there.

## Local toolchain (agent VM)

`~/.local/bin/connect-iq-sdk-manager` (lindell/connect-iq-sdk-manager-cli), logged in with 1Password item `garmin-connect-iq`;
SDK 9.3.0 and device `fenix847mm` live in `~/.Garmin/ConnectIQ`; `monkeyc` needs Java 17 on PATH (Zulu in `~/.local/jdk`).
Verified: `fenix847mm` (covers the 47 and 51 mm cases) compiles with SDK 9.3.0.

## Install

1. `just watch-prg fenix847mm` (or `fenix843mm`, `fenix8pro47mm`; a `.prg` only runs on its own device).
2. Connect the watch over USB, copy the file to `GARMIN/APPS/`, eject.
3. Open the widget once on the watch: it registers the 5 minute background event. The view then shows the last sent
   level, its time and the last HTTP status (201 = stored, 401 = wrong key, negative = no phone/connection).

## Battery

One short web request every 5 minutes (about 290 per day) over the phone link. Expect a small drain, roughly a few
percent per day; if it matters, raise `300` in `source/UesBbApp.mc` (Garmin's minimum is 300 s).

## Build

CI job `watch` in `.github/workflows/build.yml` builds one `.prg` per device. Locally: install the Connect IQ SDK and
the device files (SDK Manager), put `monkeyc` on `PATH`, then `watch/build.sh [device...]`; output in `watch/bin/`.

### What the owner must provide to CI

Garmin serves device files only to a logged-in Garmin account, so CI cannot fetch them anonymously (the SDK zip is public).
Pick one:

- Secret `CIQ_DEVICES_B64` (simplest, no password in GitHub): after downloading the four devices in the Connect IQ
  SDK Manager on your computer, run
  `tar czf - -C ~/.Garmin/ConnectIQ/Devices fenix843mm fenix847mm fenix8pro47mm | base64 -w0`
  (macOS: `base64 | tr -d '\n'`; the folder is `~/Library/Application Support/Garmin/ConnectIQ/Devices`) and store the output.
- Or secrets `GARMIN_USERNAME`, `GARMIN_PASSWORD` (no MFA on that account) and repository variable `CIQ_AGREEMENT_HASH`
  (run `connect-iq-sdk-manager agreement view` from
  [lindell/connect-iq-sdk-manager-cli](https://github.com/lindell/connect-iq-sdk-manager-cli) and read the license
  first; it prints the hash).

Optional secret `CIQ_DEVELOPER_KEY_B64`: a stable signing key (base64 DER). Without it every build uses a throwaway key,
which is fine for sideloading but not for updating a store listing. Create it with:

```
openssl genrsa 4096 | openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -out developer_key.der
base64 -w0 developer_key.der
```

Without either device secret the job only prints a warning and builds nothing.
