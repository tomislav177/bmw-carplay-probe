package io.bimmergestalt.bmwcarplay

import android.bluetooth.BluetoothClass
import io.bimmergestalt.bmwcarplay.airplay.AirPlayFeatures
import io.bimmergestalt.bmwcarplay.bt.BluetoothConstants
import io.bimmergestalt.bmwcarplay.bt.DeviceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayFeaturesAndDeviceFilterTest {

    @Test
    fun airPlayFeaturesParsesSplitHexAndDetectsBit38HkPairing() {
        // 0x61 high word = 0b0110_0001 -> bits 32, 37, 38 set!
        val withHk = AirPlayFeatures.analyze("0x4040280,0x61")
        assertNotNull(withHk)
        assertTrue(withHk!!.hasBit38HkPairing)
        assertTrue(withHk.hasBit37CarPlayControl)
        assertTrue(withHk.hasAnyPairingBit)

        // 0x21 high word = 0b0010_0001 -> bits 32, 37 set; bit 38 NOT set (pre-HomeKit / AES-CTR)
        val withoutHk = AirPlayFeatures.analyze("0x4040280,0x21")
        assertNotNull(withoutHk)
        assertFalse(withoutHk!!.hasBit38HkPairing)
        assertTrue(withoutHk.hasBit37CarPlayControl)
        assertFalse(withoutHk.hasAnyPairingBit)

        assertNull(AirPlayFeatures.analyze("invalid"))
        assertNull(AirPlayFeatures.analyze(""))
    }

    @Test
    fun deviceFilterAcceptsBmwAndStrictlyRejectsGalaxyWatchAndHeadsets() {
        val bmw = DeviceFilter.DeviceCandidate(
            name = BluetoothConstants.KNOWN_BMW_NAME,
            address = BluetoothConstants.KNOWN_BMW_MAC,
            majorDeviceClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
            deviceClass = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
        )
        val watch = DeviceFilter.DeviceCandidate(
            name = "Galaxy Watch6 Classic (M77L)",
            address = BluetoothConstants.KNOWN_WATCH_MAC,
            majorDeviceClass = BluetoothClass.Device.Major.WEARABLE,
            deviceClass = BluetoothClass.Device.WEARABLE_WRIST_WATCH,
        )
        val earbuds = DeviceFilter.DeviceCandidate(
            name = "Galaxy Buds2 Pro",
            address = "11:22:33:44:55:66",
            majorDeviceClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
            deviceClass = BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        )
        val carPlaySdpUnit = DeviceFilter.DeviceCandidate(
            name = "HeadUnit",
            address = "AA:BB:CC:DD:EE:FF",
            uuids = setOf(BluetoothConstants.ACCESSORY_IAP2_UUID),
        )

        assertTrue(DeviceFilter.isEligibleOutboundTarget(bmw))
        assertTrue(DeviceFilter.isSelectableInUi(bmw))

        assertFalse("Must NEVER target Galaxy Watch6 Classic", DeviceFilter.isEligibleOutboundTarget(watch))
        assertFalse("Must NEVER target Watch even if MAC accidentally passed", DeviceFilter.isEligibleOutboundTarget(watch, BluetoothConstants.KNOWN_WATCH_MAC))
        assertFalse(DeviceFilter.isSelectableInUi(watch))

        assertFalse(DeviceFilter.isEligibleOutboundTarget(earbuds))
        assertFalse(DeviceFilter.isSelectableInUi(earbuds))

        assertTrue(DeviceFilter.isEligibleOutboundTarget(carPlaySdpUnit))
        assertEquals(bmw, DeviceFilter.pickDefaultCar(listOf(watch, earbuds, carPlaySdpUnit, bmw)))
    }
}
