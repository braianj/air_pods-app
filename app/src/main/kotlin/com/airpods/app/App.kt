package com.airpods.app

import android.app.Application
import com.airpods.app.notification.BatteryNotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BatteryNotificationManager.ensureChannel(this)
    }
}
