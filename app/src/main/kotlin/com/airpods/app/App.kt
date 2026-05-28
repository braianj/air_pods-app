package com.airpods.app

import android.app.Application
import com.airpods.app.ble.AirPodsRepository
import com.airpods.app.ble.BluetoothBroadcastReceiver
import com.airpods.app.notification.BatteryNotificationManager
import com.airpods.app.ui.theme.ThemePrefs
import com.airpods.app.util.AppLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("App", "onCreate")
        BatteryNotificationManager.ensureChannel(this)
        ThemePrefs.init(this)
        AirPodsRepository.init(this)
        // Register the Bluetooth broadcast receiver application-wide so we
        // catch BATTERY_LEVEL_CHANGED and profile state changes even when
        // the foreground service isn't running.
        BluetoothBroadcastReceiver.register(this)
    }
}
