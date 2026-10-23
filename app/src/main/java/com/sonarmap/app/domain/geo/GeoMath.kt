package com.sonarmap.app.domain.geo

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteSegment
import com.sonarmap.app.domain.model.UserLocation
import com.sonarmap.app.domain.model.segments
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
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

/** Cells per radius of the hearing-area iso-grid (each cell ≈ R/12 arc). */
private const val ZONE_GRID_CELLS_PER_RADIUS = 12

/** Rings whose enclosed area falls below (R·0.2)² are grid noise, not real holes. */
private const val ZONE_RING_MIN_AREA_FACTOR = 0.2

/** One valid (non-degenerate) segment in the local meter frame. */
private data class ZoneFieldSegment(
    val ax: Double,
    val ay: Double,
    val dirX: Double,
    val dirY: Double,
    val lengthSq: Double,
)

/**
 * Rings of a route's hearing area: the iso-contours of the distance-to-route
 * field at exactly [radiusMeters], traced by marching squares over a local
 * equirectangular meter grid anchored at the first vertex (same frame family
 * as [projectPointToSegment]). The contour is the exact union of segment
 * capsules, so the painted edge always coincides with the acoustic one (the
 * gain floor activates at distance R from the route) — for every bend shape,
 * including the case a plain offset polygon cannot represent: a route that
 * curls tighter than the radius makes the zone an annulus, and the silent
 * "eye" of the curl comes back here as a hole ring, which map providers
 * render as a polygon inner ring. Zero-length duplicate vertices collapse.
 *
 * Rings are ordered [outer, holes…]; the outer ring is counterclockwise and
 * holes clockwise in the local meter frame.
 */
