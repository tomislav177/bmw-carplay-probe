package io.bimmergestalt.bmwcarplay.airplay.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHidDecoderTest {

    private val bmwDevices = listOf(
        CarPlayHidDecoder.HidDeviceInfo("e5f7a68d-7b0f-4305-984b-974f677a15ff", "MediaButtons", CarPlayHidDecoder.DeviceKind.MEDIA),
        CarPlayHidDecoder.HidDeviceInfo("e5f7a68d-7b0f-4305-984b-974f677a15aa", "KnobDescriptor", CarPlayHidDecoder.DeviceKind.KNOB),
        CarPlayHidDecoder.HidDeviceInfo("e5f7a68d-7b0f-4305-984b-974f677a15bb", "PhoneDescriptor", CarPlayHidDecoder.DeviceKind.PHONE),
        CarPlayHidDecoder.HidDeviceInfo("e5f7a68d-7b0f-4305-984b-974f677a22cc", "TouchDescriptor", CarPlayHidDecoder.DeviceKind.TOUCH, 1280, 480),
    ).associateBy { it.uuidKey }

    @Test
    fun decodesBmw1280x480TouchReportsDownAndUp() {
        // X = 640 (0x0280 LE -> 80 02), Y = 240 (0x00F0 LE -> F0 00)
        val downReport = byteArrayOf(0x01, 0x80.toByte(), 0x02, 0xF0.toByte(), 0x00)
        val decodedDown = CarPlayHidDecoder.decode(
            uuid = "e5f7a68d-7b0f-4305-984b-974f677a22CC", // case-insensitive UUID match!
            report = downReport,
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, decodedDown.kind)
        assertNotNull(decodedDown.touch)
        assertTrue(decodedDown.touch!!.down)
        assertEquals(640, decodedDown.touch!!.x)
        assertEquals(240, decodedDown.touch!!.y)
        assertTrue(decodedDown.summaryHr.contains("DODIR DOLJE (640, 240)"))

        // Finger lift (6-byte variant with leading Report ID = 0x01)
        val upReport = byteArrayOf(0x01, 0x00, 0x00, 0x05, 0xE0.toByte(), 0x01)
        val decodedUp = CarPlayHidDecoder.decode(
            uuid = "e5f7a68d-7b0f-4305-984b-974f677a22cc",
            report = upReport,
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertFalse(decodedUp.touch!!.down)
        assertEquals(1280, decodedUp.touch!!.x)
        assertEquals(480, decodedUp.touch!!.y)
    }

    @Test
    fun decodesBmwKnobMediaAndPhoneReports() {
        // iDrive knob: Back button (bit 2 = 0x04) + wheel rotated -2 CCW (0xFE)
        val knob = CarPlayHidDecoder.decode(
            uuid = "e5f7a68d-7b0f-4305-984b-974f677a15aa",
            report = byteArrayOf(0x04, 0x00, 0x00, (-2).toByte()),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(knob.summaryHr.contains("BACK"))
        assertTrue(knob.summaryHr.contains("-2"))

        // MediaButtons: Play/Pause (bit 2 = 0x04)
        val media = CarPlayHidDecoder.decode(
            uuid = "e5f7a68d-7b0f-4305-984b-974f677a15ff",
            report = byteArrayOf(0x04),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(media.summaryHr.contains("PLAY/PAUSE"))

        // PhoneDescriptor: Voice/Siri (bit 0 = 0x01)
        val phone = CarPlayHidDecoder.decode(
            uuid = "e5f7a68d-7b0f-4305-984b-974f677a15bb",
            report = byteArrayOf(0x01, 0x00, 0x00),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(phone.summaryHr.contains("GLAS/SIRI"))
    }
}
