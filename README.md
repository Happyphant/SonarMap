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
- Real user geolocation via `LocationRepository`: FusedLocationProviderClient
  (while-in-use, no foreground service, no background usage), runtime
  ACCESS_FINE/COARSE_LOCATION asked once at first screen entry (the FAB
  re-offers the dialog for a previously denied user); the user dot appears
  without any button as soon as the permission is granted; denied /
  permanently-denied / location-disabled / no-fix states surfaced
  as snackbars; coordinates smoothed by a per-coordinate Kalman filter
  before rendering; live "distance to pin" in the editor sheet
- Proximity loudness (`LocationAudioController`): engine volume of the
  selected pin follows the live distance — `gain = clamp(1 - d/maxDistance, 0, 1)`,
  maxDistance 150 m, hysteresis dead zone up to ×1.15 (mute stays muted
  until back inside maxDistance), per-tick EMA smoothing 0.35 (~0.7 s at
  2 Hz); no fix = full slider loudness (manual preview unaffected); pin
  switch resets the smoothing memory; the controller is the single
  engine-volume writer while the screen is alive. Verified on device
  (Galaxy S24, unlocked screen): trace matched the formula to 3 decimals
  on a ~260 m out-and-back walk, silence inside the dead zone, no pops.
- Stereo positioning of the active pin: relative angle
  `relative = normalize(bearingToPin − userHeading)` drives pan
  `pan = sign·clamp((|relative| − 10°)/(90° − 10°), 0, 1)` with EMA
  smoothing 0.3 per tick and a ±10° dead zone (small heading wobble never
  waggles the image). Heading comes from a rotation-vector sensor
  (`HeadingSensor`, portrait remap); pan saturates at the sides (behind =
  full side by convention, exact ±180° folds to the right); engine writes
  throttled to ~10 Hz and deduplicated by a 1% threshold. Degradation:
  no sensor, no fix, or no headphone-class output (wired/BT/USB,
  `AudioOutputMonitor` via `registerAudioDeviceCallback`) = center/mono.
  `Pan is a no-op in engines without a spatial stage`; the MediaPlayer
  engine applies the linear pan law `L = gain·min(1, 1−pan)`,
  `R = gain·min(1, 1+pan)`. ✅ **Verified on device** (Galaxy S24, Redmi
  Buds 3 Pro over BLE-A2DP): sensor heading within ~2° of the known
  bearing; dead zone held center (relative −2° -> pan −0.000); ~90° left
  turn -> pan glided to +1; ~180° right turn -> full smooth sweep
  1.0 → 0.8 → −0.05 (wrap handled) → −1.0; Bluetooth off → pan 0.000
  (mono, heading still live); Bluetooth reconnected → pan resumed without
  app restart.
- Domain layer: pure-Kotlin models + geometry/gain functions, unit-tested
- Dependencies: Compose (BOM), Navigation Compose, Lifecycle, Coroutines/Flow,
  `com.yandex.android:maps.mobile` (lite), play-services-maps + maps-compose
  (adapters only), play-services-location (location layer only)

Intentionally deferred (do not add here):
- Foreground service / background location (updates live only while the
  map screen is visible)
- Route rendering and route segments on the map
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
├── audio/               # MediaPlayerAudioEngine, LocationAudioController,
│                        # AudioOutputMonitor (headphone-class outputs)
├── sensor/              # HeadingSensor (rotation vector -> compass heading)
└── location/            # Fused location provider, Kalman smoothing
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
