package com.sonarmap.app.ui.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonarmap.app.data.MusicPinRepositoryProvider
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.map.GoogleMapsProvider
import com.sonarmap.app.map.MapCallbacks
import com.sonarmap.app.map.MapUiState

@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel {
        MapViewModel(MusicPinRepositoryProvider.repository)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mapProvider = remember { GoogleMapsProvider() }

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
            ),
            callbacks = callbacks,
            modifier = Modifier.fillMaxSize(),
        )

        FloatingActionButton(
            onClick = viewModel::onMyLocationClick,
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
    }

    state.selectedPin?.let { pin ->
        PinEditorSheet(
            pin = pin,
            fileName = remember(pin.audioAssetUri) { audioFileName(context, pin.audioAssetUri) },
            onDismiss = viewModel::closePinEditor,
            onRename = viewModel::renamePin,
            onGainChange = viewModel::setPinGain,
            onPickFile = { audioPicker.launch(arrayOf("audio/*")) },
            onDelete = viewModel::deleteSelectedPin,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEditorSheet(
    pin: MusicPin,
    fileName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onGainChange: (Double) -> Unit,
    onPickFile: () -> Unit,
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
