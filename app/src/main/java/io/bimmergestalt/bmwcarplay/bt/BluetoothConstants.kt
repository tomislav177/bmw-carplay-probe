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
     * Accessory / Head-unit-side Wireless iAP2 RFCOMM SDP service UUID.
     */
    val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

    const val SDP_SERVICE_NAME = "Wireless iAP"

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
