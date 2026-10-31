package com.sonarmap.app.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Whether the screen is interactive (on), as a Flow of booleans.
 *
 * Why the screen state matters: it is the cheapest reliable proxy for the
 * phone's pose. Screen on ~= held in hand — the compass azimuth means
 * something. Screen off ~= stowed in a pocket — the compass points at the
 * lining and the audio layer must switch to the GPS course (or degrade).
 *
 * Implementation: the initial value comes from PowerManager.isInteractive,
 * then ACTION_SCREEN_ON/OFF broadcast updates keep the flow current. Both
 * are protected system broadcasts, so a NOT_EXPORTED context-registered
 * receiver still receives them anywhere in the process — no additional
 * permissions (the power broadcasts are always deliverable), the receiver
 * lives exactly as long as the flow is collected and is unregistered on
 * cancellation like every other sensor stream here.
 *
 * Threading: broadcast callbacks arrive on the main looper of the
 * collector's context; the payload is a boolean, nothing to offload.
 */
class ScreenStateMonitor(private val context: Context) {

    fun isScreenOn(): Flow<Boolean> = callbackFlow {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        trySend(powerManager.isInteractive)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> trySend(true)
                    Intent.ACTION_SCREEN_OFF -> trySend(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate()
}
