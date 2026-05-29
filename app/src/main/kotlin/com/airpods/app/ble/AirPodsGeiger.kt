package com.airpods.app.ble

import android.media.AudioManager
import android.media.ToneGenerator
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Find my AirPods" Geiger counter. The phone beeps faster the closer the
 * user gets to their AirPods (according to the last RSSI from the BLE
 * scanner). Apple's chirp-the-case feature is not accessible to
 * third-party Android apps without root, so this drives the search from
 * the phone side instead.
 */
object AirPodsGeiger {

    private const val TAG = "Geiger"

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var job: Job? = null
    private var tone: ToneGenerator? = null

    fun start(scope: CoroutineScope) {
        if (_active.value) return
        AppLogger.i(TAG, "geiger start")
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        }.getOrNull()
        _active.value = true
        job = scope.launch(Dispatchers.Default) {
            while (isActive && _active.value) {
                val rssi = AirPodsRepository.state.value.snapshot?.rssi
                val delayMs = intervalForRssi(rssi)
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                delay(delayMs)
            }
        }
    }

    fun stop() {
        if (!_active.value) return
        AppLogger.i(TAG, "geiger stop")
        _active.value = false
        job?.cancel(); job = null
        runCatching { tone?.release() }
        tone = null
    }

    /**
     * Map signal strength to ping interval. Strong signal → fast pings.
     * Anything weaker than -85 dBm = 2 seconds between beeps (you're far);
     * stronger than -45 dBm = 200 ms (you're on top of them).
     */
    private fun intervalForRssi(rssi: Int?): Long {
        val r = rssi ?: return 2_000L
        return when {
            r >= -45 -> 200L
            r >= -55 -> 400L
            r >= -65 -> 700L
            r >= -75 -> 1_200L
            r >= -85 -> 1_700L
            else -> 2_200L
        }
    }
}
