package com.sonarmap.app.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonarmap.app.domain.geo.totalLengthMeters
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteDraft
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

/** Single source of truth for route UI on the map and in the routes list. */
data class RouteScreenState(
    val routes: List<Route> = emptyList(),
    val activeRouteId: String? = null,
    /** Open drawing draft; null = not drawing. */
    val draft: RouteDraft? = null,
    /** Route shown in the editor sheet (just finished, or opened from the list). */
    val editedRouteId: String? = null,
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
}

/**
 * Keeps route business logic out of composables. All shared state lives in
 * the singleton [RouteRepository], so the map screen and the routes-list
 * screen can run separate instances of this ViewModel and stay in sync.
 */
class RouteViewModel(
    private val repository: RouteRepository,
) : ViewModel() {

    /** Which route (if any) is open in the editor sheet. */
    private val editedRouteId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RouteScreenState> = combine(
        repository.routes,
        repository.activeRouteId,
        repository.draft,
        editedRouteId,
    ) { routes, activeId, draft, edited ->
        RouteScreenState(
            routes = routes,
            activeRouteId = activeId,
            draft = draft,
            editedRouteId = edited,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RouteScreenState(),
    )

    /** Starts the drawing draft ("Маршрут N" auto-title); no-op if open. */
    fun startDraft() {
        viewModelScope.launch { repository.startDraft(defaultTitle = "") }
    }

    /** Tap on the map mid-drawing adds a vertex; ignored in every other phase. */
    fun onMapClick(position: GeoPoint) {
        if (uiState.value.draft == null) return
        viewModelScope.launch { repository.appendDraftPoint(position) }
    }

    fun cancelDraft() {
        viewModelScope.launch { repository.cancelDraft() }
    }

    /** Saves the draft (>= 2 points) and opens the finished-route editor. */
    fun finishDraft() {
        viewModelScope.launch {
            val route = repository.finishDraft() ?: return@launch
            editedRouteId.value = route.id
        }
    }

    fun openEditor(routeId: String) {
        editedRouteId.value = routeId
    }

    fun closeEditor() {
        editedRouteId.value = null
    }

    /** Blank titles are ignored by the repository (previous title stays). */
    fun renameRoute(title: String) {
        val id = editedRouteId.value ?: return
        viewModelScope.launch { repository.renameRoute(id, title) }
    }

    fun selectAudioFile(uri: String) {
        val id = editedRouteId.value ?: return
        viewModelScope.launch { repository.updateAudioAssetUri(id, uri) }
    }

    fun deleteRoute(routeId: String) {
        if (editedRouteId.value == routeId) editedRouteId.value = null
        viewModelScope.launch { repository.removeRoute(routeId) }
    }

    /** Selects the active route; null clears the selection. */
    fun setActiveRoute(routeId: String?) {
        viewModelScope.launch { repository.setActiveRoute(routeId) }
    }
}
