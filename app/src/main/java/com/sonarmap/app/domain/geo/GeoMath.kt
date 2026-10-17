package com.sonarmap.app.domain.geo

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteSegment
import com.sonarmap.app.domain.model.UserLocation
import com.sonarmap.app.domain.model.segments
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_008.8 // mean radius (IUGG)
private const val RAD_PER_DEGREE = PI / 180.0
private const val DEG_PER_RAD = 180.0 / PI

/**
 * Great-circle distance in meters (haversine, mean Earth radius).
 * Deterministic and platform-free; error versus ellipsoidal formulas is
 * negligible for the distances SonarMap operates on (hundreds of meters).
 */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = a.latitude * RAD_PER_DEGREE
    val lat2 = b.latitude * RAD_PER_DEGREE
    val dLat = (b.latitude - a.latitude) * RAD_PER_DEGREE
    val dLon = (b.longitude - a.longitude) * RAD_PER_DEGREE
    val sinHalfLat = sin(dLat / 2)
    val sinHalfLon = sin(dLon / 2)
    val h = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLon * sinHalfLon
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(h), sqrt(1 - h))
}

/**
 * Initial great-circle bearing from [from] to [to] in degrees, [0, 360).
 * North = 0, east = 90. ATAN2 argument order (y = east, x = north) is what
 * makes 0/90/180/270 come out exactly at the cardinal points.
 */
fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = from.latitude * RAD_PER_DEGREE
    val lat2 = to.latitude * RAD_PER_DEGREE
    val dLon = (to.longitude - from.longitude) * RAD_PER_DEGREE
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x) * DEG_PER_RAD + 360.0) % 360.0
}

/** Shortest signed longitude delta in degrees, (-180, 180]. */
private fun longitudeDelta(from: Double, to: Double): Double =
    ((to - from + 540.0) % 360.0) - 180.0

/**
 * Orthogonal projection of [point] onto an infinite line through [segment],
 * clamped to the segment's extent (start/end when the point overshoots).
 *
 * Works in a local equirectangular frame anchored at segment start:
 * meters east = R * dLon * cos(phiRef), meters north = R * dLat,
 * where phiRef is the segment's mean latitude. The same frame back-projects
 * the result, so rounding is symmetric; accurate for short route segments.
 * A degenerate segment (start == end) projects onto the start point.
 */
fun projectPointToSegment(point: GeoPoint, segment: RouteSegment): GeoPoint {
    val a = segment.start
    val b = segment.end
    val cosRef = cos((a.latitude + b.latitude) / 2 * RAD_PER_DEGREE).coerceAtLeast(1e-9)

    // Segment vector in local meters, measured from `a`.
    val bx = EARTH_RADIUS_METERS * longitudeDelta(a.longitude, b.longitude) * RAD_PER_DEGREE * cosRef
    val by = EARTH_RADIUS_METERS * (b.latitude - a.latitude) * RAD_PER_DEGREE
    // Point vector in the same frame.
    val px = EARTH_RADIUS_METERS * longitudeDelta(a.longitude, point.longitude) * RAD_PER_DEGREE * cosRef
    val py = EARTH_RADIUS_METERS * (point.latitude - a.latitude) * RAD_PER_DEGREE

    val segmentLengthSq = bx * bx + by * by
    if (segmentLengthSq == 0.0) return a

    val t = ((px * bx + py * by) / segmentLengthSq).coerceIn(0.0, 1.0)
    val footX = bx * t
    val footY = by * t

    val lat = a.latitude + (footY / EARTH_RADIUS_METERS) * DEG_PER_RAD
    val lon = a.longitude + (footX / (EARTH_RADIUS_METERS * cosRef)) * DEG_PER_RAD
    return GeoPoint(lat, lon)
}

/**
 * The [maxCount] route segments closest to [location], ordered by ascending
 * great-circle distance from the user to the segment (as the crow flies,
 * not along the route). Ties break by segment index for determinism.
 */
fun nearestSegments(
    location: UserLocation,
    route: Route,
    maxCount: Int,
): List<RouteSegment> {
    require(maxCount >= 0) { "maxCount must be non-negative, was $maxCount" }
    if (maxCount == 0) return emptyList()

    return route
        .segments()
        .mapIndexed { index, segment ->
            RankedSegment(
                index = index,
                segment = segment,
                distanceMeters = distanceMeters(
                    location.position,
                    projectPointToSegment(location.position, segment),
                ),
            )
        }
        .sortedWith(compareBy { it.distanceMeters })
        .take(maxCount)
        .map { it.segment }
}

/** Internal ordering record: distance first, index second (stable ties). */
private data class RankedSegment(
    val index: Int,
    val segment: RouteSegment,
    val distanceMeters: Double,
)
