package com.airpods.app

import android.app.Application
import com.airpods.app.notification.BatteryNotificationManager
import com.airpods.app.ui.theme.ThemePrefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BatteryNotificationManager.ensureChannel(this)
        ThemePrefs.init(this)
    }
}
