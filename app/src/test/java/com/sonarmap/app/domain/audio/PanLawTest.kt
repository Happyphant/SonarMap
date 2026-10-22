package com.sonarmap.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Equal-power pan law tests: cardinals, constant total energy across the
 * sweep, monotonic channel movement, and clamping of out-of-range pans.
 */
class PanLawTest {

    private val eps = 1e-9

    @Test
    fun `pan 0 - center - plays both ears at 0 dot 707`() {
        val (l, r) = panGains(0.0)
        assertEquals(sqrt(0.5), l, eps)
        assertEquals(sqrt(0.5), r, eps)
    }

    @Test
    fun `pan minus 1 - full left - left ear full right ear silent`() {
        val (l, r) = panGains(-1.0)
        assertEquals(1.0, l, eps)
        assertEquals(0.0, r, eps)
    }

    @Test
    fun `pan plus 1 - full right - right ear full left ear silent`() {
        val (l, r) = panGains(1.0)
        assertEquals(0.0, l, eps)
        assertEquals(1.0, r, eps)
    }

    @Test
    fun `total energy stays constant across the whole pan sweep`() {
        // The core of the loudness-normalization fix: L^2 + R^2 = 1 for every
        // pan, so a centered source is never louder than a side-panned one.
        val steps = 41
        for (i in 0..steps) {
            val pan = -1.0 + 2.0 * i / steps
            val (l, r) = panGains(pan)
            assertEquals("energy at pan=$pan", 1.0, l * l + r * r, 1e-12)
        }
    }

    @Test
    fun `channels move monotonically and stay in bounds`() {
        val steps = 41
        var prevL = Double.POSITIVE_INFINITY
        var prevR = Double.NEGATIVE_INFINITY
        for (i in 0..steps) {
            val pan = -1.0 + 2.0 * i / steps
            val (l, r) = panGains(pan)
            assertTrue("L non-increasing at pan=$pan", l <= prevL + eps)
            assertTrue("R non-decreasing at pan=$pan", r >= prevR - eps)
            assertTrue("L in [0,1] at pan=$pan", l in 0.0..1.0)
            assertTrue("R in [0,1] at pan=$pan", r in 0.0..1.0)
            prevL = l
            prevR = r
        }
    }

    @Test
    fun `out of range pans are clamped to the cardinals`() {
        val (lHot, rHot) = panGains(2.0)
        assertEquals(0.0, lHot, eps)
        assertEquals(1.0, rHot, eps)
        val (lCold, rCold) = panGains(-5.0)
        assertEquals(1.0, lCold, eps)
        assertEquals(0.0, rCold, eps)
    }

    @Test
    fun `stereo image is symmetric around center`() {
        // Mirrored pans swap channels: loudness on either side feels alike.
        val steps = 21
        for (i in 0..steps) {
            val pan = -1.0 + 2.0 * i / steps
            val (l, r) = panGains(pan)
            val (ml, mr) = panGains(-pan)
            assertTrue(abs(l - mr) < eps)
            assertTrue(abs(r - ml) < eps)
        }
    }
}
