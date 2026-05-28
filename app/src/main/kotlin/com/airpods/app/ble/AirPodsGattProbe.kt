package com.airpods.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.airpods.app.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Opens a GATT client connection to a bonded AirPods device and dumps
 * every service / characteristic it exposes. If a standard Battery
 * Service (0x180F) is present we also read the level. This is a
 * long-shot — Apple typically does not expose standard battery GATT
 * to non-Apple clients — but it costs us nothing to try.
 */
class AirPodsGattProbe(private val context: Context) {

    companion object {
        private const val TAG = "GattProbe"
        private val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_CHAR = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHAR = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REV_CHAR = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private const val POLL_MS = 60_000L
    }

    private var pollJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private val attempts = ConcurrentHashMap<String, Int>()

    fun start(scope: CoroutineScope, deviceProvider: () -> BluetoothDevice?) {
        pollJob?.cancel()
        pollJob = scope.launch {
            // initial attempt
            tryProbe(deviceProvider())
            while (isActive) {
                delay(POLL_MS)
                tryProbe(deviceProvider())
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun tryProbe(device: BluetoothDevice?) {
        if (device == null) return
        if (!hasConnectPermission()) return
        if (gatt != null) {
            AppLogger.d(TAG, "previous GATT still open, skipping new attempt")
            return
        }
        val tries = (attempts[device.address] ?: 0) + 1
        attempts[device.address] = tries
        AppLogger.i(TAG, "connectGatt attempt #$tries to ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            AppLogger.w(TAG, "connectGatt returned null")
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val state = when (newState) {
                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                BluetoothProfile.STATE_CONNECTING -> "connecting"
                BluetoothProfile.STATE_CONNECTED -> "connected"
                BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                else -> "?$newState"
            }
            AppLogger.i(TAG, "onConnectionStateChange status=$status state=$state addr=${g.device.address}")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                val ok = g.discoverServices()
                AppLogger.i(TAG, "discoverServices() returned $ok")
            } else {
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            AppLogger.i(TAG, "onServicesDiscovered status=$status servicesCount=${g.services.size}")
            for (service in g.services) {
                dumpService(service)
            }
            // Try to read battery characteristic if present
            val battery = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL_CHAR)
            if (battery != null) {
                AppLogger.i(TAG, "found standard Battery Service — reading")
                val read = g.readCharacteristic(battery)
                AppLogger.i(TAG, "readCharacteristic(battery) returned $read")
            } else {
                AppLogger.i(TAG, "no standard 0x180F battery service exposed")
                // Nothing to read, close
                closeGatt()
            }
            // Also try device info reads
            g.getService(DEVICE_INFO_SERVICE)?.let { svc ->
                listOf(MANUFACTURER_CHAR, MODEL_NUMBER_CHAR, FIRMWARE_REV_CHAR).forEach { uuid ->
                    svc.getCharacteristic(uuid)?.let {
                        AppLogger.d(TAG, "queueing read of $uuid")
                        g.readCharacteristic(it)
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleRead(g, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // pre-API 33 callback
            handleRead(g, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        @SuppressLint("MissingPermission")
        private fun handleRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val uuid = characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.w(TAG, "read $uuid failed status=$status")
                return
            }
            when (uuid) {
                BATTERY_LEVEL_CHAR -> {
                    val level = value.firstOrNull()?.toInt()?.and(0xFF)
                    AppLogger.i(TAG, "BATTERY_LEVEL = $level% (raw=${value.toHex()})")
                    if (level != null && level in 0..100) {
                        publishGattBattery(g.device, level)
                    }
                }
                MANUFACTURER_CHAR -> AppLogger.i(TAG, "Manufacturer = '${String(value)}'")
                MODEL_NUMBER_CHAR -> AppLogger.i(TAG, "Model = '${String(value)}'")
                FIRMWARE_REV_CHAR -> AppLogger.i(TAG, "Firmware = '${String(value)}'")
                else -> AppLogger.d(TAG, "read $uuid = ${value.toHex()}")
            }
        }
    }

    private fun publishGattBattery(device: BluetoothDevice, level: Int) {
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
            timestampMs = System.currentTimeMillis()
        )
        AirPodsRepository.onSnapshot(snap)
    }

    private fun dumpService(service: BluetoothGattService) {
        val name = wellKnownName(service.uuid) ?: "?"
        AppLogger.i(TAG, "service ${service.uuid} ($name) chars=${service.characteristics.size}")
        for (ch in service.characteristics) {
            val props = describeProperties(ch.properties)
            AppLogger.i(TAG, "  char ${ch.uuid} props=$props")
        }
    }

    private fun describeProperties(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "read"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "write"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "writeNR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "notify"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "indicate"
        return parts.joinToString("+")
    }

    private fun wellKnownName(uuid: UUID): String? {
        val s = uuid.toString()
        return when {
            s.startsWith("0000180f") -> "Battery"
            s.startsWith("0000180a") -> "Device Information"
            s.startsWith("00001800") -> "Generic Access"
            s.startsWith("00001801") -> "Generic Attribute"
            s.startsWith("0000180d") -> "Heart Rate"
            else -> null
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
