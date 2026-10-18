package com.sonarmap.app.domain.audio

/**
 * Contract for the audio layer. Pure Kotlin — no Android, Compose, ViewModel,
 * or map-SDK types here. The first implementation uses Android MediaPlayer
 * (com.sonarmap.app.audio.MediaPlayerAudioEngine); FMOD/Resonance later must
 * fit the same shape.
 *
 * Contract:
 * - [prepare] is non-blocking: implementations must prepare asynchronously
 *   and report readiness through [AudioEngineListener.onStateChanged].
 * - [setVolume] applies to everything played afterwards immediately; the
 *   value is retained across pause/stop.
 * - All methods are idempotent after [release]; calling anything on a
 *   released engine is a no-op.
 */
interface AudioEngine {

    /** Receives state transitions and failures; may be reset to null. */
    fun setListener(listener: AudioEngineListener?)

    /**
     * Starts async preparation of [sourceUri] (a content:// uri from SAF).
     * Transitions IDLE -> PREPARING -> READY (or emits an error).
     * Cancel-safe: preparing a second time replaces the previous source.
     */
    fun prepare(sourceUri: String)

    /** Ready/Stopped/Paused -> Playing; no-op in other states. */
    fun play()

    /** Playing -> Paused; no-op otherwise. */
    fun pause()

    /**
     * Halts playback and discards the decode position: the next [play]
     * starts over from the beginning of the source.
     */
    fun stop()

    /** Linear gain 0..1; values outside are clamped. */
    fun setVolume(gain: Double)

    /**
     * Stereo position −1 (fully left) .. +1 (fully right); 0 = center.
     * Values outside are clamped. Applied together with the current volume
     * to playing audio immediately; retained across pause/stop. Engines
     * without a spatial stage may implement this as a no-op, which is the
     * required degradation for missing sensors or missing headphones.
     */
    fun setPan(pan: Double)

    /** Frees all player/audio resources. The engine cannot be reused. */
    fun release()
}

sealed class AudioPlaybackState {
    /** Nothing prepared yet, or the previous source was cleared. */
    data object Idle : AudioPlaybackState()

    /** Async preparation in progress; play is not available yet. */
    data object Preparing : AudioPlaybackState()

    /** Prepared, halted; play() starts from the beginning. */
    data object Ready : AudioPlaybackState()

    data object Playing : AudioPlaybackState()

    /** Held position retained; play() continues from it. */
    data object Paused : AudioPlaybackState()
}

/** Stable error taxonomy for UI mapping — the reason text lives in the app layer. */
enum class AudioErrorKind {
    /** Locator unavailable: file missing, permission to read lost, canceled. */
    UriUnavailable,

    /** The source opened but its data is not decodable as audio. */
    UnsupportedFormat,

    /** Another app took the focus and playback was stopped. */
    AudioFocusLost,
}

interface AudioEngineListener {
    fun onStateChanged(state: AudioPlaybackState)
    fun onError(kind: AudioErrorKind)
}
