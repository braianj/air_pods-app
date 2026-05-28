package com.airpods.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
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
    }

    private var pollJob: Job? = null
    private var a2dpProxy: BluetoothA2dp? = null
    private var listenerRegistered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy as BluetoothA2dp
                AppLogger.i(TAG, "A2DP proxy connected")
                refresh()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                AppLogger.i(TAG, "A2DP proxy disconnected")
            }
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
        if (!listenerRegistered) {
            val ok = adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            AppLogger.i(TAG, "getProfileProxy returned $ok")
            listenerRegistered = ok
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
        runCatching {
            a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        }
        a2dpProxy = null
        listenerRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun refresh() {
        if (!hasConnectPermission()) return
        val proxy = a2dpProxy ?: return
        val devices = try {
            proxy.connectedDevices
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "no permission for connectedDevices", e)
            return
        }
        AppLogger.i(TAG, "A2DP connectedDevices count=${devices.size}")
        var foundAirPods: String? = null
        for (device in devices) {
            val name = safeName(device) ?: continue
            val addr = device.address
            AppLogger.i(TAG, "A2DP device name='$name' addr=$addr")
            if (isAirPods(name)) {
                foundAirPods = name
                tryReadBatteryFromMetadata(device)
            }
        }
        AirPodsRepository.setAudioConnected(foundAirPods)
    }

    @SuppressLint("MissingPermission")
    private fun tryReadBatteryFromMetadata(device: BluetoothDevice) {
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
        if (left != null || right != null || case != null) {
            val snapshot = AirPodsSnapshot(
                model = inferModel(device),
                leftPct = left,
                rightPct = right,
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
