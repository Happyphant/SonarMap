package com.sonarmap.app.data

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.repository.MusicPinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Thread-safe in-memory store. Pure Kotlin/JVM — unit-testable without
 * an Android runtime. Deliberately simple: Room replaces this later behind
 * the same interface.
 */
class InMemoryMusicPinRepository : MusicPinRepository {

    private val _pins = MutableStateFlow<List<MusicPin>>(emptyList())
    override val pins: StateFlow<List<MusicPin>> = _pins.asStateFlow()

    private val _activePinId = MutableStateFlow<String?>(null)
    override val activePinId: StateFlow<String?> = _activePinId.asStateFlow()

    private val mutex = Mutex()

    override suspend fun addPin(position: GeoPoint, defaultTitle: String): MusicPin =
        mutex.withLock {
            val pin = MusicPin(
                id = UUID.randomUUID().toString(),
                position = position,
                title = defaultTitle.ifBlank { "Пин ${_pins.value.size + 1}" },
                audioAssetUri = "", // attached later from the editor sheet
                gain = MusicPin.DEFAULT_PIN_GAIN,
                createdAt = System.currentTimeMillis(),
            )
            _pins.value += pin
            pin
        }

    override suspend fun renamePin(id: String, title: String): Unit = mutex.withLock {
        if (title.isBlank()) return
        replaceWhere({ it.id == id }) { it.copy(title = title) }
    }

    override suspend fun updateGain(id: String, gain: Double): Unit = mutex.withLock {
        replaceWhere({ it.id == id }) { it.copy(gain = gain.coerceAtLeast(0.0)) }
    }

    override suspend fun updateAudioAssetUri(id: String, audioAssetUri: String): Unit = mutex.withLock {
        replaceWhere({ it.id == id }) { it.copy(audioAssetUri = audioAssetUri) }
    }

    override suspend fun removePin(id: String): Unit = mutex.withLock {
        _pins.value = _pins.value.filterNot { it.id == id }
        if (_activePinId.value == id) _activePinId.value = null
    }

    override suspend fun setActivePin(id: String?): Unit = mutex.withLock {
        if (id != null && _pins.value.none { it.id == id }) {
            throw IllegalArgumentException("unknown pin id: $id")
        }
        _activePinId.value = id
    }

    private inline fun replaceWhere(
        predicate: (MusicPin) -> Boolean,
        transform: (MusicPin) -> MusicPin,
    ) {
        _pins.value = _pins.value.map { if (predicate(it)) transform(it) else it }
    }
}

/**
 * Process-wide singleton so pins survive ViewModel recreation and back-stack
 * changes; process death still clears them until persistence (Room) is added.
 */
object MusicPinRepositoryProvider {
    val repository: MusicPinRepository = InMemoryMusicPinRepository()
}
