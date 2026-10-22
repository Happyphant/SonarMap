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
  `relative = normalize(bearingToPin − userHeading)` drives a triangular
  pan curve — dead zone ±10° (center), linear ramp to ±1 at ±90° (sides),
  then a linear fade back to 0 towards the back: directly behind (±180°)
  the signal is centered in both ears. This keeps the back crossing
  continuous — no ear ever flips at the back. EMA smoothing 0.3 per tick.
  Heading comes from a rotation-vector sensor (`HeadingSensor`, portrait
  remap); engine writes throttled to ~10 Hz and deduplicated by a 1%
  threshold. Degradation: no sensor, no fix, or no headphone-class output
  (wired/BT/USB, `AudioOutputMonitor` via `registerAudioDeviceCallback`) =
  center/mono. `setPan` is a no-op in engines without a spatial stage; the
  MediaPlayer engine shapes channels with the equal-power pan law
  (`panGains`: θ = (pan+1)·π/4, L = cos θ, R = sin θ, constant total
  energy L²+R² = 1) so a centered source is never louder than a
  side-panned one — device-reported loudness imbalance fixed; equal-power
  sweep re-verified by ear on device (side -> center -> side sounds even). ✅
  **Verified on device** (Galaxy S24, Redmi
  Buds 3 Pro over BLE-A2DP): sensor heading within ~2° of the known
  bearing; dead zone held center (relative −2° -> pan −0.000); ~90° left
  turn -> pan glided to +1; ~180° right turn -> full smooth sweep
  1.0 → 0.8 → −0.05 (wrap handled) → −1.0; Bluetooth off → pan 0.000
  (mono, heading still live); Bluetooth reconnected → pan resumed without
  app restart. Behind-fade re-verified on device: a slow 180° turn passed
  through the back continuously (−0.2 → −0.09 at the back → −0.5 on the
  other side → −0.94), no ear flip, no mute/unmute pop.
- Hand-drawn routes: "＋ Маршрут" opens a drawing draft on the map; each
  map tap appends a vertex to an amber polyline (vertex dots visible),
  long-press pin gesture is suppressed mid-drawing. Завершить saves the
  route (>= 2 points enforced: the button stays disabled below that) and
  opens the route editor sheet — rename, attach a local audio file (SAF),
  make active, delete. UX phases **idle → drawing → completed → active**:
  idle shows the "＋ Маршрут" entry, drawing shows a live banner
  ("Маршрут: N точек…"), completed is the editor sheet over the gray-saved
  polyline, active renders the route as a bold teal polyline plus a chip
  "● Активный маршрут: … (Снять)". A routes-list screen (top-left "Маршруты"
  button) lists routes with point count, audio file name and an active
  badge, opens the same editor per route, and offers "Новый маршрут на
  карте" which opens a draft and returns to the map. State lives in the
  process-wide `RouteRepository` (in-memory until Room) shared by the map
  and the list through `RouteViewModel` — no shared ViewModel needed.
  Domain: `Route`, `RouteSegment`, `RouteDraft`, `Route.totalLengthMeters()`.
  No map-provider routing API is called anywhere: routes are built by hand
  (polyline rendering is map display, ambient `MapUiState.routeLines`).
  The route editor opens by tapping a saved route polyline on the map
  (the drawing draft stays tap-through: mid-drawing taps keep adding
  vertices, and clicking a line opens the editor for that route); opening
  a route from the routes list closes the list first — the editor sheet
  always lives on the map screen, backed by the single root-scoped
  RouteViewModel shared by both destinations.
  ✅ **Verified on device** (Yandex MapKit adapter): the full cycle —
  3-tap draft ("2 точки" counter, Завершить disabled at 0), finished
  route showed "3 точки · 236 м · не активный", longtone.wav attached
  through SAF, activation turned the line teal and raised the chip; the
  list card showed title, points, audio name and the active flag; deselect
  via "Снять" works; a map tap while idle creates nothing; polyline tap
  opens the editor (verified: "Маршрут 2" sheet after tapping the line),
  card tap in the list closed the list and landed the same sheet on the map.
- Unified walking-audio activation — one active audio source at a time.
  A pin OR a route becomes the audible source (`AudioSourceSelector`:
  activating a pin deactivates the active route and vice versa; `null`
  deactivation touches only its own side; both repositories keep their own
  `activePinId`/`activeRouteId` StateFlow and reject unknown ids). The
  "Прослушать" button is gone — the pin editor sheet has
  "Сделать активным"/"Снять активность" like the route editor, and the
  active source's sound starts immediately on activation (auto-play of a
  looped stream; pause on deactivation/switch) with no sheet involvement:
  closing or reopening the editor sheet of an active source never silences
  it — audio is owned by the activation state alone. Route sound is
  proximity-driven along the line: each fix is projected onto the polyline
  (`Route.projection`, nearest point + distance), gain is
  `clamp(1 − d/150) × 1.0` (uniform along the line for parallel walks; the
  ends fade acoustically because no point lies beyond them), pan targets
  the projected point; inside a 15 m corridor the stereo image collapses to
  the center (`panFadeMeters` in `PositionedSourceAudio`), because the
  bearing to the nearest line point degenerates once the user is ON the
  line. Playback errors surface as a transient snackbar. MVP deviation from
  the spatial-audio skill guidance (several virtual sources along a route):
  MediaPlayer is a single-stream engine, so one stream is anchored at the
  nearest projected polyline point — this preserves the "nearest source
  wins" semantics without a multi-stream mixer (revisit with
  FMOD/Resonance after the MVP). Menu: the top-left map button reads
  "Маршруты и метки (N)" and the menu now lists pins in a "Метки" section
  (title, active badge, audio file; card tap closes the list and opens the
  pin editor on the map) plus "Новый пин на карте", which returns to the
  map with a one-shot hint snackbar ("Удерживайте карту, чтобы поставить
  метку") — pin placement stays a map long-press. Both ViewModels (map +
  routes) are root-scoped and share the selector.
  ✅ **Verified on device** (Galaxy S24, speaker): pin activation -> sound
  immediately (SonarGain: `gain = (1 − d/150) × slider` at d = 41 m -> 0.509
  with the 70% slider); closing the sheet kept the sound; activating a
  route switched the engine source within one tick (pin 0.509 -> route
  projection d = 206 m -> gain 0.0, bearing to the projected point 156°);
  activating the pin back silenced the route and restored the pin stream;
  chip "Снять" and both sheets' activation toggles go through the same
  selector; the hint snackbar shows exactly once after "Новый пин на
  карте" (a notice-vs-hint snackbar race was fixed on the way); menu
  labels update with entity counts ("Маршруты и метки (2)" with both);
  the idle gray route line still opens its editor by tap while a pin's
  sound keeps playing.
- Domain layer: pure-Kotlin models + geometry/gain functions, unit-tested
- Dependencies: Compose (BOM), Navigation Compose, Lifecycle, Coroutines/Flow,
  `com.yandex.android:maps.mobile` (lite), play-services-maps + maps-compose
  (adapters only), play-services-location (location layer only)

Intentionally deferred (do not add here):
- Foreground service / background location (updates live only while the
  map screen is visible)
- Route-based audio (proximity to route segments, walking guidance)
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
