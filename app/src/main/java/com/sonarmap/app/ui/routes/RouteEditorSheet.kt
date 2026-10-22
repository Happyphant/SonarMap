package com.sonarmap.app.ui.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sonarmap.app.domain.model.Route

/**
 * Editor for one saved route — rename, attach a local audio file, make it
 * the active walk route, or delete it. Rendered by the map screen, which is
 * its only host: it opens right after Завершить, when a saved route
 * polyline is tapped, or when a route is opened from the routes list
 * (the list closes and navigates back to the map first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorSheet(
    route: Route,
    lengthMeters: Double?,
    fileName: String,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
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
            Text("Маршрут", style = MaterialTheme.typography.titleMedium)

            Text(
                text = routeSummary(route, lengthMeters, isActive),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = route.title,
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

            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Удалить маршрут")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** "3 точки · 128 м · активный" — one line of status for the sheet. */
private fun routeSummary(route: Route, lengthMeters: Double?, isActive: Boolean): String =
    buildString {
        append("${route.points.size} точек")
        if (lengthMeters != null) {
            append(" · ")
            append(
                if (lengthMeters < 1_000.0) "%.0f м".format(lengthMeters)
                else "%.1f км".format(lengthMeters / 1_000.0),
            )
        }
        append(" · ")
        append(if (isActive) "активный" else "не активный")
    }
