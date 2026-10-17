package com.sonarmap.app.domain.location

import com.sonarmap.app.domain.geo.distanceMeters
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.UserLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure JVM tests for the smoothing layer (no Android classes involved). */
class KalmanLocationSmootherTest {

    private val truth = GeoPoint(latitude = 55.75222, longitude = 37.61556)

    private fun fixAt(accuracy: Double, timestamp: Long = 0L) = UserLocation(
        position = GeoPoint(truth.latitude, truth.longitude),
        headingDegrees = 0.0,
        accuracyMeters = accuracy,
        timestamp = timestamp,
    )

    /** A fix offset [dx, dy] meters from [truth]; dy is north, dx is east. */
    private fun offsetBy(dxMeters: Double, dyMeters: Double, accuracy: Double): UserLocation {
        val northMetersPerDegLat = 111_195.0
        val eastMetersPerDegLon = northMetersPerDegLat * cos(truth.latitude * Math.PI / 180.0)
        return UserLocation(
            position = GeoPoint(
                latitude = truth.latitude + dyMeters / northMetersPerDegLat,
                longitude = truth.longitude + dxMeters / eastMetersPerDegLon,
            ),
            headingDegrees = 0.0,
            accuracyMeters = accuracy,
            timestamp = 0L,
        )
    }

    @Test
    fun `first fix passes through exactly`() {
        val smoother = KalmanLocationSmoother()
        val raw = offsetBy(10.0, -5.0, accuracy = 12.0)

        assertEquals(raw, smoother.smooth(raw))
    }

    @Test
    fun `steady averaging collapses random jitter to well under its sigma`() {
        val smoother = KalmanLocationSmoother()
        // Pseudo-random (seeded, reproducible) GPS-style jitter: sigma 10 m,
        // device-reported accuracy also 10 m, 80 fixes of a standing user.
        val rng = java.util.Random(42L)
        val sigma = 10.0

        var last: UserLocation? = null
        val tail = ArrayList<UserLocation>(10)
        repeat(80) {
            last = smoother.smooth(
                offsetBy(rng.nextGaussian() * sigma, rng.nextGaussian() * sigma, accuracy = sigma),
            )
            if (last != null && it >= 70) tail.add(last!!)
        }

        val meanError = tail.map { distanceMeters(it.position, truth) }.average()
        // Radial mean of raw noise itself is ~sqrt(pi/2)*sigma ~= 12.5 m;
        // the filter must beat it by a solid margin, not reach zero (any
        // recursive filter keeps an equilibrium radius, see class KDoc).
        val rawRadialMean = sqrt(PI / 2.0) * sigma
        assertTrue(
            "filtered mean ${"%.2f".format(meanError)} m must be well under raw radial mean ${"%.2f".format(rawRadialMean)} m",
            meanError < rawRadialMean * 0.7,
        )
    }

    @Test
    fun `a precise fix snaps the estimate on top of the measurement`() {
        val smoother = KalmanLocationSmoother(processNoiseMeters = 3.0)
        smoother.smooth(offsetBy(50.0, 50.0, accuracy = 30.0)) // sloppy start
        smoother.smooth(offsetBy(30.0, 30.0, accuracy = 30.0))

        val precise = offsetBy(0.0, 0.0, accuracy = 0.001)
        val result = smoother.smooth(precise)

        val error = distanceMeters(result.position, precise.position)
        assertTrue("precise fix must win, error was $error m", error < 1.5)
    }

    @Test
    fun `low-accuracy jitter barely moves a steady estimate`() {
        val smoother = KalmanLocationSmoother(processNoiseMeters = 0.5)
        val base = smoother.smooth(offsetBy(0.0, 0.0, accuracy = 5.0))

        val maxDrift = (0 until 20).maxOf { i ->
            val angle = 2 * PI * i / 20.0
            val jittered = smoother.smooth(offsetBy(40 * cos(angle), 40 * sin(angle), accuracy = 60.0))
            distanceMeters(jittered.position, base.position)
        }

        assertTrue("40 m jitter must be almost ignored, drift was $maxDrift m", maxDrift < 10.0)
    }

    @Test
    fun `real movement is tracked, not averaged away`() {
        val smoother = KalmanLocationSmoother(processNoiseMeters = 8.0)
        smoother.smooth(offsetBy(0.0, 0.0, accuracy = 3.0))

        val target = offsetBy(60.0, 0.0, accuracy = 3.0) // user moved 60 m east
        var result: UserLocation = smoother.smooth(target)
        repeat(4) { result = smoother.smooth(target) }

        val error = distanceMeters(result.position, target.position)
        assertTrue("estimate must follow motion, tail error was $error m", error < 10.0)
    }

    @Test
    fun `filtered accuracy stays in the sane range`() {
        val smoother = KalmanLocationSmoother()
        var result = smoother.smooth(fixAt(accuracy = 8.0))
        repeat(5) { result = smoother.smooth(offsetBy(0.2, 0.2, accuracy = 8.0)) }

        assertTrue(result.accuracyMeters >= 0.0)
        assertTrue("filter must not claim magic precision", result.accuracyMeters <= 8.0)
    }

    @Test
    fun `variance converges monotonically on a stable point`() {
        val smoother = KalmanLocationSmoother()
        val accuracies = mutableListOf<Double>()

        repeat(8) {
            accuracies.add(smoother.smooth(fixAt(accuracy = 10.0, timestamp = it.toLong())).accuracyMeters)
        }

        // Each next reported accuracy is no worse than the previous.
        assertTrue(accuracies.zipWithNext().all { (a, b) -> b <= a + 1e-9 })
        assertTrue(accuracies.last() > 0.0)
    }

    @Test
    fun `spread of outputs is smaller than spread of inputs`() {
        val smoother = KalmanLocationSmoother()
        val inputs = (0 until 16).map { i ->
            val angle = 2 * PI * i / 16.0
            offsetBy(10 * cos(angle), 10 * sin(angle), accuracy = 12.0)
        }

        val inputRms = sqrt(inputs.map { distanceMeters(it.position, truth).let { d -> d * d } }.average())
        var last: UserLocation? = null
        inputs.forEach { last = smoother.smooth(it) }
        val outputRms = distanceMeters(last!!.position, truth)

        assertTrue("output rms $outputRms not below input rms $inputRms", outputRms < inputRms)
    }
}
