package com.sonarmap.app.domain.model

/**
 * Snapshot of the user's position and heading, produced by the location layer
 * and consumed by proximity/gain logic.
 *
 * @property headingDegrees azimuth the user faces, [0, 360)
 * @property accuracyMeters estimated horizontal accuracy, >= 0
 * @property timestamp epoch milliseconds, UTC
 */
data class UserLocation(
    val position: GeoPoint,
    val headingDegrees: Double,
    val accuracyMeters: Double,
    val timestamp: Long,
) {
    init {
        require(headingDegrees in 0.0..360.0) {
            "headingDegrees $headingDegrees out of range [0, 360]"
        }
        require(accuracyMeters >= 0.0) { "accuracyMeters $accuracyMeters must be non-negative" }
    }
}
