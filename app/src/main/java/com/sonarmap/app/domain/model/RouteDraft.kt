package com.sonarmap.app.domain.model

/**
 * A route currently being drawn by hand on the map: taps append vertices,
 * finishing promotes the draft into a real [Route].
 *
 * Kept separate from [Route] because the domain contract of a saved route
 * (>= 2 points, up-to-date asset uri) only holds once the user finishes
 * drawing; a draft is free to carry a single point (or none at all).
 *
 * @property points vertices in the order they were tapped
 * @property title prepared route title ("Маршрут N"); never blank
 */
data class RouteDraft(
    val points: List<GeoPoint> = emptyList(),
    val title: String,
) {
    init {
        require(title.isNotBlank()) { "draft title must not be blank" }
    }
}