fun Route.audibleZoneRings(radiusMeters: Double): List<List<GeoPoint>> {
    require(radiusMeters > 0.0) { "radiusMeters must be positive, was $radiusMeters" }

    val anchor = points.first()
    val cosRef = cos(anchor.latitude * RAD_PER_DEGREE).coerceAtLeast(1e-9)

    // Local frame: x = meters east, y = meters north of the anchor.
    val xs = points.map { EARTH_RADIUS_METERS * longitudeDelta(anchor.longitude, it.longitude) * RAD_PER_DEGREE * cosRef }
    val ys = points.map { EARTH_RADIUS_METERS * (it.latitude - anchor.latitude) * RAD_PER_DEGREE }

    val segs = buildList {
        for (i in 0 until points.size - 1) {
            val ax = xs[i]
            val ay = ys[i]
            val dx = xs[i + 1] - ax
            val dy = ys[i + 1] - ay
            if (dx * dx + dy * dy >= 1e-12) add(ZoneFieldSegment(ax, ay, dx, dy, dx * dx + dy * dy))
        }
    }

    fun backProject(x: Double, y: Double) = GeoPoint(
        latitude = anchor.latitude + (y / EARTH_RADIUS_METERS) * DEG_PER_RAD,
        longitude = anchor.longitude + (x / (EARTH_RADIUS_METERS * cosRef)) * DEG_PER_RAD,
    )

    // Grid one cell wider than the zone on every side, so a contour chain
    // can never reach the grid boundary (all boundary cells stay outside).
    val step = radiusMeters / ZONE_GRID_CELLS_PER_RADIUS
    val minX = xs.min() - radiusMeters - step
    val minY = ys.min() - radiusMeters - step
    val cols = max(1, ceil((xs.max() - xs.min() + 2 * radiusMeters + 2 * step) / step).toInt())
    val rows = max(1, ceil((ys.max() - ys.min() + 2 * radiusMeters + 2 * step) / step).toInt())

    fun distance(x: Double, y: Double): Double {
        if (segs.isEmpty()) {
            val dx = x - xs.first()
            val dy = y - ys.first()
            return sqrt(dx * dx + dy * dy)
        }
        var best = Double.MAX_VALUE
        for (seg in segs) {
            val t = (((x - seg.ax) * seg.dirX + (y - seg.ay) * seg.dirY) / seg.lengthSq).coerceIn(0.0, 1.0)
            val dx = x - seg.ax - t * seg.dirX
            val dy = y - seg.ay - t * seg.dirY
            val d = sqrt(dx * dx + dy * dy)
            if (d < best) best = d
        }
        return best
    }

    // Corner distances; rows go south -> north, columns west -> east.
    val field = DoubleArray((rows + 1) * (cols + 1))
    for (r in 0..rows) {
        val y = minY + step * r
        val rowBase = r * (cols + 1)
        for (c in 0..cols) {
            field[rowBase + c] = distance(minX + step * c, y)
        }
    }

    val iso = radiusMeters

    /**
     * Crossing points, keyed by grid edge so neighbouring cells reference the
     * SAME coordinate (same corner pair -> bitwise equal double), which makes
     * contour stitching exact. Edge id: horizontal edge h(r,c) spans corners
     * (r,c)-(r,c+1); vertical edge v(r,c) spans (r,c)-(r+1,c).
     */
    val crossingIndexByKey = HashMap<Long, Int>()
    val crossingXs = ArrayList<Double>()
    val crossingYs = ArrayList<Double>()
    val verticalKeyBase = (rows + 1L) * (cols + 1)

    fun crossingByKey(horizontal: Boolean, row: Int, col: Int): Int {
        val key = if (horizontal) {
            row * (cols + 1L) + col
        } else {
            verticalKeyBase + col * (rows + 1L) + row
        }
        crossingIndexByKey[key]?.let { return it }

        // Corner coordinates of this edge.
        val ax = minX + step * col
        val ay = minY + step * row
        val bx = if (horizontal) minX + step * (col + 1) else ax
        val by = if (horizontal) ay else minY + step * (row + 1)
        val d1 = field[row * (cols + 1) + col]
        val d2 = field[if (horizontal) row * (cols + 1) + col + 1 else (row + 1) * (cols + 1) + col]

        var x: Double
        var y: Double
        if ((d1 <= iso) != (d2 <= iso)) {
            // The edge straddles the iso; find the point where the TRUE field
            // equals it by bisection. Linear interpolation of corner values
            // can land meters off where the distance field folds around the
            // medial axis of overlapping capsules — bisection is immune.
            val insideAtA = d1 <= iso
            var lo = 0.0
            var hi = 1.0
            repeat(32) {
                val t = (lo + hi) / 2
                val dt = distance(ax + (bx - ax) * t, ay + (by - ay) * t)
                if ((dt <= iso) == insideAtA) lo = t else hi = t
            }
            val t = (lo + hi) / 2
            x = ax + (bx - ax) * t
            y = ay + (by - ay) * t
        } else {
            // Defensive fallback for a non-straddling edge (never referenced
            // by the cell LUT): midpoint, so stitching cannot break.
            x = (ax + bx) / 2
            y = (ay + by) / 2
        }
        val index = crossingXs.size
        crossingXs.add(x)
        crossingYs.add(y)
        crossingIndexByKey[key] = index
        return index
    }

    /** Oriented cell LUT, walk direction keeps the zone region on the LEFT. */
    fun cellLinks(row: Int, col: Int): List<Pair<Int, Int>> {
        val sw = field[row * (cols + 1) + col] <= iso
        val se = field[row * (cols + 1) + col + 1] <= iso
        val ne = field[(row + 1) * (cols + 1) + col + 1] <= iso
        val nw = field[(row + 1) * (cols + 1) + col] <= iso
        val code = (if (sw) 1 else 0) or (if (se) 2 else 0) or (if (ne) 4 else 0) or (if (nw) 8 else 0)
        val e0 = { crossingByKey(horizontal = true, row = row, col = col) }
        val e1 = { crossingByKey(horizontal = false, row = row, col = col + 1) }
        val e2 = { crossingByKey(horizontal = true, row = row + 1, col = col) }
        val e3 = { crossingByKey(horizontal = false, row = row, col = col) }
        return when (code) {
            1 -> listOf(e0() to e3())
            2 -> listOf(e1() to e0())
            3 -> listOf(e1() to e3())
            4 -> listOf(e2() to e1())
            5 -> if (distance(minX + step * (col + 0.5), minY + step * (row + 0.5)) <= iso) {
                listOf(e0() to e1(), e2() to e3())
            } else {
                listOf(e0() to e3(), e2() to e1())
            }
            6 -> listOf(e2() to e0())
            7 -> listOf(e2() to e3())
            8 -> listOf(e3() to e2())
            9 -> listOf(e0() to e2())
            10 -> if (distance(minX + step * (col + 0.5), minY + step * (row + 0.5)) <= iso) {
                listOf(e3() to e0(), e1() to e2())
            } else {
                listOf(e1() to e0(), e3() to e2())
            }
            11 -> listOf(e1() to e2())
            12 -> listOf(e3() to e1())
            13 -> listOf(e0() to e1())
            14 -> listOf(e3() to e0())
            else -> emptyList() // 0 and 15
        }
    }

    // One outgoing link per crossing node; every crossing lies on exactly one
    // closed chain because shared edges reuse the same crossing and the cell
    // LUT keeps the zone region on one side of the walk everywhere.
    val outgoing = HashMap<Int, Int>()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            for ((from, to) in cellLinks(r, c)) {
                outgoing[from] = to
            }
        }
    }

    val visited = HashSet<Int>()
    val rings = ArrayList<List<DoubleArray>>()
    for (start in crossingIndexByKey.values) {
        if (start in visited) continue
        val ring = ArrayList<DoubleArray>()
        var node = start
        do {
            visited.add(node)
            ring.add(doubleArrayOf(crossingXs[node], crossingYs[node]))
            node = outgoing[node] ?: break // unreachable for a well-formed chain
        } while (node != start)
        if (ring.size >= 3) rings.add(ring)
    }

    // Area in the local frame decides ring role and winding; drop noise.
    fun signedArea(ring: List<DoubleArray>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a[0] * b[1] - b[0] * a[1]
        }
        return sum / 2.0
    }

    val minAbsArea = ZONE_RING_MIN_AREA_FACTOR * radiusMeters * radiusMeters
    val kept = rings
        .filter { abs(signedArea(it)) >= minAbsArea }
        // Drop vertices that coincide with their predecessor (degenerate cells).
        .map { ring ->
            ring.distinctBy { (it[0] * 1e7).toLong() to (it[1] * 1e7).toLong() }
        }
        .filter { it.size >= 3 }
        .sortedByDescending { abs(signedArea(it)) }
    if (kept.isEmpty()) return emptyList()

    return kept.mapIndexed { index, ring ->
        val area = signedArea(ring)
        // Outer CCW, holes CW; reorient only if the walk disagrees.
        val shouldReverse = if (index == 0) area < 0 else area > 0
        val oriented = if (shouldReverse) ring.reversed() else ring
        oriented.map { backProject(it[0], it[1]) }
    }
}
