package com.airpods.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.airpods.app.BuildConfig
import com.airpods.app.R
import com.airpods.app.ble.AirPodsState
import com.airpods.app.ble.ConnectionStatus
import com.airpods.app.ble.ProximityBucket
import com.airpods.app.ble.ProximityReading
import com.airpods.app.ui.theme.BatteryBad
import com.airpods.app.ui.theme.BatteryGood
import com.airpods.app.ui.theme.BatteryWarn
import com.airpods.app.ui.theme.ThemePreference
import com.airpods.app.update.UpdateChecker
import com.airpods.app.update.Updater

private enum class Tab(val titleRes: Int, val iconRes: Int) {
    DASHBOARD(R.string.tab_dashboard, R.drawable.ic_airpods),
    INFO(R.string.tab_info, R.drawable.ic_update),
    SETTINGS(R.string.tab_settings, R.drawable.ic_brightness_auto)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    onCycleTheme: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onRequestOverlay: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = viewModel.isRunningRequested()
    val updateState by Updater.state.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(Tab.DASHBOARD) }
    var showLogViewer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            when (selectedTab) {
                                Tab.DASHBOARD -> R.string.app_name
                                Tab.INFO -> R.string.tab_info
                                Tab.SETTINGS -> R.string.tab_settings
                            }
                        ),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = stringResource(tab.titleRes),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { Text(stringResource(tab.titleRes)) }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        when (selectedTab) {
            Tab.DASHBOARD -> DashboardContent(
                padding = inner,
                state = state,
                running = running,
                hasPermissions = hasPermissions,
                onStart = {
                    if (hasPermissions) viewModel.start() else onRequestPermissions()
                },
                onStop = viewModel::stop,
                onClearCache = viewModel::clearCachedSnapshot
            )
            Tab.INFO -> InfoTab(padding = inner, onCheckUpdate = onCheckUpdate)
            Tab.SETTINGS -> SettingsTab(
                padding = inner,
                themePreference = themePreference,
                onCycleTheme = onCycleTheme,
                onShareLogs = onShareLogs,
                onViewLogs = { showLogViewer = true },
                onRequestOverlay = onRequestOverlay,
                onClearCache = viewModel::clearCachedSnapshot
            )
        }

        UpdateOverlay(state = updateState, onDismiss = { Updater.reset() })
        if (showLogViewer) LogViewerDialog(onDismiss = { showLogViewer = false })
    }
}

@Composable
private fun UpdateOverlay(
    state: Updater.UpdateState,
    onDismiss: () -> Unit
) {
    when (state) {
        Updater.UpdateState.Idle -> Unit
        is Updater.UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* no-op while downloading */ },
                confirmButton = {},
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.total > 0) {
                            Text(
                                text = stringResource(
                                    R.string.update_progress_fmt,
                                    state.progress * 100,
                                    formatBytes(state.bytes),
                                    formatBytes(state.total)
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Text(stringResource(R.string.update_progress_unknown))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                    }
                }
            )
        }
        Updater.UpdateState.NeedsInstallPermission -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                },
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = { Text(stringResource(R.string.update_needs_install_perm)) }
            )
        }
        is Updater.UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                },
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = { Text(stringResource(R.string.update_error, state.message)) }
            )
        }
        is Updater.UpdateState.Done -> {
            // The installer Activity is up; just dismiss the dialog.
            onDismiss()
        }
    }
}

private fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    padding: PaddingValues,
    state: AirPodsState,
    running: Boolean,
    hasPermissions: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearCache: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            // Trigger a scan re-arm — picks up bond/screen state changes and
            // gives the BT controller a fresh look at the radio.
            com.airpods.app.ble.AirPodsBleService.refresh(ctx.applicationContext)
            scope.launch {
                delay(1_500)
                refreshing = false
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatusCard(
            status = state.status,
            modelName = state.snapshot?.model?.displayName
        )

        BatteryRow(
            left = state.snapshot?.leftPct,
            right = state.snapshot?.rightPct,
            leftCharging = state.snapshot?.leftCharging == true,
            rightCharging = state.snapshot?.rightCharging == true
        )

        CaseCard(
            pct = state.snapshot?.casePct,
            charging = state.snapshot?.caseCharging == true,
            inCase = state.snapshot?.inCase == true
        )

        ProximityCard(reading = state.proximity)

        Spacer(Modifier.height(8.dp))

        ControlButtons(
            running = running,
            hasPermissions = hasPermissions,
            onStart = onStart,
            onStop = onStop
        )

        GeigerCard(rssi = state.snapshot?.rssi)

        val audioName = state.audioConnectedName
        val snap = state.snapshot
        if (snap == null) {
            HintCard()
        } else {
            StaleDataCard(
                timestampMs = snap.timestampMs,
                deviceName = audioName,
                onClear = onClearCache
            )
        }
        // Bottom breathing room so the last card isn't flush with the nav bar.
        Spacer(Modifier.height(24.dp))
    }
    } // close PullToRefreshBox
}

