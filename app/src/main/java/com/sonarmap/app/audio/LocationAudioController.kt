package com.sonarmap.app.audio

import com.sonarmap.app.domain.audio.AudioEngine
import com.sonarmap.app.domain.audio.ProximityGainCalculator
import com.sonarmap.app.domain.audio.ProximityGainParams
import com.sonarmap.app.domain.audio.StereoPanCalculator
import com.sonarmap.app.domain.audio.StereoPanParams
import com.sonarmap.app.domain.model.MusicPin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * The active walking-audio source (a pin OR a route) expressed as everything
 * the proximity gain and stereo pan need: identity (to detect switches), the
 * live distance and bearing recomputed per location tick, and the source's
 * own loudness.
 *
 * @property distanceMeters null = no fix yet (or nothing active upstream);
 *   for a route this is the distance to the nearest point of the polyline
 * @property bearingDegrees compass bearing user->source in [0, 360), null
 *   when either position is unknown; for a route it points at the projected
 *   point on the line
 * @property userGain the source's own loudness (pin slider; routes play at 1.0)
 * @property panFadeMeters route-only: the stereo image collapses towards the
 *   center as the user approaches the line — the bearing to the projected
 *   point degenerates once the user is ON the line (the nearest point is the
 *   user themselves). The pan is multiplied by clamp(d/panFadeMeters, 0, 1),
 *   centering the sound inside the corridor. null = no fade (pins).
 * @property audibleRadiusMeters the source's hearing-area radius: the audio is
 *   silent outside it and enters at EDGE_GAIN floor at its boundary, rising
 *   towards the source. The map draws exactly this zone around pins/routes.
 */
data class PositionedSourceAudio(
    val sourceId: String,
    val distanceMeters: Double?,
    val bearingDegrees: Double?,
    val userGain: Double,
    val panFadeMeters: Double? = null,
    val audibleRadiusMeters: Double = MusicPin.DEFAULT_AUDIBLE_RADIUS_METERS,
) {
    companion object {
        /**
         * Corridor half-width for route sources: inside this radius from the
         * line the pan collapses towards the center (both ears), because the
         * bearing to the nearest line point degenerates as the user walks
         * onto the route. Outside it, the pan is fully directional.
         */
        const val ROUTE_PAN_FADE_METERS = 15.0
    }
}

/**
 * Bridges the location and sensor layers to the [AudioEngine] for the ONE
 * currently active audio source (a pin or an active route):
 * - volume follows distance (see [ProximityGainCalculator]);
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
 *   running; UI sliders change the source's stored gain, which arrives
 *   through the flow — no competing direct engine calls.
 * - A null source (deactivation) silences and centers the engine, resetting
 *   both state machines. Note: this is about ACTIVATION, not about which
 *   editor sheet is open — closing a sheet of an active source keeps the
 *   sound playing.
 * - A source switch resets both state machines: no loudness or stereo memory
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
     * @param activePin stream of the active source's audio inputs (a pin or
     *   a route; null = deactivated — the engine is silenced)
     * @param heading compass heading of the user in [0, 360), null = sensor
     *   unavailable (mono degradation)
     * @param headphones true when a headphone-class output is connected;
     *   false = mono degradation
     */
    suspend fun run(
        activePin: Flow<PositionedSourceAudio?>,
        heading: Flow<Double?>,
        headphones: Flow<Boolean>,
    ) = coroutineScope {
        var latestPin: PositionedSourceAudio? = null
        var latestHeading: Double? = null
        var latestHeadphones: Boolean = false

        var currentPinId: String? = null
        var lastWriteMillis: Long = Long.MIN_VALUE
        var lastGain: Double? = null
        var lastPan: Double? = null

        /**
         * One processing tick: every source emission lands here; the
         * timestamp throttle decides whether it reaches the engine.
         */
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

            val sourceChanged = active?.sourceId != currentPinId
            if (sourceChanged) {
                currentPinId = active?.sourceId
                gainCalculator.reset()
                panCalculator.reset()
                lastGain = null
                lastPan = null
            }

            if (active == null) {
                writeGain(0.0, lastGain) { lastGain = it }
                writePan(0.0, lastPan) { lastPan = it }
                trace("no active source -> volume 0, pan 0")
                return
            }

            var effectivePan = if (onHeadphones) {
                panCalculator.update(active.bearingDegrees, headingDegrees)
            } else {
                panCalculator.update(null, null) // speakers: mono degradation
            }
            // Route corridor: near the line (bearing to the projected point
            // degenerates there) collapse the stereo image to the center.
            // Pins have no fade (null) and keep the exact directional pan.
            active.panFadeMeters?.let { fadeMeters ->
                val distance = active.distanceMeters ?: 0.0
                effectivePan *= (distance / fadeMeters).coerceIn(0.0, 1.0)
            }
            val gain = gainCalculator.update(active.distanceMeters, active.userGain, active.audibleRadiusMeters)

            writeGain(gain, lastGain) { lastGain = it }
            writePan(effectivePan, lastPan) { lastPan = it }
            trace(
                "source=${active.sourceId} " +
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
