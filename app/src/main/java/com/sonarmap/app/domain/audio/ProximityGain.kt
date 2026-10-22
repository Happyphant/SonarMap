package com.sonarmap.app.domain.audio

import com.sonarmap.app.domain.model.MusicPin
/**
 * Tunable proximity-to-gain parameters (defaults chosen for a walking user
 * and ~2 Hz location updates):
 *
 * - [defaultMaxDistanceMeters] 150 m — the default hearing-area radius;
 *     [ProximityGainCalculator.update] takes the actual per-source radius
 *     as an argument (the audible zone now has a user-settable size).
 * - [edgeGain] 0.15 — the "minimal volume" floor exactly at the zone edge:
 *     entering the area turns the sound ON at this level, approaching the
 *     source raises it linearly to 1.0.
 * - [hysteresisFactor] 1.15 — the source is *deactivated* only past
 *     zoneRadius * 1.15 and *reactivated* at the zone edge itself
 *     (radius), so the sound starts exactly when the visual fill is
 *     entered and GPS jitter around the boundary cannot flicker it.
 * - [smoothingFactor] 0.35 per update (~0.7 s time constant at 2 Hz) —
 *     enough to hide sensor steps, small enough to feel responsive.
 */
data class ProximityGainParams(
    val defaultMaxDistanceMeters: Double = MusicPin.DEFAULT_AUDIBLE_RADIUS_METERS,
    val edgeGain: Double = 0.15,
    val hysteresisFactor: Double = 1.15,
    val smoothingFactor: Double = 0.35,
) {
    init {
        require(defaultMaxDistanceMeters > 0.0) { "defaultMaxDistanceMeters must be positive" }
        require(edgeGain in 0.0..1.0) { "edgeGain must be in [0, 1]" }
        require(hysteresisFactor >= 1.0) { "hysteresisFactor must not shrink the dead zone" }
        require(smoothingFactor in 0.0..1.0) { "smoothingFactor must be in [0, 1]" }
    }
}

/**
 * Pure-Kotlin proximity gain state machine, the core of
 * com.sonarmap.app.audio.LocationAudioController. Formula per location tick:
 *
 * ```
 * raw      = clamp(1 - distance / zoneRadius, 0, 1)   // linear falloff
 * shaped   = edgeGain + (1 - edgeGain) * raw          // floor at the edge
 * shaped   = if (hysteresisActive) shaped else 0.0    // silent after mute
 * target   = shaped * clamp(userGain, 0, 1)           // user slider on top
 * smoothed = smoothGain(previous, target, smoothingFactor)
 * ```
 *
 * Hysteresis band: [zoneRadius .. zoneRadius * hysteresisFactor] keeps the
 * previous state — once muted (past the outer threshold) the source stays
 * silent there; while playing, a step just outside the visible zone fades
 * to the [edgeGain] floor instead of falling to zero, and full muting only
 * happens past `zoneRadius * 1.15`. Inward reactivation happens exactly at
 * the zone edge, matching what the map paints as the hearing area.
 *
 * Missing fix (distance == null): proximity is held at its last value, so a
 * brief GPS gap does not pump the volume; before the first fix proximity
 * defaults to 1.0 — a manual preview plays at the user's slider level.
 *
 * All inputs are defensively clamped; nothing here throws on UI noise.
 * Stateful — call [reset] when the active audio source changes.
 */
class ProximityGainCalculator(
    private val params: ProximityGainParams = ProximityGainParams(),
) {

    private var hysteresisActive: Boolean = true
    private var seenDistance: Boolean = false
    /** Proximity held over GPS gaps; 1.0 before the first fix. */
    private var lastProximity: Double = 1.0
    private var lastOutput: Double = 0.0

    /**
     * Feeds one location tick; returns the smoothed effective gain to apply
     * to the audio engine right now.
     *
     * @param distanceMeters great-circle meters to the active source, or null
     *   when there is no fix yet
     * @param userGain the source's own loudness knob (slider), linear 0..1
     * @param zoneRadiusMeters the active source's hearing-area radius in
     *   meters (pins and routes carry their own, editor-sheet adjustable)
     */
    fun update(
        distanceMeters: Double?,
        userGain: Double,
        zoneRadiusMeters: Double = params.defaultMaxDistanceMeters,
    ): Double {
        val slider = userGain.coerceIn(0.0, 1.0)
        val radius = zoneRadiusMeters.coerceAtLeast(1e-6)

        if (distanceMeters == null) {
            // No fix: keep the last proximity decision, just rescale to the
            // (possibly changed) slider value.
            lastOutput = smoothGain(lastOutput, lastProximity * slider, params.smoothingFactor)
            return lastOutput
        }

        seenDistance = true
        val distance = distanceMeters.coerceAtLeast(0.0)
        updateHysteresis(distance, radius)

        val raw = (1.0 - distance / radius).coerceIn(0.0, 1.0)
        val shaped = params.edgeGain + (1.0 - params.edgeGain) * raw
        lastProximity = if (hysteresisActive) shaped else 0.0

        lastOutput = smoothGain(lastOutput, lastProximity * slider, params.smoothingFactor)
        return lastOutput
    }

    /** Clears state for a newly selected source; no stale proximity leaks over. */
    fun reset() {
        hysteresisActive = true
        seenDistance = false
        lastProximity = 1.0
        lastOutput = 0.0
    }

    private fun updateHysteresis(distance: Double, radius: Double) {
        val offThreshold = radius * params.hysteresisFactor
        if (seenDistance) {
            if (hysteresisActive && distance > offThreshold) hysteresisActive = false
            if (!hysteresisActive && distance < radius) hysteresisActive = true
        }
    }
}
