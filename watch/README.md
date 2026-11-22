# UES watch app

One Connect IQ watch app (not a widget) for the Garmin Fenix 8 AMOLED 47/51 mm (`fenix847mm`; other devices are not
downloaded on the agent VM). It replaces the former "UES BB" widget and "UES Note" app; the app id is the old UES Note
one (`6D1B2C8E...`), so a note queue left in `Application.Storage` survives the update. Remove `ues-bb-*.prg` and
`ues-note-*.prg` from `GARMIN/APPS/` before copying the new file. Needs API level 6.0.3 (`Toybox.Audio.Capture`),
permissions AudioInput, Background, Communications, SensorHistory.

## What it does

- Glance (watch-app "super app" glance): `BB <level> · Q <n>` (Q only when notes are queued); `BB <level> · ⏰ HH:MM` instead when an alarm fires within 60 min.
- Background, every 5 min (always registered at app start): (0) fire due watch alarms, (a) POST the newest Body Battery
  sample, (b) fetch alarms (at most every 25 min), (c) upload the voice-note queue. One web request at a time.
- Foreground, three pages switched with UP/DOWN:

| Page | START | Other keys |
|------|-------|------------|
| Record | idle: start recording (nothing records on open); recording: stop and queue | BACK while recording: stop and queue (audio is never dropped); BACK otherwise leaves the app |
| Queue (unsent count, bytes sent/total of the oldest note, last reply code, last hold reason; "Черга n · відкладено h") | retry now | MENU (long-press UP): delete first / delete all, each with a confirmation |
| Status (version, phone connected, last BB send time + level + HTTP code, last complete note time, alarm count + next, last alarm fetch time + code) | nothing | |

Playback of recordings is not possible (Connect IQ cannot play captured audio).

## Body Battery

```
POST <baseUrl>/api/body-battery        header X-API-Key: <apiKey>
{"level": 72, "timestamp": 1731070000, "source": "watch"}
```

A sample is sent once; if its timestamp equals the last successfully sent one (`sentTs` in Storage), nothing is posted.
HTTP: 201 = ok, 401 = bad key, 400 = bad input, negative = Garmin error, e.g. -104 = phone not connected (the watch
sends requests through the phone's Bluetooth).

## Watch alarms

The owner flags a task `alarm_watch` in the dashboard; the watch fetches upcoming alarms ahead of time and fires them
locally, so they ring even when the phone is out of reach at that moment.

```
GET <baseUrl>/api/alarms?channel=watch&hours=24        header X-API-Key: <apiKey>
{"alarms": [{"id": "<task>:<fire_at>", "title": "...", "start": "09:00", "kind": "pre"|"at", "fire_at": <unix s UTC>}]}
```

- Fetched at most every 25 min (and when the list is older than that), kept in Storage `al` = `[[fire_at, "HH:MM title", kind]...]`
  (max 20, text at most 40 chars). A failed fetch keeps the old list and only updates `alCode`.
- Firing happens first in each background event, before any web request: every stored alarm with
  `now - 15 min < fire_at <= now` not in `alFired` calls `Background.requestApplicationWake("⏰ HH:MM title")` once
  (several due at once become one message). **Lateness is up to 5 min**: Connect IQ temporal events cannot run more often
  than every 300 s. Alarms older than 15 min are skipped (watch was off).
- With the app open, a due and unacknowledged alarm replaces the screen: big time + title, strong 3-pulse vibration and
  `TONE_ALARM`, repeated every 10 s until START or BACK dismisses it (`alAck`). Nothing interrupts a recording; the alarm
  shows after it. The foreground check runs every 0.5 s, so with the app open it is on time.
- Storage keys: `al`, `alAt` (last successful fetch), `alCode` (last fetch HTTP code), `alFired`, `alAck`.

## Voice notes

Recording auto-stops after 3 minutes. Screen is pure black (AMOLED): a pulsing red ring while recording, a thin teal arc
for sync progress, a green check when done. A note that fails to upload stays in `Application.Storage` and is retried by
the background event every 5 minutes, on START in the Queue page, and on the next recording.

### Delivery guarantee

A recorded note's audio is deleted from the watch only (a) after the server answered 201 (it commits the assembled audio
to its DB first), or (b) when the owner deletes it from the Queue page menu (with confirmation). A note with zero stored
chunks is also removed (nothing to lose). Anything else that used to drop a note (HTTP 400, a 409 that keeps repeating,
missing meta or chunk) now parks it in Storage `held`: audio and meta stay, it is not sent and does not block the queue.
START on the Queue page moves held notes back to the end of the queue, restarting from chunk 0. The glance Q counts
queued and held notes. While the app is open it writes Storage `fgAt`; the background service then leaves the queue
alone (two processes writing `pend` would lose updates). A chunk written but not yet counted when the app died is
recovered on the next start (the note then sends without `total_bytes`).

