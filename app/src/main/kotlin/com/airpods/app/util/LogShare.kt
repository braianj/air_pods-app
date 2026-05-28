package com.airpods.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

object LogShare {

    fun shareLogs(context: Context, chooserTitle: String) {
        val file = AppLogger.snapshotForShare(context)
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AirPods app logs")
            putExtra(Intent.EXTRA_TEXT, "Logs from the AirPods app.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
