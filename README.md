# SonarMap for Android

Minimal Android project scaffold: Kotlin + Jetpack Compose + Material 3,
Gradle Kotlin DSL, version catalog.

## Scope at this step

Implemented:
- App module `app` with package `com.sonarmap.app`
- Single-Activity Compose app, placeholder screen with "SonarMap" text
- Navigation Compose host with one destination
- Dependencies: Compose (BOM), Navigation Compose, Lifecycle, Coroutines/Flow

Intentionally deferred (do not add here):
- Map provider integration (`map` package)
- Audio engine / spatial audio (`audio` package)
- FusedLocationProvider / foreground services (`location` package)
- Persistence / Room (`data` package)

## Package layout

```
com.sonarmap.app
├── MainActivity.kt      # Compose entry point
├── ui/                  # Screens, navigation host
├── ui.theme/            # Material 3 theme
├── domain/              # Use cases (empty, reserved)
├── data/                # Data sources / repositories (empty, reserved)
├── map/                 # Map provider abstraction (empty, reserved)
├── audio/               # Spatial audio engine (empty, reserved)
└── location/            # GPS/location service (empty, reserved)
```

## Requirements

- JDK 17
- Android SDK with platform 35 (Android Studio installs it automatically)

## Build

```bash
# Bootstrap the Gradle wrapper (skip if gradlew is already present)
gradle wrapper --gradle-version 8.9

./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # install on a connected device/emulator
```

## minSdk = 26 — rationale

- **Notification channels (API 26):** required by the planned foreground
  location service; using minSdk 26 avoids runtime branching entirely.
- **java.time:** fully available, no desugaring needed for future location
  smoothing / route math.
- **FusedLocationProvider** plays nicely here: play-services has a low SDK
  floor, so minSdk is not driven by it, but 26 keeps the baseline modern.
- Smaller maintenance surface: no legacy permission/camera/notification
  compatibility paths to test.
