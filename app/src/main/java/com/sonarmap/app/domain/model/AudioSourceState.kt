package com.sonarmap.app.domain.model

/**
 * Derived, short-lived description of one audio source relative to the user.
 * Recomputed each location update; the audio layer maps it onto
 * engine-specific nodes (never stored in the domain).
 *
 * @property id identifies the source (MusicPin or Route asset)
 * @property distanceMeters great-circle distance to the source, >= 0
 * @property bearingDegrees azimuth from user to source, [0, 360)
 * @property targetGain gain the audio layer should approach (apply smoothGain)
 * @property active whether the source takes part in the current mix
 */
data class AudioSourceState(
    val id: String,
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val targetGain: Double,
    val active: Boolean,
) {
    init {
        require(distanceMeters >= 0.0) {
            "distanceMeters $distanceMeters must be non-negative"
        }
        require(bearingDegrees in 0.0..360.0) {
            "bearingDegrees $bearingDegrees out of range [0, 360)"
        }
    }
}
