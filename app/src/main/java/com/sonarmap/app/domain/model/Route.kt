package com.sonarmap.app.domain.model

/**
 * A curated sequence of points walked while a single audio asset plays.
 *
 * @property id stable identifier
 * @property points ordered polyline vertices; segments connect consecutive points
 * @property audioAssetUri opaque reference into the audio layer
 * @property createdAt epoch milliseconds, UTC
 */
data class Route(
    val id: String,
    val title: String,
    val points: List<GeoPoint>,
    val audioAssetUri: String,
    val createdAt: Long,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(audioAssetUri.isNotBlank()) { "audioAssetUri must not be blank" }
        require(points.isNotEmpty()) { "route must contain at least one point" }
    }
}

/**
 * Consecutive point pairs of [Route] as walking segments.
 * A route with fewer than two points yields no segments.
 */
fun Route.segments(): List<RouteSegment> =
    if (points.size < 2) emptyList() else points.zipWithNext { start, end -> RouteSegment(start, end) }
