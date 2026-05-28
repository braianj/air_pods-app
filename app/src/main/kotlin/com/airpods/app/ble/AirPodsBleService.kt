package com.airpods.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airpods.app.notification.BatteryNotificationManager
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AirPodsBleService : LifecycleService() {

    private val adapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var notificationJob: Job? = null
    private var watchdogJob: Job? = null
    private var audioMonitor: ConnectedAirPodsMonitor? = null

    private var lastSnapshotAt: Long = 0L
    private var retryBackoffMs: Long = INITIAL_BACKOFF_MS
    private var lastLoggedModel: AirPodsModel? = null
    private var snapshotsSeen: Int = 0

    // Diagnostic counters refreshed by the stats job
    private val subtypeCounts = java.util.HashMap<Int, Int>()
    private var statsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
        BatteryNotificationManager.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        AppLogger.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                AppLogger.i(TAG, "stop requested")
                stopForegroundAndScan()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notif = BatteryNotificationManager.buildPlaceholder(this)
        startForegroundCompat(notif)

        if (!hasPermissions()) {
            AppLogger.w(TAG, "missing BLUETOOTH_SCAN or BLUETOOTH_CONNECT permission — staying idle")
            AirPodsRepository.setStatus(ConnectionStatus.MissingPermissions)
            return START_STICKY
        }

        val bt = adapter
        if (bt == null) {
            AppLogger.w(TAG, "no BluetoothAdapter available")
            AirPodsRepository.setStatus(ConnectionStatus.BluetoothOff)
            return START_STICKY
        }
        if (!bt.isEnabled) {
            AppLogger.w(TAG, "Bluetooth adapter is OFF")
            AirPodsRepository.setStatus(ConnectionStatus.BluetoothOff)
            return START_STICKY
        }

        startScanning()
        observeStateForNotification()
        startWatchdog()
        startStatsLogger()
        startAudioMonitor()
        BluetoothBroadcastReceiver.register(this)
        return START_STICKY
    }

    private fun startAudioMonitor() {
        audioMonitor?.stop()
        audioMonitor = ConnectedAirPodsMonitor(this).also { it.start(lifecycleScope) }
    }

    private fun startStatsLogger() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                val snapshot = synchronized(subtypeCounts) {
                    if (subtypeCounts.isEmpty()) "(no Apple packets received yet)"
                    else subtypeCounts.entries
                        .sortedByDescending { it.value }
                        .joinToString(", ") { (k, v) -> "0x%02X=%d".format(k, v) }
                }
                AppLogger.i(TAG, "stats(10s): apple_subtypes=[$snapshot] snapshots=$snapshotsSeen")
            }
        }
    }

    private fun startForegroundCompat(notif: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        AppLogger.i(TAG, "startForeground type=$type")
        ServiceCompat.startForeground(this, BatteryNotificationManager.NOTIF_ID, notif, type)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val bt = adapter ?: return
        val s = bt.bluetoothLeScanner
        if (s == null) {
            AppLogger.e(TAG, "bluetoothLeScanner is null (BT off?)")
            AirPodsRepository.setStatus(ConnectionStatus.Error("Scanner unavailable"))
            return
        }
        scanner = s

        // Loose filter: accept ANY Apple manufacturer data, not just the
        // proximity-pairing subtype. AirPods only broadcast 0x07/0x19 for
        // ~30 s after the case lid opens; the rest of the time they emit
        // other Continuity subtypes (Nearby 0x10, Handoff, AirDrop, etc.).
        // The parser later filters by subtype + length, and we log every
        // subtype we see so we can diagnose what's actually nearby.
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AirPodsParser.APPLE_MANUFACTURER_ID,
                byteArrayOf(),
                byteArrayOf()
            )
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mfg = result.scanRecord?.getManufacturerSpecificData(
                    AirPodsParser.APPLE_MANUFACTURER_ID
                )
                if (mfg == null || mfg.isEmpty()) {
                    AppLogger.d(TAG, "Apple hit but empty mfg data (rssi=${result.rssi})")
                    return
                }
                val subtype = mfg[0].toInt() and 0xFF
                val length = if (mfg.size > 1) mfg[1].toInt() and 0xFF else -1
                synchronized(subtypeCounts) {
                    subtypeCounts[subtype] = (subtypeCounts[subtype] ?: 0) + 1
                }
                AppLogger.d(
                    TAG,
                    "Apple pkt subtype=0x%02X len=%d rssi=%d head=%s".format(
                        subtype,
                        length,
                        result.rssi,
                        mfg.take(12).toByteArray().toHex()
                    )
                )

                val snapshot = AirPodsParser.parse(mfg, result.rssi)
                if (snapshot == null) return

                lastSnapshotAt = System.currentTimeMillis()
                retryBackoffMs = INITIAL_BACKOFF_MS
                snapshotsSeen++
                if (snapshot.model != lastLoggedModel || snapshotsSeen % 5 == 1) {
                    AppLogger.i(
                        TAG,
                        "snapshot #$snapshotsSeen model=${snapshot.model} " +
                            "L=${snapshot.leftPct}% R=${snapshot.rightPct}% " +
                            "case=${snapshot.casePct}% chgL=${snapshot.leftCharging} " +
                            "chgR=${snapshot.rightCharging} chgCase=${snapshot.caseCharging} " +
                            "rssi=${snapshot.rssi}"
                    )
                    lastLoggedModel = snapshot.model
                }
                AirPodsRepository.onSnapshot(snapshot)
            }

            override fun onScanFailed(errorCode: Int) {
                AppLogger.e(TAG, "onScanFailed code=$errorCode")
                AirPodsRepository.setStatus(
                    ConnectionStatus.Error("Scan failed ($errorCode)")
                )
                scheduleRetry()
            }
        }

        scanCallback = callback
        try {
            s.startScan(listOf(filter), settings, callback)
            AppLogger.i(TAG, "startScan ok, filter=Apple/0x07,0x19, mode=LOW_LATENCY")
            AirPodsRepository.setStatus(ConnectionStatus.Scanning)
        } catch (se: SecurityException) {
            AppLogger.w(TAG, "SecurityException starting scan", se)
            AirPodsRepository.setStatus(ConnectionStatus.MissingPermissions)
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to start scan", e)
            AirPodsRepository.setStatus(ConnectionStatus.Error(e.message ?: "scan error"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleRetry() {
        lifecycleScope.launch {
            val backoff = retryBackoffMs.coerceAtMost(MAX_BACKOFF_MS)
            AppLogger.i(TAG, "scheduleRetry in ${backoff}ms")
            delay(backoff)
            retryBackoffMs = (retryBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            scanCallback?.let { cb ->
                runCatching { scanner?.stopScan(cb) }
            }
            scanCallback = null
            startScanning()
        }
    }

    private fun observeStateForNotification() {
        notificationJob?.cancel()
        notificationJob = lifecycleScope.launch {
            AirPodsRepository.state.collectLatest { state ->
                BatteryNotificationManager.update(this@AirPodsBleService, state)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            while (true) {
                delay(STALE_AFTER_MS)
                val age = System.currentTimeMillis() - lastSnapshotAt
                if (lastSnapshotAt != 0L && age > STALE_AFTER_MS) {
                    AppLogger.i(TAG, "watchdog: no snapshot for ${age}ms — marking lost")
                    AirPodsRepository.onLost()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopForegroundAndScan() {
        AppLogger.i(TAG, "stopForegroundAndScan")
        watchdogJob?.cancel()
        notificationJob?.cancel()
        statsJob?.cancel()
        audioMonitor?.stop()
        audioMonitor = null
        BluetoothBroadcastReceiver.unregister(this)
        scanCallback?.let { cb ->
            runCatching { scanner?.stopScan(cb) }
        }
        scanCallback = null
        synchronized(subtypeCounts) { subtypeCounts.clear() }
        AirPodsRepository.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "onDestroy")
        stopForegroundAndScan()
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean {
        val scanOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        AppLogger.d(TAG, "hasPermissions scan=$scanOk connect=$connectOk")
        return scanOk && connectOk
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }

    companion object {
        private const val TAG = "Ble"

        const val ACTION_START = "com.airpods.app.action.START"
        const val ACTION_STOP = "com.airpods.app.action.STOP"

        private const val STALE_AFTER_MS = 30_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 32_000L

        fun start(context: Context) {
            AppLogger.i(TAG, "Service.start() called")
            val intent = Intent(context, AirPodsBleService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            AppLogger.i(TAG, "Service.stop() called")
            val intent = Intent(context, AirPodsBleService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
