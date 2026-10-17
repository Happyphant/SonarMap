package com.sonarmap.app.domain.repository

import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import kotlinx.coroutines.flow.StateFlow

/**
 * Storage boundary for music pins. The in-memory implementation stands in
 * until Room arrives; callers stay storage-agnostic.
 */
interface MusicPinRepository {

    /** Current pins; hot stream, always up to date. */
    val pins: StateFlow<List<MusicPin>>

    /**
     * Creates a pin with a generated id, default full gain and no audio yet.
     * @param defaultTitle used when the caller has nothing better (UI supplies "Пин N")
     */
    suspend fun addPin(position: GeoPoint, defaultTitle: String): MusicPin

    /** Blank titles are ignored (the previous title stays). */
    suspend fun renamePin(id: String, title: String)

    /** Gain is clamped to >= 0 to satisfy the MusicPin contract. */
    suspend fun updateGain(id: String, gain: Double)

    /** Empty string detaches the audio file. */
    suspend fun updateAudioAssetUri(id: String, audioAssetUri: String)

    suspend fun removePin(id: String)
}
