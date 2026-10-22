package com.sonarmap.app.domain.model

/**
 * A curated sequence of points walked while a single audio asset plays.
 *
 * @property id stable identifier
 * @property points ordered polyline vertices; segments connect consecutive points
 * @property audioAssetUri opaque reference into the audio layer;
 *   empty string means "no audio attached yet" (same convention as [MusicPin])
 * @property audibleRadiusMeters width of the hearing-area corridor around the
 *   polyline: the audio is silent outside it and at the gain floor inside
 * @property createdAt epoch milliseconds, UTC
 */
data class Route(
    val id: String,
    val title: String,
    val points: List<GeoPoint>,
    val audioAssetUri: String,
    val audibleRadiusMeters: Double = MusicPin.DEFAULT_AUDIBLE_RADIUS_METERS,
    val createdAt: Long,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(points.size >= 2) { "route must contain at least two points, was ${points.size}" }
        require(audibleRadiusMeters > 0.0) {
            "audibleRadiusMeters $audibleRadiusMeters must be positive"
        }
    }
}

/**
 * Consecutive point pairs of [Route] as walking segments.
 * A route with fewer than two points yields no segments.
 */
fun Route.segments(): List<RouteSegment> =
    if (points.size < 2) emptyList() else points.zipWithNext { start, end -> RouteSegment(start, end) }
