package com.sonarmap.app.domain.geo

import com.sonarmap.app.domain.geo.distanceMeters
import com.sonarmap.app.domain.location.KalmanLocationSmoother
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.UserLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Distance to pin" semantics: what the ViewModel computes per location fix
 * via [distanceMeters] between the smoothed fix and the selected MusicPin.
 */
class DistanceToPinTest {

    private val userTruth = GeoPoint(latitude = 55.75222, longitude = 37.61556)

    /** The fountain pin ~330 m north-east of the test user position. */
    private val pin = MusicPin(
        id = "pin-1",
        position = offset(userTruth, dx = 233.0, dy = 233.0),
        title = "Пин 1",
        audioAssetUri = "",
        gain = 0.7,
        createdAt = 0L,
    )

    private fun offset(
        point: GeoPoint,
        dx: Double,
        dy: Double,
    ): GeoPoint {
        val northMetersPerDegLat = 111_195.0
        val eastMetersPerDegLon = northMetersPerDegLat * cos(point.latitude * Math.PI / 180.0)
        return GeoPoint(
            latitude = point.latitude + dy / northMetersPerDegLat,
            longitude = point.longitude + dx / eastMetersPerDegLon,
        )
    }

    private fun fixAt(position: GeoPoint, accuracy: Double, timestamp: Long = 0L) = UserLocation(
        position = position,
        headingDegrees = 0.0,
        accuracyMeters = accuracy,
        timestamp = timestamp,
    )

    @Test
    fun `known distance to the pin within a meter`() {
        val user = fixAt(userTruth, accuracy = 5.0)

        val distance = distanceMeters(user.position, pin.position)

        // The pin was placed at (233, 233) meters in the metric frame.
        assertTrue("expected ~330 m, got $distance", distance in 329.0..332.0)
    }

    @Test
    fun `distance to pin is symmetric`() {
        val user = fixAt(userTruth, accuracy = 5.0)

        assertEquals(
            distanceMeters(user.position, pin.position),
            distanceMeters(pin.position, user.position),
            1e-9,
        )
    }

    @Test
    fun `distance from the smoothed fix to the pin is closer than from the raw jittered one`() {
        val smoother = KalmanLocationSmoother()

        // Noisy startup ring around the truth before the estimate settles.
        val rawFirst = offset(userTruth, dx = 25.0, dy = -20.0, ) // jitter
        val raw = fixAt(rawFirst, accuracy = 40.0)
        var smoothed: UserLocation = smoother.smooth(raw)
        repeat(6) { i ->
            val jitter = offset(userTruth, dx = sin(PI / 2 * i) * 12.0, dy = cos(PI / 2 * i) * 12.0)
            smoothed = smoother.smooth(fixAt(jitter, accuracy = 12.0, timestamp = i.toLong()))
        }

        assertTrue(
            distanceMeters(smoothed.position, pin.position) <
                distanceMeters(raw.position, pin.position),
        )
    }

    @Test
    fun `approaching a pin shrinks the reported distance`() {
        var moving = offset(userTruth, dx = 0.0, dy = -200.0)
        val distanceStart = distanceMeters(fixAt(moving, accuracy = 5.0).position, pin.position)
        moving = offset(userTruth, dx = 100.0, dy = 0.0)
        val distanceMid = distanceMeters(fixAt(moving, accuracy = 5.0).position, pin.position)
        moving = pin.position
        val distanceEnd = distanceMeters(fixAt(moving, accuracy = 5.0).position, pin.position)

        assertTrue(distanceMid < distanceStart)
        assertTrue(distanceEnd < distanceMid)
        assertEquals(0.0, distanceEnd, 1e-6)
    }
}
