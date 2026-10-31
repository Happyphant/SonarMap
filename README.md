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
  (title, audio file picker, gain slider, hearing-area slider, delete)
- Local audio preview via the `AudioEngine` boundary: MediaPlayer-backed
  engine (no FMOD/Resonance), SAF-picked files, volume slider wired live,
  default gain 0.7, audio focus handled, resources freed on screen exit
- Local persistence: Room (pins/routes/route points) behind the same
  repository interfaces; foreground playback service behind an explicit
  opt-in toggle ("Фоновый режим")
- Real user geolocation via `LocationRepository`: FusedLocationProviderClient
  (while-in-use; the continuous background case is the opt-in "Фоновый
  режим" foreground service), runtime
  ACCESS_FINE/COARSE_LOCATION asked once at first screen entry (the FAB
  re-offers the dialog for a previously denied user); the user dot appears
  without any button as soon as the permission is granted; denied /
  permanently-denied / location-disabled / no-fix states surfaced
  as snackbars; coordinates smoothed by a per-coordinate Kalman filter
  before rendering; live "distance to pin" in the editor sheet
- Proximity loudness (`LocationAudioController`): engine volume of the
  selected pin follows the live distance to the source — inside the
  hearing area `gain = (0.15 + 0.85 * (1 - d/R)) * slider` (edge floor
  0.15, rising linearly to full at the source), per-tick EMA smoothing
  0.35 (~0.7 s at 2 Hz); stepping past the painted edge (d > R) cuts the
  sound IMMEDIATELY (exact zero, no smoothing tail, no dead band — the
  old ±1.15 hysteresis is gone), crossing back turns the sound ON at the
  curve value in the same tick; no fix = full slider loudness (manual
  preview unaffected; a brief GPS gap holds the last value); pin switch
  resets the smoothing memory; the controller is the single engine-volume
  writer while the screen is alive. Resizing the zone does the same
  things to the sound without moving: widening raises the volume of a
  listener inside, shrinking lowers it, and shrinking past the listener
  cuts instantly. Verified on device
  (Galaxy S24, speaker output): trace matched the formula to 3 decimals
  (d = 85.9, R = 150 -> 0.359), a slider drag to the middle of the
  50..100 segment landed on interpolated values (R ≈ 187 -> gain 0.430,
  R ≈ 145 -> 0.348), shrink-past cut at once (0.253 -> 0.000 in one
  tick), re-expansion past the listener restored the sound at once at
  the curve value (0.000 -> 0.177) and further widening kept raising it
  (0.177 .. 0.348) — no dead zone left, "звук сразу прерывается".
  (Superseded: the maxDistance is now a per-source audible zone radius —
  see "Hearing areas".)
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
  process-wide `RouteRepository` (Room-backed) shared by the map
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
- Hearing areas ("область слышимости") — every pin and route carries its own
  `audibleRadiusMeters` (domain model, default 150 m; repository-clamped
  10..5000). The map renders a zone per source: a circle around each pin
  and a corridor polygon around each route polyline (`Route.audibleZoneRings`
  traces the iso-contour of the distance-to-route field at exactly R by
  marching squares over a local equirectangular meter grid; the contour is
  the exact union of segment capsules — loop-free on any bend, and a loop
  that curls tighter than the radius comes back as an inner "hole" ring,
  which both map providers render as a polygon inner ring); the ONE active
  source is filled semitransparent teal, inactive
  ones are contour-only gray. Zones draw beneath lines/markers and are
  tap-through (no listeners on Yandex; Google layers `clickable = false`).
  Size is edited with a non-uniform slider in both editor sheets
  ("Область слышимости", shared `ui/common/HearingAreaRadiusSlider`):
  tick values 10, 20, 30, 50, 100, 150, 200 and 300 m sit at equally
  spaced positions on the track (a tick-mark row is drawn under it) and
  the value between neighbors interpolates linearly, so the default 150
  lands exactly on its tick, every intermediate position is reachable
  and the visible value updates smoothly; `roundToStep` detents are gone.
  Applied through `updateAudibleRadius` in both repositories. The gain
  curve follows the same radius per source: silence outside the zone,
  ON at the zone edge at the `edgeGain` floor (0.15 — "минимальная
  громкость") — instantly, not after a ramp — rising linearly to full
  loudness at the source (`ProximityGainCalculator.update` takes the
  per-source radius), and immediate silence again the tick the listener
  is outside the zone (d > R): entering the area → audible, widening →
  louder, narrowing → quieter, leaving (or narrowing past the listener)
  → the sound stops at once. Pin slider gain
  and route gain (1.0) multiply on top as before. MVP note: the mute floor
  replaced the old "silent at 150 m" semantics. Acoustic side unchanged:
  gain is
  computed from the distance to the nearest route point, which is exactly
  the field the painted contour is an iso-line of, so the acoustic
  activation edge and the painted edge coincide in every geometry (bends,
  self-fold, curl eyes).
  ✅ **Verified on device** (Galaxy S24, Redmi Buds 3 Pro): gray contour
  circles around idle pins; the active pin's circle filled instantly with
  the trace matching the per-radius formula exactly (390 m ->
  `(0.15 + 0.85·(1 − d/390)) · slider = 0.521` at d = 104 m); slider drags
  rescale the live geometry and the curve together (210 m -> 0.390,
  480 m -> 0.550, both matching to the third digit); a route activated as
  the source took over within one tick (`d = 48.5 m, R = 150 ->
  gain 0.725`), its corridor rendered as a filled capsule corridor with
  round caps; shot-off source turned contour-only (filled fill jumped
  between circle and capsule; the route chip and gray idle line behaved as
  before); closing/reopening sheets mid-playback never stopped the sound
  (trace continuous through sheet transitions).
  ✅ **Non-uniform scale + sharp audio edges verified on device**
  (Galaxy S24, fresh install, speaker output): the editor sheet shows the
  tick row (8 marks) under the rebuilt slider, the thumb sits on the 150 m
  tick and drags settle on interpolated values (R ≈ 187 -> zone repaints
  bigger, trace gain 0.359 -> 0.430 at the same d = 85.9); shrinking
  past the listener drops the gain monotonically (0.430 → 0.428 → 0.414 →
  0.382 → 0.333 → 0.253) and cuts it to EXACT zero the tick R < d (no
  fading tail, nothing plays between R and R·1.15 anymore); widening back
  restores the sound at once at the curve value (0.000 → 0.177) and keeps
  rising smoothly to 0.348 at R ≈ 145 — "входим -> звук становится
  слышен, расширяем -> громче, сужаем -> тише, вышли -> сразу прерывается".
  Unit coverage of the new edges: instant cut past the edge, instant ON at
  the edge floor on re-entry, exact floor at d = R, monotonic widen/narrow
  response, shrink-past cut, first-fix preview cut, GPS-gap holds (inside
  and muted) — `HearingAreaScale` mapping (tick positions, interpolation,
  round-trips, monotonicity, clamps). 145 tests green.
  ✅ **Room persistence + «Фоновый режим» verified on device** (Galaxy
  S24, 2026-10-31): a pin placed, audio attached (SAF) and activated —
  screen-owned playback traced (SonarGain 0.59 at d = 27.8 m with the 70%
  slider); enabling the toggle started the FGS (dumpsys
  `isForeground=true types=0x0A` = location|mediaPlayback), the screen
  engine went paused and SonarGainBg traces took over at the same gain;
  after HOME only SonarGainBg continued (audio playing, screen loop
  silent). The notification («Играет: Пин 1», silent low-importance,
  Стоп action wired to the same stop path — on device the stop was
  exercised through the toggle and the source-deactivation paths) is
  REMOVED on stop: toggle-off → ServiceRecord count 0, empty shade, the
  screen engine resumes seamlessly at the same gain. Silent checks:
  reinstall with `-r` (process death) — pin + audio attach + toggle
  survive via Room + SharedPreferences; relaunching shows «Фоновый
  режим: вкл (нет источника)» (the active SELECTION is deliberately
  in-session) and activating the source AUTO-STARTS the service. A
  forced crash leaves no unattended audio and no running service
  process (a system-restarted service would find no active source and
  self-stop in its first emission). 153 tests green (145 + entity
  mapping + the shared source builder).
  ✅ **Iso-contour geometry verified on device** (Galaxy S24, arc route
  near ОЦ Горностай, R = 390 m): the zone renders as a smooth union-of-
  capsules blob with a clean boundary at exactly R on the arc bend (the
  averaged-normal loop/mangling of the previous buffer construction is
  gone), survives panning the viewport a full blob away and back without
  disappearing or garbling. Unit coverage: edge-floor
  curve, per-source radius, sharp zone-edge cut/entry, contour geometry
  (constant radius on a straight route, full corridor width, duplicate
  points tolerance, exact radius on a right-angle bend, loop-free hairpin,
  closed-curl annulus with a hole ring, loop-free S-curve, non-positive
  radius rejected), repository
  `updateAudibleRadius` round trip + clamps (test count above).
- App icon from the reference art: adaptive launcher icon
  (`mipmap-anydpi-v26`, minSdk 26) — black background
  (`ic_launcher_background`) + a white map-pin foreground
  (`drawable/ic_launcher_foreground`) built from the supplied reference
  SVG: the pin's circle is a true hole (black shows through via
  `fillType="evenOdd"`) holding an eighth note; the reference path data
  (1254×1254) is used verbatim behind a `<group>` scale/translate that
  fits it into the adaptive-icon safe zone. The same vector serves as
  the `monochrome` layer for themed icons. Manifest wired
  (`android:icon`/`android:roundIcon`).
- Persistence — Room (`data/db`): pins, routes and route points live in
  SQLite (`sonarmap.db`, version 1, schema JSON exported to the committed
  `app/schemas/`). Entities `MusicPinEntity`, `RouteEntity` +
  normalized `RoutePointEntity` (composite PK route_id+ordinal, FK with
  CASCADE delete, explicit FK index); DAOs return Flow (invalidation-driven
  live updates) and are promoted to eager StateFlows in the repositories —
  the domain interface (`MusicPinRepository`/`RouteRepository`) and its
  contracts (clamps, blank titles, unknown-id IllegalArgumentException) are
  unchanged; the in-memory implementations now live only as JVM test
  fixtures. The drawing draft and the pin/route active SELECTION stay
  in-session by design (no surprise audio on app restart; active source
  not persisted). Migration strategy: every schema change bumps the DB
  version + a hand-written Migration registered in
  `SonarMapDatabase.migrations` against the committed schema exports — no
  destructive fallback.
- Foreground playback service ("Фоновый режим") — optional, opt-in via the
  explicit map-screen toggle: `PlaybackForegroundService`
  (`foregroundServiceType = location|mediaPlayback`, silent low-importance
  notification with a Стоп action) owns the session while the app is
  minimized: its own FusedLocation subscription, heading sensor, output
  monitor and MediaPlayer engine, driven by the same
  `LocationAudioController` and the same active-source builder
  (`positionedSourceFor`) as the screen. While the service runs, IT is the
  single engine writer — the map screen pauses its own engine. Service stop
  paths (notification Стоп, toggle off, active source deactivated, location
  revoked/turned off) all converge in onDestroy: location updates removed,
  engine released, notification removed. The toggle survives process death
  (SharedPreferences); the service restarts only when an active source
  exists again. No ACCESS_BACKGROUND_LOCATION — see Permission notes.
- Domain layer: pure-Kotlin models + geometry/gain functions, unit-tested
- Dependencies: Compose (BOM), Navigation Compose, Lifecycle, Coroutines/Flow,
  Room (data layer), `com.yandex.android:maps.mobile` (lite),
  play-services-maps + maps-compose (adapters only), play-services-location
  (location layer only)

Intentionally deferred (do not add here):
- Walking guidance beyond the proximity gain (turn-by-turn prompts)

## Package layout

```
com.sonarmap.app
├── MainActivity.kt      # Compose entry point
├── ui/                  # Screens, navigation host, map screen + editors
├── ui.theme/            # Material 3 theme
├── domain/              # Models (model/), repository contract (repository/),
│                        # geometry + gain functions (geo/, audio/) — pure Kotlin
├── data/                # Room-backed MusicPin/Route repositories (db/ holds
│                        # entities, DAOs, the single SonarMapDatabase + mappers)
├── service/             # PlaybackForegroundService + BackgroundModeController
│                        # ("Фоновый режим" toggle)
├── map/                 # MapProvider + YandexMapProvider (default) +
│                        # GoogleMapsProvider (optional) + MapProviders selector
├── audio/               # MediaPlayerAudioEngine, LocationAudioController,
│                        # AudioOutputMonitor, positionedSourceFor (the shared
│                        # active-source builder used by screen AND service)
├── sensor/              # HeadingSensor (rotation vector -> compass heading)
└── location/            # Fused location provider, Kalman smoothing
```

## Permissions, battery, privacy

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — runtime, requested
  at first map entry (rationale via snackbar states; FAB re-offers).
  No ACCESS_BACKGROUND_LOCATION: the playback foreground service is always
  started from the visible UI, so while-in-use authorization is sufficient
  for the whole session — background location prompt is deliberately not
  added for the MVP.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` — only for the
  opt-in "Фоновый режим" service; POST_NOTIFICATIONS is runtime-requested
  at the moment the toggle is enabled (denied → service still works, the
  user is told the notification accessory is unavailable).
- Battery: while-in-use location subscription (2 s / 1 s-throttled,
  BALANCED_POWER accuracy) lives exactly while the map screen is visible
  or, with background mode on, while the playback service runs (notification
  Стоп / toggle off is the always-available kill switch); no service, no
  location, no audio outside that. Location/audio cancels also happen
  automatically on permission revoke or master location switch off.
- Privacy: pins/routes/audio file refs are stored in the app-private
  database only (no sync, no network upload; map tiles excepted); the
  foreground service processes the user position on-device and discards it.

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
