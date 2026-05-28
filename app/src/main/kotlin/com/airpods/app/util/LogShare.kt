package com.airpods.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogShare {

    /**
     * Writes the current log snapshot into the user's public Downloads
     * directory via MediaStore (Android 10+). Returns the human-readable
     * filename on success or null on failure.
     */
    fun saveToDownloads(context: Context): String? {
        AppLogger.flushBlocking()
        val source = AppLogger.snapshotForShare(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "airpods_log_$ts.txt"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target: Uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: run {
            AppLogger.e("LogShare", "MediaStore.insert returned null")
            return null
        }

        return try {
            resolver.openOutputStream(target)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: run {
                AppLogger.e("LogShare", "openOutputStream returned null")
                resolver.delete(target, null, null)
                return null
            }
            val finalize = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(target, finalize, null, null)
            AppLogger.i("LogShare", "saved to Downloads/$name (${source.length()} bytes)")
            name
        } catch (t: Throwable) {
            AppLogger.e("LogShare", "saveToDownloads failed", t)
            runCatching { resolver.delete(target, null, null) }
            null
        }
    }

    /**
     * Optional: also open the system share sheet so the user can pick
     * WhatsApp/Drive/Mail to attach the same file straight away.
     */
    fun shareViaChooser(context: Context, chooserTitle: String) {
        AppLogger.flushBlocking()
        val file = AppLogger.snapshotForShare(context)
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AirPods app logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
