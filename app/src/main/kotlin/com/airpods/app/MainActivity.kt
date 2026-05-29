package com.airpods.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airpods.app.ui.dashboard.DashboardScreen
import com.airpods.app.ui.theme.AirPodsTheme
import com.airpods.app.ui.theme.ThemePrefs
import com.airpods.app.update.UpdateChecker
import com.airpods.app.update.Updater
import com.airpods.app.util.AppLogger
import com.airpods.app.util.LogShare
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingUpdate = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    private val updateDismissed = MutableStateFlow(false)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLogger.i("MainActivity", "onCreate, granted=${currentPermissions()}")
        kickoffUpdateCheck()

        setContent {
            val themePref by ThemePrefs.flow.collectAsState()
            val update by pendingUpdate.collectAsState()
            val dismissed by updateDismissed.collectAsState()
            AirPodsTheme(pref = themePref) {
                var hasPermissions by remember {
                    mutableStateOf(checkPermissions())
                }
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    AppLogger.i("MainActivity", "permission result: $result")
                    hasPermissions = result.values.all { it }
                }
                DashboardScreen(
                    hasPermissions = hasPermissions,
                    onRequestPermissions = { launcher.launch(requiredPermissions) },
                    themePreference = themePref,
                    onCycleTheme = {
                        val next = themePref.next()
                        AppLogger.i("MainActivity", "theme cycled to $next")
                        ThemePrefs.set(this, next)
                    },
                    onShareLogs = ::exportLogs,
                    onCheckUpdate = ::checkUpdate,
                    onRequestOverlay = ::requestOverlayPermission
                )

                val info = update
                if (info != null && !dismissed) {
                    UpdateAvailableDialog(
                        info = info,
                        onInstall = {
                            updateDismissed.value = true
                            checkUpdate()
                        },
                        onLater = {
                            updateDismissed.value = true
                        }
                    )
                }
            }
        }
    }

    private fun kickoffUpdateCheck() {
        lifecycleScope.launch {
            delay(1_500)
            val info = UpdateChecker.check()
            if (info != null) {
                AppLogger.i("MainActivity", "auto-update available: $info")
                pendingUpdate.value = info
            }
        }
    }

    private fun exportLogs() {
        AppLogger.i("MainActivity", "user tapped Export logs")
        runCatching {
            LogShare.saveToDownloads(this)
        }.onSuccess { fileName ->
            if (fileName != null) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_export_ok, fileName),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_export_failed, "MediaStore"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onFailure { err ->
            AppLogger.e("MainActivity", "exportLogs failed", err)
            Toast.makeText(
                this,
                getString(R.string.toast_export_failed, err.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkUpdate() {
        AppLogger.i("MainActivity", "user tapped Check update")
        lifecycleScope.launch {
            Updater.downloadAndInstall(this@MainActivity)
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Ya está activo", Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.i("MainActivity", "requesting overlay permission")
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }

    private fun checkPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun currentPermissions(): Map<String, Boolean> =
        requiredPermissions.associateWith {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateChecker.UpdateInfo,
    onInstall: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(
                stringResource(
                    R.string.update_available_body,
                    info.latestShort,
                    info.installedShort
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(stringResource(R.string.update_install_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}
