package com.sonarmap.app.domain.model

/**
 * A spatial audio source anchored at a fixed map position.
 *
 * @property id stable identifier (used for audio-engine node addressing)
 * @property position where the sound "lives" on the map
 * @property audioAssetUri opaque reference into the audio layer
 *   (domain knows nothing about FMOD/Resonance or file formats);
 *   empty string means "no audio attached yet"
 * @property gain target playback gain, linear scale >= 0 (1.0 = full)
 * @property createdAt epoch milliseconds, UTC
 */
data class MusicPin(
    val id: String,
    val position: GeoPoint,
    val title: String,
    val audioAssetUri: String,
    val gain: Double,
    val createdAt: Long,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(gain >= 0.0) { "gain $gain must be non-negative" }
    }
}
