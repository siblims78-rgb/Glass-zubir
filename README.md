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
