package com.airpods.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsParserTest {

    /** Builds a synthetic Continuity proximity-pairing payload. */
    private fun packet(
        modelId: Int,
        flip: Boolean,
        primaryNibble: Int,
        secondaryNibble: Int,
        caseNibble: Int,
        leftCharging: Boolean = false,
        rightCharging: Boolean = false,
        caseCharging: Boolean = false,
        lid: Int = 0x10
    ): ByteArray {
        val status = if (flip) 0x20 else 0x00
        val battery = ((primaryNibble and 0x0F) shl 4) or (secondaryNibble and 0x0F)
        val flags = (if (rightCharging) 0x01 else 0) or
            (if (leftCharging) 0x02 else 0) or
            (if (caseCharging) 0x04 else 0)
        val caseByte = ((flags and 0x0F) shl 4) or (caseNibble and 0x0F)
        return byteArrayOf(
            0x07,
            0x19,
            0x01,
            ((modelId shr 8) and 0xFF).toByte(),
            (modelId and 0xFF).toByte(),
            status.toByte(),
            battery.toByte(),
            caseByte.toByte(),
            lid.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
    }

    @Test
    fun parses_airpods_pro_2_lightning_full_charge() {
        val pkt = packet(
            modelId = 0x1420,
            flip = false,
            primaryNibble = 10,   // right primary -> 100%
            secondaryNibble = 9,  // left -> 90%
            caseNibble = 7        // case -> 70%
        )
        val snap = AirPodsParser.parse(pkt, rssi = -52)
        assertNotNull(snap)
        assertEquals(AirPodsModel.AIRPODS_PRO_2, snap!!.model)
        assertEquals(90, snap.leftPct)
        assertEquals(100, snap.rightPct)
        assertEquals(70, snap.casePct)
        assertFalse(snap.leftCharging)
        assertFalse(snap.rightCharging)
        assertEquals(-52, snap.rssi)
    }

    @Test
    fun honors_flip_bit_for_primary_pod() {
        val pkt = packet(
            modelId = 0x1420,
            flip = true,         // left becomes primary
            primaryNibble = 4,   // -> left = 40%
            secondaryNibble = 8, // -> right = 80%
            caseNibble = 5
        )
        val snap = AirPodsParser.parse(pkt, rssi = -60)!!
        assertEquals(40, snap.leftPct)
        assertEquals(80, snap.rightPct)
    }

    @Test
    fun unknown_nibble_translates_to_null() {
        val pkt = packet(
            modelId = 0x1420,
            flip = false,
            primaryNibble = 0xF,   // right unknown
            secondaryNibble = 5,
            caseNibble = 0xF       // case unknown
        )
        val snap = AirPodsParser.parse(pkt, rssi = -65)!!
        assertEquals(50, snap.leftPct)
        assertNull(snap.rightPct)
        assertNull(snap.casePct)
    }

    @Test
    fun decodes_charging_flags() {
        val pkt = packet(
            modelId = 0x1420,
            flip = false,
            primaryNibble = 3,
            secondaryNibble = 4,
            caseNibble = 2,
            leftCharging = true,
            rightCharging = false,
            caseCharging = true
        )
        val snap = AirPodsParser.parse(pkt, rssi = -70)!!
        assertTrue(snap.leftCharging)
        assertFalse(snap.rightCharging)
        assertTrue(snap.caseCharging)
    }

    @Test
    fun rejects_packets_with_wrong_header() {
        val bad = byteArrayOf(0x06, 0x19, 0, 0, 0, 0, 0, 0, 0)
        assertNull(AirPodsParser.parse(bad, rssi = -50))
    }

    @Test
    fun rejects_packets_too_short() {
        val short = byteArrayOf(0x07, 0x19, 0x01, 0x14, 0x20)
        assertNull(AirPodsParser.parse(short, rssi = -50))
    }

    @Test
    fun recognises_pro_2_usbc_model_id() {
        val pkt = packet(
            modelId = 0x2420,
            flip = false,
            primaryNibble = 7,
            secondaryNibble = 7,
            caseNibble = 5
        )
        val snap = AirPodsParser.parse(pkt, rssi = -55)!!
        assertEquals(AirPodsModel.AIRPODS_PRO_2_USBC, snap.model)
    }
}
