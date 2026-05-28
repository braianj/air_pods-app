package com.airpods.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.airpods.app.util.AppLogger
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AirPodsGattProbe(private val context: Context) {

    companion object {
        private const val TAG = "GattProbe"
        private val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_CHAR = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHAR = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REV_CHAR = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val POLL_MS = 60_000L
    }

    private var pollJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private val attempts = ConcurrentHashMap<String, Int>()
    private val readQueue: Queue<BluetoothGattCharacteristic> = LinkedList()
    private val notifyQueue: Queue<BluetoothGattCharacteristic> = LinkedList()
    @Volatile private var draining = false

    fun start(scope: CoroutineScope, deviceProvider: () -> BluetoothDevice?) {
        pollJob?.cancel()
        pollJob = scope.launch {
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

        // Log SDP service UUIDs that were discovered during classic pairing.
        val sdpUuids = runCatching { device.uuids?.map { it.uuid.toString() } }.getOrNull()
        AppLogger.i(TAG, "device sdpUuids=$sdpUuids bondState=${bondName(device.bondState)} type=${device.type}")

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) AppLogger.w(TAG, "connectGatt returned null")
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        readQueue.clear()
        notifyQueue.clear()
        draining = false
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val state = stateName(newState)
            AppLogger.i(TAG, "onConnectionStateChange status=$status state=$state addr=${g.device.address}")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                AppLogger.i(TAG, "requesting connection priority HIGH + MTU 247")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(247)
            } else {
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            AppLogger.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            val ok = g.discoverServices()
            AppLogger.i(TAG, "discoverServices() returned $ok")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            AppLogger.i(TAG, "onServicesDiscovered status=$status servicesCount=${g.services.size}")
            readQueue.clear()
            notifyQueue.clear()
            for (service in g.services) {
                dumpService(service)
                for (ch in service.characteristics) {
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        readQueue.offer(ch)
                    }
                    val notifiable =
                        ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    if (notifiable) notifyQueue.offer(ch)
                    // log descriptors
                    for (d in ch.descriptors) {
                        AppLogger.d(TAG, "    desc ${d.uuid}")
                    }
                }
            }
            AppLogger.i(TAG, "queued ${readQueue.size} reads + ${notifyQueue.size} notify-enables")
            drainQueues(g)
        }

        @SuppressLint("MissingPermission")
        private fun drainQueues(g: BluetoothGatt) {
            if (draining) return
            draining = true
            // First do all reads sequentially (Android allows only one outstanding GATT op).
            val next = readQueue.poll()
            if (next != null) {
                draining = false
                val ok = g.readCharacteristic(next)
                if (!ok) {
                    AppLogger.w(TAG, "readCharacteristic ${next.uuid} returned false, skipping")
                    drainQueues(g)
                }
                return
            }
            // Reads done — enable notifications
            val nx = notifyQueue.poll()
            if (nx != null) {
                draining = false
                enableNotifications(g, nx)
                return
            }
            // Everything done; keep connection open for incoming notifications
            AppLogger.i(TAG, "all queues drained — listening for notifications")
            draining = false
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val notifyOk = g.setCharacteristicNotification(ch, true)
            val descriptor = ch.getDescriptor(CCCD)
            if (descriptor == null) {
                AppLogger.w(TAG, "no CCCD on ${ch.uuid} — skip")
                drainQueues(g)
                return
            }
            val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val written = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                if (g.writeDescriptor(descriptor)) 0 else -1
            }
            AppLogger.i(TAG, "enable notify ${ch.uuid} returned=$notifyOk write=$written")
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int
        ) { handleRead(g, characteristic, value, status) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            handleRead(g, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        @SuppressLint("MissingPermission")
        private fun handleRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int
        ) {
            val uuid = characteristic.uuid
            val ok = status == BluetoothGatt.GATT_SUCCESS
            val rendered = renderValue(uuid, value)
            AppLogger.i(
                TAG,
                "read ${uuid} status=$status ok=$ok len=${value.size} = $rendered"
            )
            if (ok && uuid == BATTERY_LEVEL_CHAR) {
                val level = value.firstOrNull()?.toInt()?.and(0xFF)
                if (level != null && level in 0..100) {
                    publishGattBattery(g.device, level)
                }
            }
            drainQueues(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) { handleChanged(g, characteristic, value) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) { handleChanged(g, characteristic, characteristic.value ?: byteArrayOf()) }

        private fun handleChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            AppLogger.i(
                TAG,
                "notify ${characteristic.uuid} len=${value.size} = ${renderValue(characteristic.uuid, value)}"
            )
            if (characteristic.uuid == BATTERY_LEVEL_CHAR) {
                val level = value.firstOrNull()?.toInt()?.and(0xFF)
                if (level != null && level in 0..100) {
                    publishGattBattery(g.device, level)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            AppLogger.i(TAG, "descriptor write ${descriptor.uuid} status=$status")
            drainQueues(g)
        }
    }

    private fun renderValue(uuid: UUID, value: ByteArray): String {
        if (value.isEmpty()) return "(empty)"
        return when (uuid) {
            MANUFACTURER_CHAR, MODEL_NUMBER_CHAR, FIRMWARE_REV_CHAR ->
                "'${runCatching { String(value, Charsets.UTF_8) }.getOrDefault("?")}' [${value.toHex()}]"
            BATTERY_LEVEL_CHAR ->
                "${value.first().toInt() and 0xFF}% [${value.toHex()}]"
            else -> value.toHex()
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
        AppLogger.i(TAG, "publishing GATT battery $level%")
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
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) parts += "broadcast"
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
            s.startsWith("0000fdf2") -> "Apple Vendor 0xFDF2"
            s.startsWith("0000fef5") -> "Dialog Semiconductor"
            else -> null
        }
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "?$state"
    }

    private fun bondName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "?$state"
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
