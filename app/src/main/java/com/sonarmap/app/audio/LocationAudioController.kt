package com.sonarmap.app.audio

import com.sonarmap.app.domain.audio.AudioEngine
import com.sonarmap.app.domain.audio.ProximityGainCalculator
import com.sonarmap.app.domain.audio.ProximityGainParams
import com.sonarmap.app.domain.audio.StereoPanCalculator
import com.sonarmap.app.domain.audio.StereoPanParams
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * The pin the user selected on the map, expressed as everything the
 * proximity gain and stereo pan need: identity (to detect switches), the
 * live distance and bearing recomputed per location tick, and the pin's
 * own slider loudness.
 *
 * @property distanceMeters null = no fix yet (or no pin selected upstream)
 * @property bearingDegrees compass bearing user->pin in [0, 360), null when
 *   either position is unknown
 */
data class ActivePinAudio(
    val pinId: String,
    val distanceMeters: Double?,
    val bearingDegrees: Double?,
    val userGain: Double,
)

/**
 * Bridges the location and sensor layers to the [AudioEngine]:
 * - volume of the active pin follows distance (see [ProximityGainCalculator]);
 * - stereo pan follows the relative angle bearing−heading
 *   (see [StereoPanCalculator]), degraded to center (0) when there is no
 *   heading sensor or no headphone-class output.
 *
 * Threading: [run] collects on the caller's context (the UI scope); every
 * engine call is non-blocking by the engine contract, so the main thread is
 * never blocked. The sensor stream is conflated and all inputs are processed
 * at most every [minUpdateIntervalMillis] (~10 Hz); an engine write only
 * happens when the value moved by more than [writeEpsilon] — sensor storms
 * and 2 Hz fix streams never spam the engine with identical values.
 *
 * Ownership rules:
 * - This controller is the ONLY writer of engine volume and pan while
 *   running; UI sliders change the pin's stored gain, which arrives
 *   through the flow — no competing direct engine calls.
 * - A null [ActivePinAudio] (sheet closed / nothing selected) silences and
 *   centers the engine, resetting both state machines.
 * - A pin switch resets both state machines: no loudness or stereo memory
 *   crosses between different sound sources.
 */
class LocationAudioController(
    private val engine: AudioEngine,
    private val gainCalculator: ProximityGainCalculator =
        ProximityGainCalculator(ProximityGainParams()),
    private val panCalculator: StereoPanCalculator =
        StereoPanCalculator(StereoPanParams()),
    /**
     * Per-tick trace sink (distance/bearing/heading -> gain/pan). Defaults
     * to no-op so unit tests and silent production runs pay nothing; the
     * screen wires it to logcat for device verification.
     */
    private val trace: (String) -> Unit = {},
    /** Injectable clock for throttling; production uses [System] time. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Runs until cancelled (screen exit — the caller's LaunchedEffect).
     * @param activePin stream of the currently selected pin's audio inputs
     * @param heading compass heading of the user in [0, 360), null = sensor
     *   unavailable (mono degradation)
     * @param headphones true when a headphone-class output is connected;
     *   false = mono degradation
     */
    suspend fun run(
        activePin: Flow<ActivePinAudio?>,
        heading: Flow<Double?>,
        headphones: Flow<Boolean>,
    ) = coroutineScope {
        var latestPin: ActivePinAudio? = null
        var latestHeading: Double? = null
        var latestHeadphones: Boolean = false

        var currentPinId: String? = null
        var lastWriteMillis: Long = Long.MIN_VALUE
        var lastGain: Double? = null
        var lastPan: Double? = null

        /** One processing tick: every source emission lands here; the
         *  timestamp throttle decides whether it reaches the engine. */
        fun process() {
            val now = nowMillis()
            if (lastWriteMillis != Long.MIN_VALUE &&
                now - lastWriteMillis < minUpdateIntervalMillis
            ) {
                return // throttle: skip intermediate state churn
            }
            lastWriteMillis = now

            val active = latestPin
            val headingDegrees = latestHeading
            val onHeadphones = latestHeadphones

            val pinChanged = active?.pinId != currentPinId
            if (pinChanged) {
                currentPinId = active?.pinId
                gainCalculator.reset()
                panCalculator.reset()
                lastGain = null
                lastPan = null
            }

            if (active == null) {
                writeGain(0.0, lastGain) { lastGain = it }
                writePan(0.0, lastPan) { lastPan = it }
                trace("no active pin -> volume 0, pan 0")
                return
            }

            val effectivePan = if (onHeadphones) {
                panCalculator.update(active.bearingDegrees, headingDegrees)
            } else {
                panCalculator.update(null, null) // speakers: mono degradation
            }
            val gain = gainCalculator.update(active.distanceMeters, active.userGain)

            writeGain(gain, lastGain) { lastGain = it }
            writePan(effectivePan, lastPan) { lastPan = it }
            trace(
                "pin=${active.pinId} " +
                    "d=${active.distanceMeters?.let { "%.1f".format(it) } ?: "null"} " +
                    "brg=${active.bearingDegrees?.let { "%.1f".format(it) } ?: "null"} " +
                    "hdg=${headingDegrees?.let { "%.1f".format(it) } ?: "null"} " +
                    "gain=${"%.3f".format(gain)} pan=${"%.3f".format(effectivePan)}",
            )
        }

        // Each input feeds shared "latest" slots and triggers a throttled
        // process(). No combine: latest-value semantics are explicit and every
        // emission of every source is observed (combine's internal channel
        // buffers can silently drop values under rapid emissions).
        launch { activePin.collect { latestPin = it; process() } }
        launch { heading.conflate().collect { latestHeading = it; process() } }
        launch { headphones.collect { latestHeadphones = it; process() } }
    }

    private fun writeGain(value: Double, last: Double?, apply: (Double) -> Unit) {
        if (last == null || kotlin.math.abs(value - last) > writeEpsilon) {
            apply(value)
            engine.setVolume(value)
        }
    }

    private fun writePan(value: Double, last: Double?, apply: (Double) -> Unit) {
        if (last == null || kotlin.math.abs(value - last) > writeEpsilon) {
            apply(value)
            engine.setPan(value)
        }
    }

    private companion object {
        /** Audio updates at ~10 Hz are imperceptibly smooth for pan/gain. */
        const val minUpdateIntervalMillis = 100L

        /** Skip engine writes for sub-1% changes: invisible, saves binder churn. */
        const val writeEpsilon = 0.01
    }
}
