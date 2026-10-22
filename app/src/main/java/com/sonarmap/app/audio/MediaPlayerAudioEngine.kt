package com.sonarmap.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import com.sonarmap.app.domain.audio.AudioEngine
import com.sonarmap.app.domain.audio.AudioEngineListener
import com.sonarmap.app.domain.audio.AudioErrorKind
import com.sonarmap.app.domain.audio.AudioPlaybackState
import com.sonarmap.app.domain.audio.panGains
import java.io.IOException

/**
 * Standard-Android implementation of [AudioEngine] over MediaPlayer +
 * AudioManager focus. Chosen for the MVP: no native engine, no streaming,
 * no background service — playback lives inside the screen lifetime.
 *
 * Focus policy (AudioManager guidelines):
 * - permanent loss            -> pause + [AudioErrorKind.AudioFocusLost];
 * - transient loss            -> pause, auto-resume after the transient ends
 *     if the user was listening;
 * - transient loss "can duck" -> keep playing at 25% volume, restore after.
 *
 * Threading: the instance is meant to be driven from the UI thread (it lives
 * with the screen composition). Preparation is asynchronous via
 * prepareAsync(), so nothing blocks the caller; MediaPlayer invokes its
 * callbacks on the creating thread's looper.
 */
class MediaPlayerAudioEngine(
    private val context: Context,
) : AudioEngine {

    private var listener: AudioEngineListener? = null
    private var player: MediaPlayer? = null
    private var sourceUri: String? = null
    private var state: AudioPlaybackState = AudioPlaybackState.Idle
    private var baseGain = 1.0
    private var pan = 0.0
    private var ducked = false
    private var released = false
    /** User pressed play() while preparing; honored as soon as READY arrives. */
    private var pendingStart = false
    /** Playback was interrupted by a transient focus loss; resume on regain. */
    private var resumeOnFocusGain = false

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun setListener(listener: AudioEngineListener?) {
        this.listener = listener
    }

    override fun prepare(sourceUri: String) {
        if (released || sourceUri.isBlank()) return
        this.sourceUri = sourceUri
        releasePlayer()
        transition(AudioPlaybackState.Preparing)

        val newPlayer = MediaPlayer()
        player = newPlayer

        try {
            newPlayer.setAudioAttributes(attributes)
            // Loop is the SonarMap semantic: a pin's sound is an ambience the
            // user walks toward/away from — it must not end while the screen
            // is alive. Explicit stop/pause still work as usual on top.
            newPlayer.isLooping = true
            // Resolve the locator; throws when the file is gone or unreadable.
            newPlayer.setDataSource(context, Uri.parse(sourceUri))
        } catch (e: SecurityException) {
            failAndDiscardPlayer(newPlayer, AudioErrorKind.UriUnavailable)
            return
        } catch (e: IOException) {
            failAndDiscardPlayer(newPlayer, AudioErrorKind.UriUnavailable)
            return
        } catch (e: IllegalStateException) {
            failAndDiscardPlayer(newPlayer, AudioErrorKind.UriUnavailable)
            return
        } catch (e: IllegalArgumentException) {
            failAndDiscardPlayer(newPlayer, AudioErrorKind.UriUnavailable)
            return
        }

        newPlayer.setOnPreparedListener { mp ->
            if (released || mp !== player) {
                mp.release()
                return@setOnPreparedListener
            }
            transition(AudioPlaybackState.Ready)
            if (pendingStart) {
                pendingStart = false
                beginPlay()
            }
        }
        newPlayer.setOnCompletionListener {
            if (!released && it === player) {
                transition(AudioPlaybackState.Ready)
            }
        }
        newPlayer.setOnErrorListener { mp, _, extra ->
            if (released || mp !== player) return@setOnErrorListener true
            val kind =
                if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED ||
                    extra == MediaPlayer.MEDIA_ERROR_MALFORMED
                ) {
                    AudioErrorKind.UnsupportedFormat
                } else {
                    AudioErrorKind.UriUnavailable
                }
            discardPlayer(mp)
            transition(AudioPlaybackState.Idle)
            fail(kind)
            true // consumed: our listener handles the failure
        }
        newPlayer.prepareAsync()
    }

    override fun play() {
        if (released) return
        when (state) {
            AudioPlaybackState.Ready -> beginPlay()
            AudioPlaybackState.Paused -> beginPlay()
            AudioPlaybackState.Preparing -> pendingStart = true
            AudioPlaybackState.Idle -> sourceUri?.let { prepare(it) }
            AudioPlaybackState.Playing -> Unit
        }
    }

    override fun pause() {
        if (released || state != AudioPlaybackState.Playing) return
        player?.pause()
        resumeOnFocusGain = false
        transition(AudioPlaybackState.Paused)
    }

    override fun stop() {
        if (released) return
        // Rewind-to-zero flavor of stop: the prepared source stays around, so
        // the next play() starts over without re-reading the locator — and we
        // avoid MediaPlayer's stopped-state re-prepare dance entirely.
        if (state == AudioPlaybackState.Playing) player?.pause()
        player?.seekTo(0)
        pendingStart = false
        resumeOnFocusGain = false
        transition(AudioPlaybackState.Ready)
    }

    override fun setVolume(gain: Double) {
        baseGain = gain.coerceIn(0.0, 1.0)
        player?.let { applyEffectiveAudio(it) }
    }

    override fun setPan(pan: Double) {
        this.pan = pan.coerceIn(-1.0, 1.0)
        player?.let { applyEffectiveAudio(it) }
    }

    override fun release() {
        if (released) return
        released = true
        releasePlayer()
        audioManager.abandonAudioFocusRequest(focusRequest)
        listener = null
        transition(AudioPlaybackState.Idle)
    }

    // --- internals -----------------------------------------------------------

    private val attributes: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (released) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (state == AudioPlaybackState.Playing) player?.pause()
                transition(AudioPlaybackState.Paused)
                fail(AudioErrorKind.AudioFocusLost)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (state == AudioPlaybackState.Playing) {
                    player?.pause()
                    resumeOnFocusGain = true
                    transition(AudioPlaybackState.Paused)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (state == AudioPlaybackState.Playing) {
                    ducked = true
                    player?.let { applyEffectiveAudio(it) }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) {
                    ducked = false
                    player?.let { applyEffectiveAudio(it) }
                } else if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    beginPlay()
                }
            }
        }
    }

    private val focusRequest: AudioFocusRequest = AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        // We duck ourselves; the system must not pause us for CAN_DUCK losses.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private fun beginPlay() {
        val mp = player ?: return
        val gained =
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!gained) {
            fail(AudioErrorKind.AudioFocusLost)
            return
        }
        applyEffectiveAudio(mp)
        mp.start()
        transition(AudioPlaybackState.Playing)
    }

    /**
     * Effective per-channel volume = base gain shaped by the equal-power
     * pan law (`panGains`): total energy L²+R² stays constant across the
     * whole pan sweep, so a centered (or behind) source plays at the same
     * perceived loudness as a side-panned one. Reduced to 25% when ducked.
     */
    private fun applyEffectiveAudio(mp: MediaPlayer) {
        val g = baseGain * if (ducked) 0.25 else 1.0
        val (leftLaw, rightLaw) = panGains(pan)
        mp.setVolume((g * leftLaw).toFloat(), (g * rightLaw).toFloat())
    }

    private fun releasePlayer() {
        player?.let { mp ->
            mp.setOnCompletionListener(null)
            mp.setOnPreparedListener(null)
            mp.setOnErrorListener(null)
            mp.release()
        }
        player = null
    }

    private fun discardPlayer(mp: MediaPlayer) {
        mp.setOnCompletionListener(null)
        mp.setOnPreparedListener(null)
        mp.setOnErrorListener(null)
        mp.release()
        if (player === mp) player = null
    }

    private fun failAndDiscardPlayer(mp: MediaPlayer, kind: AudioErrorKind) {
        discardPlayer(mp)
        transition(AudioPlaybackState.Idle)
        fail(kind)
    }

    private fun transition(to: AudioPlaybackState) {
        state = to
        listener?.onStateChanged(to)
    }

    private fun fail(kind: AudioErrorKind) {
        listener?.onError(kind)
    }
}
