package com.airpods.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectedAirPodsMonitor(private val context: Context) {

    companion object {
        private const val TAG = "AudioMon"
        private const val POLL_MS = 20_000L

        private const val METADATA_UNTETHERED_LEFT_BATTERY = 10
        private const val METADATA_UNTETHERED_RIGHT_BATTERY = 11
        private const val METADATA_UNTETHERED_CASE_BATTERY = 12
        private const val METADATA_UNTETHERED_LEFT_CHARGING = 13
        private const val METADATA_UNTETHERED_RIGHT_CHARGING = 14
        private const val METADATA_UNTETHERED_CASE_CHARGING = 15

        // BluetoothProfile.LE_AUDIO is API 33+ and stable. Hard-code the
        // int so we don't need to reflect for older targets.
        private const val PROFILE_LE_AUDIO = 22
    }

    private var pollJob: Job? = null
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private val profileNames = mapOf(
        BluetoothProfile.HEADSET to "HFP",
        BluetoothProfile.A2DP to "A2DP",
        PROFILE_LE_AUDIO to "LE_AUDIO"
    )

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
            AppLogger.i(TAG, "${profileNames[profile] ?: profile} proxy connected")
            refresh()
        }
        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
            AppLogger.i(TAG, "${profileNames[profile] ?: profile} proxy disconnected")
        }
    }

    fun start(scope: CoroutineScope) {
        val adapter = adapter() ?: run {
            AppLogger.w(TAG, "no BT adapter — skipping audio monitor")
            return
        }
        if (!hasConnectPermission()) {
            AppLogger.w(TAG, "BLUETOOTH_CONNECT not granted — skipping audio monitor")
            return
        }

        adapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adapter.getProfileProxy(context, profileListener, PROFILE_LE_AUDIO)
        }

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                refresh()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        val adapter = adapter()
        proxies.forEach { (profile, proxy) ->
            runCatching { adapter?.closeProfileProxy(profile, proxy) }
        }
        proxies.clear()
    }

    @SuppressLint("MissingPermission")
    private fun refresh() {
        if (!hasConnectPermission()) return
        val adapter = adapter() ?: return

        AppLogger.i(
            TAG,
            "adapter state=${adapterStateName(adapter.state)} " +
                "name='${safeAdapterName(adapter)}' " +
                "scanMode=${adapter.scanMode} " +
                "LE_supported=${adapter.isLeAudioSupported()} " +
                "proxies=${proxies.keys.map { profileNames[it] ?: it }}"
        )

        // 1) List all bonded devices — this works regardless of profile state.
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "bondedDevices denied", e); emptySet()
        }
        AppLogger.i(TAG, "bonded count=${bonded.size}")

        var foundAirPods: BluetoothDevice? = null
        var foundName: String? = null

        for (device in bonded) {
            val name = safeName(device) ?: "(no name)"
            val addr = device.address
            val type = btTypeName(device.type)
            val bondState = bondStateName(device.bondState)
            val states = describeConnectionStates(device)
            AppLogger.i(
                TAG,
                "bonded device='$name' addr=$addr type=$type bond=$bondState [$states]"
            )
            if (isAirPods(name) && foundAirPods == null) {
                foundAirPods = device
                foundName = name
            }
        }

        // 2) Also check each profile's connectedDevices set (in case bonded
        //    iteration somehow misses something).
        for ((profile, proxy) in proxies) {
            val devices = try {
                proxy.connectedDevices
            } catch (e: SecurityException) {
                AppLogger.w(TAG, "connectedDevices(${profileNames[profile]}) denied", e); continue
            }
            AppLogger.i(TAG, "${profileNames[profile]} connectedDevices count=${devices.size}")
            for (device in devices) {
                val name = safeName(device) ?: continue
                AppLogger.i(TAG, "${profileNames[profile]} connected name='$name' addr=${device.address}")
                if (isAirPods(name) && foundAirPods == null) {
                    foundAirPods = device
                    foundName = name
                }
            }
        }

        if (foundAirPods != null && foundName != null) {
            AirPodsRepository.setAudioConnected(foundName)
            tryReadBatteryFromMetadata(foundAirPods)
        } else {
            AirPodsRepository.setAudioConnected(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeAdapterName(adapter: BluetoothAdapter): String? = try {
        adapter.name
    } catch (_: SecurityException) { null }

    @SuppressLint("MissingPermission")
    private fun BluetoothAdapter.isLeAudioSupported(): String =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.isLeAudioSupported.toString()
            } else "n/a-sdk"
        } catch (_: Throwable) { "?" }

    private fun adapterStateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "off"
        BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
        else -> "?$state"
    }

    private fun btTypeName(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "unknown"
        else -> "?$type"
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "?$state"
    }

    @SuppressLint("MissingPermission")
    private fun describeConnectionStates(device: BluetoothDevice): String {
        val adapter = adapter() ?: return "no-adapter"
        val list = mutableListOf<String>()
        for ((profile, label) in profileNames) {
            val state = try {
                adapter.getProfileConnectionState(profile)
                // Note: getProfileConnectionState returns the highest state
                // across ALL devices for the given profile, not per-device.
                // The per-device state we instead get from each proxy.
                val proxy = proxies[profile]
                proxy?.getConnectionState(device) ?: -1
            } catch (e: SecurityException) { -1 }
            if (state >= 0) {
                list.add("$label=${stateName(state)}")
            }
        }
        return if (list.isEmpty()) "no-proxies-yet" else list.joinToString(",")
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "off"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "ON"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "?$state"
    }

    @SuppressLint("MissingPermission")
    private fun tryReadBatteryFromMetadata(device: BluetoothDevice) {
        // Path 1 — the new untethered metadata API (gated by
        // BLUETOOTH_PRIVILEGED on Android 16, so usually blocked).
        val left = readMetaInt(device, METADATA_UNTETHERED_LEFT_BATTERY)
        val right = readMetaInt(device, METADATA_UNTETHERED_RIGHT_BATTERY)
        val case = readMetaInt(device, METADATA_UNTETHERED_CASE_BATTERY)
        val chgL = readMetaBool(device, METADATA_UNTETHERED_LEFT_CHARGING)
        val chgR = readMetaBool(device, METADATA_UNTETHERED_RIGHT_CHARGING)
        val chgC = readMetaBool(device, METADATA_UNTETHERED_CASE_CHARGING)
        AppLogger.i(
            TAG,
            "getMetadata L=$left R=$right case=$case chgL=$chgL chgR=$chgR chgC=$chgC"
        )

        // Path 2 — legacy single-battery getBatteryLevel() (also @hide,
        // but older and sometimes accessible).
        val legacy = readLegacyBatteryLevel(device)
        AppLogger.i(TAG, "getBatteryLevel() legacy=$legacy")

        // Path 3 — read the model name metadata key (METADATA_MODEL_NAME=5)
        // and the manufacturer name (1) just so we can confirm what
        // SDK string the device reports.
        val modelMeta = readMetaString(device, 5)
        val mfgMeta = readMetaString(device, 1)
        AppLogger.i(TAG, "metadata model='$modelMeta' mfg='$mfgMeta'")

        val effectiveLeft = left ?: legacy
        val effectiveRight = right ?: legacy
        if (effectiveLeft != null || effectiveRight != null || case != null) {
            val snapshot = AirPodsSnapshot(
                model = inferModel(device),
                leftPct = effectiveLeft,
                rightPct = effectiveRight,
                casePct = case,
                leftCharging = chgL ?: false,
                rightCharging = chgR ?: false,
                caseCharging = chgC ?: false,
                inCase = false,
                rssi = -50,
                timestampMs = System.currentTimeMillis()
            )
            AirPodsRepository.onSnapshot(snapshot)
        }
    }

    private fun readLegacyBatteryLevel(device: BluetoothDevice): Int? = try {
        val method = device.javaClass.getMethod("getBatteryLevel")
        val result = method.invoke(device) as? Int
        // -1 means unknown, -100 means BT off, anything 0..100 is real
        if (result != null && result in 0..100) result else null
    } catch (t: Throwable) {
        val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        AppLogger.d(TAG, "getBatteryLevel() failed: ${real.javaClass.simpleName}: ${real.message ?: ""}")
        null
    }

    private fun readMetaString(device: BluetoothDevice, key: Int): String? = try {
        val method = device.javaClass.getMethod("getMetadata", Integer.TYPE)
        val raw = method.invoke(device, key) as? ByteArray
        raw?.let { String(it, Charsets.UTF_8) }
    } catch (_: Throwable) { null }

    private fun readMetaInt(device: BluetoothDevice, key: Int): Int? = try {
        val method = device.javaClass.getMethod("getMetadata", Integer.TYPE)
        val raw = method.invoke(device, key) as? ByteArray
        raw?.let { String(it, Charsets.UTF_8).trim().toIntOrNull() }
    } catch (t: Throwable) {
        val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        AppLogger.d(
            TAG,
            "getMetadata($key) blocked: ${real.javaClass.simpleName}: ${real.message ?: "(no msg)"}"
        )
        null
    }

    private fun readMetaBool(device: BluetoothDevice, key: Int): Boolean? = try {
        val method = device.javaClass.getMethod("getMetadata", Integer.TYPE)
        val raw = method.invoke(device, key) as? ByteArray
        raw?.let {
            val s = String(it, Charsets.UTF_8).trim().lowercase()
            s == "true" || s == "1"
        }
    } catch (_: Throwable) { null }

    private fun inferModel(device: BluetoothDevice): AirPodsModel {
        val name = safeName(device) ?: return AirPodsModel.UNKNOWN
        return when {
            name.contains("Pro 2", ignoreCase = true) -> AirPodsModel.AIRPODS_PRO_2
            name.contains("Pro", ignoreCase = true) -> AirPodsModel.AIRPODS_PRO
            name.contains("Max", ignoreCase = true) -> AirPodsModel.AIRPODS_MAX
            name.contains("AirPods", ignoreCase = true) -> AirPodsModel.AIRPODS_PRO_2
            else -> AirPodsModel.UNKNOWN
        }
    }

    private fun isAirPods(name: String): Boolean =
        name.contains("AirPods", ignoreCase = true) || name.contains("Beats", ignoreCase = true)

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) { null }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
