package com.sonarmap.app.domain.model

/**
 * WGS-84 geographic coordinate.
 *
 * Domain-only type: constructed from plain doubles so it never depends on
 * Android (GeoPoint of Maps SDK), Google Maps, or MapKit types.
 *
 * @property latitude degrees, [-90, 90]
 * @property longitude degrees, [-180, 180]
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude $latitude out of range [-90, 90]" }
        require(longitude in -180.0..180.0) { "longitude $longitude out of range [-180, 180]" }
    }
}
