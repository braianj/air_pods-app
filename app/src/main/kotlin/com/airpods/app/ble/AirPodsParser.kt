package com.airpods.app.ble

object AirPodsParser {

    const val APPLE_MANUFACTURER_ID = 0x004C
    private const val PROXIMITY_PAIRING_SUBTYPE = 0x07
    private const val PROXIMITY_PAIRING_LENGTH = 0x19

    // Minimum bytes we need to read: subtype, length, vendor, model_hi, model_lo,
    // status, batteries, case+charging, lid.
    private const val MIN_PACKET_LENGTH = 9

    /**
     * Parses the manufacturer-specific data emitted by AirPods via Apple's
     * Continuity proximity pairing protocol.
     *
     * @param bytes manufacturer data **without** the 2-byte Apple ID prefix
     *              (as returned by [android.bluetooth.le.ScanRecord.getManufacturerSpecificData]).
     * @param rssi  signal strength reported by the scanner.
     */
    fun parse(bytes: ByteArray?, rssi: Int): AirPodsSnapshot? {
        if (bytes == null || bytes.size < MIN_PACKET_LENGTH) return null
        if (bytes[0].toInt() and 0xFF != PROXIMITY_PAIRING_SUBTYPE) return null
        if (bytes[1].toInt() and 0xFF != PROXIMITY_PAIRING_LENGTH) return null

        val modelId = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        val model = AirPodsModel.fromId(modelId)

        val status = bytes[5].toInt() and 0xFF
        val flip = (status and 0x20) != 0

        val batteryByte = bytes[6].toInt() and 0xFF
        val primaryNibble = (batteryByte shr 4) and 0x0F
        val secondaryNibble = batteryByte and 0x0F

        val (leftNibble, rightNibble) = if (flip) {
            primaryNibble to secondaryNibble
        } else {
            secondaryNibble to primaryNibble
        }

        val caseByte = bytes[7].toInt() and 0xFF
        val caseNibble = caseByte and 0x0F
        val chargingFlags = (caseByte shr 4) and 0x0F

        val rightChargingFlag = (chargingFlags and 0x01) != 0
        val leftChargingFlag = (chargingFlags and 0x02) != 0
        val caseCharging = (chargingFlags and 0x04) != 0

        val leftCharging = if (flip) rightChargingFlag else leftChargingFlag
        val rightCharging = if (flip) leftChargingFlag else rightChargingFlag

        val lid = bytes[8].toInt() and 0xFF
        // The lid counter increments on every open/close. Odd = open, even = closed.
        // When the lid is closed the buds are *in* the case.
        val inCase = (lid and 0x01) == 0

        return AirPodsSnapshot(
            model = model,
            leftPct = nibbleToPct(leftNibble),
            rightPct = nibbleToPct(rightNibble),
            casePct = nibbleToPct(caseNibble),
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            inCase = inCase,
            rssi = rssi,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun nibbleToPct(nibble: Int): Int? = when {
        nibble == 0x0F -> null            // unknown / disconnected
        nibble in 0..10 -> nibble * 10    // 0..10 maps to 0..100 %
        else -> null
    }
}
