package com.sonarmap.app.domain.model

/**
 * A straight piece of a [Route] between two consecutive points.
 * Pure geometry value: no entity IDs, keeping it cheap to create in bulk
 * inside nearest-segment queries.
 */
data class RouteSegment(
    val start: GeoPoint,
    val end: GeoPoint,
)
