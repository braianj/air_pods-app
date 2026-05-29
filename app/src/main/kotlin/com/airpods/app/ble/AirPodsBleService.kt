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
    private var aapClient: AirPodsAapClient? = null
    private var overlay: AirPodsOverlay? = null

    // Track the scan mode we're currently running with so we don't pointlessly
    // tear down and re-arm the scan when nothing changed.
    private var currentScanMode: Int = -1
    private var screenReceiverRegistered: Boolean = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_ON,
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    AppLogger.i(TAG, "screen ${intent.action?.removePrefix("android.intent.action.ACTION_")}")
                    restartScanIfNeeded()
                }
            }
        }
    }

    private var lastSnapshotAt: Long = 0L
    private var retryBackoffMs: Long = INITIAL_BACKOFF_MS
    private var lastLoggedModel: AirPodsModel? = null
    private var snapshotsSeen: Int = 0

    // Dedup so the per-packet log isn't flooded by identical adverts (the
    // scanner repeats the same advertisement hundreds of times per second).
    private var lastLogged07Hex: String? = null

    // Bytes of the previous packet that made it past the dedup gate, so we
    // can short-circuit identical re-broadcasts without invoking the parser.
    // AirPods repeat the same advertisement many times in succession; this
    // alone cuts the per-callback CPU work by a large factor.
    private var lastSeenMfgBytes: ByteArray? = null
    private var dedupedCount: Int = 0
    private var lastLoggedParseLine: String? = null

    // Once we've seen a snapshot with strong RSSI (≥ STRONG_RSSI_DBM), lock
    // onto that model for the rest of the session. Anything else — even at
    // borderline RSSI — is somebody else's pair, reject it.
    private var lockedModel: AirPodsModel? = null

    // Diagnostic counters refreshed by the stats job
    private val subtypeCounts = java.util.HashMap<Int, Int>()
    private var statsJob: Job? = null
    private var batteryJob: Job? = null

    // Battery-impact diagnostics. Captured when the service starts so the
    // periodic log can show the delta over the session.
    private var serviceStartedAtMs: Long = 0L
    private var batteryAtServiceStart: Int = -1

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
            ACTION_REFRESH -> {
                AppLogger.i(TAG, "refresh requested (bond change)")
                restartScanIfNeeded()
                return START_STICKY
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

        registerScreenReceiver()
        startScanning()
        observeStateForNotification()
        startWatchdog()
        startStatsLogger()
        startBatteryLogger()
        startAudioMonitor()
        BluetoothBroadcastReceiver.register(this)
        return START_STICKY
    }

    private fun startAudioMonitor() {
        if (overlay == null) overlay = AirPodsOverlay(applicationContext)

        audioMonitor?.stop()
        audioMonitor = ConnectedAirPodsMonitor(this).also { it.start(lifecycleScope) }

        // Primary live-battery path: speak Apple's AAP over L2CAP to the
        // connected AirPods. Gives full per-pod battery without iCloud.
        aapClient?.stop()
        aapClient = AirPodsAapClient(this).also { client ->
            client.start(lifecycleScope) { findBondedAirPods() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findBondedAirPods(): android.bluetooth.BluetoothDevice? {
        val bt = adapter ?: return null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            bt.bondedDevices?.firstOrNull { device ->
                val name = runCatching { device.name }.getOrNull() ?: return@firstOrNull false
                name.contains("AirPods", ignoreCase = true) ||
                    name.contains("Beats", ignoreCase = true)
            }
        }.getOrNull()
    }

    private fun startStatsLogger() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                val snapshot = synchronized(subtypeCounts) {
                    if (subtypeCounts.isEmpty()) "(no Apple packets received yet)"
                    else subtypeCounts.entries
                        .sortedByDescending { it.value }
                        .joinToString(", ") { (k, v) -> "0x%02X=%d".format(k, v) }
                }
                AppLogger.i(
                    TAG,
                    "stats(30s): apple_subtypes=[$snapshot] snapshots=$snapshotsSeen deduped=$dedupedCount"
                )
            }
        }
    }

    private fun startBatteryLogger() {
        batteryJob?.cancel()
        if (serviceStartedAtMs == 0L) {
            serviceStartedAtMs = System.currentTimeMillis()
            batteryAtServiceStart = readDeviceBatteryPct()
            AppLogger.i(
                TAG,
                "battery@start=$batteryAtServiceStart% scan=batch(reportDelay=1) mode=LOW_LATENCY"
            )
        }
        batteryJob = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                val nowBat = readDeviceBatteryPct()
                val drop = if (batteryAtServiceStart in 0..100 && nowBat in 0..100)
                    batteryAtServiceStart - nowBat
                else null
                val uptimeMin = (System.currentTimeMillis() - serviceStartedAtMs) / 60_000
                val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                val doze = pm?.isDeviceIdleMode ?: false
                val powerSave = pm?.isPowerSaveMode ?: false
                AppLogger.i(
                    TAG,
                    "battery(1min): uptime=${uptimeMin}min " +
                        "phoneBat=$nowBat% drop=${drop ?: "?"}% " +
                        "doze=$doze powerSave=$powerSave " +
                        "snapshotsThisSession=$snapshotsSeen " +
                        "scanRunning=${scanCallback != null}"
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readDeviceBatteryPct(): Int {
        val mgr = getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager
        return mgr?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(screenReceiver, filter) }
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    /**
     * Pick the scan mode based on screen state + the user's power-save
     * preference. When power-save is enabled we stay in BALANCED regardless;
     * otherwise we use LOW_LATENCY only while the screen is interactive.
     */
    private fun chooseScanMode(): Int {
        val prefs = applicationContext.getSharedPreferences(
            BootReceiver.PREFS, Context.MODE_PRIVATE
        )
        if (prefs.getBoolean(KEY_POWER_SAVE, false)) {
            return ScanSettings.SCAN_MODE_BALANCED
        }
        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        val on = pm?.isInteractive ?: true
        return if (on) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED
    }

    private fun scanModeName(mode: Int): String = when (mode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
        ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
        ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
        ScanSettings.SCAN_MODE_OPPORTUNISTIC -> "OPPORTUNISTIC"
        else -> "?$mode"
    }

    /**
     * Re-arm the scan if either the screen state or the bond state changed
     * what we should be doing. Cheap no-op when nothing actually changed.
     */
    @SuppressLint("MissingPermission")
    private fun restartScanIfNeeded() {
        val wantBond = findBondedAirPods() != null
        val wantMode = chooseScanMode()
        val nowScanning = scanCallback != null

        when {
            !wantBond && nowScanning -> {
                AppLogger.i(TAG, "no bonded AirPods anymore — pausing scan")
                scanCallback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
                scanCallback = null
                currentScanMode = -1
                AirPodsRepository.setStatus(ConnectionStatus.Idle)
            }
            wantBond && !nowScanning -> {
                AppLogger.i(TAG, "bonded AirPods present — starting scan")
                startScanning()
            }
            wantBond && nowScanning && wantMode != currentScanMode -> {
                AppLogger.i(
                    TAG,
                    "scan mode switch ${scanModeName(currentScanMode)} -> ${scanModeName(wantMode)}"
                )
                scanCallback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
                scanCallback = null
                startScanning()
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

        if (findBondedAirPods() == null) {
            // No bonded AirPods/Beats anywhere on this device — there's no
            // point burning radio. Stay foreground, but skip the scan until
            // a bond shows up (BluetoothBroadcastReceiver fires on bond).
            AppLogger.i(TAG, "no bonded AirPods — skipping scan (will resume on bond)")
            currentScanMode = -1
            AirPodsRepository.setStatus(ConnectionStatus.Idle)
            return
        }

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

        val mode = chooseScanMode()
        // Use batch-scan mode (reportDelay > 0). The Bluetooth controller
        // does the filtering at firmware level and delivers grouped results,
        // which on most Android devices is MORE reliable than the immediate-
        // callback mode for catching short bursts like the AirPods lid-open
        // proximity-pairing packet. OpenPods/MaterialPods use the same trick.
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(1)
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
                // Dedup against the previous packet's bytes. AirPods repeat
                // the same advertisement many times in a burst; running the
                // parser + filter + repository update on each identical copy
                // is wasted CPU. RSSI varies a bit between repeats, so we
                // accept the loss of those minor RSSI samples — the proximity
                // calculator already smooths.
                val prev = lastSeenMfgBytes
                if (prev != null && prev.contentEquals(mfg)) {
                    dedupedCount++
                    return
                }
                lastSeenMfgBytes = mfg

                val subtype = mfg[0].toInt() and 0xFF
                val length = if (mfg.size > 1) mfg[1].toInt() and 0xFF else -1
                synchronized(subtypeCounts) {
                    subtypeCounts[subtype] = (subtypeCounts[subtype] ?: 0) + 1
                }

                // Only the proximity-pairing subtype (0x07) can carry battery.
                // Log it in FULL (not truncated) and only when the bytes
                // actually change, so the rare unencrypted packet survives in
                // the rotated log instead of being buried under noise.
                if (subtype == 0x07) {
                    val fullHex = mfg.toHex()
                    if (fullHex != lastLogged07Hex) {
                        lastLogged07Hex = fullHex
                        AppLogger.i(
                            TAG,
                            "0x07 pkt len=$length rssi=${result.rssi} bytes=$fullHex"
                        )
                    }
                }

                val snapshot = AirPodsParser.parse(mfg, result.rssi)
                if (snapshot == null) return

                // Reject far-away AirPods from neighbors. The user's own pods
                // are typically -30 to -65 dBm; anything weaker is somebody
                // else's, and parsing it would flip the UI between unrelated
                // devices. (OpenPods uses the same -60 threshold.)
                if (result.rssi < MIN_RSSI_DBM) {
                    AppLogger.d(
                        TAG,
                        "ignoring far AirPods rssi=${result.rssi} model=${snapshot.model}"
                    )
                    return
                }

                // Belt-and-suspenders: lock onto the model that comes in
                // with strong signal. After that, reject anything with a
                // different model id even if RSSI is borderline.
                val locked = lockedModel
                if (locked == null) {
                    if (result.rssi >= STRONG_RSSI_DBM && snapshot.model != AirPodsModel.UNKNOWN) {
                        lockedModel = snapshot.model
                        AppLogger.i(TAG, "locked model=${snapshot.model} (rssi=${result.rssi})")
                    }
                } else if (snapshot.model != locked) {
                    AppLogger.d(
                        TAG,
                        "ignoring foreign model ${snapshot.model} (locked=$locked) rssi=${result.rssi}"
                    )
                    return
                }

                val now = System.currentTimeMillis()
                val freshOpen = lastSnapshotAt == 0L || (now - lastSnapshotAt) > FRESH_OPEN_GAP_MS
                lastSnapshotAt = now
                retryBackoffMs = INITIAL_BACKOFF_MS
                snapshotsSeen++
                // Fire the popups ONCE per fresh-open burst. On subsequent
                // packets we just update the underlying repository state
                // silently — no overlay re-appearance, no heads-up re-fire.
                // Also skip when the dashboard is already in the foreground:
                // the popup adds zero value when the same data is on screen.
                if (freshOpen && !com.airpods.app.MainActivity.isForeground) {
                    val overlayShown = overlay?.show(snapshot) == true
                    if (!overlayShown) {
                        BatteryNotificationManager.showPopup(applicationContext, snapshot)
                    }
                }
                // Log the parsed result together with the raw bytes, but only
                // when the parsed values change — this is what lets us verify
                // the byte→pod mapping against a real packet.
                val parseLine = "model=${snapshot.model} L=${snapshot.leftPct}% " +
                    "R=${snapshot.rightPct}% case=${snapshot.casePct}% " +
                    "chgL=${snapshot.leftCharging} chgR=${snapshot.rightCharging} " +
                    "chgCase=${snapshot.caseCharging} inCase=${snapshot.inCase}"
                if (parseLine != lastLoggedParseLine) {
                    lastLoggedParseLine = parseLine
                    lastLoggedModel = snapshot.model
                    AppLogger.i(
                        TAG,
                        "PARSED #$snapshotsSeen $parseLine rssi=${snapshot.rssi} raw=${mfg.toHex()}"
                    )
                }
                AirPodsRepository.onSnapshot(snapshot)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                // Batch mode (reportDelay > 0) delivers grouped results here
                // instead of via onScanResult. Process each one through the
                // same path so parsing / persistence works either way.
                for (r in results) onScanResult(0, r)
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
            currentScanMode = mode
            AppLogger.i(TAG, "startScan ok, filter=Apple/0x07,0x19, mode=${scanModeName(mode)}")
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
        batteryJob?.cancel()
        if (serviceStartedAtMs > 0L) {
            val uptimeMin = (System.currentTimeMillis() - serviceStartedAtMs) / 60_000
            val nowBat = readDeviceBatteryPct()
            val drop = if (batteryAtServiceStart in 0..100 && nowBat in 0..100)
                batteryAtServiceStart - nowBat
            else null
            AppLogger.i(
                TAG,
                "battery@stop: uptime=${uptimeMin}min phoneBat=$nowBat% drop=${drop ?: "?"}% snapshots=$snapshotsSeen"
            )
            serviceStartedAtMs = 0L
        }
        audioMonitor?.stop()
        audioMonitor = null
        aapClient?.stop()
        aapClient = null
        overlay?.hide()
        overlay = null
        BluetoothBroadcastReceiver.unregister(this)
        unregisterScreenReceiver()
        currentScanMode = -1
        scanCallback?.let { cb ->
            runCatching { scanner?.stopScan(cb) }
        }
        scanCallback = null
        lockedModel = null
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
        const val ACTION_REFRESH = "com.airpods.app.action.REFRESH"

        /** Preference key for the user's "power save" toggle. */
        const val KEY_POWER_SAVE = "power_save"

        /** Tell a running service to recheck bond state and scan mode. */
        fun refresh(context: Context) {
            val intent = Intent(context, AirPodsBleService::class.java)
                .setAction(ACTION_REFRESH)
            runCatching { context.startService(intent) }
        }

        private const val STALE_AFTER_MS = 30_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 32_000L
        private const val MIN_RSSI_DBM = -65
        // Strong-signal threshold to commit to a model id for the session.
        private const val STRONG_RSSI_DBM = -55
        // After this much silence, a new snapshot counts as a "fresh" lid
        // open event and we surface the heads-up popup notification.
        private const val FRESH_OPEN_GAP_MS = 20_000L

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
