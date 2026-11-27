package io.bimmergestalt.bmwcarplay.airplay.rtsp

import io.bimmergestalt.bmwcarplay.airplay.BinaryPlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHidDecoderTest {

    private val mediaUuid = "e5f7a68d-7b0f-4305-984b-974f677a15ff"
    private val knobUuid = "e5f7a68d-7b0f-4305-984b-974f677a15aa"
    private val phoneUuid = "e5f7a68d-7b0f-4305-984b-974f677a15bb"
    private val touchUuid = "e5f7a68d-7b0f-4305-984b-974f677a22cc"

    private val bmwDevices = listOf(
        CarPlayHidDecoder.HidDeviceInfo(mediaUuid, "MediaButtons", CarPlayHidDecoder.DeviceKind.MEDIA),
        CarPlayHidDecoder.HidDeviceInfo(knobUuid, "KnobDescriptor", CarPlayHidDecoder.DeviceKind.KNOB),
        CarPlayHidDecoder.HidDeviceInfo(phoneUuid, "PhoneDescriptor", CarPlayHidDecoder.DeviceKind.PHONE),
        CarPlayHidDecoder.HidDeviceInfo(touchUuid, "TouchDescriptor", CarPlayHidDecoder.DeviceKind.TOUCH, 1280, 480),
    ).associateBy { it.uuidKey }

    // ---- extractFromCommand tests ----

    @Test
    fun extractsCanonicalParamsUuidAndHidReport() {
        val report = byteArrayOf(0x01, 0x80.toByte(), 0x02, 0xF0.toByte(), 0x00)
        val cmd = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf(
                "uuid" to touchUuid,
                "hidReport" to report,
            ),
        )

        val extracted = CarPlayHidDecoder.extractFromCommand(cmd)
        assertEquals(touchUuid, extracted.uuid)
        assertArrayEquals(report, extracted.report)
        assertEquals("params", extracted.sourceNote)
    }

    @Test
    fun extractsTopLevelUuidAndHidReportWithoutParams() {
        val report = byteArrayOf(0x01, 0x00, 0x00, 0x01)
        val cmd = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "uuid" to knobUuid,
            "hidReport" to report,
        )

        val extracted = CarPlayHidDecoder.extractFromCommand(cmd)
        assertEquals(knobUuid, extracted.uuid)
        assertArrayEquals(report, extracted.report)
        assertEquals("top-level", extracted.sourceNote)
    }

    @Test
    fun extractsAlternativeAndCaseInsensitiveKeysAndPrefersParamsOverTopLevel() {
        val paramsReport = byteArrayOf(0x01, 0x20, 0x01, 0x50, 0x00)
        val topReport = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)
        val cmd = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "deviceID" to knobUuid,
            "payload" to topReport,
            "params" to linkedMapOf(
                "HIDDEVICEUUID" to touchUuid,
                "RePoRt" to paramsReport,
            ),
        )

        val extracted = CarPlayHidDecoder.extractFromCommand(cmd)
        assertEquals(touchUuid, extracted.uuid)
        assertArrayEquals(paramsReport, extracted.report)
        assertEquals("params", extracted.sourceNote)
    }

    @Test
    fun extractsFirstUsableEntryFromNestedHidReportsList() {
        val report = byteArrayOf(0x04, 0x00, 0x00, (-1).toByte())
        val cmd = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf(
                "hidReports" to listOf(
                    linkedMapOf("uuid" to touchUuid, "hidReport" to ByteArray(0)),
                    linkedMapOf("deviceUUID" to knobUuid, "data" to report),
                ),
            ),
        )

        val extracted = CarPlayHidDecoder.extractFromCommand(cmd)
        assertEquals(knobUuid, extracted.uuid)
        assertArrayEquals(report, extracted.report)
        assertEquals("params.hidReports", extracted.sourceNote)
    }

    @Test
    fun extractsFirstNonEmptyByteArrayFromReportListAndNestedReportsMap() {
        val report = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01)
        val cmdList = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "hidUUID" to touchUuid,
            "hidData" to listOf(ByteArray(0), report),
        )
        val extractedList = CarPlayHidDecoder.extractFromCommand(cmdList)
        assertEquals(touchUuid, extractedList.uuid)
        assertArrayEquals(report, extractedList.report)
        assertEquals("top-level", extractedList.sourceNote)

        val cmdMap = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "reports" to linkedMapOf(
                knobUuid to byteArrayOf(0x01, 0xFF.toByte(), 0x00, 0x00),
            ),
        )
        val extractedMap = CarPlayHidDecoder.extractFromCommand(cmdMap)
        assertEquals(knobUuid, extractedMap.uuid)
        assertArrayEquals(byteArrayOf(0x01, 0xFF.toByte(), 0x00, 0x00), extractedMap.report)
        assertEquals("top-level.reports", extractedMap.sourceNote)
    }

    @Test
    fun extractFromCommandHandlesMissingFieldsAndRoundTripsBplist() {
        val missing = CarPlayHidDecoder.extractFromCommand(mapOf("type" to "hidSendReport"))
        assertNull(missing.uuid)
        assertNull(missing.report)
        assertEquals("missing", missing.sourceNote)

        val rawReport = byteArrayOf(0x01, 0x80.toByte(), 0x02, 0xF0.toByte(), 0x00)
        val encoded = BinaryPlist.encode(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to touchUuid,
                "report" to rawReport,
            ),
        )
        val parsed = BinaryPlist.parse(encoded) as Map<*, *>
        val extracted = CarPlayHidDecoder.extractFromCommand(parsed)
        val decoded = CarPlayHidDecoder.decode(extracted.uuid, extracted.report, bmwDevices, 1280, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, decoded.kind)
        assertEquals(640, decoded.touch?.x)
        assertEquals(240, decoded.touch?.y)
        assertTrue(decoded.touch!!.down)
    }

    // ---- Touch decoding tests (5B, 6B, 10B dual-slot) ----

    @Test
    fun decodesBmw1280x480TouchReports5ByteAnd6ByteReportId() {
        // 5-byte single touch: X = 640 (0x0280 LE -> 80 02), Y = 240 (0x00F0 LE -> F0 00)
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

        // Finger lift (6-byte variant with leading Report ID = 0x01, X=1280=0x0500, Y=480=0x01E0)
        val upReport = byteArrayOf(0x01, 0x00, 0x00, 0x05, 0xE0.toByte(), 0x01)
        val decodedUp = CarPlayHidDecoder.decode(
            uuid = touchUuid,
            report = upReport,
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertFalse(decodedUp.touch!!.down)
        assertEquals(1280, decodedUp.touch!!.x)
        assertEquals(480, decodedUp.touch!!.y)
        assertTrue(decodedUp.summaryHr.contains("DODIR GORE (1280, 480)"))
    }

    @Test
    fun decodes10ByteDualSlotTouchWithoutIdleSlotOverwritingActiveSlot() {
        // Slot 0 active at (320, 120) [0x0140 -> 40 01, 0x0078 -> 78 00]; Slot 1 idle (0, 0)
        val slot0Active = byteArrayOf(
            0x01, 0x40, 0x01, 0x78, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val d0 = CarPlayHidDecoder.decode(touchUuid, slot0Active, bmwDevices, 1280, 480)
        assertNotNull(d0.touch)
        assertTrue(d0.touch!!.down)
        assertEquals(320, d0.touch!!.x)
        assertEquals(120, d0.touch!!.y)
        assertTrue(d0.summaryHr.contains("DODIR DOLJE (320, 120)"))

        // Slot 0 lift at (320, 120), Slot 1 idle (0, 0) -> retains slot 0 coordinates
        val slot0Lift = byteArrayOf(
            0x00, 0x40, 0x01, 0x78, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val dLift = CarPlayHidDecoder.decode(touchUuid, slot0Lift, bmwDevices, 1280, 480)
        assertFalse(dLift.touch!!.down)
        assertEquals(320, dLift.touch!!.x)
        assertEquals(120, dLift.touch!!.y)

        // Slot 0 idle at (0, 0), Slot 1 active at (960, 360) [0x03C0 -> C0 03, 0x0168 -> 68 01]
        val slot1Active = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0xC0.toByte(), 0x03, 0x68, 0x01,
        )
        val d1 = CarPlayHidDecoder.decode(touchUuid, slot1Active, bmwDevices, 1280, 480)
        assertTrue(d1.touch!!.down)
        assertEquals(960, d1.touch!!.x)
        assertEquals(360, d1.touch!!.y)
        assertTrue(d1.summaryHr.contains("DODIR DOLJE (960, 360)"))
    }

    // ---- Knob, Media & Phone decoding tests ----

    @Test
    fun decodes4ByteAnd5ByteBmwKnobNudgesButtonsAndWheel() {
        // Left (-1) + Up (-1) + Click (bit 0) + CW wheel (+3)
        val knobLeftUpClickCw = CarPlayHidDecoder.decode(
            uuid = knobUuid,
            report = byteArrayOf(0x01, (-1).toByte(), (-1).toByte(), 0x03),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertEquals(CarPlayHidDecoder.DeviceKind.KNOB, knobLeftUpClickCw.kind)
        assertTrue(knobLeftUpClickCw.summaryHr.contains("KLIK"))
        assertTrue(knobLeftUpClickCw.summaryHr.contains("LIJEVO(-1)"))
        assertTrue(knobLeftUpClickCw.summaryHr.contains("GORE(-1)"))
        assertTrue(knobLeftUpClickCw.summaryHr.contains("KOTAČIĆ +3 ↻"))

        // Right (+1) + Down (+1) + Home (bit 1) + Back (bit 2) + CCW wheel (-2) with 5-byte Report ID prefix
        val knob5Byte = CarPlayHidDecoder.decode(
            uuid = knobUuid,
            report = byteArrayOf(0x02, 0x06, 0x01, 0x01, (-2).toByte()),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(knob5Byte.summaryHr.contains("HOME"))
        assertTrue(knob5Byte.summaryHr.contains("BACK"))
        assertTrue(knob5Byte.summaryHr.contains("DESNO(+1)"))
        assertTrue(knob5Byte.summaryHr.contains("DOLJE(+1)"))
        assertTrue(knob5Byte.summaryHr.contains("KOTAČIĆ -2 ↺"))

        // All zero -> released
        val released = CarPlayHidDecoder.decode(
            uuid = knobUuid,
            report = byteArrayOf(0x00, 0x00, 0x00, 0x00),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(released.summaryHr.contains("OTPUŠTENO"))
    }

    @Test
    fun decodesBmwMediaPhoneAndEmptyMissingFallbacks() {
        // MediaButtons: Play/Pause (bit 2 = 0x04)
        val media = CarPlayHidDecoder.decode(
            uuid = mediaUuid,
            report = byteArrayOf(0x04),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(media.summaryHr.contains("PLAY/PAUSE"))

        // PhoneDescriptor: Voice/Siri (bit 0 = 0x01)
        val phone = CarPlayHidDecoder.decode(
            uuid = phoneUuid,
            report = byteArrayOf(0x01, 0x00, 0x00),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(phone.summaryHr.contains("GLAS/SIRI"))

        // Empty / null report fallback
        val emptyTouch = CarPlayHidDecoder.decode(
            uuid = touchUuid,
            report = ByteArray(0),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, emptyTouch.kind)
        assertNull(emptyTouch.touch)
        assertTrue(emptyTouch.summaryHr.contains("<prazan izvještaj>"))

        val nullBoth = CarPlayHidDecoder.decode(
            uuid = null,
            report = null,
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertEquals(CarPlayHidDecoder.DeviceKind.UNKNOWN, nullBoth.kind)
        assertNull(nullBoth.touch)
        assertTrue(nullBoth.summaryHr.contains("<prazan izvještaj>"))
    }

    // ---- Additional v0.2.2 QA edge-case tests ----

    @Test
    fun extractsSplitScopeUuidAndReportBetweenParamsAndTopLevel() {
        val touchBytes = byteArrayOf(0x01, 0x80.toByte(), 0x02, 0xF0.toByte(), 0x00)
        val cmdUuidTopReportParams = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "uuid" to touchUuid,
            "params" to linkedMapOf("hidReport" to touchBytes),
        )
        val e1 = CarPlayHidDecoder.extractFromCommand(cmdUuidTopReportParams)
        assertEquals(touchUuid, e1.uuid)
        assertArrayEquals(touchBytes, e1.report)
        assertEquals("uuid=top-level,report=params", e1.sourceNote)

        val knobBytes = byteArrayOf(0x01, 0x00, 0x00, 0x01)
        val cmdUuidParamsReportTop = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "hidReport" to knobBytes,
            "params" to linkedMapOf("uuid" to knobUuid),
        )
        val e2 = CarPlayHidDecoder.extractFromCommand(cmdUuidParamsReportTop)
        assertEquals(knobUuid, e2.uuid)
        assertArrayEquals(knobBytes, e2.report)
        assertEquals("uuid=params,report=top-level", e2.sourceNote)
    }

    @Test
    fun extractsAndDecodesIntegerLongAnd16ByteRawUuidFormats() {
        val numericDevices = listOf(
            CarPlayHidDecoder.HidDeviceInfo(0L, "TouchDescriptor", CarPlayHidDecoder.DeviceKind.TOUCH, 800, 480),
            CarPlayHidDecoder.HidDeviceInfo(1L, "KnobDescriptor", CarPlayHidDecoder.DeviceKind.KNOB),
        ).associateBy { it.uuidKey }

        // Integer 0 (Int) matches Long 0L registered from bplist /info
        val touchReport = byteArrayOf(0x01, 0x90.toByte(), 0x01, 0xF0.toByte(), 0x00) // (400, 240)
        val cmdInt0 = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf("uuid" to 0, "hidReport" to touchReport),
        )
        val e0 = CarPlayHidDecoder.extractFromCommand(cmdInt0)
        assertEquals(0, e0.uuid)
        val d0 = CarPlayHidDecoder.decode(e0.uuid, e0.report, numericDevices, 800, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, d0.kind)
        assertEquals(400, d0.touch?.x)
        assertEquals(240, d0.touch?.y)

        // Integer 1 matches KnobDescriptor
        val cmdInt1 = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf("uuid" to 1, "hidReport" to byteArrayOf(0x01, 0x00, 0x00, 0x02)),
        )
        val e1 = CarPlayHidDecoder.extractFromCommand(cmdInt1)
        val d1 = CarPlayHidDecoder.decode(e1.uuid, e1.report, numericDevices, 800, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.KNOB, d1.kind)
        assertTrue(d1.summaryHr.contains("KLIK"))
        assertTrue(d1.summaryHr.contains("KOTAČIĆ +2 ↻"))

        // 16-byte raw ByteArray UUID matches hyphenated String UUID in bmwDevices after bplist round-trip
        // touchUuid = "e5f7a68d-7b0f-4305-984b-974f677a22cc"
        val rawTouchUuidBytes = byteArrayOf(
            0xE5.toByte(), 0xF7.toByte(), 0xA6.toByte(), 0x8D.toByte(),
            0x7B, 0x0F, 0x43, 0x05,
            0x98.toByte(), 0x4B, 0x97.toByte(), 0x4F,
            0x67, 0x7A, 0x22, 0xCC.toByte(),
        )
        val encoded = BinaryPlist.encode(
            linkedMapOf(
                "type" to "hidSendReport",
                "params" to linkedMapOf(
                    "uuid" to rawTouchUuidBytes,
                    "hidReport" to touchReport,
                ),
            ),
        )
        val parsed = BinaryPlist.parse(encoded) as Map<*, *>
        val eRaw = CarPlayHidDecoder.extractFromCommand(parsed)
        assertTrue(eRaw.uuid is ByteArray)
        val dRaw = CarPlayHidDecoder.decode(eRaw.uuid, eRaw.report, bmwDevices, 1280, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, dRaw.kind)
        assertEquals("TouchDescriptor", dRaw.deviceName)
        assertEquals(400, dRaw.touch?.x)
        assertEquals(240, dRaw.touch?.y)
    }

    @Test
    fun extractsFirstNonEmptyFromMultiEntryHidReportsAndSkipsBlankStringUuid() {
        val touchBytes = byteArrayOf(0x01, 0x64, 0x00, 0x32, 0x00) // (100, 50)
        val knobBytes = byteArrayOf(0x01, 0x00, 0x00, 0x01)
        val multiCmd = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf(
                "hidReports" to listOf(
                    linkedMapOf("uuid" to touchUuid, "hidReport" to touchBytes),
                    linkedMapOf("uuid" to knobUuid, "hidReport" to knobBytes),
                ),
            ),
        )
        val extractedMulti = CarPlayHidDecoder.extractFromCommand(multiCmd)
        assertEquals(touchUuid, extractedMulti.uuid)
        assertArrayEquals(touchBytes, extractedMulti.report)
        assertEquals("params.hidReports", extractedMulti.sourceNote)

        // Blank string UUID is skipped in favor of nested hidDevice.uuid
        val nestedDevCmd = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf(
                "uuid" to "   ",
                "hidDevice" to linkedMapOf("uuid" to touchUuid),
                "hidReport" to touchBytes,
            ),
        )
        val extractedNested = CarPlayHidDecoder.extractFromCommand(nestedDevCmd)
        assertEquals(touchUuid, extractedNested.uuid)
        assertArrayEquals(touchBytes, extractedNested.report)
    }

    @Test
    fun decodes11ByteDualSlotTouchClampedCoordinatesAndShortMalformedReports() {
        // 11-byte dual-slot with leading Report ID = 0x02, slot 0 idle (0,0), slot 1 active (960, 360)
        val report11B = byteArrayOf(
            0x02,
            0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0xC0.toByte(), 0x03, 0x68, 0x01,
        )
        val d11 = CarPlayHidDecoder.decode(touchUuid, report11B, bmwDevices, 1280, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, d11.kind)
        assertTrue(d11.touch!!.down)
        assertEquals(960, d11.touch!!.x)
        assertEquals(360, d11.touch!!.y)
        assertTrue(d11.summaryHr.contains("[slot1]"))

        // Out-of-bounds u16 coordinates (65535, 65535) clamped cleanly to (1280, 480)
        val outOfBounds = byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val dClamp = CarPlayHidDecoder.decode(touchUuid, outOfBounds, bmwDevices, 1280, 480)
        assertEquals(1280, dClamp.touch!!.x)
        assertEquals(480, dClamp.touch!!.y)

        // Short/truncated touch (<5B) and knob (<4B) do not throw and return raw hex fallback
        val shortTouch = CarPlayHidDecoder.decode(touchUuid, byteArrayOf(0x01, 0x02, 0x03), bmwDevices, 1280, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.TOUCH, shortTouch.kind)
        assertNull(shortTouch.touch)
        assertTrue(shortTouch.summaryHr.contains("[3B:"))

        val shortKnob = CarPlayHidDecoder.decode(knobUuid, byteArrayOf(0x01, 0x02), bmwDevices, 1280, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.KNOB, shortKnob.kind)
        assertTrue(shortKnob.summaryHr.contains("[2B:"))
    }

    @Test
    fun decodesKnobSignedByteExtremesAndClassifiesByHidDescriptorFallback() {
        val extremeKnob = CarPlayHidDecoder.decode(
            uuid = knobUuid,
            report = byteArrayOf(0x00, (-128).toByte(), 127.toByte(), (-128).toByte()),
            devicesByUuidKey = bmwDevices,
            screenWidth = 1280,
            screenHeight = 480,
        )
        assertTrue(extremeKnob.summaryHr.contains("LIJEVO(-128)"))
        assertTrue(extremeKnob.summaryHr.contains("DOLJE(+127)"))
        assertTrue(extremeKnob.summaryHr.contains("KOTAČIĆ -128 ↺"))

        assertEquals(
            CarPlayHidDecoder.DeviceKind.TOUCH,
            CarPlayHidDecoder.classifyDevice(null, byteArrayOf(0x05, 0x0D, 0x09, 0x04)),
        )
        assertEquals(
            CarPlayHidDecoder.DeviceKind.KNOB,
            CarPlayHidDecoder.classifyDevice("GenericInput", byteArrayOf(0x05, 0x01, 0x09, 0x08)),
        )
        assertEquals(
            CarPlayHidDecoder.DeviceKind.MEDIA,
            CarPlayHidDecoder.classifyDevice("", byteArrayOf(0x05, 0x0C, 0x09, 0x01)),
        )
        assertEquals(
            CarPlayHidDecoder.DeviceKind.PHONE,
            CarPlayHidDecoder.classifyDevice("HID-3", byteArrayOf(0x05, 0x0B, 0x09, 0x01)),
        )
        assertEquals(
            CarPlayHidDecoder.DeviceKind.UNKNOWN,
            CarPlayHidDecoder.classifyDevice("HID-4", byteArrayOf(0x05, 0xFF.toByte(), 0x09, 0x01)),
        )
    }

    @Test
    fun prefersCompleteNestedCandidateOverGenericOuterIdAndFormatsUnknownByteArrayUuid() {
        val touchBytes = byteArrayOf(0x01, 0x64, 0x00, 0x32, 0x00)
        val cmdWithOuterId = linkedMapOf<String, Any?>(
            "type" to "hidSendReport",
            "params" to linkedMapOf(
                "id" to "unrelated-session-id",
                "hidReports" to listOf(
                    linkedMapOf("uuid" to touchUuid, "hidReport" to touchBytes),
                ),
            ),
        )
        val extracted = CarPlayHidDecoder.extractFromCommand(cmdWithOuterId)
        assertEquals(touchUuid, extracted.uuid)
        assertArrayEquals(touchBytes, extracted.report)
        assertEquals("params.hidReports", extracted.sourceNote)

        val unknownBytes = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
        )
        val r1 = CarPlayHidDecoder.ExtractedReport(unknownBytes.copyOf(), touchBytes.copyOf(), "params")
        val r2 = CarPlayHidDecoder.ExtractedReport(unknownBytes.copyOf(), touchBytes.copyOf(), "params")
        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())

        val unknownDecoded = CarPlayHidDecoder.decode(unknownBytes, touchBytes, bmwDevices, 1280, 480)
        assertEquals(CarPlayHidDecoder.DeviceKind.UNKNOWN, unknownDecoded.kind)
        assertEquals("HID(01020304-0506-0708-090a-0b0c0d0e0f10)", unknownDecoded.deviceName)
    }
}
