package com.airpods.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOSTART, false)) {
            AirPodsBleService.start(context)
        }
    }

    companion object {
        const val PREFS = "airpods_prefs"
        const val KEY_AUTOSTART = "autostart_on_boot"
    }
}
