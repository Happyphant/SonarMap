package com.sonarmap.app.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.sonarmap.app.domain.model.GeoPoint

/** Conversions live here and nowhere else: GeoPoint/LatLng never leak. */
private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)
private fun LatLng.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

private val USER_LOCATION_COLOR = Color(0xFF3FD2C7)

/**
 * Google Maps Compose implementation of [MapProvider].
 * The only place in the app that knows about `com.google.android.gms.maps`.
 */
class GoogleMapsProvider : MapProvider {

    @Composable
    override fun Content(
        state: MapUiState,
        callbacks: MapCallbacks,
        modifier: Modifier,
    ) {
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(TEST_USER_LOCATION.toLatLng(), 13f)
        }

        // One-shot camera flight; the dot itself moves without camera reaction.
        // Keyed on the request id, not the target: a repeated FAB press to the
        // same point must still fly (StateFlow dedups equal values).
        LaunchedEffect(state.cameraRequestId) {
            val target = state.cameraTarget
            if (state.cameraRequestId != 0 && target != null) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(target.toLatLng(), 16f),
                )
            }
        }

        GoogleMap(
            modifier = modifier,
            cameraPositionState = cameraPositionState,
            onMapLongClick = { latLng -> callbacks.onMapLongClick(latLng.toGeoPoint()) },
        ) {
            state.markers.forEach { marker ->
                // Pin position is immutable, so a one-shot remembered state is safe;
                // `key` ties the state to the marker identity.
                val markerState = rememberMarkerState(
                    key = marker.id,
                    position = marker.position.toLatLng(),
                )
                Marker(
                    state = markerState,
                    title = marker.title,
                    tag = marker.id,
                    // `tag` carries the domain id; marker positions are not reliable ids.
                    onClick = { clicked ->
                        (clicked.tag as? String)?.let(callbacks::onMarkerClick)
                        true // consumed: suppress the default info window, we show the sheet
                    },
                )
            }

            state.userLocation?.let { location ->
                Circle(
                    center = location.toLatLng(),
                    radius = 30.0,
                    strokeColor = USER_LOCATION_COLOR,
                    strokeWidth = 4f,
                    fillColor = USER_LOCATION_COLOR.copy(alpha = 0.25f),
                )
            }
        }
    }
}
