package com.sonarmap.app.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonarmap.app.audio.AudioOutputMonitor
import com.sonarmap.app.audio.LocationAudioController
import com.sonarmap.app.audio.MediaPlayerAudioEngine
import com.sonarmap.app.audio.PositionedSourceAudio
import com.sonarmap.app.domain.audio.AudioEngineListener
import com.sonarmap.app.domain.audio.AudioErrorKind
import com.sonarmap.app.domain.audio.AudioPlaybackState
import com.sonarmap.app.domain.geo.bearingDegrees
import com.sonarmap.app.domain.geo.distanceMeters
import com.sonarmap.app.domain.geo.projection
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.map.MapCallbacks
import com.sonarmap.app.map.MapProviders
import com.sonarmap.app.map.MapUiState
import com.sonarmap.app.sensor.HeadingSensor
import com.sonarmap.app.ui.audioFileName
import com.sonarmap.app.ui.routes.RouteEditorSheet
import com.sonarmap.app.ui.routes.RoutePhase
import com.sonarmap.app.ui.routes.RouteViewModel
import kotlinx.coroutines.flow.combine

@Composable
fun MapScreen(
    mapViewModel: MapViewModel,
    routeViewModel: RouteViewModel,
    onOpenRoutes: () -> Unit = {},
) {
    // mapViewModel/routeViewModel come from the root scope: the map screen
    // and the routes-list screens must observe one shared state (editor
    // sheets, active source). The engine lives with this composition.
    MapScreenContent(
        viewModel = mapViewModel,
        routeViewModel = routeViewModel,
        onOpenRoutes = onOpenRoutes,
    )
}

