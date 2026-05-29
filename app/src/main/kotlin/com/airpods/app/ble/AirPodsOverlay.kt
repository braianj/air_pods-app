package com.airpods.app.ble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.airpods.app.R
import com.airpods.app.util.AppLogger

/**
 * Draws an iOS-style "case open" popup above other apps when a fresh AirPods
 * battery snapshot arrives. Uses SYSTEM_ALERT_WINDOW so it works while the
 * user is in another app or the launcher.
 */
class AirPodsOverlay(private val context: Context) {

    companion object {
        private const val TAG = "Overlay"
        private const val SHOW_MS = 4_000L
        // After the user dismisses (tap or swipe), suppress all show() calls
        // for this long. Prevents the popup from re-firing on the next
        // fresh-open within a short window.
        private const val SNOOZE_MS = 30_000L
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private val dismiss = Runnable { hide() }
    private var snoozedUntil: Long = 0L

    fun show(snapshot: AirPodsSnapshot): Boolean {
        if (!canDraw()) {
            AppLogger.d(TAG, "no SYSTEM_ALERT_WINDOW permission — skipping overlay")
            return false
        }
        // Don't surface the popup in landscape — the layout was designed for
        // portrait and stretches awkwardly across the wider edge.
        val orientation = context.resources.configuration.orientation
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            AppLogger.d(TAG, "landscape orientation — skipping overlay")
            return false
        }
        if (System.currentTimeMillis() < snoozedUntil) {
            AppLogger.d(TAG, "overlay snoozed by user — skipping")
            return true
        }
        main.post {
            val existing = current
            val view = existing ?: LayoutInflater.from(context).inflate(
                R.layout.overlay_battery, null, false
            )

            bind(view, snapshot)
            attachDismissHandlers(view)

            if (existing == null) {
                val widthPx = (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
                val params = WindowManager.LayoutParams(
                    widthPx,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    // Anchor at the top: the keyboard often covers anything
                    // near the bottom, and there's no reliable way for a
                    // foreign app to know another app's IME is visible.
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dpToPx(64)
                    windowAnimations = android.R.style.Animation_Toast
                }
                runCatching { wm.addView(view, params) }
                    .onFailure { AppLogger.w(TAG, "addView failed: ${it.message}") }
                current = view
                AppLogger.i(
                    TAG,
                    "overlay shown L=${snapshot.leftPct} R=${snapshot.rightPct} case=${snapshot.casePct}"
                )
            }

            main.removeCallbacks(dismiss)
            main.postDelayed(dismiss, SHOW_MS)
        }
        return true
    }

    fun hide() {
        main.post {
            val view = current ?: return@post
            current = null
            runCatching { wm.removeView(view) }
        }
    }

    /** Hide + snooze; called when the user taps or swipes-up on the overlay. */
    private fun userDismissed() {
        snoozedUntil = System.currentTimeMillis() + SNOOZE_MS
        AppLogger.i(TAG, "user dismissed overlay, snoozed until +${SNOOZE_MS / 1000}s")
        hide()
    }

    private fun attachDismissHandlers(view: View) {
        // Tap to dismiss.
        view.setOnClickListener { userDismissed() }
        // Swipe-up to dismiss (overlay sits at the top now).
        val touchSlopDp = 24f
        val slopPx = touchSlopDp * context.resources.displayMetrics.density
        var startY = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (startY - ev.rawY > slopPx) {
                        userDismissed()
                        true
                    } else false
                }
                else -> false
            }
        }
        // Close icon (an X TextView in the layout).
        view.findViewById<TextView>(R.id.overlay_close)?.setOnClickListener {
            userDismissed()
        }
    }

    private fun bind(view: View, s: AirPodsSnapshot) {
        view.findViewById<TextView>(R.id.overlay_model)?.text = s.model.displayName
        view.findViewById<TextView>(R.id.overlay_left_pct)?.text = format(s.leftPct, s.leftCharging)
        view.findViewById<TextView>(R.id.overlay_right_pct)?.text = format(s.rightPct, s.rightCharging)
        view.findViewById<TextView>(R.id.overlay_case_pct)?.text = format(s.casePct, s.caseCharging)
    }

    private fun format(pct: Int?, charging: Boolean): String {
        if (pct == null) return "—"
        return if (charging) "$pct% ⚡" else "$pct%"
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun dpToPx(dp: Int): Int =
        (context.resources.displayMetrics.density * dp).toInt()

    private fun canDraw(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(context)
        else true
}
