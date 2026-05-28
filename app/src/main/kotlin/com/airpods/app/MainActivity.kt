package com.airpods.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.airpods.app.ui.dashboard.DashboardScreen
import com.airpods.app.ui.theme.AirPodsTheme
import com.airpods.app.ui.theme.ThemePrefs
import com.airpods.app.util.AppLogger
import com.airpods.app.util.LogShare

class MainActivity : ComponentActivity() {

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

        setContent {
            val themePref by ThemePrefs.flow.collectAsState()
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
                    onShareLogs = {
                        AppLogger.i("MainActivity", "user tapped Export logs")
                        runCatching {
                            LogShare.shareLogs(this, getString(R.string.action_share_logs))
                        }.onSuccess {
                            Toast.makeText(
                                this,
                                getString(R.string.toast_export_ok),
                                Toast.LENGTH_SHORT
                            ).show()
                        }.onFailure { err ->
                            AppLogger.e("MainActivity", "share failed", err)
                            Toast.makeText(
                                this,
                                getString(R.string.toast_export_failed, err.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }
        }
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
