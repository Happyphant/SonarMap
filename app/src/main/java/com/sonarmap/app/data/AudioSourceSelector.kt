package com.sonarmap.app.data

import com.sonarmap.app.domain.repository.MusicPinRepository
import com.sonarmap.app.domain.repository.RouteRepository

/**
 * The single owner of the "one active walking-audio source" rule:
 * activating a pin deactivates every route (and vice versa), because one
 * listener with one engine stream follows one source. Plain deactivation
 * (id = null) touches only its own side — nothing becomes "less active".
 *
 * Each repository remains authoritative for its own state; this class only
 * keeps the two state machines mutually consistent.
 */
class AudioSourceSelector(
    private val pins: MusicPinRepository,
    private val routes: RouteRepository,
) {

    /** Selects the audible pin; a non-null id clears the active route. */
    suspend fun selectPin(id: String?) {
        pins.setActivePin(id)
        if (id != null) routes.setActiveRoute(null)
    }

    /** Selects the audible route; a non-null id clears the active pin. */
    suspend fun selectRoute(id: String?) {
        routes.setActiveRoute(id)
        if (id != null) pins.setActivePin(null)
    }
}

/**
 * Process-wide singleton alongside the two repositories — the selection
 * rules must be identical no matter which screen's ViewModel calls in.
 */
object AudioSourceSelectorProvider {
    val selector: AudioSourceSelector = AudioSourceSelector(
        pins = MusicPinRepositoryProvider.repository,
        routes = RouteRepositoryProvider.repository,
    )
}
