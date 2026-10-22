package com.sonarmap.app.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power stereo pan law: pan in [−1, 1] (−1 = full left, +1 = full
 * right, 0 = center) mapped to per-channel gains with CONSTANT total
 * energy:
 *
 * ```
 * θ  = (pan + 1) · π/4          // 0..π/2
 * L  = cos(θ),  R = sin(θ)      // L² + R² = 1 for every pan
 * ```
 *
 * Why not the naive linear law `L = 1−pan, R = 1+pan`: it plays BOTH
 * channels at full gain in the center (energy 2.0) versus one channel at
 * the sides (energy 1.0), so the centered source sounds ~1.4x louder and
 * a side-panned source seems to fade — device-verified user report.
 * Equal-power plays the center at 0.707 per ear; binaural loudness
 * (sqrt of summed power) stays constant across the whole sweep.
 *
 * Values outside [−1, 1] are clamped; a misbehaving caller must never
 * produce channel gains > 1 (clipping).
 */
fun panGains(pan: Double): Pair<Double, Double> {
    val p = pan.coerceIn(-1.0, 1.0)
    val theta = (p + 1.0) * PI / 4.0
    return cos(theta) to sin(theta)
}
