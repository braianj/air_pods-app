package com.airpods.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.airpods.app.ble.AirPodsBleService
import com.airpods.app.ble.AirPodsRepository
import com.airpods.app.ble.BootReceiver

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    val state = AirPodsRepository.state

    fun start() {
        // Opt-in is sticky: once the user taps Buscar, the receiver will
        // auto-resume on every future AirPods connect. Clear any active
        // pause window so the user doesn't have to wait 30 seconds.
        prefs().edit()
            .putBoolean(BootReceiver.KEY_AUTOSTART, true)
            .putLong(BootReceiver.KEY_PAUSE_UNTIL, 0L)
            .apply()
        AirPodsBleService.start(getApplication())
    }

    fun stop() {
        // Stop the service AND pause auto-restart for a short window so the
        // BluetoothBroadcastReceiver doesn't immediately relaunch us via a
        // residual HFP / A2DP profile-state change. KEY_AUTOSTART stays true
        // so the next genuine reconnection (after the pause) auto-resumes.
        val pauseUntil = System.currentTimeMillis() + BootReceiver.PAUSE_AFTER_STOP_MS
        prefs().edit().putLong(BootReceiver.KEY_PAUSE_UNTIL, pauseUntil).apply()
        AirPodsBleService.stop(getApplication())
    }

    fun isRunningRequested(): Boolean {
        val opted = prefs().getBoolean(BootReceiver.KEY_AUTOSTART, false)
        val paused = System.currentTimeMillis() <
            prefs().getLong(BootReceiver.KEY_PAUSE_UNTIL, 0L)
        return opted && !paused
    }

    fun clearCachedSnapshot() {
        AirPodsRepository.clearPersisted()
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(BootReceiver.PREFS, 0)
}
