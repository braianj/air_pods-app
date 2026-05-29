package com.airpods.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AppLogger {

    private const val APP_TAG = "AirPods"
    private const val MAX_FILE_SIZE = 1_000_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger").apply { isDaemon = true }
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        logFile = File(dir, "airpods.log")
        i(
            "Logger",
            "Logger ready. App=${context.packageName} " +
                "Device=${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )
    }

    fun d(tag: String, msg: String) {
        Log.d("$APP_TAG/$tag", msg)
        enqueue('D', tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i("$APP_TAG/$tag", msg)
        enqueue('I', tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w("$APP_TAG/$tag", msg, t) else Log.w("$APP_TAG/$tag", msg)
        enqueue('W', tag, msg + (t?.let { "\n${Log.getStackTraceString(it)}" } ?: ""))
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e("$APP_TAG/$tag", msg, t) else Log.e("$APP_TAG/$tag", msg)
        enqueue('E', tag, msg + (t?.let { "\n${Log.getStackTraceString(it)}" } ?: ""))
    }

    private fun enqueue(level: Char, tag: String, msg: String) {
        val file = logFile ?: return
        val timestamp = dateFormat.format(Date())
        executor.execute {
            try {
                rotateIfNeeded(file)
                FileWriter(file, true).use { w ->
                    w.append(timestamp)
                    w.append(' ')
                    w.append(level)
                    w.append('/')
                    w.append(tag)
                    w.append(": ")
                    w.append(msg)
                    w.append('\n')
                }
            } catch (e: IOException) {
                Log.w(APP_TAG, "log write failed", e)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.length() < MAX_FILE_SIZE) return
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    /**
     * Builds a single file with metadata + previous + current log content
     * inside the app's cache. Caller is expected to share it via FileProvider.
     */
    /**
     * Blocks until all queued log lines are flushed to disk.
     * Useful right before reading the log file for sharing/export.
     */
    fun flushBlocking(timeoutMs: Long = 1500) {
        try {
            executor.submit { /* drain barrier */ }
                .get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // best-effort; proceed with whatever made it to disk
        }
    }

    /**
     * Returns the tail of the log file as a string for in-app display.
     * Caps at maxBytes to avoid blowing up the UI.
     */
    fun readTail(maxBytes: Int = 200_000): String {
        flushBlocking()
        val current = logFile ?: return "(logger not initialised)"
        if (!current.exists()) return "(no log file yet)"
        val size = current.length()
        val start = (size - maxBytes).coerceAtLeast(0L)
        return current.inputStream().use { stream ->
            stream.skip(start)
            stream.bufferedReader().readText()
        }
    }

    fun snapshotForShare(context: Context): File {
        i("Logger", "snapshotForShare requested by user")
        flushBlocking()
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val out = File(dir, "airpods_share.log")
        out.bufferedWriter().use { w ->
            w.append("=== AirPods app logs ===\n")
            w.append("Generated: ${dateFormat.format(Date())}\n")
            w.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            w.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            w.append("App package: ${context.packageName}\n\n")

            val backup = File(dir, "airpods.log.1")
            if (backup.exists()) {
                w.append("--- older ---\n")
                backup.bufferedReader().use { r -> r.copyTo(w) }
                w.append('\n')
            }
            val current = logFile
            if (current != null && current.exists()) {
                w.append("--- current ---\n")
                current.bufferedReader().use { r -> r.copyTo(w) }
            } else {
                w.append("(no current log file)\n")
            }
        }
        return out
    }
}
