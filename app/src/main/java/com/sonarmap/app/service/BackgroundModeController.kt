package com.sonarmap.app.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single owner of the explicit "Фоновый режим" toggle's state:
 * - [enabled]: the user's choice, persisted in SharedPreferences so the
 *   preference survives process death (music pins/routes survive via
 *   Room; the toggle should behave the same way);
 * - [running]: whether PlaybackForegroundService is alive right now
 *   (the service itself writes this in onCreate/onDestroy).
 *
 * The two differ while nothing audible exists: the toggle can be ON with
 * no service — the service starts only when an active source (pin or
 * route) appears, and stops itself when it disappears.
 */
object BackgroundModeController {

    private const val PREFS_FILE = "sonarmap.settings"
    private const val KEY_ENABLED = "background_playback_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Restores the persisted toggle; called once from Application.onCreate. */
    fun init(context: Context) {
        preferences(context).let { prefs ->
            _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        }
    }

    /**
     * Toggle handler.
     * - ON: launches the foreground playback service. The service no-ops
     *   (and immediately stops) when no active source exists — the screen
     *   only enables the toggle when a source is active.
     * - OFF: stops the service (its onDestroy releases the engine and
     *   drops location updates), keeps the persisted choice off.
     */
    fun setEnabled(context: Context, value: Boolean) {
        if (value == _enabled.value) return
        _enabled.value = value
        if (value) {
            preferences(context).edit().putBoolean(KEY_ENABLED, true).apply()
            // startForegroundService: the service must call startForeground
            // within a few seconds — it does, in its first onStartCommand.
            context.startForegroundService(startIntent(context))
        } else {
            preferences(context).edit().putBoolean(KEY_ENABLED, false).apply()
            stop(context)
        }
    }

    /** Service re-start after deactivation (no running service, enabled). */
    fun startIfEnabled(context: Context) {
        if (_enabled.value) {
            context.startForegroundService(startIntent(context))
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PlaybackForegroundService::class.java))
    }

    /** Called by the service in onCreate — the service owns its lifecycle. */
    internal fun setRunning(value: Boolean) {
        _running.value = value
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun startIntent(context: Context): Intent =
        Intent(context, PlaybackForegroundService::class.java)
}
