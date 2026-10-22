package com.sonarmap.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.absoluteValue

/**
 * Pan tests at the four cardinal relative angles. All cases settle the EMA
 * (smoothingFactor 0.3 -> residual 0.7^n) with repeated ticks so assertions
 * hit the final curve value, not a transient.
 */
class StereoPanCalculatorTest {

    private val params = StereoPanParams(
        deadZoneDegrees = 10.0,
        maxPanAtDegrees = 90.0,
        smoothingFactor = 0.3,
    )

    private fun settledPan(relativeDegrees: Double, heading: Double = 0.0): Double {
        val c = StereoPanCalculator(params)
        val bearing = (heading + relativeDegrees + 360.0) % 360.0
        var pan = 0.0
        repeat(60) { pan = c.update(bearing, heading) }
        return pan
    }

    @Test
    fun `relative 0 - source ahead - means center`() {
        assertEquals(0.0, settledPan(0.0), 1e-9)
    }

    @Test
    fun `relative 90 - source on the right - means full right`() {
        assertEquals(1.0, settledPan(90.0), 1e-9)
    }

    @Test
    fun `relative 180 - source behind - means center, no ear flip`() {
        // Behind-fade: the back signal is centered in both ears and the
        // crossing stays continuous — exactly ±180 lands on pan 0.
        assertEquals(0.0, settledPan(180.0), 1e-9)
        assertEquals(0.0, settledPan(-180.0), 1e-9)
    }

    @Test
    fun `relative 270 - source on the left - means full left`() {
        assertEquals(-1.0, settledPan(270.0), 1e-9)
    }

    @Test
    fun `pan fades linearly from the sides back to center behind the user`() {
        // Triangular curve: peak at 90°, linear falloff to 0 at 180°.
        // 110°: 1 - (110-90)/90 = 0.778.
        assertEquals(0.7777778, settledPan(110.0), 1e-6)
        // 135°: 1 - (135-90)/90 = 0.5.
        assertEquals(0.5, settledPan(135.0), 1e-9)
        // Mirror: -135° -> left half of the triangle.
        assertEquals(-0.5, settledPan(-135.0), 1e-9)
        // 160°: 1 - (160-90)/90 ≈ 0.222.
        assertEquals(0.2222222, settledPan(160.0), 1e-6)
    }

    @Test
    fun `near the back the pan stays small on both sides`() {
        // Continuity proof around the back crossing: both sides produce tiny
        // pans (no ear at full blast on either side), and the exact crossing
        // point is zero, so a smooth turn cannot audibly "switch" ears.
        // 172°: fade = 1 - (172-90)/90 = 0.0889.
        assertEquals(0.0888889, settledPan(172.0), 1e-6)
        assertEquals(-0.0888889, settledPan(-172.0), 1e-6)
        // 178°: fade = 1 - 88/90 = 0.0222.
        assertEquals(0.0222222, settledPan(178.0), 1e-6)
        assertEquals(-0.0222222, settledPan(-178.0), 1e-6)
    }

    @Test
    fun `dead zone silences small relative angles`() {
        assertEquals(0.0, settledPan(5.0), 1e-9)
        assertEquals(0.0, settledPan(-5.0), 1e-9)
        // Boundary itself (10°) is still inside the zone.
        assertEquals(0.0, settledPan(10.0), 1e-9)
    }

    @Test
    fun `pan is linear between dead zone and sides`() {
        // 50° relative: (50-10)/(90-10) = 0.5.
        assertEquals(0.5, settledPan(50.0), 1e-9)
        // 30° relative: (30-10)/80 = 0.25.
        assertEquals(0.25, settledPan(30.0), 1e-9)
        // Peak at the sides: 90° -> full +1.
        assertEquals(1.0, settledPan(90.0), 1e-9)
        // Past the sides the fade takes over: 100° -> 1 - 10/90 = 0.889.
        assertEquals(0.8888889, settledPan(100.0), 1e-6)
    }

    @Test
    fun `missing sensor degrades to mono`() {
        val c = StereoPanCalculator(params)
        var pan = 0.0
        repeat(10) { pan = c.update(bearingDegrees = 90.0, headingDegrees = null) }
        assertEquals(0.0, pan, 1e-12)
    }

    @Test
    fun `missing bearing degrades to mono`() {
        val c = StereoPanCalculator(params)
        var pan = 0.0
        repeat(10) { pan = c.update(bearingDegrees = null, headingDegrees = 45.0) }
        assertEquals(0.0, pan, 1e-12)
    }

    @Test
    fun `heading wrap-around is handled via shortest path`() {
        val c = StereoPanCalculator(params)
        // Source at bearing 10, heading 350 -> relative +20 (not -340).
        var pan = 0.0
        repeat(60) { pan = c.update(bearingDegrees = 10.0, headingDegrees = 350.0) }
        // (20-10)/80 = 0.125.
        assertEquals(0.125, pan, 1e-9)

        // Mirror: source at 350, heading 10 -> relative -20 -> left.
        c.reset()
        repeat(60) { pan = c.update(bearingDegrees = 350.0, headingDegrees = 10.0) }
        assertEquals(-0.125, pan, 1e-9)
    }

    @Test
    fun `smoothing glides instead of snapping`() {
        val c = StereoPanCalculator(params)
        val first = c.update(bearingDegrees = 90.0, headingDegrees = 0.0)
        assertTrue("partial step expected, was $first", first in 0.0..0.5)
        repeat(60) { c.update(bearingDegrees = 90.0, headingDegrees = 0.0) }
        assertEquals(1.0, c.update(bearingDegrees = 90.0, headingDegrees = 0.0), 1e-9)
    }

    @Test
    fun `heading jitter inside the dead zone never moves the pan`() {
        val c = StereoPanCalculator(params)
        var maxAbs = 0.0
        repeat(20) {
            // Source sits 5° off; heading wobbles ±5° — relative stays in [0, 10],
            // entirely inside the dead zone, so the pan must not move at all.
            val heading = if (it % 2 == 0) 5.0 else 355.0
            maxAbs = maxOf(maxAbs, c.update(bearingDegrees = 5.0, headingDegrees = heading).absoluteValue)
        }
        assertEquals(0.0, maxAbs, 1e-12)
    }

    @Test
    fun `absurd params are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StereoPanParams(deadZoneDegrees = 95.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StereoPanParams(maxPanAtDegrees = 5.0, deadZoneDegrees = 10.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StereoPanParams(maxPanAtDegrees = 200.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StereoPanParams(smoothingFactor = 2.0)
        }
    }
}
