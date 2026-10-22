package com.sonarmap.app.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sonarmap.app.domain.geo.audibleZoneBoundary
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route

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
    /** Polylines rendered over the map: the drawing draft vertex-first, then
     *  saved routes (active one highlighted). Empty = no route visuals. */
    val routeLines: List<RouteLineUi> = emptyList(),
    /**
     * Hearing-area shadings under everything: every pin and route gets one
     * (active = translucent fill, inactive = outline). Adapters must render
     * these beneath route links and markers and keep taps passing through.
     */
    val audibleZones: List<AudibleZoneUi> = emptyList(),
)

/** A hand-drawn route polyline in domain terms (no provider geometry types). */
data class RouteLineUi(
    /** Draft id or the saved route id; providers only use it as a key. */
    val id: String,
    /** Polyline vertices in walking order; >= 2 when not a draft. */
    val points: List<GeoPoint>,
    /** True while the route is being drawn (emphasis styling allowed). */
    val isDraft: Boolean,
    /** True for the currently active route (bold highlight). */
    val isActive: Boolean,
)

/**
 * The hearing-area shading rendered around a pin or a route:
 * translucent fill for the active source, outline-only otherwise.
 * Pure domain geometry — providers decide how to paint it (their circles,
 * polygons and z-order are provider details).
 */
interface AudibleZoneUi {
    /** Stable key (pin or route id). */
    val id: String
    /** Radius in meters; providers must scale the shading accordingly. */
    val radiusMeters: Double
    /** Active source = filled semitransparent; inactive = contour only. */
    val isActive: Boolean
}

/** Circular hearing area of one music pin. */
data class PinAudibleZoneUi(
    val pinId: String,
    /** Circle center = the pin position. */
    val center: GeoPoint,
    override val radiusMeters: Double,
    override val isActive: Boolean,
) : AudibleZoneUi {
    override val id: String get() = pinId
}

/** Corridor hearing area of one route (already a closed boundary polygon). */
data class RouteAudibleZoneUi(
    val routeId: String,
    /** Corridor boundary; see [com.sonarmap.app.domain.geo.audibleZoneBoundary]. */
    val boundary: List<GeoPoint>,
    override val radiusMeters: Double,
    override val isActive: Boolean,
) : AudibleZoneUi {
    override val id: String get() = routeId
}

/**
 * Events flowing from the map back to the app layer.
 * The app only ever sees domain types here — provider SDK objects are
 * converted inside the adapter and never cross this boundary.
 * [onMapClick] fires on a plain map tap without any marker hit; the app
 * layer decides what it means (route vertices during drawing, ignored
 * otherwise) — the map layer itself stays policy-free.
 * [onRouteClick] fires when a saved route polyline is tapped (never for
 * the drawing draft); the app layer decides the consequence (route
 * editor) — adapters must only report the domain route id.
 */
interface MapCallbacks {
    fun onMapClick(position: GeoPoint)
    fun onMapLongClick(position: GeoPoint)
    fun onMarkerClick(markerId: String)
    fun onRouteClick(routeId: String)
    fun onMyLocationClick()
}

/**
 * Builds the full hearing-area layer: one circular zone per pin and one
 * corridor zone per saved route, marking the single active source filled.
 * Pure function of the domain state — recomputed whenever pins/routes,
 * their radii or the activation change.
 */
fun audibleZonesOf(
    pins: List<MusicPin>,
    activePinId: String?,
    routes: List<Route>,
    activeRouteId: String?,
): List<AudibleZoneUi> =
    pins.map { pin ->
        PinAudibleZoneUi(
            pinId = pin.id,
            center = pin.position,
            radiusMeters = pin.audibleRadiusMeters,
            isActive = pin.id == activePinId,
        )
    } + routes.map { route ->
        RouteAudibleZoneUi(
            routeId = route.id,
            boundary = route.audibleZoneBoundary(route.audibleRadiusMeters),
            radiusMeters = route.audibleRadiusMeters,
            isActive = route.id == activeRouteId,
        )
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
