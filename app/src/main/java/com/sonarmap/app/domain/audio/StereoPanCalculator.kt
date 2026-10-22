package com.sonarmap.app.domain.audio

import kotlin.math.abs

/**
 * Tunable stereo-pan parameters for a virtual source the user walks with:
 *
 * - [deadZoneDegrees] 10° — heading/bearing jitter inside this relative
 *     angle produces no pan at all: small wrist rotations never waggle the
 *     stereo image.
 * - [maxPanAtDegrees] 90° — the point of maximum pan; beyond it (towards
 *     the back) the pan FADES back to center instead of staying saturated,
 *     because plain stereo has no way to render "behind" and a hard ear
 *     flip at the back-crossing sounds like an audible glitch.
 * - [smoothingFactor] 0.3 per update (~10 Hz update rate) — pans glide.
 */
data class StereoPanParams(
    val deadZoneDegrees: Double = 10.0,
    val maxPanAtDegrees: Double = 90.0,
    val smoothingFactor: Double = 0.3,
) {
    init {
        require(deadZoneDegrees in 0.0..maxPanAtDegrees) { "deadZoneDegrees must be in [0, maxPanAtDegrees]" }
        require(maxPanAtDegrees in 0.0..180.0) { "maxPanAtDegrees must be in [0, 180]" }
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
 * rise     = (|rel| - dz) / (maxPanAt - dz)        // dead zone -> sides
 * fade     = 1 - (|rel| - 90°) / (180° - 90°)      // sides -> center (back)
 * target   = dead ? 0 : sign * (|rel| <= 90° ? rise : fade)
 * smoothed = smoothGain(previous, target, smoothingFactor)
 * ```
 *
 * Piecewise-linear "triangular" pan curve: full ±1 at the sides, fading
 * back to 0 towards the back; directly behind (±180°) = center. Both ears
 * get an equal back signal — practically exact (the head barely shadows
 * rear sources) and it makes the back crossing CONTINUOUS: no ear ever
 * flips, no glitch. True behind rendering (HRTF, rear elevation) is
 * FMOD/Resonance territory, still deferred.
 *
 * Conventions (documented, deterministic):
 * - bearing 0 = north, clockwise positive; heading 0 = facing north.
 * - relative +90° = source on the right -> pan +1 (right ear louder).
 * - relative 180° = source behind -> pan 0 (center, both ears).
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
                val level = if (magnitude <= params.maxPanAtDegrees) {
                    // Rise: dead zone edge -> full ±1 at maxPanAtDegrees.
                    (magnitude - params.deadZoneDegrees) /
                        (params.maxPanAtDegrees - params.deadZoneDegrees)
                } else {
                    // Behind fade: ±1 at the side -> 0 directly behind the
                    // back (continuous no-flip crossing).
                    1.0 - (magnitude - params.maxPanAtDegrees) / (180.0 - params.maxPanAtDegrees)
                }
                val scaled = level.coerceIn(0.0, 1.0)
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

    /** Shortest signed heading delta a→b in degrees, in [−180, 180], with
     *  exact −180 canonicalized to +180 (both map to pan 0 — behind). */
    private fun shortestSignedDelta(fromDegrees: Double, toDegrees: Double): Double {
        val d = ((toDegrees - fromDegrees + 540.0) % 360.0) - 180.0
        return if (d <= -180.0) d + 360.0 else d
    }
}
