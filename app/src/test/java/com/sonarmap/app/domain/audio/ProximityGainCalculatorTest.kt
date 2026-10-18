package com.sonarmap.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of the proximity gain curve (defaults: 150 m, x1.15, alpha .35). */
class ProximityGainCalculatorTest {

    private val params = ProximityGainParams(
        maxDistanceMeters = 150.0,
        hysteresisFactor = 1.15,
        smoothingFactor = 0.35,
    )

    private fun calc() = ProximityGainCalculator(params)

    /**
     * Feeds the same tick many times: the EMA (factor 0.35) converges as
     * 0.65^n, so 80 ticks put the residual below 1e-14 — tight assertions
     * become possible without a separate settle-free code path.
     */
    private fun settle(distance: Double?, pinGain: Double = 1.0): Double {
        val c = calc()
        var out = 0.0
        repeat(80) { out = c.update(distance, pinGain) }
        return out
    }

    @Test
    fun `no fix ever means full manual preview loudness`() {
        // Converges to lastProximity (1.0) * slider, not to zero.
        assertEquals(0.8, settle(distance = null, pinGain = 0.8), 1e-9)
    }

    @Test
    fun `distance zero means the full slider value`() {
        assertEquals(1.0, settle(distance = 0.0, pinGain = 1.0), 1e-9)
        assertEquals(0.7, settle(distance = 0.0, pinGain = 0.7), 1e-9)
    }

    @Test
    fun `mid-range distance halves the gain`() {
        assertEquals(0.5, settle(distance = 75.0, pinGain = 1.0), 1e-9)
    }

    @Test
    fun `gain reaches zero at maxDistance`() {
        assertEquals(0.0, settle(distance = 150.0, pinGain = 1.0), 1e-9)
    }

    @Test
    fun `gain stays zero far beyond maxDistance`() {
        assertEquals(0.0, settle(distance = 900.0), 1e-9)
    }

    @Test
    fun `smoothing makes a sudden jump approach gradually`() {
        val c = calc()
        val first = c.update(0.0, 1.0) // target 1.0, but only a partial step
        assertTrue("partial step expected, was $first", first in 0.0..0.5)
        repeat(80) { c.update(0.0, 1.0) }
        assertEquals(1.0, c.update(0.0, 1.0), 1e-9)
    }

    @Test
    fun `dead zone keeps the source muted until back inside maxDistance`() {
        val c = calc()
        repeat(10) { c.update(100.0, 1.0) } // active, audible (~0.98)

        // Walk out past the off-threshold (150 * 1.15 = 172.5 m): muted.
        repeat(30) { c.update(180.0, 1.0) }
        assertTrue("must be muted far out", c.update(180.0, 1.0) < 1e-3)

        // Back into the dead zone (150..172.5): stays off (with the linear
        // falloff it is trivially silent here; the flag matters for the
        // reactivation edge below).
        assertTrue("dead zone must stay off", c.update(160.0, 1.0) < 1e-3)

        // Inside maxDistance again: audible (smoothing climbs from ~0).
        val reactivated = c.update(140.0, 1.0)
        assertTrue("must reactivate, was $reactivated", reactivated > 0.0)
    }

    @Test
    fun `jitter around the boundary never pops the source back on`() {
        val c = calc()
        repeat(30) { c.update(149.0, 1.0) } // active, quiet (~0.0067)
        val quietLevel = c.update(149.0, 1.0)

        // Oscillate inside the dead zone band (150..172.5): the output must
        // never rise above the quiet level — no pops either way.
        var prev = quietLevel
        var monotonic = true
        repeat(20) {
            val d = if (it % 2 == 0) 171.0 else 155.0
            val out = c.update(d, 1.0)
            if (out > prev + 1e-12) monotonic = false
            prev = out
        }
        assertTrue("flicker: output rose in the dead zone", monotonic)
        assertTrue("pop above quiet level: $prev", prev < quietLevel)
    }

    @Test
    fun `gps gap holds the last proximity instead of pumping`() {
        val c = calc()
        repeat(80) { c.update(75.0, 1.0) } // settle ~0.5
        val before = c.update(75.0, 1.0)

        val afterGap = c.update(null, 1.0)
        assertEquals(before, afterGap, 0.02) // essentially held

        // The slider still has an effect during a GPS gap.
        repeat(80) { c.update(null, 0.2) }
        assertTrue(c.update(null, 0.2) < before)
    }

    @Test
    fun `reset starts from full preview state`() {
        val c = calc()
        repeat(80) { c.update(0.0, 1.0) } // settle at full

        c.reset()
        // Fresh state = "no fix seen": null ticks converge to full slider.
        var out = 0.0
        repeat(80) { out = c.update(null, 1.0) }
        assertEquals(1.0, out, 1e-9)
        // And a fresh fix far away starts muting from scratch.
        repeat(80) { out = c.update(300.0, 1.0) }
        assertEquals(0.0, out, 1e-9)
    }

    @Test
    fun `pinGain of zero silences everything`() {
        assertEquals(0.0, settle(distance = 0.0, pinGain = 0.0), 1e-12)
    }

    @Test
    fun `over-unity and negative pinGain are clamped`() {
        assertEquals(1.0, settle(distance = 0.0, pinGain = 5.0), 1e-9)
        assertEquals(0.0, settle(distance = 0.0, pinGain = -2.0), 1e-12)
    }

    @Test
    fun `negative distance is treated as zero`() {
        assertEquals(1.0, settle(distance = -7.0, pinGain = 1.0), 1e-9)
    }

    @Test
    fun `params reject absurd configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(maxDistanceMeters = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(hysteresisFactor = 0.9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(smoothingFactor = 1.5)
        }
    }
}
