package com.airpods.app.ble

data class AirPodsSnapshot(
    val model: AirPodsModel,
    val leftPct: Int?,
    val rightPct: Int?,
    val casePct: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val inCase: Boolean,
    val rssi: Int,
    val timestampMs: Long
)

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object MissingPermissions : ConnectionStatus
    data object BluetoothOff : ConnectionStatus
    data object Scanning : ConnectionStatus
    data class AudioConnected(val deviceName: String) : ConnectionStatus
    data class Connected(val sinceMs: Long) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

data class AirPodsState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val snapshot: AirPodsSnapshot? = null,
    val proximity: ProximityReading? = null,
    val audioConnectedName: String? = null
)
