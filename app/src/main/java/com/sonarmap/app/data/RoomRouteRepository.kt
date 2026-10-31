package com.sonarmap.app.data

import android.content.Context
import androidx.room.Room
import com.sonarmap.app.data.db.RoutePointEntity
import com.sonarmap.app.data.db.SonarMapDatabase
import com.sonarmap.app.data.db.pointEntities
import com.sonarmap.app.data.db.toDomain
import com.sonarmap.app.data.db.toEntity
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.model.RouteDraft
import com.sonarmap.app.domain.repository.RouteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * Room-backed [RouteRepository] — replaces the in-memory store behind the
 * SAME interface and call contracts. Header rows (routes) and polyline
 * vertices (route_points) are normalized; the domain [Route] is
 * reassembled from both.
 *
 * The drawing draft stays IN MEMORY on purpose: an unfinished draft is a
 * UI-session artifact — committing half-drawn routes to disk (crash
 * mid-drawing, app killed) would resurrect invisible garbage routes.
 */
class RoomRouteRepository(
    database: SonarMapDatabase,
    eagerScope: CoroutineScope,
) : RouteRepository {

    private val dao = database.routeDao()

    private val routeRows: StateFlow<List<com.sonarmap.app.data.db.RouteEntity>> =
        dao.observeRoutes()
            .stateIn(eagerScope, SharingStarted.Eagerly, emptyList())

    private val pointRows: StateFlow<Map<String, List<RoutePointEntity>>> =
        dao.observePoints()
            .map { points -> points.groupBy { it.routeId } }
            .stateIn(eagerScope, SharingStarted.Eagerly, emptyMap())

    override val routes: StateFlow<List<Route>> =
        combine(routeRows, pointRows) { rows, points ->
            rows.mapNotNull { row ->
                // A route without (>= 2) vertices is not a valid domain
                // object; dropping it from the stream is the defensive read.
                row.toDomain(points[row.id] ?: emptyList())
            }
        }.stateIn(eagerScope, SharingStarted.Eagerly, emptyList())

    private val _activeRouteId = MutableStateFlow<String?>(null)
    override val activeRouteId: StateFlow<String?> = _activeRouteId.asStateFlow()

    private val _draft = MutableStateFlow<RouteDraft?>(null)
    override val draft: StateFlow<RouteDraft?> = _draft.asStateFlow()

    override suspend fun startDraft(defaultTitle: String) {
        if (_draft.value != null) return // drawing is in progress — keep it
        _draft.value = RouteDraft(
            points = emptyList(),
            title = defaultTitle.ifBlank { "Маршрут ${(dao.count() + 1)}" },
        )
    }

    override suspend fun appendDraftPoint(position: GeoPoint) {
        val current = _draft.value ?: return
        _draft.value = current.copy(points = current.points + position)
    }

    override suspend fun cancelDraft() {
        _draft.value = null
    }

    override suspend fun finishDraft(): Route? {
        val draft = _draft.value
        if (draft == null || draft.points.size < 2) return null
        val route = Route(
            id = UUID.randomUUID().toString(),
            title = draft.title,
            points = draft.points,
            audioAssetUri = "", // attached later from the editor sheet
            createdAt = System.currentTimeMillis(),
        )
        dao.insertRouteWithPoints(route.toEntity(), pointEntities(route.id, route.points))
        _draft.value = null
        return route
    }

    override suspend fun renameRoute(id: String, title: String) {
        if (title.isBlank()) return
        dao.updateTitle(id, title.trim())
    }

    override suspend fun updateAudibleRadius(id: String, radiusMeters: Double) {
        dao.updateAudibleRadius(
            id,
            radiusMeters.coerceIn(
                MusicPin.MIN_AUDIBLE_RADIUS_METERS,
                MusicPin.MAX_AUDIBLE_RADIUS_METERS,
            ),
        )
    }

    override suspend fun updateAudioAssetUri(id: String, audioAssetUri: String) {
        dao.updateAudioAssetUri(id, audioAssetUri)
    }

    override suspend fun removeRoute(id: String) {
        dao.delete(id) // CASCADE removes the vertices
        if (_activeRouteId.value == id) _activeRouteId.value = null
    }

    override suspend fun setActiveRoute(id: String?) {
        if (id != null && !dao.routeExists(id)) {
            throw IllegalArgumentException("unknown route id: $id")
        }
        _activeRouteId.value = id
    }
}

/**
 * Process-wide singleton sharing the SAME database file as
 * [MusicPinRepositoryProvider] — one SonarMapDatabase per process, single
 * write connection, shared InvalidationTracker.
 */
object RouteRepositoryProvider {

    @Volatile
    private var instance: RouteRepository? = null

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun repository(context: Context): RouteRepository =
        instance ?: synchronized(this) {
            instance ?: RoomRouteRepository(
                database = com.sonarmap.app.data.db.SonarMapDatabaseProvider.database(context),
                eagerScope = repoScope,
            ).also { instance = it }
        }
}
