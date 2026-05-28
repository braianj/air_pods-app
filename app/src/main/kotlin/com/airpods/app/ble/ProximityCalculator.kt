package com.airpods.app.ble

import kotlin.math.pow

enum class ProximityBucket {
    Touching, Near, Close, Far, Lost
}

data class ProximityReading(
    val distanceMeters: Float,
    val bucket: ProximityBucket,
    val rssi: Int
)

/**
 * Translates raw RSSI samples into a smoothed distance estimate using the
 * log-distance path-loss model:
 *   distance = 10 ^ ((txPower - rssi) / (10 * n))
 *
 * - txPower: reference RSSI at 1 m (-59 dBm is the calibrated value for AirPods).
 * - n: environmental factor, 2.7 mimics a typical indoor space.
 * Samples are smoothed with an exponential moving average so the meter doesn't
 * jump on a single noisy packet.
 */
class ProximityCalculator(
    private val txPower: Int = -59,
    private val n: Float = 2.7f,
    private val alpha: Float = 0.3f
) {
    private var smoothedRssi: Float? = null

    fun update(rssi: Int): ProximityReading {
        val prev = smoothedRssi
        val next = if (prev == null) rssi.toFloat() else prev + alpha * (rssi - prev)
        smoothedRssi = next

        val distance = 10f.pow((txPower - next) / (10f * n))
        return ProximityReading(
            distanceMeters = distance,
            bucket = bucketFor(distance),
            rssi = next.toInt()
        )
    }

    fun reset() {
        smoothedRssi = null
    }

    private fun bucketFor(distance: Float): ProximityBucket = when {
        distance <= 0.4f -> ProximityBucket.Touching
        distance <= 1.5f -> ProximityBucket.Near
        distance <= 3.0f -> ProximityBucket.Close
        distance <= 6.0f -> ProximityBucket.Far
        else -> ProximityBucket.Lost
    }
}
