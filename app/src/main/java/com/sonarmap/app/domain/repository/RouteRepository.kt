package com.sonarmap.app.domain.repository

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteDraft
import kotlinx.coroutines.flow.StateFlow

/**
 * Storage boundary for hand-drawn routes. In-memory until Room arrives;
 * callers stay storage-agnostic. Routes are built manually (map taps) —
 * no routing API of any map provider is involved.
 */
interface RouteRepository {

    /** Saved routes, oldest first; hot stream, always up to date. */
    val routes: StateFlow<List<Route>>

    /** The route the walk-audio feature follows; null = nothing active. */
    val activeRouteId: StateFlow<String?>

    /** The route being drawn right now; null = not drawing. */
    val draft: StateFlow<RouteDraft?>

    /**
     * Opens a drawing draft titled [defaultTitle] (UI supplies "Маршрут N").
     * No-op when a draft is already open: drawing progresses, not resets.
     */
    suspend fun startDraft(defaultTitle: String)

    /** Appends a tapped vertex. No-op while no draft is open. */
    suspend fun appendDraftPoint(position: GeoPoint)

    /** Discards the draft. No-op when nothing was being drawn. */
    suspend fun cancelDraft()

    /**
     * Promotes the draft into a saved route and closes the draft.
     * @return the created route, or null when there is no draft or it has
     *   fewer than two points (must not clear the draft — keep drawing).
     */
    suspend fun finishDraft(): Route?

    /** Blank titles are ignored (the previous title stays). */
    suspend fun renameRoute(id: String, title: String)

    /** Empty string detaches the audio file. */
    suspend fun updateAudioAssetUri(id: String, audioAssetUri: String)

    /**
     * Removes the route. If it was the active one, the selection clears —
     * there is no "active route pointing at nothing" state.
     */
    suspend fun removeRoute(id: String)

    /**
     * Selects the active route; null deselects.
     * @throws IllegalArgumentException for a non-null id of an unknown route.
     */
    suspend fun setActiveRoute(id: String?)
}
