package com.sonarmap.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of the proximity gain curve (defaults: 150 m, edge 0.15, x1.15, alpha .35). */
class ProximityGainCalculatorTest {

    private val params = ProximityGainParams(
        defaultMaxDistanceMeters = 150.0,
        edgeGain = 0.15,
        hysteresisFactor = 1.15,
        smoothingFactor = 0.35,
    )

    private fun calc() = ProximityGainCalculator(params)

    /**
     * Feeds the same tick many times: the EMA (factor 0.35) converges as
     * 0.65^n, so 80 ticks put the residual below 1e-14 — tight assertions
     * become possible without a separate settle-free code path.
     */
    private fun settle(
        distance: Double?,
        userGain: Double = 1.0,
        zoneRadius: Double = params.defaultMaxDistanceMeters,
    ): Double {
        val c = calc()
        var out = 0.0
        repeat(80) { out = c.update(distance, userGain, zoneRadius) }
        return out
    }

    @Test
    fun `no fix ever means full manual preview loudness`() {
        // Converges to lastProximity (1.0) * slider, not to zero.
        assertEquals(0.8, settle(distance = null, userGain = 0.8), 1e-9)
    }

    @Test
    fun `distance zero means the full slider value`() {
        assertEquals(1.0, settle(distance = 0.0, userGain = 1.0), 1e-9)
        assertEquals(0.7, settle(distance = 0.0, userGain = 0.7), 1e-9)
    }

    @Test
    fun `mid-range distance lands mid-curve above the edge floor`() {
        // shaped = edgeGain + (1 - edgeGain) * (1 - 75/150) = 0.15 + 0.85 * 0.5
        assertEquals(0.575, settle(distance = 75.0, userGain = 1.0), 1e-9)
    }

    @Test
    fun `at the zone edge the gain floors at edgeGain`() {
        // Entering the hearing area turns the sound ON at the minimal level;
        // it never starts at zero inside the zone.
        assertEquals(0.15, settle(distance = 149.9, userGain = 1.0), 1e-3)
    }

    @Test
    fun `gain stays zero far beyond the zone`() {
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
    fun `dead zone keeps the source muted until back inside the zone`() {
        val c = calc()
        repeat(10) { c.update(100.0, 1.0) } // active, audible (~0.43)

        // Walk out past the off-threshold (150 * 1.15 = 172.5 m): muted.
        repeat(30) { c.update(180.0, 1.0) }
        assertTrue("must be muted far out", c.update(180.0, 1.0) < 1e-3)

        // Back into the hysteresis band (150..172.5): stays off.
        assertTrue("dead zone must stay off", c.update(160.0, 1.0) < 1e-3)

        // Inside the zone again: audible (smoothing climbs from ~0).
        val reactivated = c.update(140.0, 1.0)
        assertTrue("must reactivate, was $reactivated", reactivated > 0.0)
    }

    @Test
    fun `jitter in the hysteresis band never pops the muted source back on`() {
        val c = calc()
        repeat(30) { c.update(180.0, 1.0) } // muted far out

        // Oscillate inside the dead zone band (150..172.5): the muted state
        // must hold until the user is truly back inside the zone.
        repeat(20) {
            val out = c.update(if (it % 2 == 0) 171.0 else 155.0, 1.0)
            assertTrue("flicker: muted source popped on at ${out}", out < 1e-3)
        }
    }

    @Test
    fun `jitter just outside the zone keeps playing at the floor`() {
        val c = calc()
        repeat(30) { c.update(149.5, 1.0) } // audible near the edge (~0.153)

        // Steps just outside the visible boundary stay above zero until the
        // outer threshold; no drop-to-silence flicker at the edge.
        repeat(20) {
            val out = c.update(if (it % 2 == 0) 155.0 else 171.0, 1.0)
            assertTrue("pop to silence at the edge: $out", out > 0.1)
        }
    }

    @Test
    fun `gps gap holds the last proximity instead of pumping`() {
        val c = calc()
        repeat(80) { c.update(75.0, 1.0) } // settle ~0.575
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
    fun `userGain of zero silences everything`() {
        assertEquals(0.0, settle(distance = 0.0, userGain = 0.0), 1e-12)
    }

    @Test
    fun `over-unity and negative userGain are clamped`() {
        assertEquals(1.0, settle(distance = 0.0, userGain = 5.0), 1e-9)
        assertEquals(0.0, settle(distance = 0.0, userGain = -2.0), 1e-12)
    }

    @Test
    fun `negative distance is treated as zero`() {
        assertEquals(1.0, settle(distance = -7.0, userGain = 1.0), 1e-9)
    }

    @Test
    fun `per-source zone radius changes the curve and the mute point`() {
        val c = calc()
        // d = 30 inside a 60 m zone: raw = 0.5 -> shaped = 0.575 (the same d
        // inside the default 150 m zone would give 0.83).
        assertEquals(0.575, settle(distance = 30.0, userGain = 1.0, zoneRadius = 60.0), 1e-9)

        // A tiny zone also silences sooner: 70 m is past 60 * 1.15.
        assertEquals(0.0, settle(distance = 70.0, userGain = 1.0, zoneRadius = 60.0), 1e-3)
    }

    @Test
    fun `params reject absurd configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(defaultMaxDistanceMeters = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(hysteresisFactor = 0.9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(smoothingFactor = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(edgeGain = 1.5)
        }
    }
}
