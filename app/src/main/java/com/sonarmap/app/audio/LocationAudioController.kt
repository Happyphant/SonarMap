package com.sonarmap.app.audio

import com.sonarmap.app.domain.audio.AudioEngine
import com.sonarmap.app.domain.audio.ProximityGainCalculator
import com.sonarmap.app.domain.audio.ProximityGainParams
import kotlinx.coroutines.flow.Flow

/**
 * The pin the user selected on the map, expressed as everything the
 * proximity gain needs: identity (to detect switches), the live distance
 * from [com.sonarmap.app.domain.geo.distanceMeters] recomputed per location
 * tick, and the pin's own slider loudness.
 *
 * @property distanceMeters null = no fix yet (or no pin selected upstream)
 */
data class ActivePinAudio(
    val pinId: String,
    val distanceMeters: Double?,
    val userGain: Double,
)

/**
 * Bridges the location layer to the [AudioEngine]: volume of the active pin
 * rises as the user approaches and falls to zero past the proximity radius
 * (linear falloff + hysteresis + per-tick smoothing — see
 * [ProximityGainCalculator] for the exact formula and default parameters).
 *
 * Threading: [run] collects on the caller's context (the UI scope); every
 * call it makes into the engine is non-blocking by the engine contract, so
 * the main thread is never blocked. The stream is consumed as it comes —
 * no buffering, the latest state always wins.
 *
 * Ownership rules:
 * - This controller is the ONLY writer of engine volume while running;
 *   UI sliders change the pin's stored gain, which arrives through the
 *   flow — no competing direct [AudioEngine.setVolume] calls.
 * - A null [ActivePinAudio] (sheet closed / nothing selected) silences the
 *   engine and resets the smoothing state.
 * - A pin switch resets the smoothing state: no loudness memory crosses
 *   between different sound sources.
 */
class LocationAudioController(
    private val engine: AudioEngine,
    private val calculator: ProximityGainCalculator =
        ProximityGainCalculator(ProximityGainParams()),
    /**
     * Per-tick trace sink (distance -> gain). Defaults to no-op so unit
     * tests and silent production runs pay nothing; the screen wires it to
     * logcat for device verification.
     */
    private val trace: (String) -> Unit = {},
) {

    /**
     * Runs until cancelled (screen exit — the caller's LaunchedEffect).
     * @param activePin stream of the currently selected pin's audio inputs;
     *   emits on every pin/state/location change
     */
    suspend fun run(activePin: Flow<ActivePinAudio?>) {
        var currentPinId: String? = null

        activePin.collect { active ->
            val pinChanged = active?.pinId != currentPinId
            if (pinChanged) {
                currentPinId = active?.pinId
                calculator.reset()
            }
            if (active == null) {
                trace("no active pin -> volume 0")
                engine.setVolume(0.0) // no active pin: nothing should be audible
                return@collect
            }
            val gain = calculator.update(active.distanceMeters, active.userGain)
            trace(
                "pin=${active.pinId} " +
                    "d=${active.distanceMeters?.let { "%.1f".format(it) } ?: "null"} " +
                    "gain=${"%.3f".format(gain)}",
            )
            engine.setVolume(gain)
        }
    }
}