**Uninstalling or deleting the app from the watch wipes its Storage, including unsent notes.** Check that the Queue page
shows 0 (nothing queued or held) before replacing or removing the app.

## Audio

Preferred: Ogg/Opus, 16 kHz mono. Fallback when `Capture.isFormatSupported` says no: WAV/LPCM 8 kHz mono (16-bit, about
16 KB/s, so a long note may hit the Storage limit and stops early). The chosen name goes to the server as `format`
(`ogg_opus` or `wav_lpcm`). Capture only works in the foreground.

Memory: audio is never held in RAM beyond about two chunks (~6 KB raw each, base64-encoded straight into
`Application.Storage`), so the 768 KB app limit does not depend on the codec bitrate. Chunks are deleted when the server
answers 201 (complete) or the owner deletes the note. If Storage fills up, recording stops early and sends what it has.

## Upload

```
POST <baseUrl>/api/voice-notes/chunk        header X-API-Key: <apiKey>
{"note_id": "<unix>-<5 digits>", "seq": 0, "last": false, "data": "<base64>",
 "format": "ogg_opus"            (seq 0 only), "recorded_at": <unix s> (seq 0 only), "duration_s": 42, "total_bytes": <sum of raw audio bytes>, "chunks": <count> (last chunk only)}
```

One request in flight, chunks in order, uploading while still recording (the last chunk is held back until stop so it can
carry `last`). 202/200 = next chunk, 201 = note complete and verified by the server (the only thing that deletes the chunks; a 2xx other than 201 on the last chunk counts as failed and is retried), 409 `{"missing":[..]}` = resume from the first
missing seq (max 5 times), 400 = note dropped, anything else (401, 5xx, negative Garmin codes such as -104 = no phone)
= keep and retry later.

## Settings and build

`baseUrl`, default `https://memory.kudu-skate.ts.net` (the phone needs Tailscale; Garmin Connect makes the request), and
`apiKey`, no default (never committed). A sideloaded app gets no settings from the phone, so the personal build bakes
the key in: `just watch-prg [device]` (repo root) reads `UES_API_KEY` from 1Password via fnox and writes
`watch/bin/ues-<device>-v<VERSION>.prg`. Never attach such a build to a release or CI artifact: this repo is public. CI
builds keyless, only to prove it compiles. A Connect IQ store (beta) install does get phone settings.

Install: connect the watch over USB, copy the `.prg` to `GARMIN/APPS/`, eject, open the app once (registers the
background event). Assign it to a hot key under Settings -> System -> Hot Keys if wanted.

Not verified on a real watch or the simulator (headless VM): the compile for `fenix847mm` is the only check. Unconfirmed on a
real device: the `⏰` glyph in the glance and wake prompt, `requestApplicationWake` text length, background memory with 20 alarms.

## Battery

One short web request every 5 minutes (about 290 per day) over the phone link when the queue is empty. Expect a small
drain, roughly a few percent per day; Garmin's minimum period is 300 s.

## Local toolchain (agent VM)

`~/.local/bin/connect-iq-sdk-manager` (lindell/connect-iq-sdk-manager-cli), logged in with 1Password item `garmin-connect-iq`;
SDK 9.3.0 and device `fenix847mm` live in `~/.Garmin/ConnectIQ`; `monkeyc` needs Java 17 on PATH (Zulu in `~/.local/jdk`).
Verified: `fenix847mm` (covers the 47 and 51 mm cases) compiles with SDK 9.3.0.

## Build

CI job `watch` in `.github/workflows/build.yml` builds one `.prg` per device in `manifest.xml`. Locally: install the Connect IQ SDK and
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

## Version

Semver in `VERSION`, shown on the Status page. Bump it for every build handed to the watch.
