package com.airpods.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Speaks Apple's Accessory Protocol (AAP) over the L2CAP channel that
 * connected AirPods expose on PSM 0x1001. Unlike the BLE advertisement
 * (encrypted on recent firmware) or getMetadata (privileged), this works on
 * a normally-connected, bonded pair and streams full per-pod battery.
 *
 * Protocol cribbed from the open-source LibrePods / AirPodsDesktop work:
 * after the L2CAP connect we send a handshake, request notifications, and
 * then receive battery packets prefixed with 04 00 04 00 04 00.
 */
class AirPodsAapClient(private val context: Context) {

    companion object {
        private const val TAG = "Aap"
        private const val PSM = 0x1001

        private val HANDSHAKE = byteArrayOf(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        private val SET_SPECIFIC_FEATURES = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x4d, 0x00, 0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00
        )
        private val REQUEST_NOTIFICATIONS = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x0f, 0x00,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()
        )
        private val BATTERY_HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x04, 0x00)

        // AAP battery component + status codes (LibrePods).
        private const val COMP_HEADPHONE = 0x01 // single (e.g. AirPods Max)
        private const val COMP_RIGHT = 0x02
        private const val COMP_LEFT = 0x04
        private const val COMP_CASE = 0x08
        private const val STATUS_CHARGING = 0x01
        private const val STATUS_DISCONNECTED = 0x04

        private const val RETRY_MS = 15_000L
    }

    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null

    fun start(scope: CoroutineScope, deviceProvider: () -> BluetoothDevice?) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val device = deviceProvider()
                if (device != null && hasConnectPermission()) {
                    runCatching { connectAndListen(device) }
                        .onFailure {
                            AppLogger.w(TAG, "AAP session ended: ${it.javaClass.simpleName}: ${it.message}")
                        }
                }
                closeSocket()
                if (!isActive) break
                delay(RETRY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        closeSocket()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndListen(device: BluetoothDevice) {
        AppLogger.i(TAG, "opening L2CAP PSM 0x%04X to %s".format(PSM, device.address))
        val sock = device.createL2capChannel(PSM)
        socket = sock
        sock.connect()
        AppLogger.i(TAG, "L2CAP connected")

        val out = sock.outputStream
        val inp = sock.inputStream
        out.write(HANDSHAKE); out.flush()
        AppLogger.i(TAG, "sent handshake")
        Thread.sleep(250)
        out.write(SET_SPECIFIC_FEATURES); out.flush()
        Thread.sleep(250)
        out.write(REQUEST_NOTIFICATIONS); out.flush()
        AppLogger.i(TAG, "sent feature + notification requests — listening")

        val buf = ByteArray(1024)
        while (socket === sock) {
            val n = inp.read(buf)
            if (n < 0) {
                AppLogger.i(TAG, "L2CAP stream closed by peer")
                break
            }
            if (n == 0) continue
            val packet = buf.copyOf(n)
            AppLogger.i(TAG, "rx ${n}B: ${packet.toHex()}")
            if (packet.size >= 7 && packet.startsWith(BATTERY_HEADER)) {
                parseBattery(packet)
            }
        }
    }

    private fun parseBattery(data: ByteArray) {
        val count = data[6].toInt() and 0xFF
        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        var leftChg = false
        var rightChg = false
        var caseChg = false
        for (i in 0 until count) {
            val base = 7 + i * 5
            if (base + 3 >= data.size) break
            val component = data[base].toInt() and 0xFF
            val level = data[base + 2].toInt() and 0xFF
            val status = data[base + 3].toInt() and 0xFF
            val charging = status == STATUS_CHARGING
            val pct = if (status != STATUS_DISCONNECTED && level in 0..100) level else null
            AppLogger.i(
                TAG,
                "battery component=0x%02X level=%d status=0x%02X".format(component, level, status)
            )
            when (component) {
                COMP_RIGHT -> { right = pct; rightChg = charging }
                COMP_LEFT -> { left = pct; leftChg = charging }
                COMP_CASE -> { case = pct; caseChg = charging }
                COMP_HEADPHONE -> {
                    left = pct; right = pct; leftChg = charging; rightChg = charging
                }
            }
        }
        if (left != null || right != null || case != null) {
            publish(left, right, case, leftChg, rightChg, caseChg)
        }
    }

    private fun publish(
        l: Int?, r: Int?, c: Int?, lc: Boolean, rc: Boolean, cc: Boolean
    ) {
        val current = AirPodsRepository.state.value.snapshot
        val snap = (current ?: AirPodsSnapshot(
            model = AirPodsModel.AIRPODS_PRO_2,
            leftPct = null, rightPct = null, casePct = null,
            leftCharging = false, rightCharging = false, caseCharging = false,
            inCase = false, rssi = -50,
            timestampMs = System.currentTimeMillis()
        )).copy(
            leftPct = l ?: current?.leftPct,
            rightPct = r ?: current?.rightPct,
            casePct = c ?: current?.casePct,
            leftCharging = lc,
            rightCharging = rc,
            caseCharging = cc,
            timestampMs = System.currentTimeMillis()
        )
        AppLogger.i(TAG, "publish AAP battery L=$l R=$r case=$c chgL=$lc chgR=$rc chgCase=$cc")
        AirPodsRepository.onSnapshot(snap)
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        runCatching { s?.close() }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
