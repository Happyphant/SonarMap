# SonarMap for Android

Minimal Android project scaffold: Kotlin + Jetpack Compose + Material 3,
Gradle Kotlin DSL, version catalog.

## Scope at this step

Implemented:
- App module `app` with package `com.sonarmap.app`
- Single-Activity Compose app
- Map screen via `MapProvider` abstraction with two adapters:
  **Yandex MapKit (default)** and Google Maps Compose (optional)
- Long-press to place a MusicPin; marker tap opens editor bottom sheet
  (title, audio file picker, gain slider, delete)
- Local audio preview via the `AudioEngine` boundary: MediaPlayer-backed
  engine (no FMOD/Resonance), SAF-picked files, volume slider wired live,
  default gain 0.7, audio focus handled, resources freed on screen exit
- In-memory pin storage (ViewModel + Repository); Room comes later
- "My location" button using a fixed test point (no geolocation yet)
- Domain layer: pure-Kotlin models + geometry/gain functions, unit-tested
- Dependencies: Compose (BOM), Navigation Compose, Lifecycle, Coroutines/Flow,
  `com.yandex.android:maps.mobile` (lite), play-services-maps + maps-compose
  (adapters only)

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
├── map/                 # MapProvider + YandexMapProvider (default) +
│                        # GoogleMapsProvider (optional) + MapProviders selector
├── audio/               # Spatial audio engine (empty, reserved)
└── location/            # GPS/location service (empty, reserved)
```

## Choosing a map provider

`map/MapProviders.kt` is the single selection point:

- `MapProviders.default` — Yandex MapKit (`YandexMapProvider`), MVP default;
  does not require Google Play services on the device.
- `MapProviders.google` — Google Maps (`GoogleMapsProvider`), kept fully
  working as an optional alternative.

`MapScreen` reads `MapProviders.default` and nothing else; swapping the
default never touches UI, ViewModel, or the domain layer. Both adapters only
appear under `map/` — SDK-specific types (`com.yandex.mapkit`, `com.google.*`)
never leak into app/domain code.

## Yandex MapKit API key

1. In the [Developer Dashboard](https://yandex.com/maps-api/console) connect
   **MapKit Mobile SDK** and get an API key.
2. Put the key into `local.properties` (gitignored):

   ```
   MAPKIT_API_KEY=<your MapKit key>
   ```

3. Initialization happens once per process in `SonarMapApplication.onCreate()`
   (`setLocale` → `setApiKey(BuildConfig.MAPKIT_API_KEY)` → `initialize`),
   strictly before the first `MapView` is created. The key reaches the app via
   `BuildConfig.MAPKIT_API_KEY` and never enters version control.

Notes:
- The build uses the `-lite` artifact (map view, placemarks, gestures —
  everything the MVP needs); routing/search SDK parts are not shipped.
- **APK size:** with MapKit included the debug APK is ~110 MB (native
  libraries for all four ABIs). Trimming via ABI splits is a known follow-up.
- The Yandex key is validated at runtime; free-tier usage is counted in DAU
  (see the Yandex tariffs FAQ).
- ✅ **Verified on a real device**: tiles render (Moscow test point), long-tap
  pin creation, marker-tap editor sheet, and "My location" camera flight all
  work with MapKit 4.45.0-lite.

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
>
> **✅ Verified: Yandex MapKit (default provider).** Tile rendering, key
> authorization, long-tap pin creation, marker-tap editor, and "My location"
> camera flight confirmed on a physical device (Samsung Galaxy S24, Android 16)
> with `maps.mobile` 4.45.0-lite.

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
