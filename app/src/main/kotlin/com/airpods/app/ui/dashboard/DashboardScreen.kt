package com.airpods.app.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.airpods.app.R
import com.airpods.app.ble.AirPodsState
import com.airpods.app.ble.ConnectionStatus
import com.airpods.app.ble.ProximityBucket
import com.airpods.app.ble.ProximityReading
import com.airpods.app.ui.theme.BatteryBad
import com.airpods.app.ui.theme.BatteryGood
import com.airpods.app.ui.theme.BatteryWarn
import com.airpods.app.ui.theme.ThemePreference
import com.airpods.app.update.Updater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    onCycleTheme: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = viewModel.isRunningRequested()
    val updateState by Updater.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onShareLogs) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.action_share_logs),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    ThemeToggleButton(
                        current = themePreference,
                        onClick = onCycleTheme
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        DashboardContent(
            padding = inner,
            state = state,
            running = running,
            hasPermissions = hasPermissions,
            onStart = {
                if (hasPermissions) viewModel.start() else onRequestPermissions()
            },
            onStop = viewModel::stop,
            onShareLogs = onShareLogs,
            onCheckUpdate = onCheckUpdate,
            onClearCache = viewModel::clearCachedSnapshot
        )

        UpdateOverlay(state = updateState, onDismiss = { Updater.reset() })
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

@Composable
private fun DashboardContent(
    padding: PaddingValues,
    state: AirPodsState,
    running: Boolean,
    hasPermissions: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShareLogs: () -> Unit,
    onCheckUpdate: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
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

        ExportLogsButton(onClick = onShareLogs)
        UpdateButton(onClick = onCheckUpdate)

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
    }
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
    BatteryCard(
        modifier = Modifier.fillMaxWidth(),
        iconRes = R.drawable.ic_case,
        title = if (inCase)
            stringResource(R.string.case_open_lid_hint)
        else
            stringResource(R.string.case_title),
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
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
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

@Composable
private fun ExportLogsButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_share),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_export_logs))
    }
}

@Composable
private fun UpdateButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_update),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_check_update))
    }
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

