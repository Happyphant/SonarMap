package com.sonarmap.app.data

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.repository.MusicPinRepository
import com.sonarmap.app.domain.repository.RouteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The one-active-walking-source rule across the two repositories: activating
 * one side clears the other, deactivating touches only its own side. The
 * test holds its own repository references — the selector's ctor params are
 * private by design; its API is just the two selection calls.
 */
class AudioSourceSelectorTest {

    /**
     * Creates a pin (auto-titled) and a two-point route, returning their ids
     * along with the repositories themselves for assertions.
     */
    private fun bind(): Triple<AudioSourceSelector, MusicPinRepository, RouteRepository> {
        val pins = InMemoryMusicPinRepository()
        val routes = InMemoryRouteRepository()
        val sel = AudioSourceSelector(pins = pins, routes = routes)
        return Triple(sel, pins, routes)
    }

    @Test
    fun `activating a pin clears the active route`() = runBlocking {
        val (sel, pins, routes) = bind()
        val pin = pins.addPin(GeoPoint(55.75222, 37.61556), defaultTitle = "")
        routes.startDraft(defaultTitle = "")
        routes.appendDraftPoint(GeoPoint(55.75222, 37.61556))
        routes.appendDraftPoint(GeoPoint(55.76293, 37.60868))
        val route = routes.finishDraft()!!
        sel.selectRoute(route.id)
        assertEquals(route.id, routes.activeRouteId.first())

        sel.selectPin(pin.id)

        assertEquals(pin.id, pins.activePinId.first())
        assertNull(routes.activeRouteId.first())
    }

    @Test
    fun `activating a route clears the active pin`() = runBlocking {
        val (sel, pins, routes) = bind()
        val pin = pins.addPin(GeoPoint(55.75222, 37.61556), defaultTitle = "")
        routes.startDraft(defaultTitle = "")
        routes.appendDraftPoint(GeoPoint(55.75222, 37.61556))
        routes.appendDraftPoint(GeoPoint(55.76293, 37.60868))
        val route = routes.finishDraft()!!
        sel.selectPin(pin.id)
        assertEquals(pin.id, pins.activePinId.first())

        sel.selectRoute(route.id)

        assertEquals(route.id, routes.activeRouteId.first())
        assertNull(pins.activePinId.first())
    }

    @Test
    fun `plain deactivation touches only its own side`() = runBlocking {
        val (sel, pins, routes) = bind()
        val pin = pins.addPin(GeoPoint(55.75222, 37.61556), defaultTitle = "")
        sel.selectPin(pin.id)

        // Deactivating the pin must stay a pin-only operation: no hidden
        // route gets cleared or (re)activated by a null selection.
        sel.selectPin(null)
        assertNull(pins.activePinId.first())
        assertNull(routes.activeRouteId.first())
    }

    @Test
    fun `unknown ids fail the same way as the plain repos`() {
        val (sel, _, _) = bind()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sel.selectPin("ghost") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sel.selectRoute("ghost") }
        }
    }
}
