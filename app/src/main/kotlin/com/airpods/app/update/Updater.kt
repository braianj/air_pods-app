package com.airpods.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    private const val APK_URL =
        "https://github.com/braianj/air_pods-app/releases/download/latest/app-debug.apk"

    sealed interface UpdateState {
        data object Idle : UpdateState
        data class Downloading(val progress: Float, val bytes: Long, val total: Long) : UpdateState
        data object NeedsInstallPermission : UpdateState
        data class Done(val apkPath: String) : UpdateState
        data class Error(val message: String) : UpdateState
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun reset() {
        _state.value = UpdateState.Idle
    }

    suspend fun downloadAndInstall(context: Context) {
        AppLogger.i(TAG, "downloadAndInstall start")
        if (!context.packageManager.canRequestPackageInstalls()) {
            AppLogger.w(TAG, "REQUEST_INSTALL_PACKAGES not granted, routing to settings")
            _state.value = UpdateState.NeedsInstallPermission
            openInstallPermissionSettings(context)
            return
        }
        try {
            _state.value = UpdateState.Downloading(0f, 0L, -1L)
            val apk = downloadApk(context)
            if (apk == null) {
                _state.value = UpdateState.Error("Download failed")
                return
            }
            AppLogger.i(TAG, "download complete: ${apk.length()} bytes")
            launchInstaller(context, apk)
            _state.value = UpdateState.Done(apk.absolutePath)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "update flow failed", t)
            _state.value = UpdateState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun downloadApk(context: Context): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "airpods-update.apk")
        runCatching { out.delete() }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/vnd.android.package-archive")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                AppLogger.e(TAG, "HTTP $code from $APK_URL")
                return@withContext null
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            AppLogger.i(TAG, "downloading $total bytes from $APK_URL")
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val progress = (copied.toFloat() / total).coerceIn(0f, 1f)
                            _state.value = UpdateState.Downloading(progress, copied, total)
                        }
                    }
                }
            }
            out
        } catch (t: Throwable) {
            AppLogger.e(TAG, "downloadApk error", t)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLogger.i(TAG, "launching installer for $uri")
        context.startActivity(intent)
    }

    private fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private const val TAG = "Updater"
}
