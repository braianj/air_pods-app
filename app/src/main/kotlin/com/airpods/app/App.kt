package com.airpods.app

import android.app.Application
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
    }
}
