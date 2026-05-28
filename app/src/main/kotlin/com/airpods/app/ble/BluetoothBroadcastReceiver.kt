package com.airpods.app.ble

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.airpods.app.util.AppLogger

/**
 * Listens for the system's Bluetooth battery / connection broadcasts.
 *
 * - ACTION_BATTERY_LEVEL_CHANGED is dispatched by the system Bluetooth
 *   stack when the connected device reports its battery via HFP
 *   ([+IPHONEACCEV]) or AVRCP. The action constant is @hide but the
 *   string is stable, so a regular app can register for it.
 *
 * - ACL_CONNECTED / ACL_DISCONNECTED gives us realtime audio connect
 *   state instead of polling A2DP every 20 s.
 */
object BluetoothBroadcastReceiver : BroadcastReceiver() {

    private const val TAG = "BtRx"
    private const val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL =
        "android.bluetooth.device.extra.BATTERY_LEVEL"

    @Volatile
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(
                this,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.applicationContext.registerReceiver(this, filter)
        }
        registered = true
        AppLogger.i(TAG, "registered for battery + acl broadcasts")
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.applicationContext.unregisterReceiver(this) }
        registered = false
        AppLogger.i(TAG, "unregistered")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = extractDevice(intent)
        val name = device?.let { safeName(it) } ?: "?"
        when (action) {
            ACTION_BATTERY_LEVEL_CHANGED -> {
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                AppLogger.i(TAG, "BATTERY_LEVEL_CHANGED device='$name' level=$level")
                if (level in 0..100 && isAirPods(name)) {
                    publishSyntheticSnapshot(level)
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                AppLogger.i(TAG, "ACL_CONNECTED device='$name'")
                if (isAirPods(name)) {
                    AirPodsRepository.setAudioConnected(name)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                AppLogger.i(TAG, "ACL_DISCONNECTED device='$name'")
                if (isAirPods(name)) {
                    AirPodsRepository.setAudioConnected(null)
                }
            }
        }
    }

    private fun publishSyntheticSnapshot(level: Int) {
        val current = AirPodsRepository.state.value.snapshot
        val snap = if (current != null) {
            // Refresh both pods with the same level (HFP doesn't distinguish L/R)
            current.copy(
                leftPct = level,
                rightPct = level,
                timestampMs = System.currentTimeMillis()
            )
        } else {
            AirPodsSnapshot(
                model = AirPodsModel.AIRPODS_PRO_2,
                leftPct = level,
                rightPct = level,
                casePct = null,
                leftCharging = false,
                rightCharging = false,
                caseCharging = false,
                inCase = false,
                rssi = -50,
                timestampMs = System.currentTimeMillis()
            )
        }
        AppLogger.i(TAG, "publishing snapshot from BATTERY_LEVEL_CHANGED: $level%")
        AirPodsRepository.onSnapshot(snap)
    }

    @Suppress("DEPRECATION")
    private fun extractDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) { null }

    private fun isAirPods(name: String?): Boolean {
        if (name == null) return false
        return name.contains("AirPods", ignoreCase = true) ||
            name.contains("Beats", ignoreCase = true)
    }
}
