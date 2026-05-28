package com.airpods.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.airpods.app.MainActivity
import com.airpods.app.R
import com.airpods.app.ble.AirPodsState
import com.airpods.app.ble.ConnectionStatus

object BatteryNotificationManager {

    const val CHANNEL_ID = "airpods_battery"
    const val NOTIF_ID = 4242

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun buildPlaceholder(context: Context): android.app.Notification {
        return baseBuilder(context, statusText(context, ConnectionStatus.Scanning))
            .build()
    }

    fun update(context: Context, state: AirPodsState) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        val snapshot = state.snapshot

        val content = RemoteViews(context.packageName, R.layout.notification_battery)
        val statusLabel = statusText(context, state.status)
        content.setTextViewText(R.id.notif_status, statusLabel)

        bindSlot(
            content,
            iconViewId = R.id.notif_left_icon,
            textViewId = R.id.notif_left_pct,
            chargingViewId = R.id.notif_left_charging,
            pct = snapshot?.leftPct,
            charging = snapshot?.leftCharging == true
        )
        bindSlot(
            content,
            iconViewId = R.id.notif_right_icon,
            textViewId = R.id.notif_right_pct,
            chargingViewId = R.id.notif_right_charging,
            pct = snapshot?.rightPct,
            charging = snapshot?.rightCharging == true
        )
        bindSlot(
            content,
            iconViewId = R.id.notif_case_icon,
            textViewId = R.id.notif_case_pct,
            chargingViewId = R.id.notif_case_charging,
            pct = snapshot?.casePct,
            charging = snapshot?.caseCharging == true
        )

        val builder = baseBuilder(context, statusLabel)
            .setCustomContentView(content)
            .setCustomBigContentView(content)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        // Show the battery level as a number in the status bar. The system
        // reserves the right edge (next to its own battery/clock) for itself,
        // so this lands in the status-bar notification area on the left.
        val statusBarPct = listOfNotNull(snapshot?.leftPct, snapshot?.rightPct).minOrNull()
            ?: snapshot?.casePct
        if (statusBarPct != null) {
            builder.setSmallIcon(batteryStatusIcon(context, statusBarPct))
        }

        nm.notify(NOTIF_ID, builder.build())
    }

    private fun batteryStatusIcon(context: Context, pct: Int): IconCompat {
        val size = (context.resources.displayMetrics.density * 24f).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val text = pct.coerceIn(0, 100).toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * if (text.length >= 3) 0.52f else 0.72f
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, size / 2f, y, paint)
        return IconCompat.createWithBitmap(bmp)
    }

    private fun bindSlot(
        content: RemoteViews,
        iconViewId: Int,
        textViewId: Int,
        chargingViewId: Int,
        pct: Int?,
        charging: Boolean
    ) {
        val text = pct?.let { "$it%" } ?: "—"
        content.setTextViewText(textViewId, text)
        content.setViewVisibility(
            chargingViewId,
            if (charging) android.view.View.VISIBLE else android.view.View.GONE
        )
        // Always keep the icon visible; alpha hints "unknown".
        content.setInt(iconViewId, "setImageAlpha", if (pct == null) 90 else 255)
    }

    private fun baseBuilder(context: Context, status: CharSequence): NotificationCompat.Builder {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun statusText(context: Context, status: ConnectionStatus): String =
        when (status) {
            ConnectionStatus.Idle -> context.getString(R.string.status_idle)
            ConnectionStatus.Scanning -> context.getString(R.string.status_scanning)
            is ConnectionStatus.AudioConnected ->
                context.getString(R.string.status_audio_connected, status.deviceName)
            is ConnectionStatus.Connected -> context.getString(R.string.status_connected)
            ConnectionStatus.BluetoothOff -> context.getString(R.string.status_bt_off)
            ConnectionStatus.MissingPermissions -> context.getString(R.string.status_missing_perms)
            is ConnectionStatus.Error -> context.getString(R.string.status_error, status.message)
        }
}
