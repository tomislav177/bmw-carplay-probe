package io.bimmergestalt.bmwcarplay.video

import io.bimmergestalt.bmwcarplay.airplay.BinaryPlist
import io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CarPlayVideoFramingTest {

    private fun readU32Le(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readF32Le(bytes: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    @Test
    fun videoConfigHeaderSetsOpcode1EncryptedFlagZeroPtsAndFloat32Dimensions() {
        for ((w, h) in listOf(1280 to 480, 800 to 480)) {
            val header = CarPlayVideoFraming.buildVideoConfigHeader(
                avccBodySize = 42,
                width = w,
                height = h,
                encryptedFrames = true,
            )
            assertEquals(128, header.size)
            assertEquals(42, readU32Le(header, 0))
            assertEquals(CarPlayVideoFraming.OP_VIDEO_CONFIG, header[4].toInt() and 0xFF)
            assertEquals(CarPlayVideoFraming.SCREEN_FLAG_ENCRYPTED, header[6].toInt() and 0xFF)
            // params[0] (bytes 8..15) must be 0 in VideoConfig
            for (i in 8 until 16) assertEquals(0, header[i].toInt())
            assertEquals(w.toFloat(), readF32Le(header, 16), 0.0f)
            assertEquals(h.toFloat(), readF32Le(header, 20), 0.0f)
        }
    }

    @Test
    fun spsLevelPatchesUpwardTo40AndNeverLowersHigherLevels() {
        val sps31 = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 31, 0x99.toByte())
        val sps42 = byteArrayOf(0x67, 0x64, 0x00, 42, 0xAA.toByte())
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

        assertEquals(40, CarPlayVideoFraming.patchSpsLevelTo40(sps31)[3].toInt() and 0xFF)
        assertEquals(42, CarPlayVideoFraming.patchSpsLevelTo40(sps42)[3].toInt() and 0xFF)

        val avcc = CarPlayVideoFraming.buildAvcc(sps31, pps)
        assertEquals(0x01, avcc[0].toInt() and 0xFF)
        assertEquals(0x42, avcc[1].toInt() and 0xFF)
        assertEquals(40, avcc[3].toInt() and 0xFF) // Patched to Level 4.0 inside avcC header!
        assertEquals(40, avcc[8 + 3].toInt() and 0xFF) // Patched inside SPS NAL bytes too!

        val annexB = byteArrayOf(0, 0, 0, 1) + sps31 + byteArrayOf(0, 0, 1) + pps
        val packed = CarPlayVideoFraming.packAvccNals(annexB)
        // First 4 bytes BE length == sps31.size (5)
        assertEquals(5, ByteBuffer.wrap(packed, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(40, packed[4 + 3].toInt() and 0xFF)
    }

    @Test
    fun keepAliveOpcode5IncludesValidBplistStatsAndMirrorsLengthInParams14() {
        val frame = CarPlayVideoFraming.buildKeepAliveFrame(
            withStats = true,
            framesSentWindow = 30,
            bytesPerSecond = 812_500.0,
            framesPerSecond = 30,
        )
        assertEquals(CarPlayVideoFraming.OP_KEEP_ALIVE_WITH_STATS, frame[4].toInt() and 0xFF)
        val bodyLen = readU32Le(frame, 0)
        assertEquals(frame.size - 128, bodyLen)
        assertTrue(bodyLen > 40)
        // params[14] at offset 120..127 = (0.0f, bodyLen.toFloat())
        assertEquals(0.0f, readF32Le(frame, 120), 0.0f)
        assertEquals(bodyLen.toFloat(), readF32Le(frame, 124), 0.0f)

        val parsed = BinaryPlist.parse(frame.copyOfRange(128, frame.size)) as Map<*, *>
        assertEquals(30L, parsed["encoderCurrentFPS"])
        assertEquals(30L, parsed["sentFramesAvg"])
        assertEquals(0L, parsed["queuedFramesAvg"])
        assertEquals(812_500.0, parsed["txUsageAvg"] as Double, 0.01)

        // Empty keepAlive (opcode=2) must be exactly 128 bytes
        val empty = CarPlayVideoFraming.buildKeepAliveFrame(false, 0, 0.0, 0)
        assertEquals(128, empty.size)
        assertEquals(CarPlayVideoFraming.OP_KEEP_ALIVE_EMPTY, empty[4].toInt() and 0xFF)
        assertEquals(0, readU32Le(empty, 0))
    }

    @Test
    fun videoFrameChaCha20SealingUsesExact128ByteHeaderAsAad() {
        val streamKey = AirPlayCrypto.deriveDataStreamKey(ByteArray(32) { 0x5A }, 123456789L)
        val plainAvcc = byteArrayOf(0, 0, 0, 4, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        val header = CarPlayVideoFraming.buildVideoFrameHeader(
            wireBodySize = plainAvcc.size + CarPlayVideoFraming.CHACHA_TAG_SIZE,
            monotonicNanos = 5_000_000_000L,
        )
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(0L), plainAvcc, aad = header)
        assertEquals(plainAvcc.size + 16, sealed.size)
        assertEquals(sealed.size, readU32Le(header, 0))

        val opened = AirPlayCrypto.chachaOpen(streamKey, AirPlayCrypto.nonce64(0L), sealed, aad = header)
        assertArrayEquals(plainAvcc, opened)
    }
}
