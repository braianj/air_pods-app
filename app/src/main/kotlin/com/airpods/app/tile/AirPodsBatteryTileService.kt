package com.airpods.app.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airpods.app.R
import com.airpods.app.ble.AirPodsRepository
import com.airpods.app.ble.AirPodsState
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AirPodsBatteryTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        AppLogger.i(TAG, "onStartListening")
        collectJob?.cancel()
        collectJob = scope.launch {
            AirPodsRepository.state.collectLatest { state -> render(state) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        AppLogger.i(TAG, "onStopListening")
        collectJob?.cancel()
        collectJob = null
    }

    override fun onClick() {
        super.onClick()
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = android.app.PendingIntent.getActivity(
                this, 0, launch,
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    private fun render(state: AirPodsState) {
        val tile = qsTile ?: return
        val snap = state.snapshot
        tile.icon = Icon.createWithResource(this, R.drawable.ic_airpods)

        if (snap == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.qs_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = state.audioConnectedName
                    ?.let { getString(R.string.qs_tile_audio_only, it) }
                    ?: getString(R.string.qs_tile_no_data)
            }
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = snap.model.displayName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = formatSubtitle(snap.leftPct, snap.rightPct, snap.casePct)
            }
        }
        tile.updateTile()
    }

    private fun formatSubtitle(left: Int?, right: Int?, case: Int?): String {
        fun fmt(p: Int?) = p?.let { "${it}%" } ?: "—"
        return "L ${fmt(left)} · R ${fmt(right)} · ${fmt(case)}"
    }

    companion object {
        private const val TAG = "Tile"
    }
}
