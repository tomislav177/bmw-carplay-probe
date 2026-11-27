package io.bimmergestalt.bmwcarplay.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AapProtoTest {

    @Test
    fun protoWriterAndReaderRoundTripAllWireTypesAndSkipsGroups() {
        val raw = AapProto.ProtoWriter().apply {
            writeVarintField(1, 0L)
            writeVarintField(2, 123_456_789_012L)
            writeVarintField(3, -1) // 10-byte two's complement int32
            writeSint32Field(4, -42)
            writeBoolField(5, true)
            writeFixed64Field(6, 0x0102_0304_0506_0708L)
            writeFixed32Field(7, 0x11223344)
            writeStringField(8, "BMW X1 ČĆŽŠĐ")
            writeBytesField(9, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
            writePackedVarintsField(10, intArrayOf(3, 4, 0x10000))
            writeSubmessageField(11) {
                writeVarintField(1, 77)
            }
        }.toByteArray()

        // Append a deprecated proto2 group (field 15, START_GROUP=3 ... END_GROUP=4) followed by field 12
        val withGroup = raw + AapProto.ProtoWriter().apply {
            writeVarint(((15 shl 3) or AapProto.WIRE_START_GROUP).toLong())
            writeVarintField(1, 999)
            writeStringField(2, "skip-me")
            writeVarint(((15 shl 3) or AapProto.WIRE_END_GROUP).toLong())
            writeVarintField(12, 42)
        }.toByteArray()

        val fields = AapProto.ProtoReader(withGroup).readAllFields()
        assertEquals(12, fields.size)
        assertEquals(0L, fields.first { it.fieldNumber == 1 }.asLong())
        assertEquals(123_456_789_012L, fields.first { it.fieldNumber == 2 }.asLong())
        assertEquals(-1, fields.first { it.fieldNumber == 3 }.asInt())
        assertEquals(-42, fields.first { it.fieldNumber == 4 }.asSint32())
        assertTrue(fields.first { it.fieldNumber == 5 }.asBool())
        assertEquals(0x0102_0304_0506_0708L, fields.first { it.fieldNumber == 6 }.fixed64)
        assertEquals(0x11223344, fields.first { it.fieldNumber == 7 }.fixed32)
        assertEquals("BMW X1 ČĆŽŠĐ", fields.first { it.fieldNumber == 8 }.asString())
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            fields.first { it.fieldNumber == 9 }.asBytes(),
        )
        assertEquals(listOf(3, 4, 0x10000), fields.first { it.fieldNumber == 10 }.asPackedInts())
        assertEquals(77, fields.first { it.fieldNumber == 11 }.asSubmessageFields().first().asInt())
        assertEquals(42, fields.first { it.fieldNumber == 12 }.asInt())

        // Invalid wire type 6 must throw IOException
        try {
            AapProto.ProtoReader(byteArrayOf(((1 shl 3) or 6).toByte())).readAllFields()
            fail("Expected IOException for invalid wire type 6")
        } catch (_: IOException) {
            // Expected
        }
    }

    @Test
    fun zigZag32EncodesAndDecodesEdgeValues() {
        val values = intArrayOf(0, -1, 1, -2, 2, -128, 127, Int.MIN_VALUE, Int.MAX_VALUE)
        for (v in values) {
            val enc = AapProto.encodeZigZag32(v)
            assertEquals("ZigZag mismatch for $v", v, AapProto.decodeZigZag32(enc))
        }
        assertEquals(0L, AapProto.encodeZigZag32(0))
        assertEquals(1L, AapProto.encodeZigZag32(-1))
        assertEquals(2L, AapProto.encodeZigZag32(1))
        assertEquals(3L, AapProto.encodeZigZag32(-2))
    }

    @Test
    fun displayProfileSelectsExactBmw1280x480And800x480Geometries() {
        val wide60 = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)
        assertEquals(2, wide60.videoResolutionEnum)
        assertEquals(1280, wide60.videoWidth)
        assertEquals(720, wide60.videoHeight)
        assertEquals(0, wide60.marginWidth)
        assertEquals(240, wide60.marginHeight)
        assertEquals(1280, wide60.activeWidth)
        assertEquals(480, wide60.activeHeight)
        assertEquals(0, wide60.cropLeft)
        assertEquals(120, wide60.cropTop)
        assertEquals(60, wide60.targetFps)
        assertEquals(1, wide60.videoFpsEnum)
        assertEquals(180, wide60.dpi)

        val wide30 = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 30)
        assertEquals(30, wide30.targetFps)
        assertEquals(2, wide30.videoFpsEnum)

        val small = AapProto.AapDisplayProfile.forBmwScreen(800, 480, 60)
        assertEquals(1, small.videoResolutionEnum)
        assertEquals(800, small.videoWidth)
        assertEquals(480, small.videoHeight)
        assertEquals(0, small.marginWidth)
        assertEquals(0, small.marginHeight)
        assertEquals(800, small.activeWidth)
        assertEquals(480, small.activeHeight)
        assertEquals(0, small.cropLeft)
        assertEquals(0, small.cropTop)
        assertEquals(160, small.dpi)
    }

    @Test
    fun versionRequestAndResponseEncodeDecode() {
        val req = AapProto.encodeVersionRequest(1, 6)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x00, 0x06), req)

        val respOk = AapProto.decodeVersionResponse(AapProto.encodeVersionResponse(1, 6, 0))
        assertEquals(1, respOk.major)
        assertEquals(6, respOk.minor)
        assertEquals(0, respOk.status)
        assertTrue(respOk.isMatch)

        val respFail = AapProto.decodeVersionResponse(AapProto.encodeVersionResponse(1, 7, 0xFFFF))
        assertFalse(respFail.isMatch)
        assertEquals(0xFFFF, respFail.status)
    }

    @Test
    fun serviceDiscoveryResponseContainsAll5ChannelsAndVerifiedFields() {
        val profile = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)
        val bytes = AapProto.encodeServiceDiscoveryResponse(profile, carSerial = "AL07FFL0665583")
        val root = AapProto.ProtoReader(bytes).readAllFields()

        val channels = root.filter { it.fieldNumber == 1 }.map { it.asSubmessageFields() }
        assertEquals(5, channels.size)
        val channelIds = channels.map { ch -> ch.first { it.fieldNumber == 1 }.asInt() }
        assertEquals(
            listOf(
                AapProto.CH_SENSOR,
                AapProto.CH_VIDEO,
                AapProto.CH_INPUT,
                AapProto.CH_AUDIO_SYSTEM,
                AapProto.CH_MIC,
            ),
            channelIds,
        )

        // Verify Sensor channel (ch=1) declares DRIVING_STATUS(13), NIGHT_DATA(10), PARKING_BRAKE(7)
        val sensorDesc = channels[0].first { it.fieldNumber == 2 }.asSubmessageFields()
        val sensorTypes = sensorDesc.filter { it.fieldNumber == 1 }
            .map { it.asSubmessageFields().first { f -> f.fieldNumber == 1 }.asInt() }
        assertEquals(listOf(13, 10, 7), sensorTypes)

        // Verify Video channel (ch=2)
        val videoAv = channels[1].first { it.fieldNumber == 3 }.asSubmessageFields()
        assertEquals(AapProto.STREAM_TYPE_VIDEO_H264_BP, videoAv.first { it.fieldNumber == 1 }.asInt())
        assertEquals(0, videoAv.first { it.fieldNumber == 6 }.asInt()) // display_id
        assertEquals(0, videoAv.first { it.fieldNumber == 7 }.asInt()) // display_type MAIN
        val videoCfg = videoAv.first { it.fieldNumber == 4 }.asSubmessageFields()
        assertEquals(2, videoCfg.first { it.fieldNumber == 1 }.asInt()) // 1280x720
        assertEquals(1, videoCfg.first { it.fieldNumber == 2 }.asInt()) // 60 fps
        assertEquals(0, videoCfg.first { it.fieldNumber == 3 }.asInt()) // margin_width
        assertEquals(240, videoCfg.first { it.fieldNumber == 4 }.asInt()) // margin_height
        assertEquals(180, videoCfg.first { it.fieldNumber == 5 }.asInt()) // dpi
        assertEquals(10000, videoCfg.first { it.fieldNumber == 8 }.asInt()) // pixel_aspect_ratio_e4
        assertEquals(3, videoCfg.first { it.fieldNumber == 10 }.asInt()) // codec H264_BP

        // Verify Input channel (ch=3)
        val inputCfg = channels[2].first { it.fieldNumber == 4 }.asSubmessageFields()
        val supportedKeys = inputCfg.first { it.fieldNumber == 1 }.asPackedInts()
        assertTrue(AapProto.AapKeyCode.DPAD_CENTER in supportedKeys)
        assertTrue(AapProto.AapKeyCode.SCROLL_WHEEL in supportedKeys)
        val touchCfg = inputCfg.first { it.fieldNumber == 2 }.asSubmessageFields()
        assertEquals(1280, touchCfg.first { it.fieldNumber == 1 }.asInt())
        assertEquals(720, touchCfg.first { it.fieldNumber == 2 }.asInt())
        assertEquals(0, touchCfg.first { it.fieldNumber == 3 }.asInt())
        assertEquals(0, inputCfg.first { it.fieldNumber == 5 }.asInt())

        // Verify System Audio channel (ch=5)
        val audioAv = channels[3].first { it.fieldNumber == 3 }.asSubmessageFields()
        assertEquals(1, audioAv.first { it.fieldNumber == 1 }.asInt()) // AUDIO_PCM
        assertEquals(2, audioAv.first { it.fieldNumber == 2 }.asInt()) // SYSTEM
        val audioCfg = audioAv.first { it.fieldNumber == 3 }.asSubmessageFields()
        assertEquals(16000, audioCfg.first { it.fieldNumber == 1 }.asInt())
        assertEquals(16, audioCfg.first { it.fieldNumber == 2 }.asInt())
        assertEquals(1, audioCfg.first { it.fieldNumber == 3 }.asInt())

        // Verify Mic channel (ch=7)
        val micCfg = channels[4].first { it.fieldNumber == 5 }.asSubmessageFields()
        assertEquals(1, micCfg.first { it.fieldNumber == 1 }.asInt())
        val micAudio = micCfg.first { it.fieldNumber == 2 }.asSubmessageFields()
        assertEquals(16000, micAudio.first { it.fieldNumber == 1 }.asInt())

        // Verify top-level metadata & HeadUnitInfo (field 17)
        assertEquals("BMW", root.first { it.fieldNumber == 2 }.asString())
        assertEquals("X1 F48", root.first { it.fieldNumber == 3 }.asString())
        assertEquals("2020", root.first { it.fieldNumber == 4 }.asString())
        assertEquals("AL07FFL0665583", root.first { it.fieldNumber == 5 }.asString())
        assertEquals(0, root.first { it.fieldNumber == 6 }.asInt())
        assertEquals("BMW", root.first { it.fieldNumber == 7 }.asString())
        assertEquals("HU_ENTRYNAV2", root.first { it.fieldNumber == 8 }.asString())
        assertEquals("1", root.first { it.fieldNumber == 9 }.asString())
        assertEquals("0.3.0", root.first { it.fieldNumber == 10 }.asString())
        assertFalse(root.first { it.fieldNumber == 11 }.asBool())
        assertEquals(0, root.first { it.fieldNumber == 13 }.asInt())
        assertEquals("BMW X1", root.first { it.fieldNumber == 14 }.asString())

        val huInfo = root.first { it.fieldNumber == 17 }.asSubmessageFields()
        assertEquals("BMW", huInfo.first { it.fieldNumber == 1 }.asString())
        assertEquals("X1 F48", huInfo.first { it.fieldNumber == 2 }.asString())
        assertEquals("2020", huInfo.first { it.fieldNumber == 3 }.asString())
        assertEquals("AL07FFL0665583", huInfo.first { it.fieldNumber == 4 }.asString())
        assertEquals("BMW", huInfo.first { it.fieldNumber == 5 }.asString())
        assertEquals("HU_ENTRYNAV2", huInfo.first { it.fieldNumber == 6 }.asString())
        assertEquals("1", huInfo.first { it.fieldNumber == 7 }.asString())
        assertEquals("0.3.0", huInfo.first { it.fieldNumber == 8 }.asString())
        assertEquals(0, huInfo.first { it.fieldNumber == 9 }.asInt())
    }

    @Test
    fun controlSensorAvAndInputMessagesRoundTrip() {
        // ChannelOpen with negative sint32 priority
        val openReq = AapProto.decodeChannelOpenRequest(AapProto.encodeChannelOpenRequest(-3, AapProto.CH_VIDEO))
        assertEquals(-3, openReq.priority)
        assertEquals(AapProto.CH_VIDEO, openReq.channelId)
        assertEquals(0, AapProto.decodeChannelOpenResponse(AapProto.encodeChannelOpenResponse(0)))

        // Ping
        val pingPayload = byteArrayOf(1, 2, 3, 4)
        val pingReq = AapProto.decodePingRequest(AapProto.encodePingRequest(99_999L, true, pingPayload))
        assertEquals(99_999L, pingReq.timestamp)
        assertTrue(pingReq.bugreportRequest)
        assertArrayEquals(pingPayload, pingReq.payload)
        val pingResp = AapProto.decodePingResponse(AapProto.encodePingResponse(99_999L, pingPayload))
        assertEquals(99_999L, pingResp.timestamp)
        assertArrayEquals(pingPayload, pingResp.payload)

        // Audio / Nav / Video Focus
        assertEquals(AapProto.AUDIO_FOCUS_RELEASE, AapProto.decodeAudioFocusRequest(AapProto.encodeAudioFocusRequest(AapProto.AUDIO_FOCUS_RELEASE)))
        val afResp = AapProto.decodeAudioFocusResponse(AapProto.encodeAudioFocusResponse(AapProto.AUDIO_FOCUS_STATE_LOSS, true))
        assertEquals(AapProto.AUDIO_FOCUS_STATE_LOSS, afResp.focusState)
        assertTrue(afResp.granted)

        assertEquals(AapProto.NAV_FOCUS_PROJECTED, AapProto.decodeNavFocusResponse(AapProto.encodeNavFocusResponse(AapProto.NAV_FOCUS_PROJECTED)))

        val vfReq = AapProto.decodeVideoFocusRequest(AapProto.encodeVideoFocusRequest(AapProto.VIDEO_FOCUS_NATIVE, 2))
        assertEquals(AapProto.VIDEO_FOCUS_NATIVE, vfReq.focusMode)
        assertEquals(2, vfReq.focusReason)
        val vfInd = AapProto.decodeVideoFocusIndication(AapProto.encodeVideoFocusIndication(AapProto.VIDEO_FOCUS_PROJECTED, true))
        assertEquals(AapProto.VIDEO_FOCUS_PROJECTED, vfInd.focusMode)
        assertTrue(vfInd.unrequested)

        // Sensor events
        val brakeFields = AapProto.ProtoReader(AapProto.encodeSensorParkingBrake(true)).readAllFields()
        assertTrue(brakeFields.first { it.fieldNumber == 7 }.asSubmessageFields().first { it.fieldNumber == 1 }.asBool())
        val nightFields = AapProto.ProtoReader(AapProto.encodeSensorNightMode(true)).readAllFields()
        assertTrue(nightFields.first { it.fieldNumber == 10 }.asSubmessageFields().first { it.fieldNumber == 1 }.asBool())
        val drivingFields = AapProto.ProtoReader(AapProto.encodeSensorDrivingStatus(0)).readAllFields()
        assertEquals(0, drivingFields.first { it.fieldNumber == 13 }.asSubmessageFields().first { it.fieldNumber == 1 }.asInt())

        // AV Setup / Start / Ack / Mic
        val avResp = AapProto.decodeAvSetupResponse(AapProto.encodeAvSetupResponse(2, 12, intArrayOf(0)))
        assertEquals(2, avResp.mediaStatus)
        assertEquals(12, avResp.maxUnacked)
        assertEquals(listOf(0), avResp.configs)

        val start = AapProto.decodeAvStartIndication(AapProto.encodeAvStartIndication(7, 0))
        assertEquals(7, start.session)
        assertEquals(0, start.config)

        val ack = AapProto.decodeAvMediaAck(AapProto.encodeAvMediaAck(7, 1))
        assertEquals(7, ack.sessionId)
        assertEquals(1, ack.ackCount)

        assertTrue(AapProto.decodeAvInputOpenRequest(AapProto.encodeAvInputOpenRequest(true)))
        val micResp = AapProto.decodeAvInputOpenResponse(AapProto.encodeAvInputOpenResponse(0, 0))
        assertEquals(0, micResp.session)
        assertEquals(0, micResp.value)

        // InputBinding (packed & unpacked)
        val keys = intArrayOf(AapProto.AapKeyCode.HOME, AapProto.AapKeyCode.BACK, AapProto.AapKeyCode.SCROLL_WHEEL)
        assertEquals(keys.toList(), AapProto.decodeInputBindingRequest(AapProto.encodeInputBindingRequest(keys, packed = true)))
        assertEquals(keys.toList(), AapProto.decodeInputBindingRequest(AapProto.encodeInputBindingRequest(keys, packed = false)))
        assertEquals(0, AapProto.decodeInputBindingResponse(AapProto.encodeInputBindingResponse(0)))

        // InputEventIndication: Touch, Button, Relative (negative delta)
        val touchMsg = AapProto.ProtoReader(AapProto.encodeTouchInputEvent(12345L, 640, 360, AapProto.TouchAction.PRESS, 0)).readAllFields()
        assertEquals(12345L, touchMsg.first { it.fieldNumber == 1 }.asLong())
        val touchEv = touchMsg.first { it.fieldNumber == 3 }.asSubmessageFields()
        val loc = touchEv.first { it.fieldNumber == 1 }.asSubmessageFields()
        assertEquals(640, loc.first { it.fieldNumber == 1 }.asInt())
        assertEquals(360, loc.first { it.fieldNumber == 2 }.asInt())
        assertEquals(0, loc.first { it.fieldNumber == 3 }.asInt())
        assertEquals(0, touchEv.first { it.fieldNumber == 2 }.asInt()) // action_index
        assertEquals(AapProto.TouchAction.PRESS, touchEv.first { it.fieldNumber == 3 }.asInt())

        val btnMsg = AapProto.ProtoReader(AapProto.encodeButtonInputEvent(22222L, AapProto.AapKeyCode.DPAD_CENTER, true, 0, false)).readAllFields()
        val btnEv = btnMsg.first { it.fieldNumber == 4 }.asSubmessageFields()
            .first { it.fieldNumber == 1 }.asSubmessageFields()
        assertEquals(AapProto.AapKeyCode.DPAD_CENTER, btnEv.first { it.fieldNumber == 1 }.asInt())
        assertTrue(btnEv.first { it.fieldNumber == 2 }.asBool())
        assertEquals(0, btnEv.first { it.fieldNumber == 3 }.asInt())
        assertFalse(btnEv.first { it.fieldNumber == 4 }.asBool())

        val relMsg = AapProto.ProtoReader(AapProto.encodeRelativeInputEvent(33333L, AapProto.AapKeyCode.SCROLL_WHEEL, -2)).readAllFields()
        val relEv = relMsg.first { it.fieldNumber == 6 }.asSubmessageFields()
            .first { it.fieldNumber == 1 }.asSubmessageFields()
        assertEquals(AapProto.AapKeyCode.SCROLL_WHEEL, relEv.first { it.fieldNumber == 1 }.asInt())
        assertEquals(-2, relEv.first { it.fieldNumber == 2 }.asInt())
        assertNotNull(relEv)
    }
}
