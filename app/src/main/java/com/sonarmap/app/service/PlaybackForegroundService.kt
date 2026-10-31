package com.sonarmap.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sonarmap.app.MainActivity
import com.sonarmap.app.R
import com.sonarmap.app.audio.AudioOutputMonitor
import com.sonarmap.app.audio.LocationAudioController
import com.sonarmap.app.audio.MediaPlayerAudioEngine
import com.sonarmap.app.audio.positionedSourceFor
import com.sonarmap.app.data.MusicPinRepositoryProvider
import com.sonarmap.app.data.RouteRepositoryProvider
import com.sonarmap.app.domain.audio.AudioEngineListener
import com.sonarmap.app.domain.audio.AudioErrorKind
import com.sonarmap.app.domain.audio.AudioPlaybackState
import com.sonarmap.app.domain.location.LocationUpdate
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.model.Route
import com.sonarmap.app.domain.repository.MusicPinRepository
import com.sonarmap.app.domain.repository.RouteRepository
import com.sonarmap.app.location.FusedLocationRepository
import com.sonarmap.app.sensor.HeadingSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service for continuous walking-audio playback ("Фоновый
 * режим"): keeps geolocation updates and the spatial audio engine alive
 * while the user walks with the app minimized.
 *
 * Ownership: while the service runs, IT is the single writer of the audio
 * stream — the map screen defers (pauses its own engine and does not run
 * its controller loop; see MapScreen). The service runs its own
 * FusedLocationRepository + HeadingSensor + AudioOutputMonitor instances
 * (the smoother state is per-flow) and releases everything on the stop
 * path (see [stopPlayback]):
 * - location: the updates() flow collection is cancelled first — the
 *   callbackFlow's awaitClose removes the FusedLocationProvider callback;
 * - audio: engine.release() (MediaPlayer teardown + abandoned audio focus);
 * - notification: stopForeground(REMOVE).
 *
 * foregroundServiceType = location | mediaPlayback (manifest): both work
 * types are genuinely happening. NO ACCESS_BACKGROUND_LOCATION is
 * requested: the service is always started from the visible UI, so
 * while-in-use authorization is sufficient for the whole session (a
 * location-type foreground service started in the foreground may keep
 * collecting fixes while backgrounded without background location
 * permission).
 *
 * Stop semantics (every path converges on [onDestroy]):
 * - explicit "Стоп" on the notification / toggle off -> [ACTION_STOP];
 * - the active source was deactivated               -> observed, stopSelf;
 * - permission revoked / location turned off        -> observed, stopSelf;
 * - task removal keeps a media FGS alive by design — the notification's
 *   "Стоп" stays the user's off-switch; a system stop is not resurrected
 *   as an audible surprise.
 */
class PlaybackForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var engine: MediaPlayerAudioEngine? = null
    private var outputMonitor: AudioOutputMonitor? = null
    private var playbackJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BackgroundModeController.setRunning(true)
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                enterForeground(buildNotification(null))
                startPlayback()
            }
        }
        // On a system restart after process death the in-memory ACTIVE
        // SELECTION is deliberately gone (not persisted): the loop sees
        // no active source and self-stops in its first emission — the
        // notification blips at most once and no audio ever plays
        // unattended. Playback resumes only from the UI.
        return START_REDELIVER_INTENT
    }

    // --- playback ------------------------------------------------------------

    /**
     * The service-side audio loop: owns its own engine (the screen's one
     * is paused while the service runs), its own location subscription and
     * its own sensors. The active source (pin/route + live property edits)
     * comes from the Room-backed repositories, so slider changes made on
     * the screen arrive exactly like in screen-owned playback.
     */
    private fun startPlayback() {
        if (playbackJob != null) return // already running (repeated starts)
        playbackJob = serviceScope.launch {
            val appContext = applicationContext
            val engine = MediaPlayerAudioEngine(this@PlaybackForegroundService).also {
                this@PlaybackForegroundService.engine = it
            }
            // Distance -> gain needs fixes; own subscription (the screen's
            // is screen-lifecycle and this repository's smoother state is
            // per-subscription — two instances must not share it).
            val locationRepository = FusedLocationRepository(appContext)
            val headingSensor = HeadingSensor(appContext)
            val outputMonitor = AudioOutputMonitor(appContext).also { outputMonitor = it }
            outputMonitor.start()

            engine.setListener(object : AudioEngineListener {
                override fun onStateChanged(state: AudioPlaybackState) = Unit
                override fun onError(kind: AudioErrorKind) {
                    Log.w(TAG, "playback error: $kind")
                }
            })

            val pinRepository = MusicPinRepositoryProvider.repository(appContext)
            val routeRepository = RouteRepositoryProvider.repository(appContext)

            // Latest fix feeds the source builder; the single-threaded Main
            // scope makes the captured var race-free across collectors.
            var fix: GeoPoint? = null
            var fatalLocation = false

            fun activeRouteOf(routes: List<Route>, activeId: String?) =
                routes.firstOrNull { it.id == activeId }

            fun activePinOf(pins: List<MusicPin>, activeId: String?) =
                pins.firstOrNull { it.id == activeId }

            fun currentSource() = positionedSourceFor(
                fix = fix,
                activeRoute = activeRouteOf(routeRepository.routes.value, routeRepository.activeRouteId.value),
                activePin = activePinOf(pinRepository.pins.value, pinRepository.activePinId.value),
            )

            // The one active source, rebuilt when pin/route data or the
            // active selection changes (property edits arrive live through
            // the same Room flows — slider changes included).
            val activeSource = combine(
                pinRepository.pins,
                pinRepository.activePinId,
                routeRepository.routes,
                routeRepository.activeRouteId,
            ) { pins, pinId, routes, routeId ->
                positionedSourceFor(fix, activeRouteOf(routes, routeId), activePinOf(pins, pinId))
            }

            // The SINGLE location subscription of the session: fixes refresh
            // `fix` and re-emit the recomputed source; fatal diagnostics
            // (revoked permission, provider off) end the session — a
            // background mode without location has nothing to compute.
            val spatialInput = combine(
                locationRepository.updates(),
                activeSource,
            ) { update, _ ->
                when (update) {
                    is LocationUpdate.Fix -> fix = update.location.position
                    LocationUpdate.PermissionDenied, LocationUpdate.ProviderDisabled -> {
                        Log.w(TAG, "location unusable in background ($update) — stopping")
                        fatalLocation = true
                    }
                    LocationUpdate.WaitingForFix -> Unit
                }
                currentSource() to fatalLocation
            }

            // Auto-play per active source file; nothing active -> pause and
            // fall back to the bare "фон включён" notification.
            val playingSource = combine(
                pinRepository.pins,
                pinRepository.activePinId,
                routeRepository.routes,
                routeRepository.activeRouteId,
            ) { pins, pinId, routes, routeId ->
                val route = activeRouteOf(routes, routeId)
                val pin = activePinOf(pins, pinId)
                when {
                    route?.audioAssetUri?.isNotBlank() == true -> route.id to route.audioAssetUri
                    pin?.audioAssetUri?.isNotBlank() == true -> pin.id to pin.audioAssetUri
                    else -> null
                }
            }.distinctUntilChanged()

            launch {
                playingSource.collect { key ->
                    val t = sourceTitle(pinRepository, routeRepository)
                    Log.d(TAG, "playingSource key=$key title=$t")
                    val (_, uri) = key ?: run {
                        engine.pause()
                        updateNotification(null)
                        return@collect
                    }
                    engine.prepare(uri)
                    engine.play()
                    updateNotification(t)
                }
            }

            // The spatial loop: gain follows distance, pan follows
            // bearing − heading (mono-degraded without headphones).
            launch {
                LocationAudioController(engine, trace = { Log.d("SonarGainBg", it) }).run(
                    activePin = spatialInput.map { it.first },
                    heading = headingSensor.heading(),
                    headphones = outputMonitor.headphonesConnected,
                )
            }

            // Fatal location state ends the session entirely.
            launch {
                spatialInput.collect { (_, fatal) ->
                    if (fatal) {
                        stopPlayback()
                        stopSelf()
                    }
                }
            }

            // Nothing audible -> nothing to hold foreground resources for.
            launch {
                activeSource.map { it != null }.distinctUntilChanged().collect { hasSource ->
                    if (!hasSource) {
                        Log.i(TAG, "no active source — stopping")
                        stopPlayback()
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun sourceTitle(
        pinRepository: MusicPinRepository,
        routeRepository: RouteRepository,
    ): String? {
        val pin = pinRepository.pins.value.firstOrNull { it.id == pinRepository.activePinId.value }
        if (pin != null) return pin.title
        val route = routeRepository.routes.value.firstOrNull { it.id == routeRepository.activeRouteId.value }
        return route?.title
    }

    // --- stopping ------------------------------------------------------------

    /**
     * Releases EVERY resource the session holds. Idempotent; reached from
     * the explicit stop action and from onDestroy — ANY stop path (the
     * notification action, source loss, or the system stopping the
     * service) ends here.
     */
    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        // Engine first: silence is the user-visible effect; the location
        // teardown (removeLocationUpdates inside the flow's awaitClose)
        // runs as the cancelled job completes.
        engine?.release()
        engine = null
        outputMonitor?.stop()
        outputMonitor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopPlayback()
        serviceScope.cancel()
        BackgroundModeController.setRunning(false)
        super.onDestroy()
    }

    // --- notification --------------------------------------------------------

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Both work types are declared in the manifest; passing them
            // explicitly is required on API 34+.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(sourceTitle: String?) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(sourceTitle))
    }

    private fun buildNotification(sourceTitle: String?): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fgs_pin)
            .setContentTitle(if (sourceTitle != null) "Играет: $sourceTitle" else "SonarMap: фоновый режим")
            .setContentText("Звук вокруг метки или маршрута активен")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Стоп", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновое воспроизведение",
            NotificationManager.IMPORTANCE_LOW, // visible, silent — no heads-up blare
        )
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.sonarmap.app.action.STOP_PLAYBACK"
    }
}
