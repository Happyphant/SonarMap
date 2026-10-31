package com.sonarmap.app.audio

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared active-source audio builder used by BOTH playback owners
 * (the map screen and the foreground playback service): precedence
 * (route > pin > none) and the distance/bearing semantics must stay
 * identical whichever of the two runs the loop.
 */
class ActiveSourceAudioTest {

    private val pin = MusicPin(
        id = "pin1",
        position = GeoPoint(55.75222, 37.61556),
        title = "p",
        audioAssetUri = "",
        gain = 0.8,
        audibleRadiusMeters = 120.0,
        createdAt = 1L,
    )
    private val route = Route(
        id = "route1",
        title = "r",
        points = listOf(GeoPoint(55.75, 37.61), GeoPoint(55.76, 37.62)),
        audioAssetUri = "",
        createdAt = 1L,
    )

    @Test
    fun `nothing active yields null`() {
        assertNull(positionedSourceFor(fix = null, activeRoute = null, activePin = null))
    }

    @Test
    fun `active route beats the active pin`() {
        val source = positionedSourceFor(
            fix = GeoPoint(55.75222, 37.61556),
            activeRoute = route,
            activePin = pin,
        )!!
        assertEquals("route1", source.sourceId)
        assertEquals(1.0, source.userGain, 1e-9) // routes play at full gain
        assertEquals(150.0, source.audibleRadiusMeters, 1e-1) // route default
        // corridor fade is route-only
        assertEquals(PositionedSourceAudio.ROUTE_PAN_FADE_METERS, source.panFadeMeters!!, 1e-9)
    }

    @Test
    fun `pin without a fix keeps full slider gain preview`() {
        val source = positionedSourceFor(fix = null, activeRoute = null, activePin = pin)!!
        assertEquals("pin1", source.sourceId)
        assertNull(source.distanceMeters)
        assertNull(source.bearingDegrees)
        assertEquals(0.8, source.userGain, 1e-9)
        assertNull(source.panFadeMeters)
        assertEquals(120.0, source.audibleRadiusMeters, 1e-9)
    }

    @Test
    fun `pin with a fix computes distance and bearing`() {
        val source = positionedSourceFor(
            fix = GeoPoint(55.75222, 37.61556),
            activeRoute = null,
            activePin = pin,
        )!!
        assertEquals(0.0, source.distanceMeters!!, 1.0) // same point ± projection
        assertEquals(0.0, source.bearingDegrees!!, 1e-6)
    }
}