@Composable
private fun StatusCard(status: ConnectionStatus, modelName: String?) {
    val (label, dotColor) = when (status) {
        ConnectionStatus.Idle ->
            stringResource(R.string.status_idle) to MaterialTheme.colorScheme.outline
        ConnectionStatus.Scanning ->
            stringResource(R.string.status_scanning) to MaterialTheme.colorScheme.tertiary
        is ConnectionStatus.AudioConnected ->
            stringResource(R.string.status_audio_connected, status.deviceName) to BatteryGood
        is ConnectionStatus.Connected ->
            stringResource(R.string.status_connected) to BatteryGood
        ConnectionStatus.BluetoothOff ->
            stringResource(R.string.status_bt_off) to BatteryBad
        ConnectionStatus.MissingPermissions ->
            stringResource(R.string.status_missing_perms) to BatteryWarn
        is ConnectionStatus.Error ->
            stringResource(R.string.status_error, status.message) to BatteryBad
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (modelName != null) {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryRow(
    left: Int?,
    right: Int?,
    leftCharging: Boolean,
    rightCharging: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BatteryCard(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_airpod_left,
            title = stringResource(R.string.bud_left),
            pct = left,
            charging = leftCharging
        )
        BatteryCard(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_airpod_right,
            title = stringResource(R.string.bud_right),
            pct = right,
            charging = rightCharging
        )
    }
}

@Composable
private fun CaseCard(pct: Int?, charging: Boolean, inCase: Boolean) {
    // Don't expose `inCase` in the title — Apple only broadcasts while the
    // lid is open, so a "lid closed" hint would never be accurate.
    BatteryCard(
        modifier = Modifier.fillMaxWidth(),
        iconRes = R.drawable.ic_case,
        title = stringResource(R.string.case_title),
        pct = pct,
        charging = charging
    )
}

@Composable
private fun BatteryCard(
    modifier: Modifier,
    iconRes: Int,
    title: String,
    pct: Int?,
    charging: Boolean
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (charging) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = stringResource(R.string.charging),
                        tint = BatteryGood,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = pct?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            LinearProgressIndicator(
                progress = { (pct ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = batteryColor(pct),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun batteryColor(pct: Int?): Color = when {
    pct == null -> MaterialTheme.colorScheme.outline
    pct <= 15 -> BatteryBad
    pct <= 35 -> BatteryWarn
    else -> BatteryGood
}

@Composable
private fun ProximityCard(reading: ProximityReading?) {
    val (label, fraction) = when (reading?.bucket) {
        ProximityBucket.Touching -> stringResource(R.string.proximity_touching) to 1f
        ProximityBucket.Near -> stringResource(R.string.proximity_near) to 0.85f
        ProximityBucket.Close -> stringResource(R.string.proximity_close) to 0.6f
        ProximityBucket.Far -> stringResource(R.string.proximity_far) to 0.35f
        ProximityBucket.Lost -> stringResource(R.string.proximity_lost) to 0.1f
        null -> stringResource(R.string.proximity_unknown) to 0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_proximity),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.proximity_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (reading != null) {
                Text(
                    text = stringResource(
                        R.string.proximity_distance_fmt,
                        reading.distanceMeters,
                        reading.rssi
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun ControlButtons(
    running: Boolean,
    hasPermissions: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (running) {
            FilledTonalButton(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.action_stop),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    painter = painterResource(
                        if (!hasPermissions) R.drawable.ic_lock else R.drawable.ic_search
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (!hasPermissions)
                        stringResource(R.string.action_grant_permissions)
                    else
                        stringResource(R.string.action_start),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoTab(padding: PaddingValues, onCheckUpdate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commits = remember { mutableStateListOf<UpdateChecker.Commit>() }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun refreshAll() {
        loading = true
        updateInfo = UpdateChecker.check()
        commits.clear()
        commits.addAll(UpdateChecker.recentCommits())
        loading = false
    }

    LaunchedEffect(Unit) { refreshAll() }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { scope.launch { refreshAll() } },
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.info_installed_version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = BuildConfig.GIT_SHA.take(7),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val info = updateInfo
                if (info != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.info_update_available, info.latestShort
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onCheckUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_update),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_check_update))
                    }
                } else if (!loading) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.info_up_to_date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.info_recent_changes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                if (loading) {
                    Text(
                        text = stringResource(R.string.info_loading),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (commits.isEmpty()) {
                    Text(
                        text = stringResource(R.string.info_no_commits),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    commits.forEachIndexed { idx, commit ->
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                text = commit.firstLine,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = commit.shortSha,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (idx < commits.lastIndex) {
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    } // close PullToRefreshBox
}

@Composable
private fun SettingsTab(
    padding: PaddingValues,
    themePreference: ThemePreference,
    onCycleTheme: () -> Unit,
    onShareLogs: () -> Unit,
    onViewLogs: () -> Unit,
    onRequestOverlay: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingRow(
            iconRes = when (themePreference) {
                ThemePreference.SYSTEM -> R.drawable.ic_brightness_auto
                ThemePreference.LIGHT -> R.drawable.ic_brightness_light
                ThemePreference.DARK -> R.drawable.ic_brightness_dark
            },
            title = stringResource(R.string.theme_action),
            subtitle = stringResource(
                when (themePreference) {
                    ThemePreference.SYSTEM -> R.string.theme_label_system
                    ThemePreference.LIGHT -> R.string.theme_label_light
                    ThemePreference.DARK -> R.string.theme_label_dark
                }
            ),
            onClick = onCycleTheme
        )
        PowerSaveToggleRow()
        SettingRow(
            iconRes = R.drawable.ic_airpods,
            title = stringResource(R.string.action_grant_overlay),
            subtitle = stringResource(R.string.settings_overlay_subtitle),
            onClick = onRequestOverlay
        )
        SettingRow(
            iconRes = R.drawable.ic_share,
            title = stringResource(R.string.action_export_logs),
            subtitle = stringResource(R.string.settings_save_logs_subtitle),
            onClick = onShareLogs
        )
        SettingRow(
            iconRes = R.drawable.ic_proximity,
            title = stringResource(R.string.action_view_logs),
            subtitle = stringResource(R.string.settings_view_logs_subtitle),
            onClick = onViewLogs
        )
        SettingRow(
            iconRes = R.drawable.ic_bolt,
            title = stringResource(R.string.action_clear_cache),
            subtitle = stringResource(R.string.settings_clear_cache_subtitle),
            onClick = onClearCache
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PowerSaveToggleRow() {
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences(
            com.airpods.app.ble.BootReceiver.PREFS,
            android.content.Context.MODE_PRIVATE
        )
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(com.airpods.app.ble.AirPodsBleService.KEY_POWER_SAVE, false))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        onClick = {
            enabled = !enabled
            prefs.edit().putBoolean(com.airpods.app.ble.AirPodsBleService.KEY_POWER_SAVE, enabled).apply()
            com.airpods.app.ble.AirPodsBleService.refresh(ctx.applicationContext)
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bolt),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_power_save_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_power_save_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(com.airpods.app.ble.AirPodsBleService.KEY_POWER_SAVE, it).apply()
                    com.airpods.app.ble.AirPodsBleService.refresh(ctx.applicationContext)
                }
            )
        }
    }
}

@Composable
private fun SettingRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LogViewerDialog(onDismiss: () -> Unit) {
    var content by remember { mutableStateOf("Cargando…") }
    LaunchedEffect(Unit) {
        content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.airpods.app.util.AppLogger.readTail()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_view_logs)) },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
private fun ThemeToggleButton(
    current: ThemePreference,
    onClick: () -> Unit
) {
    val (iconRes, label) = when (current) {
        ThemePreference.SYSTEM ->
            R.drawable.ic_brightness_auto to stringResource(R.string.theme_label_system)
        ThemePreference.LIGHT ->
            R.drawable.ic_brightness_light to stringResource(R.string.theme_label_light)
        ThemePreference.DARK ->
            R.drawable.ic_brightness_dark to stringResource(R.string.theme_label_dark)
    }
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(R.string.theme_action) + ": " + label,
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun HintCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(R.string.hint_open_lid),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GeigerCard(rssi: Int?) {
    val active by com.airpods.app.ble.AirPodsGeiger.active.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (active) com.airpods.app.ble.AirPodsGeiger.stop()
                else com.airpods.app.ble.AirPodsGeiger.start(scope)
            },
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_proximity),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (active) R.string.geiger_active else R.string.geiger_start
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (active && rssi != null)
                        stringResource(R.string.geiger_rssi_fmt, rssi)
                    else stringResource(R.string.geiger_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StaleDataCard(timestampMs: Long, deviceName: String?, onClear: () -> Unit) {
    val ageText = remember(timestampMs) { formatAge(timestampMs) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.data_age_label, ageText),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (deviceName != null)
                    stringResource(R.string.hint_audio_connected, deviceName)
                else
                    stringResource(R.string.hint_stale_no_audio),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_clear_cache))
            }
        }
    }
}

private fun formatAge(timestampMs: Long): String {
    val seconds = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}

@Composable
private fun AudioConnectedHintCard(name: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(R.string.hint_audio_connected, name),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

