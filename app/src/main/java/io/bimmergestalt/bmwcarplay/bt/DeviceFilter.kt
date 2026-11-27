package io.bimmergestalt.bmwcarplay.bt

import android.bluetooth.BluetoothClass
import java.util.UUID

/**
 * Pure filtering logic ensuring the Bluetooth scanner/outbound client ONLY targets
 * the user's selected vehicle / BMW head unit / CarPlay accessory UUID and NEVER
 * attempts to connect to smartwatches (e.g. Galaxy Watch6 Classic AC:80:FB:DA:A7:CB)
 * or headphones.
 */
object DeviceFilter {

    data class DeviceCandidate(
        val name: String?,
        val address: String,
        val majorDeviceClass: Int = BluetoothClass.Device.Major.UNCATEGORIZED,
        val deviceClass: Int = 0,
        val uuids: Set<UUID> = emptySet(),
    )

    private val EXCLUDED_NAME_KEYWORDS = listOf(
        "watch",
        "buds",
        "airpods",
        "headset",
        "headphone",
        "earbud",
        "earphone",
        "band",
        "fit",
        "garmin",
        "mi band",
    )

    private val EXCLUDED_DEVICE_CLASSES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        BluetoothClass.Device.WEARABLE_WRIST_WATCH,
        BluetoothClass.Device.WEARABLE_PAGER,
        BluetoothClass.Device.WEARABLE_JACKET,
        BluetoothClass.Device.WEARABLE_HELMET,
        BluetoothClass.Device.WEARABLE_GLASSES,
    )

    /**
     * Returns true if the device is explicitly a wearable/headset or known excluded watch.
     */
    fun isExplicitlyExcluded(candidate: DeviceCandidate): Boolean {
        val normalizedMac = candidate.address.trim().uppercase()
        if (normalizedMac == BluetoothConstants.KNOWN_WATCH_MAC) return true
        if (candidate.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE) return true
        if (candidate.deviceClass in EXCLUDED_DEVICE_CLASSES) return true

        val lowerName = candidate.name.orEmpty().lowercase()
        if (EXCLUDED_NAME_KEYWORDS.any { lowerName.contains(it) }) return true

        return false
    }

    /**
     * Returns true if the device is eligible as a CarPlay head unit target for outbound RFCOMM.
     *
     * Rules:
     * 1. Explicitly excluded wearables/headphones are ALWAYS rejected (even if misconfigured).
     * 2. If [selectedTargetMac] is configured, ONLY that exact MAC is allowed.
     * 3. Otherwise, allow only devices matching BMW* name, known BMW MAC (F8:30:02:FC:79:A4),
     *    or advertising the CarPlay Accessory SDP UUID (...decacaff).
     */
    fun isEligibleOutboundTarget(
        candidate: DeviceCandidate,
        selectedTargetMac: String? = null,
    ): Boolean {
        if (isExplicitlyExcluded(candidate)) return false

        val normalizedMac = candidate.address.trim().uppercase()
        val targetMac = selectedTargetMac?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (targetMac != null) {
            return normalizedMac == targetMac
        }

        if (normalizedMac == BluetoothConstants.KNOWN_BMW_MAC) return true
        if (candidate.name.orEmpty().trim().startsWith("BMW", ignoreCase = true)) return true
        if (BluetoothConstants.ACCESSORY_IAP2_UUID in candidate.uuids) return true

        return false
    }

    /**
     * Checks whether a bonded device should be offered in the UI car selector dropdown.
     */
    fun isSelectableInUi(candidate: DeviceCandidate): Boolean = !isExplicitlyExcluded(candidate)

    /**
     * Picks the best auto-detected car device from a bonded list (prioritizing known BMW MAC,
     * then BMW* name, then CarPlay Accessory SDP UUID).
     */
    fun pickDefaultCar(candidates: List<DeviceCandidate>): DeviceCandidate? {
        val allowed = candidates.filter { !isExplicitlyExcluded(it) }
        return allowed.firstOrNull { it.address.equals(BluetoothConstants.KNOWN_BMW_MAC, ignoreCase = true) }
            ?: allowed.firstOrNull { it.name.orEmpty().trim().startsWith("BMW", ignoreCase = true) }
            ?: allowed.firstOrNull { BluetoothConstants.ACCESSORY_IAP2_UUID in it.uuids }
    }
}
