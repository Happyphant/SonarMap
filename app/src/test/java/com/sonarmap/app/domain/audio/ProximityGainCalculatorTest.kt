package com.sonarmap.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of the proximity gain curve (defaults: 150 m, edge 0.15, alpha .35). */
class ProximityGainCalculatorTest {

    private val params = ProximityGainParams(
        defaultMaxDistanceMeters = 150.0,
        edgeGain = 0.15,
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
    fun `the zone edge itself still plays at the floor`() {
        // d == R is inside (the painted boundary is inclusive).
        assertEquals(0.15, settle(distance = 150.0, userGain = 1.0), 1e-9)
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
    fun `walking out past the edge cuts the sound immediately`() {
        val c = calc()
        var out = 0.0
        repeat(80) { out = c.update(100.0, 1.0) } // settled audible ~0.433

        // One tick beyond the painted boundary (R = 150): EXACT zero, no
        // fade tail, no dead band up to R * 1.15.
        val cut = c.update(152.0, 1.0)
        assertEquals("must cut instantly, was $cut", 0.0, cut, 0.0)
        repeat(5) {
            assertEquals(0.0, c.update(152.0, 1.0), 0.0)
        }
    }

    @Test
    fun `first fix beyond the zone from the preview state cuts immediately`() {
        val c = calc()
        repeat(80) { c.update(null, 1.0) } // preview at full slider
        assertEquals(0.0, c.update(300.0, 1.0), 0.0)
    }

    @Test
    fun `entering the zone turns the sound on at the floor immediately`() {
        val c = calc()
        repeat(10) { c.update(180.0, 1.0) } // outside, silenced
        assertEquals(0.0, c.update(180.0, 1.0), 0.0)

        // One tick back inside at the boundary: audible at edgeGain right
        // away, not a smoothed ramp.
        val reactivated = c.update(150.0, 1.0)
        assertEquals("must come on instantly, was $reactivated", 0.15, reactivated, 1e-9)

        // Just inside the boundary the snapped value is the exact curve.
        assertEquals(0.0, c.update(152.0, 1.0), 0.0)
        val justInside = c.update(149.0, 1.0)
        assertEquals(0.15 + 0.85 * (1 - 149.0 / 150.0), justInside, 1e-9)
    }

    @Test
    fun `expanding the zone raises the volume, shrinking lowers it`() {
        val d = 100.0
        val narrow = settle(distance = d, userGain = 1.0, zoneRadius = 110.0)
        val wide = settle(distance = d, userGain = 1.0, zoneRadius = 200.0)
        // shaped = 0.15 + 0.85 * (1 - d/R): a wider R is always louder
        // for the same d. Between them a middle zone is strictly in the
        // middle, i.e. the response is monotonic in R.
        val mid = settle(distance = d, userGain = 1.0, zoneRadius = 150.0)
        assertTrue("narrow $narrow must be the quietest", narrow < mid)
        assertTrue("mid $mid must stay below wide $wide", mid < wide)
        // Spot-check the exact curve values while inside.
        assertEquals(0.15 + 0.85 * (1 - d / 110), narrow, 1e-9)
        assertEquals(0.15 + 0.85 * (1 - d / 200), wide, 1e-9)
    }

    @Test
    fun `shrinking the zone past the listener cuts immediately`() {
        val c = calc()
        var out = 0.0
        repeat(80) { out = c.update(100.0, 1.0, zoneRadiusMeters = 150.0) } // ~0.433 audible

        // The editor shrinks the zone below the listener's distance: that
        // is "outside the area" — an instant cut, not a fade.
        val cut = c.update(100.0, 1.0, zoneRadiusMeters = 95.0)
        assertEquals("shrink-past must cut instantly, was $cut", 0.0, cut, 0.0)
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
    fun `gps gap does not resurrect a source muted by leaving the zone`() {
        val c = calc()
        repeat(10) { c.update(180.0, 1.0) } // outside -> cut
        assertEquals(0.0, c.update(null, 1.0), 1e-9) // gap holds silence
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

        // A small zone cuts the sound at its own edge: 70 m is past 60 m.
        assertEquals(0.0, settle(distance = 70.0, userGain = 1.0, zoneRadius = 60.0), 1e-12)
    }

    @Test
    fun `params reject absurd configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(defaultMaxDistanceMeters = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(smoothingFactor = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProximityGainParams(edgeGain = 1.5)
        }
    }
}
