package com.airpods.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.airpods.app.util.AppLogger

/**
 * Catch-all listener for the system's Bluetooth-related broadcasts.
 * Maximum verbosity — every interesting action and every interesting
 * extra is logged, so we can diagnose what Android is actually sending
 * for the user's AirPods on Android 16 / Pixel 10 Pro.
 */
object BluetoothBroadcastReceiver : BroadcastReceiver() {

    private const val TAG = "BtRx"

    private const val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL =
        "android.bluetooth.device.extra.BATTERY_LEVEL"

    // A2DP / HFP profile connection state broadcasts
    private const val ACTION_A2DP_CONNECTION_STATE_CHANGED =
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
    private const val ACTION_A2DP_PLAYING_STATE_CHANGED =
        "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"
    private const val ACTION_HEADSET_CONNECTION_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
    private const val ACTION_HEADSET_AUDIO_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED"
    private const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED =
        "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"
    private const val ACTION_HEARING_AID_CONNECTION_STATE_CHANGED =
        "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED"

    private const val EXTRA_CONNECTION_STATE = "android.bluetooth.profile.extra.STATE"
    private const val EXTRA_PREV_CONNECTION_STATE = "android.bluetooth.profile.extra.PREVIOUS_STATE"

    // Apple's vendor-specific HFP AT command. When AirPods connect over
    // Bluetooth Classic (HFP) they push battery via AT+IPHONEACCEV; the
    // Android stack rebroadcasts it as a vendor-specific headset event,
    // but ONLY to receivers registered with the Apple company-id category.
    private const val IPHONEACCEV = "+IPHONEACCEV"

    @Volatile
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            // Battery + ACL connection (the obvious ones)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            // Profile state changes — these are what fire when a device
            // connects via A2DP / HFP / LE Audio / Hearing Aid
            addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED)
            addAction(ACTION_A2DP_PLAYING_STATE_CHANGED)
            addAction(ACTION_HEADSET_CONNECTION_STATE_CHANGED)
            addAction(ACTION_HEADSET_AUDIO_STATE_CHANGED)
            addAction(ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            addAction(ACTION_HEARING_AID_CONNECTION_STATE_CHANGED)
            // Adapter state
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // Device-level
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(this, filter, flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.applicationContext.registerReceiver(this, filter)
        }

        // Separate filter for the vendor-specific HFP event. It MUST carry
        // the Apple company-id category or the system won't deliver it.
        // (Categories can't share the catch-all filter above — they'd then
        // be required on every other action too.)
        val vendorFilter = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            addCategory(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY +
                    "." + BluetoothAssignedNumbers.APPLE
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(this, vendorFilter, flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.applicationContext.registerReceiver(this, vendorFilter)
        }

        registered = true
        AppLogger.i(TAG, "registered for ${filter.countActions()} BT actions + Apple HFP vendor event")
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
        val addr = device?.address ?: "?"
        val shortAction = action.removePrefix("android.bluetooth.")
            .removePrefix("device.action.")
            .removePrefix("action.")
            .removePrefix("profile.action.")
        val extras = describeExtras(intent)
        AppLogger.i(TAG, "rx action=$shortAction device='$name' addr=$addr extras=$extras")

        when (action) {
            ACTION_BATTERY_LEVEL_CHANGED -> {
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                if (level in 0..100 && isAirPods(name)) {
                    AppLogger.i(TAG, "→ publishing AirPods battery $level%")
                    publishSyntheticSnapshot(level)
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (isAirPods(name)) AirPodsRepository.setAudioConnected(name)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (isAirPods(name)) AirPodsRepository.setAudioConnected(null)
            }
            ACTION_A2DP_CONNECTION_STATE_CHANGED,
            ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> {
                val newState = intent.getIntExtra(EXTRA_CONNECTION_STATE, -1)
                if (isAirPods(name)) {
                    if (newState == 2) AirPodsRepository.setAudioConnected(name)
                    else if (newState == 0) AirPodsRepository.setAudioConnected(null)
                }
            }
            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                handleVendorEvent(intent, name)
            }
        }
    }

    private fun handleVendorEvent(intent: Intent, name: String) {
        val cmd = intent.getStringExtra(
            BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD
        )
        val args = intent.getSerializableExtra(
            BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS
        ) as? Array<*>
        val rendered = args?.joinToString(",") { it.toString() } ?: "null"
        AppLogger.i(TAG, "vendorEvent cmd=$cmd device='$name' args=[$rendered]")

        if (cmd != IPHONEACCEV) return
        if (args == null || args.isEmpty()) return

        // Format: args[0] = number of key/value pairs, then key,value,...
        // key 1 = battery (0..9, real% = (v+1)*10), key 2 = charging (1 = yes).
        val numPairs = args[0].toIntSafe() ?: return
        var battery: Int? = null
        var charging = false
        for (i in 0 until numPairs) {
            val keyIdx = 1 + i * 2
            val valIdx = keyIdx + 1
            if (valIdx >= args.size) break
            val key = args[keyIdx].toIntSafe() ?: continue
            val value = args[valIdx].toIntSafe() ?: continue
            when (key) {
                1 -> if (value in 0..9) battery = (value + 1) * 10
                2 -> charging = value == 1
            }
        }

        if (battery != null) {
            AppLogger.i(TAG, "→ IPHONEACCEV battery=$battery% charging=$charging")
            publishHfpBattery(battery, charging)
        }
    }

    private fun Any?.toIntSafe(): Int? = when (this) {
        is Int -> this
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    }

    private fun publishHfpBattery(level: Int, charging: Boolean) {
        val current = AirPodsRepository.state.value.snapshot
        val snap = (current ?: AirPodsSnapshot(
            model = AirPodsModel.AIRPODS_PRO_2,
            leftPct = null, rightPct = null, casePct = null,
            leftCharging = false, rightCharging = false, caseCharging = false,
            inCase = false, rssi = -50,
            timestampMs = System.currentTimeMillis()
        )).copy(
            leftPct = level,
            rightPct = level,
            leftCharging = charging,
            rightCharging = charging,
            timestampMs = System.currentTimeMillis()
        )
        AirPodsRepository.onSnapshot(snap)
    }

    private fun publishSyntheticSnapshot(level: Int) {
        val current = AirPodsRepository.state.value.snapshot
        val snap = if (current != null) {
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
        AirPodsRepository.onSnapshot(snap)
    }

    private fun describeExtras(intent: Intent): String {
        val bundle = intent.extras ?: return "{}"
        val keys = bundle.keySet().sorted()
        if (keys.isEmpty()) return "{}"
        return keys.joinToString(",", "{", "}") { key ->
            val short = key.removePrefix("android.bluetooth.")
                .removePrefix("device.extra.")
                .removePrefix("extra.")
                .removePrefix("profile.extra.")
            val value = @Suppress("DEPRECATION") bundle.get(key)
            val rendered = when (value) {
                is ByteArray -> "byte[${value.size}]"
                is BluetoothDevice -> "device:${value.address}"
                null -> "null"
                else -> value.toString().take(40)
            }
            "$short=$rendered"
        }
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
