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
 * Walking length of a route along its polyline: the sum of great-circle
 * distances of consecutive vertices. Independent of [segments] so a single
 * pass computes both geometry and length without object churn.
 */
fun Route.totalLengthMeters(): Double =
    points.zipWithNext { start, end -> distanceMeters(start, end) }.sum()

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

/**
 * Nearest-point projection of a position onto a route polyline:
 * everything route-sound needs (distance for the proximity gain, the
 * projected point for the stereo bearing, segment index for tracing).
 *
 * @property point the closest point of the route (a vertex or a foot on a segment)
 * @property distanceMeters great-circle distance position -> [point]
 * @property segmentIndex which segment owns the projection (ties -> lowest index)
 */
data class RouteProjection(
    val point: GeoPoint,
    val distanceMeters: Double,
    val segmentIndex: Int,
)

/**
 * The closest point of the whole route to [position]: one pass over the
 * segments, keeping the best (distance, segmentIndex) pair. A route has at
 * least two points by its own contract, so at least one segment exists.
 */
fun Route.projection(position: GeoPoint): RouteProjection {
    var bestPoint: GeoPoint = points.first()
    var bestDistance = Double.MAX_VALUE
    var bestIndex = 0
    segments().forEachIndexed { index, segment ->
        val foot = projectPointToSegment(position, segment)
        val distance = distanceMeters(position, foot)
        if (distance < bestDistance) {
            bestPoint = foot
            bestDistance = distance
            bestIndex = index
        }
    }
    return RouteProjection(bestPoint, bestDistance, bestIndex)
}

/** Semicircle steps of the zone's round caps (visual, not acoustic). */
private const val ZONE_CAP_STEPS = 12

/** Fallback walking direction for a fully degenerate (zero-length) route. */
private val DOUBLE_UNIT_X = doubleArrayOf(1.0, 0.0)

/**
 * Boundary of a route's hearing area: a closed buffer polygon around the
 * polyline at distance [radiusMeters] (the corridor the map renders as the
 * "область слышимости" outline/fill). Semicircle caps at the ends; interior
 * vertices are miter-joined via averaged adjacent normals, so at very sharp
 * bends the boundary can sit marginally closer than the radius to the line
 * — acceptable for hand-drawn walking routes, exact buffering is overkill
 * for a translucent shading layer.
 *
 * Works in one local equirectangular frame anchored at the first vertex
 * (same frame family as [projectPointToSegment]); accurate for city-scale
 * routes.
 */
fun Route.audibleZoneBoundary(radiusMeters: Double): List<GeoPoint> {
    require(radiusMeters > 0.0) { "radiusMeters must be positive, was $radiusMeters" }

    val anchor = points.first()
    val cosRef = cos(anchor.latitude * RAD_PER_DEGREE).coerceAtLeast(1e-9)

    // Local frame: x = meters east, y = meters north of the anchor.
    val xs = points.map { EARTH_RADIUS_METERS * longitudeDelta(anchor.longitude, it.longitude) * RAD_PER_DEGREE * cosRef }
    val ys = points.map { EARTH_RADIUS_METERS * (it.latitude - anchor.latitude) * RAD_PER_DEGREE }

    // Per-vertex unit left normal (interior: mean of the adjacent ones).
    val n = xs.size
    val leftUnit = arrayOfNulls<DoubleArray>(n)
    val directions = arrayOfNulls<DoubleArray>(n - 1) // segment unit directions
    for (i in 0 until n - 1) {
        val dx = xs[i + 1] - xs[i]
        val dy = ys[i + 1] - ys[i]
        val length = sqrt(dx * dx + dy * dy)
        if (length >= 1e-6) directions[i] = doubleArrayOf(dx / length, dy / length)
    }
    for (i in 0 until n) {
        // Nearest VALID segment directions around the vertex (zero-length
        // duplicates in between are skipped, scanning outwards).
        val prev = (i - 1 downTo 0).firstNotNullOfOrNull { j ->
            directions[j]?.let { d -> doubleArrayOf(-d[1], d[0]) } // left of direction
        }
        val next = (i until n - 1).firstNotNullOfOrNull { j ->
            directions[j]?.let { d -> doubleArrayOf(-d[1], d[0]) }
        }
        val unit = when {
            prev != null && next != null -> {
                val sumX = prev[0] + next[0]
                val sumY = prev[1] + next[1]
                val sumLength = sqrt(sumX * sumX + sumY * sumY)
                if (sumLength < 1e-9) prev // U-turn: bevel with the previous side
                else doubleArrayOf(sumX / sumLength, sumY / sumLength)
            }
            prev != null -> prev
            next != null -> next
            else -> DOUBLE_UNIT_X // fully degenerate; direction irrelevant
        }
        leftUnit[i] = unit
    }

    fun backProject(x: Double, y: Double) = GeoPoint(
        latitude = anchor.latitude + (y / EARTH_RADIUS_METERS) * DEG_PER_RAD,
        longitude = anchor.longitude + (x / (EARTH_RADIUS_METERS * cosRef)) * DEG_PER_RAD,
    )

    val boundary = mutableListOf<GeoPoint>()
    // Inner side of the corridor (left of walking direction), walking order.
    for (i in 0 until n) boundary += backProject(xs[i] + leftUnit[i]!![0] * radiusMeters, ys[i] + leftUnit[i]!![1] * radiusMeters)
    // Semicircular cap around the end vertex (through the forward direction).
    val endDir = directions[n - 2] ?: DOUBLE_UNIT_X
    run {
        val base = atan2(endDir[1], endDir[0])
        for (step in 1 until ZONE_CAP_STEPS) {
            val angle = base + (PI / 2) - PI * step / ZONE_CAP_STEPS
            boundary += backProject(
                xs[n - 1] + radiusMeters * cos(angle),
                ys[n - 1] + radiusMeters * sin(angle),
            )
        }
    }
    // Outer side of the corridor, reversed walking order.
    for (i in n - 1 downTo 0) {
        val u = leftUnit[i]!!
        boundary += backProject(xs[i] - u[0] * radiusMeters, ys[i] - u[1] * radiusMeters)
    }
    // Semicircular cap around the start vertex (through the backward direction).
    val startDir = directions[0] ?: DOUBLE_UNIT_X
    run {
        val base = atan2(startDir[1], startDir[0])
        for (step in 1 until ZONE_CAP_STEPS) {
        // Decreasing angle: the arc bulges BACKWARD (through -dir), not
        // through the corridor interior — otherwise the polygon self-intersects.
        val angle = base - (PI / 2) - PI * step / ZONE_CAP_STEPS
            boundary += backProject(
                xs[0] + radiusMeters * cos(angle),
                ys[0] + radiusMeters * sin(angle),
            )
        }
    }
    return boundary
}
