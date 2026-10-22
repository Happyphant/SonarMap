package com.sonarmap.app.data

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteDraft
import com.sonarmap.app.domain.repository.RouteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Thread-safe in-memory store for hand-drawn routes. Same pattern as
 * [InMemoryMusicPinRepository]: pure Kotlin/JVM, unit-testable without an
 * Android runtime; Room later replaces this class behind the interface.
 */
class InMemoryRouteRepository : RouteRepository {

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    override val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _activeRouteId = MutableStateFlow<String?>(null)
    override val activeRouteId: StateFlow<String?> = _activeRouteId.asStateFlow()

    private val _draft = MutableStateFlow<RouteDraft?>(null)
    override val draft: StateFlow<RouteDraft?> = _draft.asStateFlow()

    private val mutex = Mutex()

    override suspend fun startDraft(defaultTitle: String): Unit = mutex.withLock {
        if (_draft.value != null) return // drawing is in progress — keep it
        _draft.value = RouteDraft(
            points = emptyList(),
            title = defaultTitle.ifBlank { "Маршрут ${_routes.value.size + 1}" },
        )
    }

    override suspend fun appendDraftPoint(position: GeoPoint): Unit = mutex.withLock {
        val current = _draft.value ?: return
        _draft.value = current.copy(points = current.points + position)
    }

    override suspend fun cancelDraft(): Unit = mutex.withLock {
        _draft.value = null
    }

    override suspend fun finishDraft(): Route? = mutex.withLock {
        val draft = _draft.value
        if (draft == null || draft.points.size < 2) return null
        val route = Route(
            id = UUID.randomUUID().toString(),
            title = draft.title,
            points = draft.points,
            audioAssetUri = "", // attached later from the editor sheet
            createdAt = System.currentTimeMillis(),
        )
        _routes.value += route
        _draft.value = null
        route
    }

    override suspend fun renameRoute(id: String, title: String): Unit = mutex.withLock {
        if (title.isBlank()) return
        replaceWhere({ it.id == id }) { it.copy(title = title) }
    }

    override suspend fun updateAudibleRadius(id: String, radiusMeters: Double): Unit = mutex.withLock {
        replaceWhere({ it.id == id }) {
            it.copy(
                audibleRadiusMeters = radiusMeters.coerceIn(
                    MusicPin.MIN_AUDIBLE_RADIUS_METERS,
                    MusicPin.MAX_AUDIBLE_RADIUS_METERS,
                ),
            )
        }
    }

    override suspend fun updateAudioAssetUri(id: String, audioAssetUri: String): Unit = mutex.withLock {
        replaceWhere({ it.id == id }) { it.copy(audioAssetUri = audioAssetUri) }
    }

    override suspend fun removeRoute(id: String): Unit = mutex.withLock {
        _routes.value = _routes.value.filterNot { it.id == id }
        if (_activeRouteId.value == id) _activeRouteId.value = null
    }

    override suspend fun setActiveRoute(id: String?): Unit = mutex.withLock {
        if (id != null && _routes.value.none { it.id == id }) {
            throw IllegalArgumentException("unknown route id: $id")
        }
        _activeRouteId.value = id
    }

    private inline fun replaceWhere(
        predicate: (Route) -> Boolean,
        transform: (Route) -> Route,
    ) {
        _routes.value = _routes.value.map { if (predicate(it)) transform(it) else it }
    }
}

/**
 * Process-wide singleton so routes survive ViewModel recreation and back-stack
 * changes; process death still clears them until persistence (Room) is added.
 */
object RouteRepositoryProvider {
    val repository: RouteRepository = InMemoryRouteRepository()
}
