package com.sonarmap.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainTest {

    @Test
    fun `factor zero keeps previous gain`() {
        assertEquals(0.72, smoothGain(previous = 0.72, target = 0.0, factor = 0.0), 1e-12)
    }

    @Test
    fun `factor one jumps to target`() {
        assertEquals(0.5, smoothGain(previous = 0.0, target = 0.5, factor = 1.0), 1e-12)
    }

    @Test
    fun `factor half moves halfway`() {
        assertEquals(-10.0, smoothGain(previous = -20.0, target = 0.0, factor = 0.5), 1e-12)
    }

    @Test
    fun `factor quarter moves quarter of the way`() {
        assertEquals(0.25, smoothGain(previous = 0.0, target = 1.0, factor = 0.25), 1e-12)
    }

    @Test
    fun `oversized factor is clamped to one`() {
        assertEquals(0.9, smoothGain(previous = 0.1, target = 0.9, factor = 2.0), 1e-12)
    }

    @Test
    fun `negative factor is clamped to zero`() {
        assertEquals(0.3, smoothGain(previous = 0.3, target = 0.9, factor = -1.0), 1e-12)
    }

    @Test
    fun `result always stays between previous and target`() {
        val pairs = listOf(0.0 to 1.0, 1.0 to 0.2, -5.0 to 5.0, 0.8 to 0.8)
        val factors = listOf(0.0, 0.13, 0.5, 0.87, 1.0)
        // Floating-point: exact lerps like 1.0 + (0.2 - 1.0) * 1.0 can miss the
        // bound by ulps, so compare with a relative epsilon.
        val epsilon = 1e-9
        for ((previous, target) in pairs) {
            for (factor in factors) {
                val value = smoothGain(previous, target, factor)
                val lo = minOf(previous, target)
                val hi = maxOf(previous, target)
                assertTrue(
                    "value $value outside [$lo, $hi] for previous=$previous target=$target factor=$factor",
                    value in lo - epsilon..hi + epsilon,
                )
            }
        }
    }

    @Test
    fun `repeated smoothing converges to target without overshoot`() {
        var gain = 1.0
        val target = 0.0
        repeat(30) {
            val next = smoothGain(gain, target, factor = 0.5)
            assertTrue("overshoot: $next < $target", next >= target)
            assertTrue("non-monotonic: $next !< $gain", next < gain || gain == target)
            gain = next
        }
        assertTrue("did not converge, gain=$gain", gain < 0.001)
    }
}
