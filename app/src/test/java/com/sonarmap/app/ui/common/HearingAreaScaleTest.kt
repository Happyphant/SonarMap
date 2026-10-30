package com.sonarmap.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The non-uniform hearing-area scale: tick values 10..300 m at equally
 * spaced slider positions, linear interpolation between neighbors.
 */
class HearingAreaScaleTest {

    private val intervals = HearingAreaScale.TICK_METERS.size - 1 // 7

    @Test
    fun `ticks sit at equally spaced positions`() {
        HearingAreaScale.TICK_METERS.forEachIndexed { index, meters ->
            assertEquals(
                "tick at $meters m",
                index / intervals.toFloat(),
                HearingAreaScale.positionForMeters(meters.toDouble()),
                1e-6f,
            )
        }
    }

    @Test
    fun `ten meters is the start of the track`() {
        assertEquals(0f, HearingAreaScale.positionForMeters(10.0), 0f)
        assertEquals(0f, HearingAreaScale.positionForMeters(0.0), 0f)
        assertEquals(0f, HearingAreaScale.positionForMeters(-3.0), 0f)
    }

    @Test
    fun `three hundred meters is the end of the track`() {
        assertEquals(1f, HearingAreaScale.positionForMeters(300.0), 0f)
        assertEquals(1f, HearingAreaScale.positionForMeters(444.0), 0f)
        assertEquals(1f, HearingAreaScale.positionForMeters(5000.0), 0f)
    }

    @Test
    fun `the midpoint of a segment interpolates halfway in value and position`() {
        // Between the 20 and 30 ticks: 25 m <-> position (1 + 0.5) / 7.
        val position = HearingAreaScale.positionForMeters(25.0)
        assertEquals(1.5f / intervals, position, 1e-6f)
        assertEquals(25.0, HearingAreaScale.metersForPosition(position), 1e-9)
    }

    @Test
    fun `every position maps linearly inside its segment`() {
        // Position 0.5 is halfway along the 4th interval (100..150).
        val meters = HearingAreaScale.metersForPosition((4f + 0.5f) / intervals)
        assertEquals(125.0, meters, 1e-9)
    }

    @Test
    fun `mapping round-trips for arbitrary values`() {
        for (position in intArrayOf(0, 3, 11, 34, 71, 100)) {
            val p = position / 100f
            val meters = HearingAreaScale.metersForPosition(p)
            val back = HearingAreaScale.positionForMeters(meters)
            assertEquals("position $p round-trip", p, back, 1e-5f)
        }
    }

    @Test
    fun `the mapping is strictly monotonic`() {
        var previous = HearingAreaScale.metersForPosition(0f)
        for (step in 1..1000) {
            val current = HearingAreaScale.metersForPosition(step / 1000f)
            assertTrue("must increase at step $step", current > previous)
            previous = current
        }
        assertEquals(300.0, previous, 1e-9)
    }

    @Test
    fun `default 150 meter radius pins exactly on its tick`() {
        assertEquals(150.0, HearingAreaScale.metersForPosition(5f / intervals), 1e-9)
        assertEquals(5f / intervals, HearingAreaScale.positionForMeters(150.0), 1e-6f)
    }

    @Test
    fun `out of range positions are clamped`() {
        assertEquals(10.0, HearingAreaScale.metersForPosition(-0.5f), 1e-9)
        assertEquals(300.0, HearingAreaScale.metersForPosition(1.5f), 1e-9)
        assertEquals(300.0, HearingAreaScale.metersForPosition(1.0f), 1e-9)
    }
}
