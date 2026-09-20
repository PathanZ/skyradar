# SkyRadar 🛩️

A native Wear OS radar app that tracks live aircraft around you, built for the Galaxy Watch 8 (and other Wear OS devices) using Jetpack Compose.

![Wear OS](https://img.shields.io/badge/Wear%20OS-4285F4?style=flat&logo=wear-os&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)

## What it does

SkyRadar turns your watch into a classic green sweep radar scope, showing real aircraft flying near you in real time:

- Live ADS-B data from the [OpenSky Network](https://opensky-network.org/) API
- Rotating radar sweep with fading trail, range rings, and heading ticks on each aircraft
- Tap any aircraft blip to see its callsign, altitude, speed, and heading
- Runs standalone over the watch's own LTE connection — no phone required
- Auto-refreshes every 45 seconds, tuned to be easy on a small watch battery

## Why native Compose, not a WebView

This started as an HTML/Canvas radar wrapped in an Android WebView. That approach hit a wall: this Galaxy Watch 8 unit ships with **no WebView provider installed at all** — instantiating `android.webkit.WebView` throws `UnsupportedOperationException`, and `pm list packages | grep webview` comes back empty. That's a real constraint on some Wear OS builds, not a bug in the app.

The app now renders everything natively with Jetpack Compose's `Canvas` API — no WebView dependency, and the more standard way to build for Wear OS anyway.

## Project structure

```
├── app/
│   ├── build.gradle.kts              # Module config — Compose + Wear Compose Material3
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions, standalone-app flag, launcher activity
│       └── java/com/zaid/skyradar/presentation/
│           └── MainActivity.kt       # Splash screen, radar canvas, HUD, OpenSky fetch logic
├── gradle/libs.versions.toml         # Version catalog (Kotlin 2.2.10, AGP 9.4.1, Compose BOM 2024.09.00)
├── build.gradle.kts                  # Root Gradle config
└── settings.gradle.kts
```

## Getting started

**Requirements:** Android Studio (Quail or later), a Wear OS device or emulator running API 30+, and a free [OpenSky Network](https://opensky-network.org/) account.

1. Clone this repo and open it in Android Studio.
2. Let Gradle sync (first sync downloads the Android SDK/build tools).
3. In `MainActivity.kt`, find `fetchAircraft()` and replace the placeholder:
   ```kotlin
   val creds = "id:password"
   ```
   with your own OpenSky username and password. **Don't commit real credentials** — see the note below.
4. Connect your watch:
   - Enable Developer Options and Wireless Debugging on the watch
   - `adb pair <ip>:<port> <pairing-code>`
   - `adb connect <ip>:<port>`
5. Hit **Run ▶** in Android Studio.

## A note on credentials

Keep your OpenSky login out of source control. For anything beyond quick local testing, load it from a `local.properties` entry (already gitignored by default in Android projects) or an environment variable rather than hardcoding it in `MainActivity.kt`.

## Known limitations

- Location uses `getLastKnownLocation()` (last cached GPS fix) rather than an active location request, with a hardcoded fallback to Surrey, BC if no fix is available.
- Refresh interval is fixed at 45 seconds to respect OpenSky's anonymous-tier rate limits and conserve battery.
- Tested on a Galaxy Watch 8 (40mm, LTE); other Wear OS devices should work but haven't been verified.

## License

MIT — do whatever you'd like with it.
