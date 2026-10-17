# SonarMap for Android

Minimal Android project scaffold: Kotlin + Jetpack Compose + Material 3,
Gradle Kotlin DSL, version catalog.

## Scope at this step

Implemented:
- App module `app` with package `com.sonarmap.app`
- Single-Activity Compose app
- Map screen via `MapProvider` abstraction + Google Maps Compose adapter
- Long-press to place a MusicPin; marker tap opens editor bottom sheet
  (title, audio file picker, gain slider, delete)
- In-memory pin storage (ViewModel + Repository); Room comes later
- "My location" button using a fixed test point (no geolocation yet)
- Domain layer: pure-Kotlin models + geometry/gain functions, unit-tested
- Dependencies: Compose (BOM), Navigation Compose, Lifecycle, Coroutines/Flow,
  play-services-maps + maps-compose (adapter-only)

Intentionally deferred (do not add here):
- Real geolocation / foreground service (`location` package)
- Route rendering and route segments on the map
- Audio engine / spatial audio (`audio` package)
- Persistence / Room (`data` package)

## Package layout

```
com.sonarmap.app
├── MainActivity.kt      # Compose entry point
├── ui/                  # Screens, navigation host
│   └── map/             # Map screen, ViewModel, pin editor sheet
├── ui.theme/            # Material 3 theme
├── domain/              # Models (model/), repository contract (repository/),
│                        # geometry + gain functions (geo/, audio/) — pure Kotlin
├── data/                # In-memory MusicPinRepository implementation
├── map/                 # MapProvider interface + GoogleMapsProvider adapter
├── audio/               # Spatial audio engine (empty, reserved)
└── location/            # GPS/location service (empty, reserved)
```

## Google Maps API key

1. In [Google Cloud Console](https://console.cloud.google.com/) enable
   **Maps SDK for Android** for your project.
2. Create an API key (APIs & Services → Credentials).
3. Restrict it: *Application restrictions → Android apps* (add package name
   `com.sonarmap.app` + SHA-1), *API restrictions → Maps SDK for Android*.
4. Put the key into `local.properties` (gitignored):

   ```
   MAPS_API_KEY=AIza...
   ```

Debug keystore SHA-1: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`

The build substitutes the key into the manifest placeholder
(`com.google.android.geo.API_KEY`). Without a key the app builds and runs,
but the map renders blank tiles.

> **⚠️ Unverified: "Maps SDK for Android" integration has not been verified
> yet.** Client-side setup is correct and confirmed by logs (key, package
> `com.sonarmap.app`, debug SHA-1 `07:44:43:D0:…:94` are submitted by Google
> Play services and match this project), but the backend still rejects
> authorization until the Google Cloud project has Billing enabled and
> Maps SDK for Android activated in the same project. Until then the map
> renders a gray placeholder instead of tiles. Mark this item verified only
> after a real tile render on a device.

## Build

```bash
# Bootstrap the Gradle wrapper (skip if gradlew is already present)
gradle wrapper --gradle-version 8.9

./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # install on a connected device/emulator
./gradlew :app:testDebugUnitTest # domain layer unit tests (pure JVM)
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
