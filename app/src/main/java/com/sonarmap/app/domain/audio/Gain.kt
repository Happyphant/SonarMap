package com.sonarmap.app.domain.audio

/**
 * One smoothing step toward a new gain target, linear interpolation:
 * `previous + (target - previous) * factor`.
 *
 * `factor = 0.0` keeps [previous], `factor = 1.0` jumps straight to [target];
 * intermediate values lag behind target — used to fade gain between location
 * ticks so sources never "snap" audibly. Values outside [0, 1] are clamped
 * instead of throwing: a misbehaving UI knob or ticker must never crash audio.
 */
fun smoothGain(previous: Double, target: Double, factor: Double): Double =
    previous + (target - previous) * factor.coerceIn(0.0, 1.0)
