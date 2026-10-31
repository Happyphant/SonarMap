package com.sonarmap.app.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fused-heading priority machine: GPS course while actually moving
 * (pocket-proof), the held-phone compass while standing with the screen
 * on, and null (pan -> center) otherwise — plus the start/stop hysteresis
 * that must not flip the source (and the pan it drives) on one slow fix.
 */
class HeadingFusionTest {

    /** Fully deterministic clock: each tick advances by exactly [stepMs]. */
    private class FakeClock {
        var now = 0L

        fun step(ms: Long) {
            now += ms
        }
    }

    /**
     * Fresh fusion ticking on the fake clock; the clock ticks are explicit
     * so linger windows are asserted exactly.
     */
    private fun fusionWith(clock: FakeClock) = HeadingFusion(
        walkSpeedThresholdMps = 1.0,
        courseLingerMillis = 3500,
        nowMillis = { clock.now },
    )

    @Test
    fun `walking fix with a bearing wins everywhere - pocket or hand`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        for (screenOn in listOf(false, true)) {
            val decision = fusion.update(
                speedMps = 1.3,
                bearingDegrees = 87.0,
                screenOn = screenOn,
                compassDegrees = null, // no compass data at all
            )
            assertEquals(87.0, decision.headingDegrees!!, 1e-9)
            assertEquals(HeadingSource.GPS_COURSE, decision.source)
            clock.step(2000)
        }
    }

    @Test
    fun `standstill with screen on degrades to the compass`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        val decision = fusion.update(
            speedMps = 0.1,
            bearingDegrees = null,
            screenOn = true,
            compassDegrees = 123.4,
        )
        assertEquals(123.4, decision.headingDegrees!!, 1e-9)
        assertEquals(HeadingSource.COMPASS, decision.source)
    }

    @Test
    fun `standstill with screen off is the center-degradation null`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        val decision = fusion.update(
            speedMps = 0.0,
            bearingDegrees = null,
            screenOn = false,
            compassDegrees = 123.4, // compass exists but is pocket garbage
        )
        assertNull(decision.headingDegrees)
        assertEquals(HeadingSource.NONE, decision.source)
    }

    @Test
    fun `screen off without any fix data stays centered too`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        val decision = fusion.update(
            speedMps = null,
            bearingDegrees = null,
            screenOn = false,
            compassDegrees = null,
        )
        assertNull(decision.headingDegrees)
        assertEquals(HeadingSource.NONE, decision.source)
    }

    @Test
    fun `screen on but no compass falls back to center - never to a guess`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        val decision = fusion.update(
            speedMps = 0.0,
            bearingDegrees = null,
            screenOn = true,
            compassDegrees = null,
        )
        assertNull(decision.headingDegrees)
        assertEquals(HeadingSource.NONE, decision.source)
    }

    @Test
    fun `course lingers past the threshold hysteresis window`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        // Walking east; course becomes the heading.
        fusion.update(1.4, 90.0, screenOn = false, compassDegrees = null)

        // 3.4 s later: one slow fix (below threshold) — the course must still
        // stand in, so a GPS-gap step does not flip the pan source.
        clock.step(3400)
        val lingering = fusion.update(0.4, null, screenOn = false, compassDegrees = null)
        assertEquals(90.0, lingering.headingDegrees!!, 1e-9)
        assertEquals(HeadingSource.GPS_COURSE, lingering.source)

        // Past the linger window there is no course left: centered.
        clock.step(500)
        val expired = fusion.update(0.4, null, screenOn = false, compassDegrees = null)
        assertNull(expired.headingDegrees)
        assertEquals(HeadingSource.NONE, expired.source)
    }

    @Test
    fun `a fresh walking fix refreshes the course in place`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        fusion.update(1.4, 90.0, screenOn = false, compassDegrees = null)
        clock.step(1000)
        val decision = fusion.update(1.5, 130.0, screenOn = false, compassDegrees = null)
        assertEquals(130.0, decision.headingDegrees!!, 1e-9)
        assertEquals(HeadingSource.GPS_COURSE, decision.source)
    }

    @Test
    fun `walking screen-on user takes the GPS course over the compass`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        val decision = fusion.update(
            speedMps = 2.0,
            bearingDegrees = 10.0,
            screenOn = true,
            compassDegrees = 300.0, // would produce a very different pan
        )
        assertEquals(10.0, decision.headingDegrees!!, 1e-9)
        assertEquals(HeadingSource.GPS_COURSE, decision.source)
    }

    @Test
    fun `a bare-bearing fix without walking speed is not a course`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        // Stationary devices may still report a bearing: with the phone in
        // the pocket that direction is noise, the screen-on compass wins.
        val decision = fusion.update(
            speedMps = 0.0,
            bearingDegrees = 200.0,
            screenOn = true,
            compassDegrees = 45.0,
        )
        assertEquals(45.0, decision.headingDegrees!!, 1e-9)
        assertEquals(HeadingSource.COMPASS, decision.source)
    }

    @Test
    fun `linger never resurrects a long-expired course on later ticks`() {
        val clock = FakeClock()
        val fusion = fusionWith(clock)

        fusion.update(1.4, 90.0, screenOn = false, compassDegrees = null)
        clock.step(60_000) // far past the linger window
        val expired = fusion.update(0.0, null, screenOn = false, compassDegrees = null)
        assertNull(expired.headingDegrees)
        assertEquals(HeadingSource.NONE, expired.source)
    }
}
