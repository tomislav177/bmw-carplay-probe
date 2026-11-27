package io.bimmergestalt.bmwcarplay.iap2.csm

/**
 * Registry of iAP2 Control Session Message (CSM) IDs used in Wireless CarPlay.
 */
object Iap2MessageIds {
    // Identification
    const val START_IDENTIFICATION = 0x1D00
    const val IDENTIFICATION_INFORMATION = 0x1D01
    const val IDENTIFICATION_ACCEPTED = 0x1D02
    const val IDENTIFICATION_REJECTED = 0x1D03
    const val CANCEL_IDENTIFICATION = 0x1D05
    const val IDENTIFICATION_INFORMATION_UPDATE = 0x1D06

    // MFi Authentication (one-way: phone challenges accessory)
    const val REQUEST_AUTH_CERTIFICATE = 0xAA00
    const val AUTH_CERTIFICATE = 0xAA01
    const val REQUEST_AUTH_CHALLENGE_RESPONSE = 0xAA02
    const val AUTH_RESPONSE = 0xAA03
    const val AUTH_FAILED = 0xAA04
    const val AUTH_SUCCEEDED = 0xAA05
    const val AUTH_SERIAL_NUMBER = 0xAA06

    // Wi-Fi Configuration
    const val REQUEST_WIFI_INFO = 0x5700
    const val WIFI_INFO = 0x5701
    const val REQUEST_ACCESSORY_WIFI_CONFIG = 0x5702
    const val ACCESSORY_WIFI_CONFIG = 0x5703

    // CarPlay Session & Device Transport Notifications
    const val CARPLAY_AVAILABILITY = 0x4300
    const val START_WIRELESS_CARPLAY_SESSION = 0x4301
    const val DEVICE_INFORMATION_UPDATE = 0x4E09
    const val DEVICE_LANGUAGE_UPDATE = 0x4E0A
    const val DEVICE_TIME_UPDATE = 0x4E0B
    const val DEVICE_UUID_UPDATE = 0x4E0C
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D
    const val DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E

    // HID, External Accessory, Media, Vehicle, Location
    const val START_HID = 0x6800
    const val DEVICE_HID_REPORT = 0x6801
    const val ACCESSORY_HID_REPORT = 0x6802
    const val STOP_HID = 0x6803

    const val START_EXTERNAL_ACCESSORY_PROTOCOL_SESSION = 0xEA00
    const val STOP_EXTERNAL_ACCESSORY_PROTOCOL_SESSION = 0xEA01
    const val REQUEST_APP_LAUNCH = 0xEA02
    const val STATUS_EXTERNAL_ACCESSORY_PROTOCOL_SESSION = 0xEA03

    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val NOW_PLAYING_UPDATES = 0x5001
    const val STOP_NOW_PLAYING_UPDATES = 0x5002

    const val START_VEHICLE_STATUS_UPDATES = 0xA100
    const val VEHICLE_STATUS_UPDATE = 0xA101
    const val STOP_VEHICLE_STATUS_UPDATES = 0xA102

    const val START_LOCATION_INFORMATION = 0xFFFA
    const val LOCATION_INFORMATION = 0xFFFB
    const val STOP_LOCATION_INFORMATION = 0xFFFC

    fun nameOf(id: Int): String = when (id) {
        START_IDENTIFICATION -> "StartIdentification"
        IDENTIFICATION_INFORMATION -> "IdentificationInformation"
        IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
        IDENTIFICATION_REJECTED -> "IdentificationRejected"
        CANCEL_IDENTIFICATION -> "CancelIdentification"
        IDENTIFICATION_INFORMATION_UPDATE -> "IdentificationInformationUpdate"
        REQUEST_AUTH_CERTIFICATE -> "RequestAuthenticationCertificate"
        AUTH_CERTIFICATE -> "AuthenticationCertificate"
        REQUEST_AUTH_CHALLENGE_RESPONSE -> "RequestAuthenticationChallengeResponse"
        AUTH_RESPONSE -> "AuthenticationResponse"
        AUTH_FAILED -> "AuthenticationFailed"
        AUTH_SUCCEEDED -> "AuthenticationSucceeded"
        AUTH_SERIAL_NUMBER -> "AuthenticationSerialNumber"
        REQUEST_WIFI_INFO -> "RequestWiFiInformation"
        WIFI_INFO -> "WiFiInformation"
        REQUEST_ACCESSORY_WIFI_CONFIG -> "RequestAccessoryWiFiConfigurationInformation"
        ACCESSORY_WIFI_CONFIG -> "AccessoryWiFiConfigurationInformation"
        CARPLAY_AVAILABILITY -> "CarPlayAvailability"
        START_WIRELESS_CARPLAY_SESSION -> "StartWirelessCarPlaySession"
        DEVICE_INFORMATION_UPDATE -> "DeviceInformationUpdate"
        DEVICE_LANGUAGE_UPDATE -> "DeviceLanguageUpdate"
        DEVICE_TIME_UPDATE -> "DeviceTimeUpdate"
        DEVICE_UUID_UPDATE -> "DeviceUUIDUpdate"
        WIRELESS_CARPLAY_UPDATE -> "WirelessCarPlayUpdate"
        DEVICE_TRANSPORT_IDENTIFIER -> "DeviceTransportIdentifierNotification"
        START_HID -> "StartHID"
        DEVICE_HID_REPORT -> "DeviceHIDReport"
        ACCESSORY_HID_REPORT -> "AccessoryHIDReport"
        STOP_HID -> "StopHID"
        START_EXTERNAL_ACCESSORY_PROTOCOL_SESSION -> "StartExternalAccessoryProtocolSession"
        STOP_EXTERNAL_ACCESSORY_PROTOCOL_SESSION -> "StopExternalAccessoryProtocolSession"
        REQUEST_APP_LAUNCH -> "RequestAppLaunch"
        STATUS_EXTERNAL_ACCESSORY_PROTOCOL_SESSION -> "StatusExternalAccessoryProtocolSession"
        START_NOW_PLAYING_UPDATES -> "StartNowPlayingUpdates"
        NOW_PLAYING_UPDATES -> "NowPlayingUpdates"
        STOP_NOW_PLAYING_UPDATES -> "StopNowPlayingUpdates"
        START_VEHICLE_STATUS_UPDATES -> "StartVehicleStatusUpdates"
        VEHICLE_STATUS_UPDATE -> "VehicleStatusUpdate"
        STOP_VEHICLE_STATUS_UPDATES -> "StopVehicleStatusUpdates"
        START_LOCATION_INFORMATION -> "StartLocationInformation"
        LOCATION_INFORMATION -> "LocationInformation"
        STOP_LOCATION_INFORMATION -> "StopLocationInformation"
        else -> "Unknown"
    }

    fun formatId(id: Int): String = String.format("0x%04X (%s)", id and 0xFFFF, nameOf(id))
}
