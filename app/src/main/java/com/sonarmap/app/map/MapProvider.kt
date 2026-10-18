package com.sonarmap.app.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sonarmap.app.domain.model.GeoPoint

/** A single map marker expressed in domain terms, no provider types inside. */
data class MapMarkerUi(
    val id: String,
    val position: GeoPoint,
    val title: String,
)

/** Everything the map layer renders; provider implementations must honor it. */
data class MapUiState(
    val markers: List<MapMarkerUi> = emptyList(),
    /** Raw user dot on the map; null until the first fix. Updates silently —
     *  no camera reaction, so smoothed fix jitter never yanks the viewport. */
    val userLocation: GeoPoint? = null,
    /**
     * One-shot camera flight request ("Моё местоположение"): adapters move
     * once per [cameraRequestId] change and never on plain updates.
     * Null target = no request. The id increments on every request, so a
     * repeated flight to the very same point still fires despite StateFlow
     * value dedup — without the id, pressing the FAB twice in a row would
     * recenter only the first time.
     */
    val cameraTarget: GeoPoint? = null,
    val cameraRequestId: Int = 0,
)

/**
 * Events flowing from the map back to the app layer.
 * The app only ever sees domain types here — provider SDK objects are
 * converted inside the adapter and never cross this boundary.
 */
interface MapCallbacks {
    fun onMapLongClick(position: GeoPoint)
    fun onMarkerClick(markerId: String)
    fun onMyLocationClick()
}

/**
 * Swappable map technology boundary. UI code calls [Content] and stays
 * oblivious to whether Google Maps, MapKit, or a debug stub renders below.
 *
 * Contract:
 * - [state] is a full description of what must be visible.
 * - [callbacks] receive already-converted [GeoPoint]s.
 * - Implementations must not leak provider-specific types into signatures.
 */
interface MapProvider {
    @Composable
    fun Content(
        state: MapUiState,
        callbacks: MapCallbacks,
        modifier: Modifier = Modifier,
    )
}
