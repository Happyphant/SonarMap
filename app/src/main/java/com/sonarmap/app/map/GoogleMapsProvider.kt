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
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.sonarmap.app.domain.model.GeoPoint

/** Conversions live here and nowhere else: GeoPoint/LatLng never leak. */
private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)
private fun LatLng.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

private val USER_LOCATION_COLOR = Color(0xFF3FD2C7)
private val DRAFT_LINE_COLOR = Color(0xFFFFB300) // amber: mid-drawing
private val ACTIVE_LINE_COLOR = Color(0xFF3FD2C7) // teal: active route
private val IDLE_LINE_COLOR = Color(0xFF8A8F98) // gray: saved, not active

/** Zone contour / fill policy (same palette as the Yandex provider). */
private val ZONE_ACTIVE_STROKE_COLOR = Color(0xFF3FD2C7)
private val ZONE_ACTIVE_FILL_COLOR = Color(0xFF3FD2C7).copy(alpha = 0.18f)
private val ZONE_IDLE_STROKE_COLOR = Color(0xFF8A8F98).copy(alpha = 0.5f)
private val ZONE_IDLE_FILL_COLOR = Color.Transparent

private fun AudibleZoneUi.strokeColor(): Color =
    if (isActive) ZONE_ACTIVE_STROKE_COLOR else ZONE_IDLE_STROKE_COLOR

private fun AudibleZoneUi.fillColor(): Color =
    if (isActive) ZONE_ACTIVE_FILL_COLOR else ZONE_IDLE_FILL_COLOR

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
            onMapClick = { latLng -> callbacks.onMapClick(latLng.toGeoPoint()) },
            onMapLongClick = { latLng -> callbacks.onMapLongClick(latLng.toGeoPoint()) },
        ) {
            // Hearing-area layer first (declaration order = z-order below the
            // markers and polylines added later). clickable = false keeps all
            // taps passing through to the map and route lines.
            state.audibleZones.forEach { zone ->
                if (zone is PinAudibleZoneUi) {
                    Circle(
                        center = zone.center.toLatLng(),
                        radius = zone.radiusMeters,
                        clickable = false,
                        strokeColor = zone.strokeColor(),
                        strokeWidth = 2f,
                        fillColor = zone.fillColor(),
                    )
                } else if (zone is RouteAudibleZoneUi) {
                    Polygon(
                        points = zone.rings.firstOrNull().orEmpty().map { it.toLatLng() },
                        holes = zone.rings.drop(1).map { hole -> hole.map { it.toLatLng() } },
                        clickable = false,
                        strokeColor = zone.strokeColor(),
                        strokeWidth = 2f,
                        fillColor = zone.fillColor(),
                    )
                }
            }

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

            // Route polylines; draft vertices get small filled circles so
            // individual taps are visible while drawing. Saved routes are
            // clickable and report the domain route id; the draft stays
            // tap-through so taps keep adding vertices.
            state.routeLines.forEach { line ->
                if (line.points.size >= 2) {
                    Polyline(
                        points = line.points.map { it.toLatLng() },
                        clickable = !line.isDraft,
                        onClick = { callbacks.onRouteClick(line.id) },
                        color = line.lineColor(),
                        width = line.lineWidth(),
                    )
                }
                if (line.isDraft) {
                    line.points.forEach { point ->
                        Circle(
                            center = point.toLatLng(),
                            radius = 3.0,
                            strokeColor = Color.White,
                            strokeWidth = 2f,
                            fillColor = DRAFT_LINE_COLOR,
                        )
                    }
                }
            }
        }
    }

    /**
     * Route-line visual policy of this adapter: the drawing draft is amber
     * and thicker than everything else (mid-edit emphasis), the active
     * route teal and bold, saved inactive routes a muted gray. Widths in
     * screen pixels; the exact look is provider-specific by design.
     */
    private fun RouteLineUi.lineColor(): Color = when {
        isDraft -> DRAFT_LINE_COLOR
        isActive -> ACTIVE_LINE_COLOR
        else -> IDLE_LINE_COLOR
    }

    private fun RouteLineUi.lineWidth(): Float = when {
        isDraft -> 12f
        isActive -> 10f
        else -> 6f
    }
}
