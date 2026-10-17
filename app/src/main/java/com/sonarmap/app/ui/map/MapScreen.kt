package com.sonarmap.app.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonarmap.app.audio.MediaPlayerAudioEngine
import com.sonarmap.app.data.MusicPinRepositoryProvider
import com.sonarmap.app.domain.audio.AudioEngineListener
import com.sonarmap.app.domain.audio.AudioErrorKind
import com.sonarmap.app.domain.audio.AudioPlaybackState
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.location.LocationRepositoryProvider
import com.sonarmap.app.map.MapCallbacks
import com.sonarmap.app.map.MapProviders
import com.sonarmap.app.map.MapUiState
import kotlinx.coroutines.launch

@Composable
fun MapScreen() {
    // Application context — the repository itself is a process-wide singleton.
    val context = LocalContext.current
    val viewModel = viewModel {
        MapViewModel(
            repository = MusicPinRepositoryProvider.repository,
            locationRepository = LocationRepositoryProvider.repository(context.applicationContext),
        )
    }
    MapScreenContent(viewModel = viewModel)
}

@Composable
private fun MapScreenContent(viewModel: MapViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapProvider = remember { MapProviders.default }
    val snackbarHostState = remember { SnackbarHostState() }

    // Local preview playback lives with the screen; released on exit (also
    // across configuration changes — DisposableEffect disposes the remembered
    // engine whenever this composition leaves).
    val engine = remember { MediaPlayerAudioEngine(context) }
    var playbackState by remember { mutableStateOf<AudioPlaybackState>(AudioPlaybackState.Idle) }
    var playbackError by remember { mutableStateOf<AudioErrorKind?>(null) }

    DisposableEffect(engine) {
        engine.setListener(object : AudioEngineListener {
            override fun onStateChanged(state: AudioPlaybackState) {
                // Listener callbacks arrive on the main thread (see engine KDoc).
                playbackState = state
            }

            override fun onError(kind: AudioErrorKind) {
                playbackError = kind
            }
        })
        onDispose {
            engine.release()
            engine.setListener(null)
        }
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

    // --- location permission (requested only from the FAB tap) ---------------

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

    // No updates while backgrounded: subscriber must be STARTED to collect.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startLocationTracking()
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

    LaunchedEffect(noticeText) {
        // An Indefinite snackbar never finishes on its own: dismiss whatever
        // is on screen so the queued notice (or a clearing) can proceed.
        snackbarHostState.currentSnackbarData?.dismiss()
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

    val callbacks = remember(viewModel) {
        object : MapCallbacks {
            override fun onMapLongClick(position: GeoPoint) = viewModel.addPin(position)
            override fun onMarkerClick(markerId: String) = viewModel.openPinEditor(markerId)
            override fun onMyLocationClick() = viewModel.onMyLocationClick()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        mapProvider.Content(
            state = MapUiState(
                markers = state.markers,
                userLocation = state.userLocation,
                cameraTarget = state.cameraTarget,
            ),
            callbacks = callbacks,
            modifier = Modifier.fillMaxSize(),
        )

        FloatingActionButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    viewModel.onMyLocationClick()
                } else {
                    // Point-of-use request: never asked at app startup.
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
        // Fresh selection: reset the error, rewind, and (re)prepare the source.
        LaunchedEffect(pin.id, pin.audioAssetUri) {
            playbackError = null
            if (pin.audioAssetUri.isNotBlank()) engine.prepare(pin.audioAssetUri)
        }
        PinEditorSheet(
            pin = pin,
            fileName = remember(pin.audioAssetUri) { audioFileName(context, pin.audioAssetUri) },
            distanceToPin = state.distanceToSelectedPin,
            onDismiss = viewModel::closePinEditor,
            onRename = viewModel::renamePin,
            onGainChange = { gain ->
                viewModel.setPinGain(gain)
                engine.setVolume(gain) // the slider previews its own loudness live
            },
            onPickFile = { audioPicker.launch(arrayOf("audio/*")) },
            onDelete = viewModel::deleteSelectedPin,
            hasAudioFile = pin.audioAssetUri.isNotBlank(),
            playbackState = playbackState,
            playbackError = playbackError,
            onTogglePlayback = {
                playbackError = null
                // The button is a genuine toggle: Pause stops the playing source,
                // otherwise play() handles Ready/Paused/Preparing/auto-resume.
                if (playbackState == AudioPlaybackState.Playing) {
                    engine.pause()
                } else {
                    engine.play()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEditorSheet(
    pin: MusicPin,
    fileName: String,
    distanceToPin: Double?,
    hasAudioFile: Boolean,
    playbackState: AudioPlaybackState,
    playbackError: AudioErrorKind?,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onGainChange: (Double) -> Unit,
    onPickFile: () -> Unit,
    onDelete: () -> Unit,
    onTogglePlayback: () -> Unit,
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

            // Local preview listen-back; enabled only for a real prepared file.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onTogglePlayback,
                    enabled = hasAudioFile &&
                        playbackState !is AudioPlaybackState.Preparing &&
                        playbackState !is AudioPlaybackState.Idle,
                ) {
                    val label = when (playbackState) {
                        AudioPlaybackState.Playing -> "⏸ Пауза"
                        else -> "▶ Прослушать"
                    }
                    Text(label)
                }
                when (playbackError) {
                    AudioErrorKind.UriUnavailable ->
                        Text(
                            "Файл недоступен",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    AudioErrorKind.UnsupportedFormat ->
                        Text(
                            "Формат не поддерживается",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    AudioErrorKind.AudioFocusLost ->
                        Text(
                            "Воспроизведение прервано (занято другим приложением)",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    null -> Unit
                }
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

/** Human-readable file name for the picked content URI, safe on any failure. */
private fun audioFileName(context: Context, uriString: String): String {
    if (uriString.isBlank()) return "аудиофайл не выбран"
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
    val fromResolver = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromResolver ?: uri.lastPathSegment ?: uriString
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

