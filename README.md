# Glass ONN Launcher

Android TV launcher and local phone Companion.

## Source layout
- `app/src/main/java/com/glassonn/home/` — Android app, services, local API
- `app/src/main/assets/` — TV and Companion interfaces, audio and wallpapers
- `app/src/main/res/` — Android resources
- `app/build.gradle`, `settings.gradle`, `build.gradle` — build configuration

## Build
GitHub Actions workflow **Build Glass ONN APK** now compiles the tracked source files directly. You do **not** need to upload a versioned ZIP or rename the workflow input for normal source changes.

To build, update source on `main` or run the workflow manually from GitHub Actions. Download `GlassONN-debug-apk` from the successful run.

## Versions
Source was initially migrated from the tested v0.6.5 archive on 12 November 2026. The v0.6.6 ZIP discussed in chat is **not** yet merged into tracked source; do not describe direct builds as v0.6.6 until those source changes are committed.

Historical ZIP archives are left untouched in the repo.

## v0.7.0 Aero Interactive preview (2026-11-13)

- Native-DPAD-friendly WebView focus engine remembers a selected poster per row, the last app in the dock, and returns focus when closing overlays.
- Dynamic movie billboard uses the selected card's real metadata (title/type/year/overview) and available artwork, with safe text insertion and a graceful image fallback. The default centered clock and prayer view returns when movie focus ends.
- Central palette tokens for Glass, Pastel, Grey, Dark, Colourful and Bright; blur is confined to Glass. Home panel opacity remains 0–100 percent in every theme.
- This is a separate test package `com.glassonn.home.v070`, to preserve prior test builds and local data. No IPTV playback features or third-party GPL code were copied.
