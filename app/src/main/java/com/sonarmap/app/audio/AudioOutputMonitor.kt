package com.sonarmap.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks whether any headphone-class output exists (wired, Bluetooth, USB),
 * because stereo pan is only meaningful on headphones. Speakers force the
 * mono degradation — both channels are audible to both ears anyway.
 *
 * Uses AudioManager.registerAudioDeviceCallback (API 23+): callback fires
 * whenever outputs appear/disappear — no broadcast receivers, covers
 * Bluetooth without profile-proxy plumbing. TYPE_USB_HEADSET constant is
 * API 26+, matching minSdk 26.
 *
 * Lifecycle: [start] from the screen, [stop] on dispose — the callback
 * registration must not outlive the audio session.
 */
class AudioOutputMonitor(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _headphonesConnected = MutableStateFlow(queryHeadphones())
    val headphonesConnected: StateFlow<Boolean> = _headphonesConnected

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun refresh() {
        _headphonesConnected.value = queryHeadphones()
    }

    private fun queryHeadphones(): Boolean =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in HEADPHONE_TYPES }

    private companion object {
        val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
