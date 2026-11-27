package io.bimmergestalt.bmwcarplay.aap

import io.bimmergestalt.bmwcarplay.airplay.BinaryPlist
import io.bimmergestalt.bmwcarplay.airplay.rtsp.CarPlayHidDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwToAapInputMapperTest {

    private val bmw1280Profile = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)

    @Test
    fun touchMapsCoordinatesWithCropOffsetClampingAndTransitions() {
        val mapper = BmwToAapInputMapper(bmw1280Profile)

        // Spurious release when already up must be ignored
        assertTrue(mapper.onTouch(down = false, bmwX = 100, bmwY = 100).isEmpty())

        // Initial touch down -> PRESS at (173, 239 + 120 = 359)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(173, 359, AapProto.TouchAction.PRESS)),
            mapper.onTouch(down = true, bmwX = 173, bmwY = 239),
        )

        // Continued touch down -> DRAG
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(200, 360, AapProto.TouchAction.DRAG)),
            mapper.onTouch(down = true, bmwX = 200, bmwY = 240),
        )

        // Out-of-bounds clamping during DRAG
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(0, 720, AapProto.TouchAction.DRAG)),
            mapper.onTouch(down = true, bmwX = -50, bmwY = 999),
        )

        // Touch up -> RELEASE
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(83, 296, AapProto.TouchAction.RELEASE)),
            mapper.onTouch(down = false, bmwX = 83, bmwY = 176),
        )

        // Repeated up -> ignored
        assertTrue(mapper.onTouch(down = false, bmwX = 83, bmwY = 176).isEmpty())
    }

    @Test
    fun knobEmitsEdgeTriggeredButtonsDirectionsAndRotaryDeltas() {
        val mapper = BmwToAapInputMapper(bmw1280Profile)

        // Press select + home
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_CENTER, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.HOME, true),
            ),
            mapper.onKnob(select = true, home = true, back = false, nudgeX = 0, nudgeY = 0, wheel = 0),
        )

        // Holding same state emits nothing
        assertTrue(
            mapper.onKnob(select = true, home = true, back = false, nudgeX = 0, nudgeY = 0, wheel = 0).isEmpty(),
        )

        // Release both + press back
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_CENTER, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.HOME, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.BACK, true),
            ),
            mapper.onKnob(select = false, home = false, back = true, nudgeX = 0, nudgeY = 0, wheel = 0),
        )

        // Release back + nudge left (-127)
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.BACK, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_LEFT, true),
            ),
            mapper.onKnob(select = false, home = false, back = false, nudgeX = -127, nudgeY = 0, wheel = 0),
        )

        // Flip directly from left to right + nudge up (-127) + wheel +2
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_LEFT, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_RIGHT, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_UP, true),
                BmwToAapInputMapper.AapInputAction.Relative(AapProto.AapKeyCode.SCROLL_WHEEL, 2),
            ),
            mapper.onKnob(select = false, home = false, back = false, nudgeX = 127, nudgeY = -127, wheel = 2),
        )

        // Flip up to down + wheel -1
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_RIGHT, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_UP, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_DOWN, true),
                BmwToAapInputMapper.AapInputAction.Relative(AapProto.AapKeyCode.SCROLL_WHEEL, -1),
            ),
            mapper.onKnob(select = false, home = false, back = false, nudgeX = 0, nudgeY = 127, wheel = -1),
        )

        // Return to neutral -> release DPAD_DOWN
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_DOWN, false)),
            mapper.onKnob(select = false, home = false, back = false, nudgeX = 0, nudgeY = 0, wheel = 0),
        )
    }

    @Test
    fun mediaAndPhoneButtonsAreEdgeTriggeredAndResetClearsState() {
        val mapper = BmwToAapInputMapper(bmw1280Profile)

        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_NEXT, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PLAY_PAUSE, true),
            ),
            mapper.onMedia(next = true, prev = false, playPause = true, play = false, pause = false),
        )
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_NEXT, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PREVIOUS, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PLAY_PAUSE, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PLAY, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PAUSE, true),
            ),
            mapper.onMedia(next = false, prev = true, playPause = false, play = true, pause = true),
        )

        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.CALL, true),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.ENDCALL, true),
            ),
            mapper.onPhone(voice = true, hook = true, drop = true),
        )
        assertTrue(mapper.onPhone(voice = true, hook = true, drop = true).isEmpty())

        // Put touch in down state, then reset()
        mapper.onTouch(down = true, bmwX = 10, bmwY = 10)
        mapper.reset()

        // After reset(), touch down must emit fresh PRESS (not DRAG) and held buttons must emit fresh press
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(10, 130, AapProto.TouchAction.PRESS)),
            mapper.onTouch(down = true, bmwX = 10, bmwY = 10),
        )
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, true)),
            mapper.onPhone(voice = true, hook = false, drop = false),
        )
    }

    @Test
    fun realBmwX1F48CapturedHidBytePathDecodesAndMapsEndToEnd() {
        // Real BMW X1 F48 (HU_ENTRYNAV2) HID device registry from session_20261127_193530.log
        val mediaUuid = "e5f7a68d-7b0f-4305-984b-974f677a15ff"
        val knobUuid = "e5f7a68d-7b0f-4305-984b-974f677a15aa"
        val phoneUuid = "e5f7a68d-7b0f-4305-984b-974f677a15bb"
        val touchUuid = "e5f7a68d-7b0f-4305-984b-974f677a22cc"

        val bmwDevices = listOf(
            CarPlayHidDecoder.HidDeviceInfo(mediaUuid, "MediaButtons", CarPlayHidDecoder.DeviceKind.MEDIA, 1280, 480),
            CarPlayHidDecoder.HidDeviceInfo(knobUuid, "KnobDescriptor", CarPlayHidDecoder.DeviceKind.KNOB, 1280, 480),
            CarPlayHidDecoder.HidDeviceInfo(phoneUuid, "PhoneDescriptor", CarPlayHidDecoder.DeviceKind.PHONE, 1280, 480),
            CarPlayHidDecoder.HidDeviceInfo(touchUuid, "TouchDescriptor", CarPlayHidDecoder.DeviceKind.TOUCH, 1280, 480),
        ).associateBy { it.uuidKey }

        assertEquals(240, bmw1280Profile.marginHeight)
        assertEquals(120, bmw1280Profile.cropTop)

        val mapper = BmwToAapInputMapper(bmw1280Profile)

        fun pipeRealBmwHidCommand(uuid: String, reportBytes: ByteArray): List<BmwToAapInputMapper.AapInputAction> {
            // Mirror real BMW HU_ENTRYNAV2 top-level bplist format captured on Event channel
            val wireBplist = BinaryPlist.encode(
                linkedMapOf<String, Any?>(
                    "type" to "hidSendReport",
                    "uuid" to uuid,
                    "hidReport" to reportBytes,
                ),
            )
            val parsedCmd = BinaryPlist.parse(wireBplist) as Map<*, *>
            val extracted = CarPlayHidDecoder.extractFromCommand(parsedCmd)
            val decoded = CarPlayHidDecoder.decode(
                uuid = extracted.uuid,
                report = extracted.report,
                devicesByUuidKey = bmwDevices,
                screenWidth = 1280,
                screenHeight = 480,
            )
            return when (decoded.kind) {
                CarPlayHidDecoder.DeviceKind.TOUCH -> {
                    val tp = decoded.touch!!
                    mapper.onTouch(down = tp.down, bmwX = tp.x, bmwY = tp.y)
                }

                CarPlayHidDecoder.DeviceKind.KNOB -> {
                    val k = decoded.knob!!
                    mapper.onKnob(k.select, k.home, k.back, k.nudgeX, k.nudgeY, k.wheel)
                }

                CarPlayHidDecoder.DeviceKind.MEDIA -> {
                    val m = decoded.media!!
                    mapper.onMedia(m.next, m.prev, m.playPause, m.play, m.pause)
                }

                CarPlayHidDecoder.DeviceKind.PHONE -> {
                    val p = decoded.phone!!
                    mapper.onPhone(p.voice, p.hook, p.drop)
                }

                CarPlayHidDecoder.DeviceKind.UNKNOWN -> emptyList()
            }
        }

        // 1. Touch down 01 AD 00 EF 00 -> BMW (173, 239) -> AAP Touch(173, 359, PRESS)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(173, 359, AapProto.TouchAction.PRESS)),
            pipeRealBmwHidCommand(touchUuid, byteArrayOf(0x01, 0xAD.toByte(), 0x00, 0xEF.toByte(), 0x00)),
        )

        // 2. Touch drag 01 5F 00 BB 00 -> BMW (95, 187) -> AAP Touch(95, 307, DRAG)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(95, 307, AapProto.TouchAction.DRAG)),
            pipeRealBmwHidCommand(touchUuid, byteArrayOf(0x01, 0x5F, 0x00, 0xBB.toByte(), 0x00)),
        )

        // 3. Touch up 00 53 00 B0 00 -> BMW (83, 176) -> AAP Touch(83, 296, RELEASE)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Touch(83, 296, AapProto.TouchAction.RELEASE)),
            pipeRealBmwHidCommand(touchUuid, byteArrayOf(0x00, 0x53, 0x00, 0xB0.toByte(), 0x00)),
        )

        // 4. iDrive Left 00 81 00 00 -> Key(DPAD_LEFT, true)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_LEFT, true)),
            pipeRealBmwHidCommand(knobUuid, byteArrayOf(0x00, 0x81.toByte(), 0x00, 0x00)),
        )

        // 5. iDrive Release 00 00 00 00 -> Key(DPAD_LEFT, false)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_LEFT, false)),
            pipeRealBmwHidCommand(knobUuid, byteArrayOf(0x00, 0x00, 0x00, 0x00)),
        )

        // 6. iDrive Right 00 7F 00 00 -> Key(DPAD_RIGHT, true)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_RIGHT, true)),
            pipeRealBmwHidCommand(knobUuid, byteArrayOf(0x00, 0x7F, 0x00, 0x00)),
        )

        // 7. iDrive Up 00 00 81 00 -> releases DPAD_RIGHT + presses DPAD_UP
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_RIGHT, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_UP, true),
            ),
            pipeRealBmwHidCommand(knobUuid, byteArrayOf(0x00, 0x00, 0x81.toByte(), 0x00)),
        )

        // 8. iDrive Down 00 00 7F 00 -> releases DPAD_UP + presses DPAD_DOWN
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_UP, false),
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_DOWN, true),
            ),
            pipeRealBmwHidCommand(knobUuid, byteArrayOf(0x00, 0x00, 0x7F, 0x00)),
        )

        // 9. iDrive Rotary +2 00 00 00 02 -> releases DPAD_DOWN + Relative(SCROLL_WHEEL=0x10000, +2)
        assertEquals(
            listOf(
                BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.DPAD_DOWN, false),
                BmwToAapInputMapper.AapInputAction.Relative(AapProto.AapKeyCode.SCROLL_WHEEL, 2),
            ),
            pipeRealBmwHidCommand(knobUuid, byteArrayOf(0x00, 0x00, 0x00, 0x02)),
        )
        assertEquals(0x10000, AapProto.AapKeyCode.SCROLL_WHEEL)

        // 10. Media 0x01 (Next) press & release
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_NEXT, true)),
            pipeRealBmwHidCommand(mediaUuid, byteArrayOf(0x01)),
        )
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.MEDIA_NEXT, false)),
            pipeRealBmwHidCommand(mediaUuid, byteArrayOf(0x00)),
        )

        // 11. Phone 0x01 (Voice/Siri) press & release (1-byte and 3-byte BMW PhoneDescriptor format)
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, true)),
            pipeRealBmwHidCommand(phoneUuid, byteArrayOf(0x01)),
        )
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, false)),
            pipeRealBmwHidCommand(phoneUuid, byteArrayOf(0x00)),
        )
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, true)),
            pipeRealBmwHidCommand(phoneUuid, byteArrayOf(0x01, 0x00, 0x00)),
        )
        assertEquals(
            listOf(BmwToAapInputMapper.AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, false)),
            pipeRealBmwHidCommand(phoneUuid, byteArrayOf(0x00, 0x00, 0x00)),
        )
    }
}
