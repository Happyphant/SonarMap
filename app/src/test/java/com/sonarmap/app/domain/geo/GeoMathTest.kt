package com.sonarmap.app.domain.geo

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteSegment
import com.sonarmap.app.domain.model.UserLocation
import com.sonarmap.app.domain.model.segments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One degree of latitude in meters for the IUGG radius used in GeoMath. */
private const val METERS_PER_DEGREE_LAT = 111_195.0

/**
 * Pure JVM tests: the domain layer refers to no Android classes, so these run
 * without an emulator or Robolectric.
 */
class GeoMathTest {

    // Meters per degree of arc on a sphere: R * pi / 180, R = 6371008.8
    private val degDistance = 111_194.93

    // ---------- distanceMeters ----------

    @Test
    fun `distance is zero for identical points`() {
        val p = GeoPoint(55.75222, 37.61556)
        assertEquals(0.0, distanceMeters(p, p), 1e-9)
    }

    @Test
    fun `distance is symmetric`() {
        val a = GeoPoint(55.0, 37.0)
        val b = GeoPoint(48.0, 2.0)
        assertEquals(distanceMeters(a, b), distanceMeters(b, a), 1e-6)
    }

    @Test
    fun `one degree of latitude is about 111195 meters`() {
        val d = distanceMeters(GeoPoint(0.0, 10.0), GeoPoint(1.0, 10.0))
        assertEquals(degDistance, d, 1.0) // 1 m tolerance on the mean-radius model
    }

    @Test
    fun `one degree of longitude at equator is about 111195 meters`() {
        val d = distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(degDistance, d, 1.0)
    }

    @Test
    fun `distance uses short arc across antimeridian`() {
        // 179 -> -178 is a 3-degree hop east on the equator, not 357 around.
        // (An exactly antipodal pair like 179 to -1 would be undefined: every
        // great circle through antipodes has the same length, so avoid it here.)
        val d = distanceMeters(GeoPoint(0.0, 179.0), GeoPoint(0.0, -178.0))
        assertEquals(3 * degDistance, d, 1.0)
    }

    // ---------- bearingDegrees ----------

    @Test
    fun `bearing is 0 due north`() {
        // Meridian bearings are exact because meridians are great circles.
        assertEquals(0.0, bearingDegrees(GeoPoint(10.0, 0.0), GeoPoint(20.0, 0.0)), 1e-6)
    }

