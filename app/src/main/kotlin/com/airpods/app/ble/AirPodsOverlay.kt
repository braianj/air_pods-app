package com.airpods.app.ble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
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
        private const val SHOW_MS = 5_000L
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private val dismiss = Runnable { hide() }

    fun show(snapshot: AirPodsSnapshot): Boolean {
        if (!canDraw()) {
            AppLogger.d(TAG, "no SYSTEM_ALERT_WINDOW permission — skipping overlay")
            return false
        }
        main.post {
            val existing = current
            val view = existing ?: LayoutInflater.from(context).inflate(
                R.layout.overlay_battery, null, false
            )

            bind(view, snapshot)

            if (existing == null) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dpToPx(80)
                    windowAnimations = android.R.style.Animation_Toast
                }
                runCatching { wm.addView(view, params) }
                    .onFailure { AppLogger.w(TAG, "addView failed: ${it.message}") }
                current = view
                AppLogger.i(TAG, "overlay shown L=${snapshot.leftPct} R=${snapshot.rightPct} case=${snapshot.casePct}")
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
