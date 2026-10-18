package com.sonarmap.app.domain.audio

/**
 * Tunable proximity-to-gain parameters (defaults chosen for a walking user
 * and ~2 Hz location updates):
 *
 * - [maxDistanceMeters] 150 m — gain reaches zero exactly here; 74 m (the
 *     device-verified demo distance) lands mid-curve at ~0.5.
 * - [hysteresisFactor] 1.15 — the source is *deactivated* only past
 *     maxDistance * 1.15 and *reactivated* only before maxDistance, so
 *     GPS jitter around the boundary cannot flicker the state.
 * - [smoothingFactor] 0.35 per update (~0.7 s time constant at 2 Hz) —
 *     enough to hide sensor steps, small enough to feel responsive.
 */
data class ProximityGainParams(
    val maxDistanceMeters: Double = 150.0,
    val hysteresisFactor: Double = 1.15,
    val smoothingFactor: Double = 0.35,
) {
    init {
        require(maxDistanceMeters > 0.0) { "maxDistanceMeters must be positive" }
        require(hysteresisFactor >= 1.0) { "hysteresisFactor must not shrink the dead zone" }
        require(smoothingFactor in 0.0..1.0) { "smoothingFactor must be in [0, 1]" }
    }
}

/**
 * Pure-Kotlin proximity gain state machine, the core of
 * com.sonarmap.app.audio.LocationAudioController. Formula per location tick:
 *
 * ```
 * raw      = clamp(1 - distance / maxDistance, 0, 1)   // linear falloff
 * raw      = if (hysteresisActive) raw else 0.0         // dead zone after mute
 * target   = raw * clamp(pinGain, 0, 1)                // user slider on top
 * smoothed = smoothGain(previous, target, smoothingFactor)
 * ```
 *
 * Hysteresis band: [maxDistance .. maxDistance * hysteresisFactor] is a
 * dead zone — once muted, the source stays muted there; it comes back only
 * when the user is again within [maxDistance]. Without the band, returning
 * fixes at 150–172 m would toggle the (already-zero) gain flag every tick.
 *
 * Missing fix (distance == null): proximity is held at its last value, so a
 * brief GPS gap does not pump the volume; before the first fix proximity
 * defaults to 1.0 — a manual preview plays at the user's slider level.
 *
 * All inputs are defensively clamped; nothing here throws on UI noise.
 * Stateful — call [reset] when the active pin changes.
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
     * @param distanceMeters great-circle meters to the active pin, or null
     *   when there is no fix yet
     * @param pinGain the pin's own loudness knob (slider), linear 0..1
     */
    fun update(distanceMeters: Double?, pinGain: Double): Double {
        val slider = pinGain.coerceIn(0.0, 1.0)

        if (distanceMeters == null) {
            // No fix: keep the last proximity decision, just rescale to the
            // (possibly changed) slider value.
            lastOutput = smoothGain(lastOutput, lastProximity * slider, params.smoothingFactor)
            return lastOutput
        }

        seenDistance = true
        val distance = distanceMeters.coerceAtLeast(0.0)
        updateHysteresis(distance)

        val raw = (1.0 - distance / params.maxDistanceMeters).coerceIn(0.0, 1.0)
        lastProximity = if (hysteresisActive) raw else 0.0

        lastOutput = smoothGain(lastOutput, lastProximity * slider, params.smoothingFactor)
        return lastOutput
    }

    /** Clears state for a newly selected pin; no stale proximity leaks over. */
    fun reset() {
        hysteresisActive = true
        seenDistance = false
        lastProximity = 1.0
        lastOutput = 0.0
    }

    private fun updateHysteresis(distance: Double) {
        val offThreshold = params.maxDistanceMeters * params.hysteresisFactor
        if (seenDistance) {
            if (hysteresisActive && distance > offThreshold) hysteresisActive = false
            if (!hysteresisActive && distance < params.maxDistanceMeters) hysteresisActive = true
        }
    }
}
