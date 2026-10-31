package com.sonarmap.app.location

import com.sonarmap.app.domain.location.HeadingFusion
import com.sonarmap.app.domain.location.LocationUpdate
import com.sonarmap.app.sensor.HeadingSensor
import com.sonarmap.app.sensor.ScreenStateMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The fused heading that both audio consumers (map screen and foreground
 * service) feed into LocationAudioController instead of the bare compass:
 * GPS course while the user actually walks, the held-phone compass while
 * standing with the screen on, and null (pan -> center) otherwise. See
 * [HeadingFusion] for the exact priority and the pocket rationale; this
 * class is the streams glue only.
 *
 * Built per consumer exactly like HeadingSensor/AudioOutputMonitor lived
 * before, but the FIX stream is a constructor parameter: screen and service
 * each wire their own (already existing) location subscription in — no new
 * location request is spawned, the course rides fixes that are being pulled
 * anyway, so the battery cost of the fusion is a broadcast receiver.
 *
 * Trace: every decision lands in [trace] (logcat during device checks) as
 * `src=crs|cmp|none hdg=... screen=... spd=... brg=...` — needed to verify
 * on a real device which physics produced the stereo pan.
 */
class FusedHeadingSource(
    private val compass: HeadingSensor,
    private val screen: ScreenStateMonitor,
    private val fusion: HeadingFusion = HeadingFusion(),
    private val trace: (String) -> Unit = {},
) {

    /**
     * @param fixUpdates the consumer's own location stream (raw speed and
     *   bearing ride inside [LocationUpdate.Fix]); diagnostics emits are
     *   treated as "no fix this tick" — the fusion then leans on
     *   compass/screen state (or degrades).
     */
    fun heading(fixUpdates: Flow<LocationUpdate>): Flow<Double?> =
        combine(fixUpdates, compass.heading(), screen.isScreenOn()) { update, compassDeg, screenOn ->
            var speed: Double? = null
            var bearing: Double? = null
            if (update is LocationUpdate.Fix) {
                speed = update.speedMeters
                bearing = update.bearingDegrees
            }
            val decision = fusion.update(speed, bearing, screenOn, compassDeg)
            trace(
                "screen=${if (screenOn) "on" else "off"} " +
                    "spd=${speed?.let { "%.2f".format(it) } ?: "null"} " +
                    "crs=${bearing?.let { "%.1f".format(it) } ?: "null"} " +
                    "cmp=${compassDeg?.let { "%.1f".format(it) } ?: "null"} " +
                    "hdg=${decision.headingDegrees?.let { "%.1f".format(it) } ?: "null"} " +
                    "src=${decision.source}",
            )
            decision.headingDegrees
        }
}
