package io.bimmergestalt.bmwcarplay.bt

import java.util.UUID

/**
 * Confirmed Wireless CarPlay iAP2 Bluetooth constants and target vehicle/phone defaults.
 */
object BluetoothConstants {
    /**
     * iPhone / Phone-side Wireless iAP2 RFCOMM SDP service UUID.
     * The BMW head unit connects inbound to this record when pairing/starting Wireless CarPlay.
     */
    val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

    /**
     * iPhone / Phone-side Wireless iAP v2 RFCOMM SDP service UUID (advertised alongside ...decacafe).
     */
    val IPHONE_IAP2_V2_UUID: UUID = UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77")

    /**
     * Apple CarPlay iPhone-side Bluetooth SDP/EIR capability UUID.
     * Direct counterpart to the vehicle-side UUID (d31fbf50-5d57-2797-a240-41cd484388ec)
     * live-confirmed in BMW 04990 (HU_ENTRYNAV2) SDP records.
     */
    val IPHONE_CARPLAY_UUID: UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")

    /**
     * Apple CarPlay vehicle-side Bluetooth SDP UUID (live-confirmed on BMW 04990).
     */
    val VEHICLE_CARPLAY_UUID: UUID = UUID.fromString("d31fbf50-5d57-2797-a240-41cd484388ec")

    /**
     * Accessory / Head-unit-side Wireless iAP2 RFCOMM SDP service UUID.
     */
    val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

    const val SDP_SERVICE_NAME = "Wireless iAP"
    const val SDP_IAP2_V2_SERVICE_NAME = "Wireless iAP v2"
    const val SDP_CARPLAY_SERVICE_NAME = "CarPlay"

    /** Confirmed Samsung Galaxy S25 real Bluetooth BR/EDR MAC address. */
    const val DEFAULT_PHONE_BT_MAC = "7C:7B:BF:74:40:67"

    /** Confirmed BMW X1 F48 (HU_ENTRYNAV2) Bluetooth BR/EDR MAC address and name. */
    const val KNOWN_BMW_MAC = "F8:30:02:FC:79:A4"
    const val KNOWN_BMW_NAME = "BMW 04990"

    /** Known wearable MAC to always exclude from outbound car connection attempts. */
    const val KNOWN_WATCH_MAC = "AC:80:FB:DA:A7:CB"

    /** Placeholder address returned by Android 12+ BluetoothAdapter.getAddress(). */
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
}
