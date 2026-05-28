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
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airpods.app.notification.BatteryNotificationManager
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

    private var lastSnapshotAt: Long = 0L
    private var retryBackoffMs: Long = INITIAL_BACKOFF_MS

    override fun onCreate() {
        super.onCreate()
        BatteryNotificationManager.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundAndScan()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notif = BatteryNotificationManager.buildPlaceholder(this)
        startForegroundCompat(notif)

        if (!hasPermissions()) {
            AirPodsRepository.setStatus(ConnectionStatus.MissingPermissions)
            return START_STICKY
        }

        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            AirPodsRepository.setStatus(ConnectionStatus.BluetoothOff)
            return START_STICKY
        }

        startScanning()
        observeStateForNotification()
        startWatchdog()
        return START_STICKY
    }

    private fun startForegroundCompat(notif: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, BatteryNotificationManager.NOTIF_ID, notif, type)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val bt = adapter ?: return
        val s = bt.bluetoothLeScanner
        if (s == null) {
            AirPodsRepository.setStatus(ConnectionStatus.Error("Scanner unavailable"))
            return
        }
        scanner = s

        // Filter for Apple manufacturer data starting with the proximity-pairing
        // header (0x07 0x19). The mask ensures we only match those two bytes.
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AirPodsParser.APPLE_MANUFACTURER_ID,
                byteArrayOf(0x07, 0x19),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte())
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
                val snapshot = AirPodsParser.parse(mfg, result.rssi) ?: return
                lastSnapshotAt = System.currentTimeMillis()
                retryBackoffMs = INITIAL_BACKOFF_MS
                AirPodsRepository.onSnapshot(snapshot)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: code=$errorCode")
                AirPodsRepository.setStatus(
                    ConnectionStatus.Error("Scan failed ($errorCode)")
                )
                scheduleRetry()
            }
        }

        scanCallback = callback
        try {
            s.startScan(listOf(filter), settings, callback)
            AirPodsRepository.setStatus(ConnectionStatus.Scanning)
        } catch (se: SecurityException) {
            AirPodsRepository.setStatus(ConnectionStatus.MissingPermissions)
            Log.w(TAG, "SecurityException starting scan", se)
        } catch (e: Exception) {
            AirPodsRepository.setStatus(ConnectionStatus.Error(e.message ?: "scan error"))
            Log.e(TAG, "Failed to start scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleRetry() {
        lifecycleScope.launch {
            val backoff = retryBackoffMs.coerceAtMost(MAX_BACKOFF_MS)
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
                    AirPodsRepository.onLost()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopForegroundAndScan() {
        watchdogJob?.cancel()
        notificationJob?.cancel()
        scanCallback?.let { cb ->
            runCatching { scanner?.stopScan(cb) }
        }
        scanCallback = null
        AirPodsRepository.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
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
        return scanOk && connectOk
    }

    companion object {
        private const val TAG = "AirPodsBleService"

        const val ACTION_START = "com.airpods.app.action.START"
        const val ACTION_STOP = "com.airpods.app.action.STOP"

        private const val STALE_AFTER_MS = 30_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 32_000L

        fun start(context: Context) {
            val intent = Intent(context, AirPodsBleService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirPodsBleService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
