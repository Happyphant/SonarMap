package com.sonarmap.app.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonarmap.app.data.AudioSourceSelector
import com.sonarmap.app.domain.geo.totalLengthMeters
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteDraft
import com.sonarmap.app.domain.repository.MusicPinRepository
import com.sonarmap.app.domain.repository.RouteRepository
import com.sonarmap.app.map.RouteLineUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UX phases of the route feature on the map — the four states the product
 * spec calls out. Mapped from repository state (single source of truth),
 * never stored locally:
 * - [RoutePhase.Idle] nothing drawn, nothing active: entry point for a new route.
 * - [RoutePhase.Drawing] a draft is open: taps add vertices, Завершить saves (>= 2 points).
 * - [RoutePhase.Completed] the finished-route editor sheet is open (rename /
 *   audio / activate).
 * - [RoutePhase.Active] a saved route is selected: highlighted polyline + chip.
 */
enum class RoutePhase { Idle, Drawing, Completed, Active }

/**
 * Single source of truth for the walking-audio sources: routes (drawing,
 * editing, selection) AND pins (plain list data for the shared menu screen).
 * Pin EDITING selection lives with [com.sonarmap.app.ui.map.MapViewModel];
 * only activation matters here.
 */
data class RouteScreenState(
    val routes: List<Route> = emptyList(),
    val activeRouteId: String? = null,
    /** Open drawing draft; null = not drawing. */
    val draft: RouteDraft? = null,
    /** Route shown in the editor sheet (just finished, or opened from the list). */
    val editedRouteId: String? = null,
    val pins: List<MusicPin> = emptyList(),
    val activePinId: String? = null,
) {
    /** The route open in the editor sheet; null when closed or deleted. */
    val editedRoute: Route?
        get() = routes.firstOrNull { it.id == editedRouteId }

    /** Walk length of the route open in the editor; null when none. */
    val editedRouteLengthMeters: Double?
        get() = editedRoute?.totalLengthMeters()

    fun phase(): RoutePhase = when {
        draft != null -> RoutePhase.Drawing
        editedRoute != null -> RoutePhase.Completed
        activeRouteId != null -> RoutePhase.Active
        else -> RoutePhase.Idle
    }

    /** The polyline set for the map layer: draft first, saved routes after. */
    fun routeLines(): List<RouteLineUi> = buildList {
        draft?.let {
            add(
                RouteLineUi(
                    id = "draft",
                    points = it.points,
                    isDraft = true,
                    isActive = false,
                ),
            )
        }
        routes.forEach { route ->
            add(
                RouteLineUi(
                    id = route.id,
                    points = route.points,
                    isDraft = false,
                    isActive = route.id == activeRouteId,
                ),
            )
        }
    }

    fun activeRoute(): Route? = routes.firstOrNull { it.id == activeRouteId }

    fun activePin(): MusicPin? = pins.firstOrNull { it.id == activePinId }
}

/**
 * Keeps route business logic out of composables, and keeps the route/pin
 * activation rules in [AudioSourceSelector]. The ViewModel is created once
 * at the root scope and shared by the map screen and the routes-list
 * screen, so the editor-open state is one shared truth.
 */
class RouteViewModel(
    private val routeRepository: RouteRepository,
    private val pinRepository: MusicPinRepository,
    private val sourceSelector: AudioSourceSelector,
) : ViewModel() {

    /** Which route (if any) is open in the editor sheet. */
    private val editedRouteId = MutableStateFlow<String?>(null)

    /** Typed intermediate over the four route flows. */
    private val routeUnits = combine(
        routeRepository.routes,
        routeRepository.activeRouteId,
        routeRepository.draft,
        editedRouteId,
    ) { routes, activeId, draft, edited ->
        RouteScreenState(
            routes = routes,
            activeRouteId = activeId,
            draft = draft,
            editedRouteId = edited,
        )
    }

    val uiState: StateFlow<RouteScreenState> = combine(
        routeUnits,
        pinRepository.pins,
        pinRepository.activePinId,
    ) { units, pins, activePinId ->
        units.copy(pins = pins, activePinId = activePinId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RouteScreenState(),
    )

    /** Starts the drawing draft ("Маршрут N" auto-title); no-op if open. */
    fun startDraft() {
        viewModelScope.launch { routeRepository.startDraft(defaultTitle = "") }
    }

    /** Tap on the map mid-drawing adds a vertex; ignored in every other phase. */
    fun onMapClick(position: GeoPoint) {
        if (uiState.value.draft == null) return
        viewModelScope.launch { routeRepository.appendDraftPoint(position) }
    }

    fun cancelDraft() {
        viewModelScope.launch { routeRepository.cancelDraft() }
    }

    /** Saves the draft (>= 2 points) and opens the finished-route editor. */
    fun finishDraft() {
        viewModelScope.launch {
            val route = routeRepository.finishDraft() ?: return@launch
            editedRouteId.value = route.id
        }
    }

    /**
     * Opens the editor for a saved route. Unknown ids (deleted routes, the
     * "draft" pseudo-id tapped on the map) are ignored — nothing opens.
     */
    fun openEditor(routeId: String) {
        if (uiState.value.routes.none { it.id == routeId }) return
        editedRouteId.value = routeId
    }

    fun closeEditor() {
        editedRouteId.value = null
    }

    /** Blank titles are ignored by the repository (previous title stays). */
    fun renameRoute(title: String) {
        val id = editedRouteId.value ?: return
        viewModelScope.launch { routeRepository.renameRoute(id, title) }
    }

    fun selectAudioFile(uri: String) {
        val id = editedRouteId.value ?: return
        viewModelScope.launch { routeRepository.updateAudioAssetUri(id, uri) }
    }

    /** Resizes the edited route's hearing-area corridor (editor-sheet slider). */
    fun setRouteAudibleRadius(radiusMeters: Double) {
        val id = editedRouteId.value ?: return
        viewModelScope.launch { routeRepository.updateAudibleRadius(id, radiusMeters) }
    }

    fun deleteRoute(routeId: String) {
        if (editedRouteId.value == routeId) editedRouteId.value = null
        viewModelScope.launch { routeRepository.removeRoute(routeId) }
    }

    /**
     * Route activation: deactivates all other routes AND the active pin
     * (single walking-audio source rule). null clears the selection.
     */
    fun setActiveRoute(routeId: String?) {
        viewModelScope.launch { sourceSelector.selectRoute(routeId) }
    }
}
