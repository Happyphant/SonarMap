package com.sonarmap.app.data

import android.content.Context
import com.sonarmap.app.data.db.SonarMapDatabase
import com.sonarmap.app.data.db.toDomain
import com.sonarmap.app.data.db.toEntity
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.repository.MusicPinRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Room-backed [MusicPinRepository] — replaces the in-memory store behind
 * the SAME interface and the same call contracts (clamps, blank-title
 * rules, unknown-id IllegalArgumentException).
 *
 * Public reads are DAO flows promoted to eager StateFlows on a
 * process-lifetime scope: the domain interface promises "always up to
 * date" even before the first collector, and the Active-source
 * selection/service read `.value` directly. The eager subscription cost is
 * one InvalidationTracker observer — negligible.
 */
class RoomMusicPinRepository(
    database: SonarMapDatabase,
    eagerScope: CoroutineScope,
) : MusicPinRepository {

    private val dao = database.musicPinDao()

    override val pins: StateFlow<List<MusicPin>> = dao.observePins()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(eagerScope, SharingStarted.Eagerly, emptyList())

    /** Active selection is intentionally NOT persisted: after a process
     *  restart nothing plays until the user picks a source again (no
     *  surprise audio on app launch). */
    private val _activePinId = MutableStateFlow<String?>(null)
    override val activePinId: StateFlow<String?> = _activePinId.asStateFlow()

    override suspend fun addPin(position: GeoPoint, defaultTitle: String): MusicPin {
        val pin = MusicPin(
            id = java.util.UUID.randomUUID().toString(),
            position = position,
            title = defaultTitle.ifBlank { "Пин ${(dao.count() + 1)}" },
            audioAssetUri = "", // attached later from the editor sheet
            gain = MusicPin.DEFAULT_PIN_GAIN,
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(pin.toEntity())
        return pin
    }

    override suspend fun renamePin(id: String, title: String) {
        if (title.isBlank()) return
        dao.updateTitle(id, title.trim())
    }

    override suspend fun updateGain(id: String, gain: Double) {
        dao.updateGain(id, gain.coerceAtLeast(0.0))
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

    override suspend fun removePin(id: String) {
        dao.delete(id)
        if (_activePinId.value == id) _activePinId.value = null
    }

    override suspend fun setActivePin(id: String?) {
        if (id != null && !dao.pinExists(id)) {
            throw IllegalArgumentException("unknown pin id: $id")
        }
        _activePinId.value = id
    }
}

/**
 * Process-wide singleton. Built lazily with the app context, so the
 * database (and the DB schema) live as long as the process — survives
 * ViewModel recreation and acts as an app-model scope source.
 */
object MusicPinRepositoryProvider {

    @Volatile
    private var instance: MusicPinRepository? = null

    /**
     * The eager scope powers the StateFlow promotion above; the Eagerly
     * share keeps the first-composition free of DB cold-start latency and
     * keeps `.value` truthful for the audio service.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun repository(context: Context): MusicPinRepository =
        instance ?: synchronized(this) {
            instance ?: RoomMusicPinRepository(
                database = com.sonarmap.app.data.db.SonarMapDatabaseProvider.database(context),
                eagerScope = repoScope,
            ).also { instance = it }
        }
}
