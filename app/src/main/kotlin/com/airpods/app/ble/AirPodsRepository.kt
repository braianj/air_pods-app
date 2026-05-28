package com.airpods.app.ble

import android.content.Context
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AirPodsRepository {

    private const val PREFS = "airpods_repo"
    private const val KEY_LAST_LEFT = "last_left"
    private const val KEY_LAST_RIGHT = "last_right"
    private const val KEY_LAST_CASE = "last_case"
    private const val KEY_LAST_MODEL = "last_model"
    private const val KEY_LAST_TS = "last_ts"

    private val proximity = ProximityCalculator()

    private val _state = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = _state.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        restoreLastSnapshot()
    }

    fun setStatus(status: ConnectionStatus) {
        _state.update { it.copy(status = status) }
    }

    fun setAudioConnected(name: String?) {
        _state.update { it.copy(audioConnectedName = name) }
        if (name != null && _state.value.snapshot == null) {
            _state.update { it.copy(status = ConnectionStatus.AudioConnected(name)) }
        }
    }

    fun onSnapshot(snapshot: AirPodsSnapshot) {
        val reading = proximity.update(snapshot.rssi)
        _state.update {
            it.copy(
                status = ConnectionStatus.Connected(snapshot.timestampMs),
                snapshot = snapshot,
                proximity = reading
            )
        }
        // Only persist FULL captures (all three values present). Partial
        // captures — like the one we get during pairing when one pod is in
        // the ear — would otherwise stick around forever as misleading
        // "40% / — / 40%" stale data with no way to know it's stale.
        if (snapshot.leftPct != null && snapshot.rightPct != null && snapshot.casePct != null) {
            persistLastSnapshot(snapshot)
        }
    }

    fun clearPersisted() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        proximity.reset()
        val audio = _state.value.audioConnectedName
        _state.value = AirPodsState(audioConnectedName = audio)
        AppLogger.i("Repo", "persisted snapshot cleared by user")
    }

    fun onLost() {
        proximity.reset()
        _state.update {
            val audio = it.audioConnectedName
            val nextStatus = if (audio != null) ConnectionStatus.AudioConnected(audio)
            else ConnectionStatus.Idle
            it.copy(status = nextStatus, proximity = null)
        }
    }

    fun clear() {
        proximity.reset()
        val audio = _state.value.audioConnectedName
        _state.value = AirPodsState(audioConnectedName = audio)
    }

    private fun persistLastSnapshot(s: AirPodsSnapshot) {
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit()
            .putInt(KEY_LAST_LEFT, s.leftPct ?: -1)
            .putInt(KEY_LAST_RIGHT, s.rightPct ?: -1)
            .putInt(KEY_LAST_CASE, s.casePct ?: -1)
            .putString(KEY_LAST_MODEL, s.model.name)
            .putLong(KEY_LAST_TS, s.timestampMs)
            .apply()
    }

    private fun restoreLastSnapshot() {
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = sp.getLong(KEY_LAST_TS, 0L)
        if (ts == 0L) return
        val ageMs = System.currentTimeMillis() - ts
        // Anything older than 24h is so stale it's actively misleading. Wipe it.
        if (ageMs > 24 * 60 * 60 * 1000L) {
            sp.edit().clear().apply()
            AppLogger.i("Repo", "wiped stale snapshot from ${ageMs / 1000}s ago")
            return
        }
        val left = sp.getInt(KEY_LAST_LEFT, -1).takeIf { it >= 0 }
        val right = sp.getInt(KEY_LAST_RIGHT, -1).takeIf { it >= 0 }
        val case = sp.getInt(KEY_LAST_CASE, -1).takeIf { it >= 0 }
        if (left == null && right == null && case == null) return
        val modelName = sp.getString(KEY_LAST_MODEL, null)
        val model = runCatching { AirPodsModel.valueOf(modelName ?: "UNKNOWN") }
            .getOrDefault(AirPodsModel.UNKNOWN)
        val snapshot = AirPodsSnapshot(
            model = model,
            leftPct = left,
            rightPct = right,
            casePct = case,
            leftCharging = false,
            rightCharging = false,
            caseCharging = false,
            inCase = false,
            rssi = 0,
            timestampMs = ts
        )
        _state.update { it.copy(snapshot = snapshot) }
        AppLogger.i("Repo", "restored last snapshot L=$left R=$right case=$case from ${(System.currentTimeMillis() - ts) / 1000}s ago")
    }
}
