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
 *     entering the area turns the sound ON at this level (immediately, not
 *     after a smoothing ramp), approaching the source raises it linearly
 *     to 1.0, and stepping outside the area cuts the sound off IMMEDIATELY.
 * - [smoothingFactor] 0.35 per update (~0.7 s time constant at 2 Hz) —
 *     smooths volume changes while the listener stays inside the zone
 *     (sensor steps, resizing the zone), small enough to feel responsive.
 */
data class ProximityGainParams(
    val defaultMaxDistanceMeters: Double = MusicPin.DEFAULT_AUDIBLE_RADIUS_METERS,
    val edgeGain: Double = 0.15,
    val smoothingFactor: Double = 0.35,
) {
    init {
        require(defaultMaxDistanceMeters > 0.0) { "defaultMaxDistanceMeters must be positive" }
        require(edgeGain in 0.0..1.0) { "edgeGain must be in [0, 1]" }
        require(smoothingFactor in 0.0..1.0) { "smoothingFactor must be in [0, 1]" }
    }
}

/**
 * Pure-Kotlin proximity gain state machine, the core of
 * com.sonarmap.app.audio.LocationAudioController. Formula per location tick:
 *
 * ```
 * inside   = distance <= zoneRadius
 * raw      = clamp(1 - distance / zoneRadius, 0, 1)   // linear falloff
 * shaped   = edgeGain + (1 - edgeGain) * raw          // floor at the edge
 * target   = shaped * clamp(userGain, 0, 1)           // user slider on top
 * outside  -> output = 0 immediately                  // hard cut at the edge,
 *                                                     // no smoothing tail,
 *                                                     // no ±1.15 dead band
 * re-entry -> output = target immediately             // ON at the edge floor
 * inside   -> output = smooth(output, target, alpha)  // EMA smoothing
 * ```
 *
 * The boundary is crisp in both directions: past the painted hearing-area
 * edge the sound stops at once, crossing it inward turns the sound ON at
 * the [edgeGain] floor at once — the acoustic edge coincides with the map's
 * painted boundary in every geometry. Smoothing applies only to volume
 * changes while the listener stays INSIDE the zone (walking, resizing the
 * zone in the editor sheet); resizing the zone past the listener cuts the
 * sound exactly like walking out.
 *
 * Missing fix (distance == null): proximity is held at its last value, so a
 * brief GPS gap does not pump the volume; before the first fix proximity
 * defaults to 1.0 — a manual preview plays at the user's slider level, and
 * the first fix applies the boundary logic immediately.
 *
 * All inputs are defensively clamped; nothing here throws on UI noise.
 * Stateful — call [reset] when the active audio source changes.
 */
class ProximityGainCalculator(
    private val params: ProximityGainParams = ProximityGainParams(),
) {

    /** Proximity held over GPS gaps; 1.0 before the first fix (preview). */
    private var lastProximity: Double = 1.0
    private var lastOutput: Double = 0.0
    /** Inside/outside state of the previous fix; null before the first fix. */
    private var lastInside: Boolean? = null

    /**
     * Feeds one location tick; returns the effective gain to apply
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

        val distance = distanceMeters.coerceAtLeast(0.0)
        val inside = distance <= radius

        val raw = (1.0 - distance / radius).coerceIn(0.0, 1.0)
        val shaped = params.edgeGain + (1.0 - params.edgeGain) * raw
        lastProximity = if (inside) shaped else 0.0

        val target = lastProximity * slider
        lastOutput = when {
            // Outside the hearing area: the sound stops RIGHT NOW — no
            // smoothing tail beyond the boundary, no dead band, no pops back.
            !inside -> 0.0
            // Crossed the boundary inward: the sound comes ON at once (at
            // the edge floor when entering at the boundary) instead of
            // ramping up there over a few ticks.
            lastInside == false -> target
            // Inside: smooth the steps (sensor noise, zone resize).
            else -> smoothGain(lastOutput, target, params.smoothingFactor)
        }
        lastInside = inside
        return lastOutput
    }

    /** Clears state for a newly selected source; no stale proximity leaks over. */
    fun reset() {
        lastProximity = 1.0
        lastOutput = 0.0
        lastInside = null
    }
}
