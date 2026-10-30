package com.sonarmap.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The hearing-area ("область слышимости") radius scale — non-uniform by
 * design: [TICK_METERS] stops sit at equally spaced positions on the track,
 * and the value between neighboring stops interpolates linearly, so every
 * interval is a smooth transition, and the default 150 m lands exactly on
 * its own tick.
 */
object HearingAreaScale {

    /** Tick values in meters: 10, 20, 30, 50, 100, 150, 200, 300. */
    val TICK_METERS = listOf(10, 20, 30, 50, 100, 150, 200, 300)

    /** Slider position (0..1) -> meters, linear between neighboring ticks. */
    fun metersForPosition(position: Float): Double {
        val scaled = position.coerceIn(0f, 1f) * (TICK_METERS.size - 1)
        val index = scaled.toInt().coerceIn(0, TICK_METERS.size - 2)
        val fraction = (scaled - index).coerceIn(0f, 1f)
        val low = TICK_METERS[index].toDouble()
        val high = TICK_METERS[index + 1].toDouble()
        return low + fraction * (high - low)
    }

    /** Meters -> slider position (0..1); the exact inverse of the mapping. */
    fun positionForMeters(meters: Double): Float {
        if (meters <= TICK_METERS.first()) return 0f
        if (meters >= TICK_METERS.last()) return 1f
        for (i in 0 until TICK_METERS.size - 1) {
            val low = TICK_METERS[i]
            val high = TICK_METERS[i + 1]
            if (meters <= high) {
                val fraction = (meters - low) / (high - low)
                return ((i + fraction) / (TICK_METERS.size - 1)).toFloat()
            }
        }
        return 1f
    }
}

/**
 * "Область слышимости: N м" over a continuous (steps-free) slider whose
 * track is the [HearingAreaScale]: tick values at equal intervals, smooth
 * interpolation between them, and a tick-mark row under the track aligned
 * with the stops.
 *
 * @property radiusMeters the stored hearing-area radius (source of truth);
 *   a value outside the 10..300 scale pins at the nearest end
 * @property onRadiusChange fires on every drag with the interpolated meters
 */
@Composable
fun HearingAreaRadiusSlider(
    radiusMeters: Double,
    onRadiusChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = "Область слышимости: ${radiusMeters.roundToInt()} м",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = HearingAreaScale.positionForMeters(radiusMeters),
            onValueChange = { onRadiusChange(HearingAreaScale.metersForPosition(it)) },
            valueRange = 0f..1f,
        )
        TickRow(ticks = HearingAreaScale.TICK_METERS.size)
    }
}

/**
 * Small marks under the track, one per [HearingAreaScale.TICK_METERS] stop.
 * The M3 slider thumb is inset by half its width at the track ends, so the
 * marks use the same 10 dp inset to line up.
 */
@Composable
private fun TickRow(ticks: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val inset = 10.dp // half of the M3 slider thumb width
        val span = maxWidth - inset * 2
        (0 until ticks).forEach { i ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = inset
                            + span * (i.toFloat() / (ticks - 1).toFloat())
                            - 0.5.dp, // center a 1.dp-wide mark on the stop
                    )
                    .size(width = 1.dp, height = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(0.5.dp),
                    ),
            )
        }
    }
}
