package com.sonarmap.app.audio

import com.sonarmap.app.domain.geo.bearingDegrees
import com.sonarmap.app.domain.geo.distanceMeters
import com.sonarmap.app.domain.geo.projection
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route

/**
 * The ONE active walking-audio source (an active route first, else the
 * active pin) expressed as the audio-loop input. Shared by the map screen
 * (screen-owned playback) and the foreground playback service (background
 * mode) so both compute the identical distance/bearing/radius triple from
 * the same rules:
 * - route: distance/bearing to the NEAREST POINT of the polyline; stereo
 *   collapses to the center inside the corridor (the bearing degenerates
 *   on the line);
 * - pin: distance/bearing to the pin itself; no fix → no distance → full
 *   slider loudness preview.
 */
fun positionedSourceFor(
    fix: GeoPoint?,
    activeRoute: Route?,
    activePin: MusicPin?,
): PositionedSourceAudio? = when {
    activeRoute != null -> {
        val projected = fix?.let { activeRoute.projection(it) }
        PositionedSourceAudio(
            sourceId = activeRoute.id,
            distanceMeters = projected?.distanceMeters,
            bearingDegrees = fix?.let { current -> projected?.let { bearingDegrees(current, it.point) } },
            userGain = 1.0,
            panFadeMeters = PositionedSourceAudio.ROUTE_PAN_FADE_METERS,
            audibleRadiusMeters = activeRoute.audibleRadiusMeters,
        )
    }

    activePin != null -> PositionedSourceAudio(
        sourceId = activePin.id,
        distanceMeters = fix?.let { distanceMeters(it, activePin.position) },
        bearingDegrees = fix?.let { bearingDegrees(it, activePin.position) },
        userGain = activePin.gain,
        audibleRadiusMeters = activePin.audibleRadiusMeters,
    )

    else -> null // nothing active: the controller silences
}
