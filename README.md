# ues-widget

Minimal Android home-screen widget (Jetpack Glance) that shows today's tasks from the UES Memory API.

## Install

Add this repo's GitHub Releases to [Obtainium](https://obtainium.imranr.dev/) (`https://github.com/dnullproject/ues-widget`) and install the APK.

## First run

Open the app and fill in: Memory API base URL, API key, dashboard URL. Save, then add the "UES Widget" widget to the home screen.
The widget refreshes every minute while the screen is on (a foreground service with a silent notification), plus via the ⟳ button; the time next to ⟳ is the last fetch. Tapping the list opens the dashboard.
When a new inbox note gets an AI suggestion, a heads-up notification pops up with one-tap actions (⏰ +2 год: schedule it for the next quarter-hour after two hours and get a local reminder then, ✓ create task, 🗑 trash).
The 🎤 button on the widget records a voice note (Android 10+) and uploads it to the inbox; tap "Відправити" when done.
Tasks with "phone alarm" enabled in the dashboard are fetched ahead of time (next 48h) and scheduled locally with `AlarmManager.setAlarmClock` so they ring on the "UES alarms" channel even offline, and are re-armed on reboot.

## Build

```
gradle test assembleDebug
```

(No Gradle wrapper is committed; use Gradle 8.11.1 and JDK 17.)

## Release

```
git tag v0.1.0 && git push --tags
```

CI builds a signed APK (needs secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and publishes a GitHub Release.