@Composable
private fun MapScreenContent(
    viewModel: MapViewModel,
    routeViewModel: RouteViewModel,
    onOpenRoutes: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val routeState by routeViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapProvider = remember { MapProviders.default }
    val snackbarHostState = remember { SnackbarHostState() }

    // Local preview playback lives with the screen; released on exit (also
    // across configuration changes — DisposableEffect disposes the remembered
    // engine whenever this composition leaves).
    val engine = remember { MediaPlayerAudioEngine(context) }
    var playbackError by remember { mutableStateOf<AudioErrorKind?>(null) }

    DisposableEffect(engine) {
        engine.setListener(object : AudioEngineListener {
            override fun onStateChanged(state: AudioPlaybackState) = Unit

            override fun onError(kind: AudioErrorKind) {
                playbackError = kind
            }
        })
        onDispose {
            engine.release()
            engine.setListener(null)
        }
    }

    // Proximity loudness + stereo pan: run for the screen's lifetime. The ONE
    // active source (an active route first, else the active pin; the editor
    // sheet is irrelevant to the engine) drives the engine: volume follows
    // the distance from each location fix to the source (for a route — to
    // the nearest point of its polyline), pan follows bearing-to-source
    // minus user heading (mono-degraded without headphones or heading
    // sensor). Cancelled with the composition — no leaks, no blocking.
    val headingSensor = remember { HeadingSensor(context.applicationContext) }
    val audioOutputMonitor = remember(context.applicationContext) {
        AudioOutputMonitor(context.applicationContext)
    }
    DisposableEffect(audioOutputMonitor) {
        audioOutputMonitor.start()
        onDispose { audioOutputMonitor.stop() }
    }
    LaunchedEffect(engine, viewModel, routeViewModel) {
        val activeSource = combine(
            viewModel.uiState,
            routeViewModel.uiState,
        ) { map, routes ->
            val fix = map.userLocation
            val route = routes.activeRoute()
            val pin = map.activePin
            when {
                route != null -> {
                    // Route sound: distance/bearing to the NEAREST POINT of
                    // the polyline; stereo collapses to the center inside
                    // the corridor (the bearing degenerates on the line).
                    val source = fix?.let { route.projection(it) }
                    PositionedSourceAudio(
                        sourceId = route.id,
                        distanceMeters = source?.distanceMeters,
                        bearingDegrees = source?.let { bearingDegrees(fix, it.point) },
                        userGain = 1.0,
                        panFadeMeters = PositionedSourceAudio.ROUTE_PAN_FADE_METERS,
                    )
                }

                pin != null -> {
                    // Pin sound: distance/bearing to the pin itself. No fix
                    // = no distance = full slider loudness, like before.
                    PositionedSourceAudio(
                        sourceId = pin.id,
                        distanceMeters = fix?.let { distanceMeters(it, pin.position) },
                        bearingDegrees = fix?.let { bearingDegrees(it, pin.position) },
                        userGain = pin.gain,
                    )
                }

                else -> null // nothing active: the controller silences
            }
        }
        LocationAudioController(engine, trace = { Log.d("SonarGain", it) }).run(
            activePin = activeSource,
            heading = headingSensor.heading(),
            headphones = audioOutputMonitor.headphonesConnected,
        )
    }

    // The active source starts playing AS SOON AS it is activated (auto-play,
    // no Прослушать button anywhere) with looped playback; switching or
    // deactivating pauses the previous stream. The key covers the source AND
    // its file: re-picking the file restarts the prepared stream. Do not
    // touch anything here on sheet open/close — sound survives sheets.
    val activeAudioKey: Pair<String, String>? = when {
        routeState.activeRoute()?.audioAssetUri?.isNotBlank() == true ->
            routeState.activeRoute()!!.id to routeState.activeRoute()!!.audioAssetUri

        state.activePin?.audioAssetUri?.isNotBlank() == true ->
            state.activePin!!.id to state.activePin!!.audioAssetUri

        else -> null
    }
    LaunchedEffect(activeAudioKey) {
        val (_, uri) = activeAudioKey ?: return@LaunchedEffect engine.pause()
        playbackError = null
        engine.prepare(uri)
        engine.play()
    }

    // One-shot hints requested from other screens (e.g. the pin placement
    // hint from the routes list) share the snackbar host with notices.
    LaunchedEffect(state.mapHint) {
        state.mapHint?.let { hint ->
            snackbarHostState.showSnackbar(hint, duration = SnackbarDuration.Short)
            viewModel.consumeMapHint()
        }
    }

    // Playback failures surface as a transient snackbar (no in-sheet audio
    // controls anymore); the location notice regains the host afterwards.
    LaunchedEffect(playbackError) {
        val message = when (playbackError) {
            AudioErrorKind.UriUnavailable -> "Аудиофайл недоступен"
            AudioErrorKind.UnsupportedFormat -> "Формат не поддерживается"
            AudioErrorKind.AudioFocusLost -> "Звук прерван другим приложением"
            null -> return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Keep read access to the picked file across process restarts.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.selectAudioFile(uri.toString())
        }
    }

    // Same picker for the route editor sheet: a route gets its own audio file.
    val routeAudioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            routeViewModel.selectAudioFile(uri.toString())
        }
    }

    // --- location (dot always on, permission asked at first screen entry) ----

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val anyGranted = result.values.any { it }
        if (anyGranted) {
            viewModel.onMyLocationClick()
        } else {
            // Manifest-level "don't ask again" : after the double denial the
            // rationale callback returns false — only app settings remain.
            val activity = context.findActivity()
            val permanent = activity.let { a ->
                a != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        a,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        a,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
            }
            viewModel.onLocationPermissionDenied(permanent)
        }
    }

    // The system permission dialog is shown exactly once per screen instance:
    // re-launching on every ON_START would re-open the dialog (or re-fire the
    // permanently-denied snackbar) each time the user returns to the app.
    var autoAskedForLocation by rememberSaveable { mutableStateOf(false) }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    // No updates while backgrounded: subscriber must be STARTED to collect.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (hasLocationPermission()) {
                        // Dot without user action; camera centers once per
                        // process (see onLocationGranted / hasFlown).
                        viewModel.onLocationGranted()
                    } else if (!autoAskedForLocation) {
                        autoAskedForLocation = true
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                }

                Lifecycle.Event.ON_STOP -> viewModel.stopLocationTracking()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (noticeText, noticeAction) = state.locationNotice?.let { notice ->
        val (text, action) = when (notice) {
            LocationNotice.PermissionDenied ->
                "Доступ к геолокации не выдан. Нажмите «Моё местоположение» ещё раз" to null

            LocationNotice.PermissionPermanentlyDenied ->
                "Доступ закрыт — включите геолокацию в настройках приложения" to "Настройки"

            LocationNotice.ProviderDisabled ->
                "Геолокация выключена на устройстве" to "Включить"

            LocationNotice.WaitingForFix ->
                "Ждём сигнал GPS…" to null
        }
        text to action
    } ?: (null as String?) to (null as String?)

    // The indefinite notice replaces what is on screen ONLY when a notice is
    // actually present (or just cleared): on a plain screen entry it must not
    // snuff out a one-shot hint snackbar shown at the same moment.
    val hadNotice = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(noticeText) {
        val wasNotice = hadNotice.value
        hadNotice.value = noticeText
        if (wasNotice != null) {
            // A notice WAS on screen: dismiss whatever is there so the queued
            // notice (or a clearing) can proceed.
            snackbarHostState.currentSnackbarData?.dismiss()
        }
        if (noticeText == null) {
            return@LaunchedEffect
        }
        val result = snackbarHostState.showSnackbar(
            message = noticeText,
            actionLabel = noticeAction,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (state.locationNotice) {
                LocationNotice.PermissionPermanentlyDenied ->
                    context.openAppDetailsSettings()

                LocationNotice.ProviderDisabled ->
                    context.openLocationSourceSettings()

                else -> Unit
            }
        }
    }

    val callbacks = remember(viewModel, routeViewModel) {
        object : MapCallbacks {
            // A plain tap is a route vertex while drawing; otherwise the map
            // layer has no tap semantics (pins come from a long press).
            override fun onMapClick(position: GeoPoint) = routeViewModel.onMapClick(position)

            override fun onMapLongClick(position: GeoPoint) {
                // Long press stays the pin gesture; mid-drawing it is ignored
                // so a heavy finger never litters the drawing route with pins.
                if (routeViewModel.uiState.value.draft == null) {
                    viewModel.addPin(position)
                }
            }

            override fun onMarkerClick(markerId: String) = viewModel.openPinEditor(markerId)

            override fun onRouteClick(routeId: String) {
                // Saved routes open the route editor on tap — except while
                // drawing, where the tap must keep adding vertices instead.
                if (routeViewModel.uiState.value.draft == null) {
                    routeViewModel.openEditor(routeId)
                }
            }

            override fun onMyLocationClick() = viewModel.onMyLocationClick()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        mapProvider.Content(
            state = MapUiState(
                markers = state.markers,
                userLocation = state.userLocation,
                cameraTarget = state.cameraTarget,
                cameraRequestId = state.cameraRequestId,
                routeLines = routeState.routeLines(),
            ),
            callbacks = callbacks,
            modifier = Modifier.fillMaxSize(),
        )

        // --- route feature overlays: the four UX phases ----------------------

        val routePhase = routeState.phase()
        if (routePhase == RoutePhase.Drawing) {
            val draft = routeState.draft
            DrawingBanner(
                pointCount = draft?.points?.size ?: 0,
                onFinish = routeViewModel::finishDraft,
                onCancel = routeViewModel::cancelDraft,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (routePhase == RoutePhase.Active) {
            routeState.activeRoute()?.let { route ->
                ActiveRouteChip(
                    title = route.title,
                    onClear = { routeViewModel.setActiveRoute(null) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        OutlinedButton(
            onClick = onOpenRoutes,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 48.dp),
        ) {
            val total = routeState.routes.size + routeState.pins.size
            Text(
                when {
                    total == 0 -> "Маршруты и метки"
                    routeState.pins.isEmpty() -> "Маршруты ($total)"
                    routeState.routes.isEmpty() -> "Метки ($total)"
                    else -> "Маршруты и метки ($total)"
                },
            )
        }

        // Phase Idle entry point: start a new hand-drawn route.
        if (routePhase == RoutePhase.Idle) {
            OutlinedButton(
                onClick = routeViewModel::startDraft,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 112.dp),
            ) {
                Text("+ Маршрут")
            }
        }

        FloatingActionButton(
            onClick = {
                if (hasLocationPermission()) {
                    viewModel.onMyLocationClick()
                } else {
                    // Point-of-use request: the dialog was already offered at
                    // screen entry; FAB re-offers it for a previously denied
                    // user (denial UX handled by the launcher callback).
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Bottom padding keeps the FAB above the map's zoom controls
                // (they are ~100dp tall at the bottom-right corner).
                .padding(end = 16.dp, bottom = 112.dp),
        ) {
            Icon(
                imageVector = MyLocationIcon,
                contentDescription = "Моё местоположение",
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )
    }

    state.selectedPin?.let { pin ->
        PinEditorSheet(
            pin = pin,
            fileName = remember(pin.audioAssetUri) { audioFileName(context, pin.audioAssetUri) },
            distanceToPin = state.distanceToSelectedPin,
            isActive = pin.id == state.activePin?.id,
            onDismiss = viewModel::closePinEditor,
            onRename = viewModel::renamePin,
            onGainChange = { gain ->
                // Slider changes the pin's stored gain; the proximity
                // controller is the single engine-volume writer and picks
                // the new value up through the state flow.
                viewModel.setPinGain(gain)
            },
            onPickFile = { audioPicker.launch(arrayOf("audio/*")) },
            onSetActive = { active -> viewModel.setPinActive(pin.id, active) },
            onDelete = viewModel::deleteSelectedPin,
        )
    }

    // Phase Completed: the finished (or list-opened) route editor sheet —
    // rename, local audio attach, active-route selection, delete.
    routeState.editedRoute?.let { route ->
        RouteEditorSheet(
            route = route,
            lengthMeters = routeState.editedRouteLengthMeters,
            fileName = remember(route.audioAssetUri) { audioFileName(context, route.audioAssetUri) },
            isActive = route.id == routeState.activeRouteId,
            onDismiss = routeViewModel::closeEditor,
            onRename = routeViewModel::renameRoute,
            onPickFile = { routeAudioPicker.launch(arrayOf("audio/*")) },
            onSetActive = { active ->
                routeViewModel.setActiveRoute(if (active) route.id else null)
            },
            onDelete = { routeViewModel.deleteRoute(route.id) },
        )
    }
}

/** Drawing-mode banner: live vertex count plus Завершить/Отмена controls. */
@Composable
private fun DrawingBanner(
    pointCount: Int,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(top = 112.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Маршрут: $pointCount точек. Коснитесь карты, чтобы добавить точку",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Отмена") }
                OutlinedButton(onClick = onFinish, enabled = pointCount >= 2) {
                    Text("Завершить")
                }
            }
        }
    }
}

/** Slim chip naming the active route with a one-tap deselect. */
@Composable
private fun ActiveRouteChip(
    title: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(top = 112.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "● Активный маршрут: $title",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onClear) { Text("Снять") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEditorSheet(
    pin: MusicPin,
    fileName: String,
    distanceToPin: Double?,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onGainChange: (Double) -> Unit,
    onPickFile: () -> Unit,
    onSetActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Метка", style = MaterialTheme.typography.titleMedium)

            Text(
                text = "До метки: ${formatDistance(distanceToPin)}",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = pin.title,
                onValueChange = onRename,
                singleLine = true,
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onPickFile) { Text("Выбрать аудиофайл") }
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            // Activation is the audio control: an active pin's sound plays
            // immediately, looped, with proximity loudness and stereo pan.
            // Closing this sheet never touches the sound.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = { onSetActive(false) },
                    enabled = isActive,
                ) { Text("Снять активность") }
                TextButton(
                    onClick = { onSetActive(true) },
                    enabled = !isActive,
                ) { Text("Сделать активным") }
            }

            Column {
                Text(
                    text = "Громкость: ${(pin.gain * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = pin.gain.toFloat().coerceIn(0f, 1f),
                    onValueChange = { onGainChange(it.toDouble()) },
                    valueRange = 0f..1f,
                )
            }

            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Удалить метку")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Meters under a kilometer, kilometers with one decimal; unknown otherwise. */
private fun formatDistance(distanceMeters: Double?): String = when {
    distanceMeters == null -> "неизвестно (нет фикса)"
    distanceMeters < 1_000.0 -> "%.0f м".format(distanceMeters)
    else -> "%.1f км".format(distanceMeters / 1_000.0)
}

private fun Context.openAppDetailsSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.openLocationSourceSettings() {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/** Unwrapping to the hosting Activity for the rationale check; null in tools. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

