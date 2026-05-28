package com.airpods.app.ble

enum class AirPodsModel(val id: Int, val displayName: String) {
    AIRPODS_1(0x0220, "AirPods (1st gen)"),
    AIRPODS_2(0x0F20, "AirPods (2nd gen)"),
    AIRPODS_3(0x1320, "AirPods (3rd gen)"),
    AIRPODS_PRO(0x0E20, "AirPods Pro"),
    AIRPODS_PRO_2(0x1420, "AirPods Pro (2nd gen)"),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro (2nd gen, USB-C)"),
    AIRPODS_MAX(0x0A20, "AirPods Max"),
    AIRPODS_MAX_USBC(0x1F20, "AirPods Max (USB-C)"),
    UNKNOWN(0x0000, "Unknown AirPods");

    companion object {
        fun fromId(id: Int): AirPodsModel =
            entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}
