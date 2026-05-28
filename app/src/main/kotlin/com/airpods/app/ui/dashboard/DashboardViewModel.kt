package com.airpods.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.airpods.app.ble.AirPodsBleService
import com.airpods.app.ble.AirPodsRepository
import com.airpods.app.ble.BootReceiver

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    val state = AirPodsRepository.state

    fun start() {
        AirPodsBleService.start(getApplication())
        prefs().edit().putBoolean(BootReceiver.KEY_AUTOSTART, true).apply()
    }

    fun stop() {
        AirPodsBleService.stop(getApplication())
        prefs().edit().putBoolean(BootReceiver.KEY_AUTOSTART, false).apply()
    }

    fun isRunningRequested(): Boolean =
        prefs().getBoolean(BootReceiver.KEY_AUTOSTART, false)

    fun clearCachedSnapshot() {
        AirPodsRepository.clearPersisted()
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(BootReceiver.PREFS, 0)
}
