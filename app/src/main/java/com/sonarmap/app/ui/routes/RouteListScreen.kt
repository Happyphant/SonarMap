package com.sonarmap.app.ui.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.ui.audioFileName

/**
 * Routes list. Rows show the feature state inline: idle (empty state),
 * draft indicator (drawing continues on the map), completed vs active
 * badge per route. Tapping a route card closes the list and opens the
 * route editor over the map ("open from the list" means: navigate back
 * first — the editor lives with the map screen); [onOpenMapToDraw]
 * starts a new draft and does the same (drawing happens by hand — map
 * taps only, no routing API).
 */
@Composable
fun RouteListScreen(
    routeViewModel: RouteViewModel,
    onOpenRouteEditor: (String) -> Unit,
    onOpenMapToDraw: () -> Unit,
) {
    val state by routeViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.routes.isEmpty() && state.draft == null) {
                // Idle phase: no routes exist yet.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Маршрутов пока нет", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Постройте первый маршрут на карте: коснитесь карты в местах, " +
                            "по которым хотите пройти, и завершите рисование.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 64.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.draft?.let { draft ->
                        item(key = "draft") {
                            Card {
                                Text(
                                    text = "● Рисование «${draft.title}»: " +
                                        "${draft.points.size} точек — продолжите на карте",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    items(state.routes, key = { it.id }) { route ->
                        RouteCard(
                            route = route,
                            isActive = route.id == state.activeRouteId,
                            fileName = remember(route.audioAssetUri) {
                                audioFileName(context, route.audioAssetUri)
                            },
                            onOpen = { onOpenRouteEditor(route.id) },
                        )
                    }
                }
            }

            Button(
                onClick = {
                    // Draft opens in the shared repository; the map screen
                    // picks it up and shows the drawing banner.
                    routeViewModel.startDraft()
                    onOpenMapToDraw()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                Text(if (state.draft == null) "Новый маршрут на карте" else "Продолжить на карте")
            }

            if (state.draft != null) {
                TextButton(
                    onClick = routeViewModel::cancelDraft,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp),
                ) {
                    Text("Отменить рисование")
                }
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: Route,
    isActive: Boolean,
    fileName: String,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(route.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${route.points.size} точек · " +
                    (if (isActive) "активный" else "не активный"),
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
