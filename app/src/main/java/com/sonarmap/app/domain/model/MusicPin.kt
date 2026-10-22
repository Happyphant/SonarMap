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
 * @property audibleRadiusMeters radius of the hearing area shown around the
 *   pin; the audio is silent outside it and at its EDGE_GAIN floor inside
 * @property createdAt epoch milliseconds, UTC
 */
data class MusicPin(
    val id: String,
    val position: GeoPoint,
    val title: String,
    val audioAssetUri: String,
    val gain: Double,
    val audibleRadiusMeters: Double = DEFAULT_AUDIBLE_RADIUS_METERS,
    val createdAt: Long,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(gain >= 0.0) { "gain $gain must be non-negative" }
        require(audibleRadiusMeters > 0.0) {
            "audibleRadiusMeters $audibleRadiusMeters must be positive"
        }
    }

    companion object {
        /** Playback loudness for freshly created pins (70%). */
        const val DEFAULT_PIN_GAIN = 0.7

        /** Default hearing-area radius, kept in sync with the gain curve. */
        const val DEFAULT_AUDIBLE_RADIUS_METERS = 150.0

        /** Editor clamps for the hearing-area slider (repo storage range). */
        const val MIN_AUDIBLE_RADIUS_METERS = 10.0
        const val MAX_AUDIBLE_RADIUS_METERS = 5000.0
    }
}
