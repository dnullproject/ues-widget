# UES Note watch app

Connect IQ watch app (not a widget) for the Garmin Fenix 8 AMOLED 47/51 mm (`fenix847mm`; other devices are not
downloaded on the agent VM). Opening it records a voice note at once and uploads it to UES. Needs API level 6.0.3
(`Toybox.Audio.Capture`), permissions AudioInput, Background, Communications.

## Use (2-3 button presses)

1. Assign it to a hot key: Settings -> System -> Hot Keys (or Controls menu) -> pick the key -> add **UES Note**.
2. Press the hot key: a red dot and `mm:ss` appear, the watch vibrates, recording is already running.
3. Press START (or BACK) to stop: the watch vibrates, shows `Sending 3/12`, then `Sent` and exits after ~2 s.

Auto-stop after 3 minutes. Screen is pure black (AMOLED): a pulsing red ring while recording, a thin teal arc round the bezel for sync progress, a green check when done. If the upload fails the note stays in `Application.Storage`, the screen says `Not sent /
will retry`, and a small `N pending` line shows on the next start. Retries run from a background temporal event every
5 minutes and on the next app start. Leaving early (BACK while sending) also leaves the rest to the background.

## Audio

Preferred: Ogg/Opus, 16 kHz mono. Fallback when `Capture.isFormatSupported` says no: WAV/LPCM 8 kHz mono (16-bit, about
16 KB/s, so a long note may hit the Storage limit and stops early). The chosen name goes to the server as `format`
(`ogg_opus` or `wav_lpcm`). Capture only works in the foreground.

Memory: audio is never held in RAM beyond about two chunks (~6 KB raw each, base64-encoded straight into
`Application.Storage`), so the 768 KB app limit does not depend on the codec bitrate. Chunks are deleted when the server
answers 201 (complete). If Storage fills up, recording stops early and sends what it has.

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

Same `baseUrl` / `apiKey` properties as the BB widget (see `../watch/README.md`, including the local toolchain).
`just note-prg [device]` builds `watch-note/bin/ues-note-<device>.prg` with the key baked in (copy to `GARMIN/APPS/`).
`APP_DIR=watch-note watch/build.sh fenix847mm` builds keyless. CI does not build this app yet.

Not verified on a real watch or the simulator (headless VM): the compile for `fenix847mm` is the only check.
