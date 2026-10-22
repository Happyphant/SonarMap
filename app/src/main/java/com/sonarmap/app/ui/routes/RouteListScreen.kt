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
import androidx.compose.material3.OutlinedButton
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
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.ui.audioFileName
import com.sonarmap.app.ui.map.MapViewModel

/**
 * Shared walking-audio menu: routes AND pins. Rows show the feature state
 * inline: idle (nothing exists), draft indicator (drawing continues on the
 * map), completed vs active badge per entry. Tapping a route card closes
 * the list and opens the route editor over the map; tapping a pin card
 * does the same with the pin editor — the list has no editor of its own,
 * the sheet always lives on the map screen. "Новый пин на карте" asks the
 * map for the placement hint (pins are placed by long-press on the map);
 * "Новый маршрут на карте" opens a draft and returns to the map (drawing
 * happens by hand — map taps only, no routing API).
 */
@Composable
fun RouteListScreen(
    routeViewModel: RouteViewModel,
    mapViewModel: MapViewModel,
    onOpenRouteEditor: (String) -> Unit,
    onOpenPinEditor: (String) -> Unit,
    onOpenMapToDraw: () -> Unit,
    onOpenMapForPinRequest: () -> Unit,
) {
    val state by routeViewModel.uiState.collectAsStateWithLifecycle()
    val pinsState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.routes.isEmpty() && state.pins.isEmpty() && state.draft == null) {
                // Idle phase: nothing exists yet at all.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Маршрутов и меток пока нет", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Постройте первый маршрут на карте: коснитесь карты в местах, " +
                            "по которым хотите пройти, и завершите рисование. Метки " +
                            "ставятся удержанием пальца на карте.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 32.dp,
                        bottom = 160.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "routes-header") {
                        Text("Маршруты", style = MaterialTheme.typography.titleSmall)
                    }
                    if (state.routes.isEmpty()) {
                        item(key = "routes-empty") {
                            Text(
                                "Маршрутов пока нет — постройте первый на карте",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
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

                    item(key = "pins-header") {
                        Text("Метки", style = MaterialTheme.typography.titleSmall)
                    }
                    if (state.pins.isEmpty()) {
                        item(key = "pins-empty") {
                            Text(
                                "Меток пока нет — удерживайте карту, чтобы поставить",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(pinsState.pins, key = { it.id }) { pin ->
                        PinCard(
                            pin = pin,
                            isActive = pin.id == pinsState.activePinId,
                            fileName = remember(pin.audioAssetUri) {
                                audioFileName(context, pin.audioAssetUri)
                            },
                            onOpen = { onOpenPinEditor(pin.id) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        // Draft opens in the shared repository; the map screen
                        // picks it up and shows the drawing banner.
                        routeViewModel.startDraft()
                        onOpenMapToDraw()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.draft == null) "Новый маршрут на карте" else "Продолжить на карте")
                }

                OutlinedButton(
                    onClick = onOpenMapForPinRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Новый пин на карте")
                }

                if (state.draft != null) {
                    TextButton(
                        onClick = routeViewModel::cancelDraft,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Отменить рисование")
                    }
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
                color = entryStatusColor(isActive),
            )
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinCard(
    pin: MusicPin,
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
            Text(pin.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (isActive) "активная метка" else "метка",
                style = MaterialTheme.typography.bodySmall,
                color = entryStatusColor(isActive),
            )
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun entryStatusColor(isActive: Boolean) =
    if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
