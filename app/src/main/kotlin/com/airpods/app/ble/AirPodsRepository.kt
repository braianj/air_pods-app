package com.airpods.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AirPodsRepository {

    private val proximity = ProximityCalculator()

    private val _state = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = _state.asStateFlow()

    fun setStatus(status: ConnectionStatus) {
        _state.update { it.copy(status = status) }
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
    }

    fun onLost() {
        proximity.reset()
        _state.update { it.copy(status = ConnectionStatus.Idle, proximity = null) }
    }

    fun clear() {
        proximity.reset()
        _state.value = AirPodsState()
    }
}
