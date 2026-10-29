# ues-widget

Minimal Android home-screen widget (Jetpack Glance) that shows today's tasks from the UES Memory API.

## Install

Add this repo's GitHub Releases to [Obtainium](https://obtainium.imranr.dev/) (`https://github.com/dnullproject/ues-widget`) and install the APK.

## First run

Open the app and fill in: Memory API base URL, API key, dashboard URL. Save, then add the "UES Widget" widget to the home screen.
The widget refreshes every 30 min or via the ⟳ button; tapping the list opens the dashboard.

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
