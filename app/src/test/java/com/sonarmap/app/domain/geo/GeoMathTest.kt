package com.sonarmap.app.domain.geo

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteSegment
import com.sonarmap.app.domain.model.UserLocation
import com.sonarmap.app.domain.model.segments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `zone boundary keeps a constant distance from a straight route`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)), // ~111 m east
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 100.0
        val boundary = route.audibleZoneBoundary(radius)

        assertTrue("closed polygon, got ${boundary.size}", boundary.size > 10)
        // Every boundary point sits one radius away from the polyline
        // (round caps + straight sides of a corridor make this exact).
        boundary.forEach { point ->
            val min = route.segments().minOf { segment ->
                distanceMeters(point, projectPointToSegment(point, segment))
            }
            assertTrue("point $point is $min m from the line", radius - min < 0.5 && min < radius + 0.5)
        }
    }

    @Test
    fun `zone boundary spans the full corridor width`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val radius = 50.0
        val lats = route.audibleZoneBoundary(radius).map { it.latitude }
        // North/south extremes at exactly ±radius around the equator.
        assertEquals(radius, (lats.max() * METERS_PER_DEGREE_LAT), 1.0)
        assertEquals(radius, (-lats.min() * METERS_PER_DEGREE_LAT), 1.0)
    }

    @Test
    fun `zone boundary tolerates duplicate consecutive points`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        val boundary = route.audibleZoneBoundary(40.0)
        // All real (non-cap) offsets stay at the requested radius.
        boundary.forEach { point ->
            val min = route.segments().minOf { segment ->
                distanceMeters(point, projectPointToSegment(point, segment))
            }
            assertTrue("point $point is $min m from the line", min >= 40.0 - 1.0 && min <= 40.0 + 1.0)
        }
    }

    @Test
    fun `zone boundary rejects non-positive radius`() {
        val route = Route(
            id = "r",
            title = "t",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)),
            audioAssetUri = "",
            createdAt = 0L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            route.audibleZoneBoundary(0.0)
        }
    }
}
