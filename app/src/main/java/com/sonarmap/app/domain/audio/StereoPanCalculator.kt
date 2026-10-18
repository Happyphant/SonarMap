package com.sonarmap.app.domain.audio

import kotlin.math.abs

/**
 * Tunable stereo-pan parameters for a virtual source the user walks with:
 *
 * - [deadZoneDegrees] 10° — heading/bearing jitter inside this relative
 *     angle produces no pan at all: small wrist rotations never waggle the
 *     stereo image.
 * - [maxPanAtDegrees] 90° — pan saturates at the sides; behind the user
 *     (90..180°) stays at ±1 (true behind-detection is HRTF territory,
 *     deferred with the FMOD/Resonance engine).
 * - [smoothingFactor] 0.3 per update (~10 Hz update rate) — pans glide.
 */
data class StereoPanParams(
    val deadZoneDegrees: Double = 10.0,
    val maxPanAtDegrees: Double = 90.0,
    val smoothingFactor: Double = 0.3,
) {
    init {
        require(deadZoneDegrees in 0.0..90.0) { "deadZoneDegrees must be in [0, 90]" }
        require(maxPanAtDegrees > deadZoneDegrees) { "maxPanAtDegrees must exceed the dead zone" }
        require(smoothingFactor in 0.0..1.0) { "smoothingFactor must be in [0, 1]" }
    }
}

/**
 * Pure-Kotlin stereo positioning state machine: relative angle of the active
 * source (bearing to pin minus user heading) mapped to a pan in [−1, 1]:
 *
 * ```
 * relative = normalize(bearing - heading)          // to [-180, 180]
 * dead     = |relative| < deadZone
 * target   = dead ? 0 : sign * clamp((|rel|-dz)/(maxPanAt - dz), 0, 1)
 * smoothed = smoothGain(previous, target, smoothingFactor)
 * ```
 *
 * Conventions (documented, deterministic):
 * - bearing 0 = north, clockwise positive; heading 0 = facing north.
 * - relative +90° = source on the right -> pan +1 (right ear louder).
 * - directly behind (±180°) folds to pan +1; the delta normalization maps
 *   to [−180, 180] with exact −180 folded to +180. Note: crossing the back
 *   (±90°..180°) pans at saturation and the image flips sides through the
 *   EMA glide — true behind rendering is HRTF territory, deferred with
 *   FMOD/Resonance.
 * - null heading (no sensor) or null bearing (no fix) -> target 0: mono
 *   degradation, never a random side.
 *
 * Stateful — call [reset] when the active pin changes.
 */
class StereoPanCalculator(
    private val params: StereoPanParams = StereoPanParams(),
) {

    private var lastPan: Double = 0.0

    /**
     * Feeds one update tick (throttled upstream to ~10 Hz); returns the
     * smoothed pan to apply to the audio engine right now.
     */
    fun update(bearingDegrees: Double?, headingDegrees: Double?): Double {
        val target = if (bearingDegrees == null || headingDegrees == null) {
            0.0 // sensor or fix missing: degrade to center, never guess a side
        } else {
            val relative = shortestSignedDelta(headingDegrees, bearingDegrees)
            val magnitude = abs(relative)
            if (magnitude <= params.deadZoneDegrees) {
                0.0
            } else {
                val span = params.maxPanAtDegrees - params.deadZoneDegrees
                val scaled = ((magnitude - params.deadZoneDegrees) / span).coerceIn(0.0, 1.0)
                if (relative < 0) -scaled else scaled
            }
        }
        lastPan = smoothGain(lastPan, target, params.smoothingFactor)
        return lastPan
    }

    /** Clears state for a newly selected pin. */
    fun reset() {
        lastPan = 0.0
    }

    /** Shortest signed heading delta a→b in degrees, in [−180, 180] with
     *  exact −180 folded to +180 (directly behind = right side, documented). */
    private fun shortestSignedDelta(fromDegrees: Double, toDegrees: Double): Double {
        val d = ((toDegrees - fromDegrees + 540.0) % 360.0) - 180.0
        return if (d <= -180.0) d + 360.0 else d
    }
}