    @Test
    fun `bearing is 90 due east on the equator`() {
        assertEquals(90.0, bearingDegrees(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0)), 1e-6)
    }

    @Test
    fun `bearing is 180 due south`() {
        assertEquals(180.0, bearingDegrees(GeoPoint(-10.0, 0.0), GeoPoint(-20.0, 0.0)), 1e-6)
    }

    @Test
    fun `bearing is 270 due west on the equator`() {
        assertEquals(270.0, bearingDegrees(GeoPoint(0.0, 10.0), GeoPoint(0.0, 0.0)), 1e-6)
    }

    @Test
    fun `bearing stays in range 0 to 360 exclusive`() {
        val pairs = listOf(
            GeoPoint(5.0, -179.0) to GeoPoint(5.0, 179.0),  // antimeridian crossing
            GeoPoint(89.0, 0.0) to GeoPoint(-89.0, 90.0),   // near the pole
            GeoPoint(-45.0, -45.0) to GeoPoint(45.0, 45.0),
        )
        pairs.forEach { (from, to) ->
            val b = bearingDegrees(from, to)
            assertTrue("bearing $b out of [0, 360)", b >= 0.0 && b < 360.0)
        }
    }

    @Test
    fun `northeast bearing matches closed form`() {
        // (0,0) -> (45,45): bearing = atan2(sin45*cos45, sin45) = atan(0.5).
        assertEquals(35.2644, bearingDegrees(GeoPoint(0.0, 0.0), GeoPoint(45.0, 45.0)), 0.01)
    }

    // ---------- projectPointToSegment ----------

    private fun pointToSegment(point: GeoPoint, start: GeoPoint, end: GeoPoint) =
        projectPointToSegment(point, RouteSegment(start, end))

    @Test
    fun `projection is the orthogonal foot for equator segment`() {
        val foot = pointToSegment(GeoPoint(0.1, 0.5), GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(0.0, foot.latitude, 1e-6)
        assertEquals(0.5, foot.longitude, 1e-6)
    }

    @Test
    fun `projection honors latitude reference away from equator`() {
        // Meridian segment at lat 10..11; point is 0.2deg east of it.
        val foot = pointToSegment(GeoPoint(10.6, 0.2), GeoPoint(10.0, 0.0), GeoPoint(11.0, 0.0))
        assertEquals(10.6, foot.latitude, 1e-6)
        assertEquals(0.0, foot.longitude, 1e-6)
    }

    @Test
    fun `projection clamps to segment start`() {
        val foot = pointToSegment(GeoPoint(-0.5, -0.2), GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(GeoPoint(0.0, 0.0), foot)
    }

    @Test
    fun `projection clamps to segment end`() {
        val foot = pointToSegment(GeoPoint(-0.5, 1.5), GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(GeoPoint(0.0, 1.0), foot)
    }

    @Test
    fun `projection onto degenerate segment yields start`() {
        val foot = pointToSegment(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0), GeoPoint(2.0, 2.0))
        assertEquals(GeoPoint(2.0, 2.0), foot)
    }

    @Test
    fun `foot lies on the segment and shortens user distance`() {
        val start = GeoPoint(0.0, 0.0)
        val end = GeoPoint(0.0, 1.0)
        val point = GeoPoint(0.1, 0.5)
        val foot = pointToSegment(point, start, end)

        val throughFoot = distanceMeters(point, foot)
        val viaStart = distanceMeters(point, start)
        assertTrue("foot must be closer than the segment endpoints", throughFoot < viaStart)
        assertEquals(0.1 * degDistance, throughFoot, 1.0)
    }

    // ---------- nearestSegments ----------

    private fun locationAt(lat: Double, lon: Double) = UserLocation(
        position = GeoPoint(lat, lon),
        headingDegrees = 0.0,
        accuracyMeters = 0.0,
        timestamp = 0L,
    )

    private fun routeOnEquator() = Route(
        id = "r1",
        title = "line",
        points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 1.0),
            GeoPoint(0.0, 2.0),
            GeoPoint(0.0, 3.0),
        ),
        audioAssetUri = "asset:///route1.ogg",
        createdAt = 0L,
    )

    @Test
    fun `segments of a route are consecutive pairs`() {
        val segments = routeOnEquator().segments()
        assertEquals(3, segments.size)
        assertEquals(RouteSegment(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), segments.first())
        assertEquals(RouteSegment(GeoPoint(0.0, 2.0), GeoPoint(0.0, 3.0)), segments.last())
    }

    @Test
    fun `nearest segments are ordered by distance`() {
        // Position sits right next to segment 3, then 2, then 1.
        val nearest = nearestSegments(locationAt(0.5, 2.9), routeOnEquator(), maxCount = 3)
        val all = routeOnEquator().segments()
        assertEquals(all[2], nearest[0])
        assertEquals(all[1], nearest[1])
        assertEquals(all[0], nearest[2])
    }

    @Test
    fun `maxCount limits the result`() {
        val route = routeOnEquator()
        assertEquals(3, nearestSegments(locationAt(0.0, 0.5), route, 3).size)
        assertEquals(1, nearestSegments(locationAt(0.0, 0.5), route, 1).size)
        assertTrue(nearestSegments(locationAt(0.0, 0.5), route, 0).isEmpty())
    }

    // ---------- Route.projection ----------

    @Test
    fun `projection projects onto the nearest segment interior`() {
        // Midpoint between vertices: the foot is the user's own longitude on
        // the equator; segment 1 (equator 0..1) is the closest.
        val projection = routeOnEquator().projection(GeoPoint(0.001, 0.5))
        val expected = degDistance * 0.001
        assertEquals(0.0, projection.point.latitude, 1e-9)
        assertEquals(0.5, projection.point.longitude, 1e-6)
        assertEquals(expected, projection.distanceMeters, 1.0)
        assertEquals(0, projection.segmentIndex)
    }

    @Test
    fun `projection clamps to the endpoint when beyond the route`() {
        // South-west of the very first vertex: the foot is the vertex itself.
        val projection = routeOnEquator().projection(GeoPoint(-0.001, -0.001))
        assertEquals(GeoPoint(0.0, 0.0), projection.point)
        val d = distanceMeters(GeoPoint(-0.001, -0.001), GeoPoint(0.0, 0.0))
        assertEquals(d, projection.distanceMeters, 1e-9)
        assertEquals(0, projection.segmentIndex)
    }

    @Test
    fun `projection near a later segment reports that segment`() {
        val projection = routeOnEquator().projection(GeoPoint(0.0, 2.5))
        assertEquals(2.5, projection.point.longitude, 1e-6)
        assertEquals(0.0, projection.distanceMeters, 1e-9)
        assertEquals(2, projection.segmentIndex) // foot on segment (2..3)
    }

    @Test
    fun `route requires at least two points`() {
        // Completed-route domain contract: a route is walkable sequence of
        // segments, so a single-point route is rejected at construction,
        // and `segments()` on a valid two-point route yields one segment.
        assertThrows(IllegalArgumentException::class.java) {
            routeOnEquator().copy(points = listOf(GeoPoint(0.0, 0.0)))
        }
        val twoPoints = routeOnEquator().copy(
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)),
        )
        assertEquals(1, twoPoints.segments().size)
        assertEquals(1, nearestSegments(locationAt(0.0, 0.5), twoPoints, 5).size)
    }

    @Test
    fun `returned distances are ascending`() {
        val user = locationAt(0.5, 2.9)
        val distances = nearestSegments(user, routeOnEquator(), 3).map { segment ->
            distanceMeters(user.position, projectPointToSegment(user.position, segment))
        }
        assertTrue(
            "distances must be ascending, got $distances",
            distances.zipWithNext { a, b -> a <= b }.all { it },
        )
    }

    @Test
    fun `zone rings keep a constant distance from a straight route`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)), // ~111 m east
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 100.0
        val rings = route.audibleZoneRings(radius)
        assertEquals("straight route draws a single ring, no hole", 1, rings.size)
        val outer = rings.first()
        assertTrue("closed ring, got ${outer.size}", outer.size > 10)
        // Every ring point sits one radius away from the polyline; the grid
        // iso-interpolation error is well under a meter (step = R/12).
        outer.forEach { point ->
            val min = route.segments().minOf { segment ->
                distanceMeters(point, projectPointToSegment(point, segment))
            }
            assertTrue("point $point is $min m from the line", radius - min < 1.0 && min < radius + 1.0)
        }
    }

    @Test
    fun `zone rings span the full corridor width`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 50.0
        val lats = route.audibleZoneRings(radius).first().map { it.latitude }
        // North/south extremes at exactly ±radius around the equator.
        assertEquals(radius, (lats.max() * METERS_PER_DEGREE_LAT), 1.0)
        assertEquals(radius, (-lats.min() * METERS_PER_DEGREE_LAT), 1.0)
    }

    @Test
    fun `zone rings tolerate duplicate consecutive points`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val rings = route.audibleZoneRings(40.0)
        assertEquals("duplicate vertices collapse: one ring", 1, rings.size)
        rings.first().forEach { point ->
            val min = route.segments().minOf { segment ->
                distanceMeters(point, projectPointToSegment(point, segment))
            }
            assertTrue("point $point is $min m from the line", min >= 40.0 - 1.0 && min <= 40.0 + 1.0)
        }
    }

    @Test
    fun `zone rings reject non-positive radius`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            route.audibleZoneRings(0.0)
        }
    }

    /** Straight segments of the boundary touching a shared vertex only. */
    private fun List<GeoPoint>.containsSelfIntersection(): Boolean {
        fun cross(o: GeoPoint, a: GeoPoint, b: GeoPoint) =
            (a.longitude - o.longitude) * (b.latitude - o.latitude) -
                (a.latitude - o.latitude) * (b.longitude - o.longitude)

        fun onSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint) =
            minOf(a.latitude, b.latitude) - 1e-12 <= p.latitude &&
                p.latitude <= maxOf(a.latitude, b.latitude) + 1e-12 &&
                minOf(a.longitude, b.longitude) - 1e-12 <= p.longitude &&
                p.longitude <= maxOf(a.longitude, b.longitude) + 1e-12

        fun segmentsIntersect(p1: GeoPoint, p2: GeoPoint, p3: GeoPoint, p4: GeoPoint): Boolean {
            val d1 = cross(p3, p4, p2)
            val d2 = cross(p3, p4, p1)
            val d3 = cross(p1, p2, p4)
            val d4 = cross(p1, p2, p3)
            if ((d1 > 1e-12 && d2 < -1e-12) || (d1 < -1e-12 && d2 > 1e-12)) {
                if ((d3 > 1e-12 && d4 < -1e-12) || (d3 < -1e-12 && d4 > 1e-12)) return true
            }
            // Collinear overlap counts as an intersection unless the
            // segments merely touch at both their shared endpoints.
            val eps = 1e-12
            if (abs(d1) < eps && onSegment(p3, p1, p2)) return true
            if (abs(d2) < eps && onSegment(p4, p1, p2)) return true
            if (abs(d3) < eps && onSegment(p1, p3, p4)) return true
            if (abs(d4) < eps && onSegment(p2, p3, p4)) return true
            return false
        }

        val n = size
        for (i in 0 until n) {
            val a1 = this[i]
            val a2 = this[(i + 1) % n]
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue // adjacent through closure
                val b1 = this[j]
                val b2 = this[(j + 1) % n]
                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    /** Even-odd point-in-polygon (ray casting), for the hole-coverage asserts. */
    private fun List<GeoPoint>.containsPoint(point: GeoPoint): Boolean {
        var inside = false
        for (i in indices) {
            val a = this[i]
            val b = this[(i + 1) % size]
            val straddles = (a.latitude > point.latitude) != (b.latitude > point.latitude)
            if (straddles) {
                val lonAtLat = (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                    (b.latitude - a.latitude) + a.longitude
                if (point.longitude < lonAtLat) inside = !inside
            }
        }
        return inside
    }

    @Test
    fun `zone rings keep exact radius around a right-angle bend`() {
        val route = Route(
            id = "r",
            title = "t",
            // 90° bend with legs much longer than the radius: a plain castle
            // corner, well clear of the annulus regime.
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004), GeoPoint(0.004, 0.004)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 150.0
        val rings = route.audibleZoneRings(radius)
        assertEquals("right-angle bend draws a single ring", 1, rings.size)
        rings.first().forEach { point ->
            val min = route.segments().minOf { segment ->
                distanceMeters(point, projectPointToSegment(point, segment))
            }
            assertTrue("point $point is $min m from the line", abs(min - radius) < 1.0)
        }
    }

    @Test
    fun `zone rings stay loop free on a sharp hairpin bend`() {
        val route = Route(
            id = "r",
            title = "t",
            // Sharp ~135° bend with segments SHORTER than the radius: the
            // corridor folds on itself, the iso-contour must stay simple.
            points = listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.001),
                GeoPoint(0.0008, 0.00187),
            ),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 400.0 // wider than a segment: stressed folding
        val rings = route.audibleZoneRings(radius)
        assertTrue("at least the outer ring", rings.isNotEmpty())
        rings.forEach { ring ->
            assertFalse("ring must not self-intersect", ring.containsSelfIntersection())
            ring.forEach { point ->
                val min = route.segments().minOf { segment ->
                    distanceMeters(point, projectPointToSegment(point, segment))
                }
                assertTrue("point $point is $min m from the line", min <= radius + 2.0)
            }
        }
    }

    @Test
    fun `zone rings of a closed curl form an annulus with a hole`() {
        // Twelve vertices on a 500 m circle covering 330°: a near-closed loop
        // whose interior pokes further from the route (500 m) than the zone
        // radius (300 m) — the exact union of capsules is an annulus, and the
        // untouched island in the middle must come back as a hole ring.
        val loopRadius = 500.0
        fun circlePoint(angleDegrees: Double): GeoPoint {
            val rad = angleDegrees * PI / 180.0
            return GeoPoint(
                latitude = loopRadius * sin(rad) / METERS_PER_DEGREE_LAT,
                longitude = loopRadius * cos(rad) / METERS_PER_DEGREE_LAT,
            )
        }
        val route = Route(
            id = "r",
            title = "t",
            points = (0..330 step 30).map { circlePoint(it.toDouble()) },
            audioAssetUri = "",
            createdAt = 0L,
        )
        val zoneRadius = 300.0
        val rings = route.audibleZoneRings(zoneRadius)
        assertEquals("annulus: outer ring + hole", 2, rings.size)
        val (outer, hole) = rings
        listOf(outer, hole).forEach { ring ->
            assertFalse("ring must not self-intersect", ring.containsSelfIntersection())
            ring.forEach { point ->
                val min = route.segments().minOf { segment ->
                    distanceMeters(point, projectPointToSegment(point, segment))
                }
                assertTrue("point $point is $min m from the line", min <= zoneRadius + 2.0)
            }
        }
        // Route vertices sit inside the painted area and never inside the hole.
        route.points.forEach { vertex ->
            assertTrue("vertex $vertex outside outer ring", outer.containsPoint(vertex))
            assertFalse("vertex $vertex inside hole", hole.containsPoint(vertex))
        }
    }

    @Test
    fun `zone rings stay loop free on an S-curved arc route`() {
        val route = Route(
            id = "r",
            title = "t",
            // Two alternating sharp bends (proto-arc "дуговой" profile).
            points = listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.0008),
                GeoPoint(0.0006, 0.0013),
                GeoPoint(0.0012, 0.0013),
                GeoPoint(0.0017, 0.0008),
                GeoPoint(0.0017, 0.0),
            ),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 250.0
        val rings = route.audibleZoneRings(radius)
        assertTrue("at least the outer ring", rings.isNotEmpty())
        rings.forEach { ring ->
            assertFalse("ring must not self-intersect", ring.containsSelfIntersection())
            ring.forEach { point ->
                val min = route.segments().minOf { segment ->
                    distanceMeters(point, projectPointToSegment(point, segment))
                }
                assertTrue("point $point is $min m from the line", min <= radius + 2.0)
            }
        }
    }
}
