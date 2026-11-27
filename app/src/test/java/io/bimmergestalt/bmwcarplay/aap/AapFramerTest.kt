package io.bimmergestalt.bmwcarplay.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class AapFramerTest {

    @Test
    fun encodeAndReadSingleFrameRoundTrip() {
        val payload = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x06)
        val wire = AapFramer.encodeFrame(
            channelId = AapProto.CH_CONTROL,
            flags = AapFramer.FLAG_PLAIN_BULK,
            payload = payload,
        )

        assertEquals(10, wire.size)
        assertEquals(0x00.toByte(), wire[0])
        assertEquals(0x03.toByte(), wire[1])
        assertEquals(0x00.toByte(), wire[2])
        assertEquals(0x06.toByte(), wire[3])

        val decoded = AapFramer.readRawFrame(ByteArrayInputStream(wire))
        assertEquals(AapProto.CH_CONTROL, decoded.channelId)
        assertEquals(AapFramer.FLAG_PLAIN_BULK, decoded.flags)
        assertTrue(decoded.isFirst)
        assertTrue(decoded.isLast)
        assertFalse(decoded.isControl)
        assertFalse(decoded.isEncrypted)
        assertNull(decoded.totalSizeHint)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun encodeFrameEnforces65535ByteLimit() {
        val maxOk = AapFramer.encodeFrame(2, AapFramer.FLAG_ENC_BULK, ByteArray(0xFFFF))
        assertEquals(4 + 0xFFFF, maxOk.size)

        try {
            AapFramer.encodeFrame(2, AapFramer.FLAG_ENC_BULK, ByteArray(0xFFFF + 1))
            fail("Expected IllegalArgumentException for payload > 65535 bytes")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun prependAndReadMessageIdRoundTrip() {
        for (msgId in intArrayOf(0x0000, 0x0001, 0x0013, 0x8000, 0x8008, 0xFFFF)) {
            val body = byteArrayOf(0x11, 0x22, 0x33)
            val combined = AapFramer.prependMessageId(msgId, body)
            assertEquals(5, combined.size)
            assertEquals(msgId, AapFramer.readMessageId(combined))
            assertArrayEquals(body, combined.copyOfRange(2, combined.size))
        }

        try {
            AapFramer.readMessageId(byteArrayOf(0x01))
            fail("Expected IllegalArgumentException for 1-byte buffer")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun readRawFrameParsesFirstFragmentTotalSizeHintAndRejectsInvalidSizes() {
        val chunk = ByteArray(128) { it.toByte() }
        val firstWire = AapFramer.encodeFirstFragmentFrame(
            channelId = AapProto.CH_VIDEO,
            flags = AapFramer.FLAG_FIRST or AapFramer.FLAG_ENCRYPTED,
            totalSizeHint = 24_576,
            payload = chunk,
        )

        val decoded = AapFramer.readRawFrame(ByteArrayInputStream(firstWire))
        assertEquals(AapProto.CH_VIDEO, decoded.channelId)
        assertEquals(AapFramer.FLAG_FIRST or AapFramer.FLAG_ENCRYPTED, decoded.flags)
        assertEquals(24_576, decoded.totalSizeHint)
        assertArrayEquals(chunk, decoded.payload)

        // Negative totalSizeHint (0x80000000) must throw IOException
        val negativeWire = byteArrayOf(
            0x02,
            (AapFramer.FLAG_FIRST or AapFramer.FLAG_ENCRYPTED).toByte(),
            0x00, 0x04,
            0x80.toByte(), 0x00, 0x00, 0x00,
            1, 2, 3, 4,
        )
        try {
            AapFramer.readRawFrame(ByteArrayInputStream(negativeWire))
            fail("Expected IOException for negative totalSizeHint")
        } catch (_: IOException) {
            // Expected
        }

        // Oversized totalSizeHint (> 4 MiB) must throw IOException
        val tooLarge = AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES + 1
        val oversizedWire = byteArrayOf(
            0x02,
            AapFramer.FLAG_FIRST.toByte(),
            0x00, 0x02,
            ((tooLarge ushr 24) and 0xFF).toByte(),
            ((tooLarge ushr 16) and 0xFF).toByte(),
            ((tooLarge ushr 8) and 0xFF).toByte(),
            (tooLarge and 0xFF).toByte(),
            0xAA.toByte(), 0xBB.toByte(),
        )
        try {
            AapFramer.readRawFrame(ByteArrayInputStream(oversizedWire))
            fail("Expected IOException for oversized totalSizeHint")
        } catch (_: IOException) {
            // Expected
        }
    }

    @Test
    fun readRawFrameThrowsEofOnPrematureStreamEnd() {
        // Truncated header
        try {
            AapFramer.readRawFrame(ByteArrayInputStream(byteArrayOf(0x00, 0x03)))
            fail("Expected EOFException on truncated header")
        } catch (_: EOFException) {
            // Expected
        }

        // Truncated totalSizeHint
        try {
            AapFramer.readRawFrame(ByteArrayInputStream(byteArrayOf(0x02, 0x01, 0x00, 0x04, 0x00, 0x00)))
            fail("Expected EOFException on truncated totalSizeHint")
        } catch (_: EOFException) {
            // Expected
        }

        // Truncated payload
        try {
            AapFramer.readRawFrame(ByteArrayInputStream(byteArrayOf(0x00, 0x03, 0x00, 0x08, 1, 2, 3)))
            fail("Expected EOFException on truncated payload")
        } catch (_: EOFException) {
            // Expected
        }
    }

    @Test
    fun frameAssemblerReassemblesInterleavedChannelsAndClearsStalePartialBuffers() {
        val assembler = AapFrameAssembler()

        val v1 = ByteArray(10_000) { (it and 0xFF).toByte() }
        val v2 = ByteArray(10_000) { ((it * 3) and 0xFF).toByte() }
        val v3 = ByteArray(4_576) { ((it * 7) and 0xFF).toByte() }
        val expectedVideo = ByteArrayOutputStream().apply {
            write(v1)
            write(v2)
            write(v3)
        }.toByteArray()

        // Interleave control ping between video fragments
        assertNull(assembler.feed(AapProto.CH_VIDEO, AapFramer.FLAG_FIRST or AapFramer.FLAG_ENCRYPTED, v1, expectedVideo.size))

        val ctrlMsg = byteArrayOf(0x00, 0x0B, 0x08, 0x2A)
        val assembledCtrl = assembler.feed(AapProto.CH_CONTROL, AapFramer.FLAG_ENC_BULK, ctrlMsg, null)
        assertNotNull(assembledCtrl)
        assertArrayEquals(ctrlMsg, assembledCtrl)

        assertNull(assembler.feed(AapProto.CH_VIDEO, AapFramer.FLAG_ENCRYPTED, v2, null))
        val assembledVideo = assembler.feed(AapProto.CH_VIDEO, AapFramer.FLAG_LAST or AapFramer.FLAG_ENCRYPTED, v3, null)
        assertNotNull(assembledVideo)
        assertArrayEquals(expectedVideo, assembledVideo)

        // Stale partial buffer on CH_VIDEO must be discarded if a single FIRST|LAST frame arrives
        assertNull(assembler.feed(AapProto.CH_VIDEO, AapFramer.FLAG_FIRST, byteArrayOf(1, 2, 3), 100))
        val freshSingle = byteArrayOf(9, 8, 7)
        assertArrayEquals(freshSingle, assembler.feed(AapProto.CH_VIDEO, AapFramer.FLAG_PLAIN_BULK, freshSingle, null))
        // Subsequent orphan LAST must now return null
        assertNull(assembler.feed(AapProto.CH_VIDEO, AapFramer.FLAG_LAST, byteArrayOf(4, 5), null))
    }

    @Test
    fun frameAssemblerDropsOrphansEnforcesCapAndClears() {
        val assembler = AapFrameAssembler()

        // Orphan middle & last without prior FIRST return null
        assertNull(assembler.feed(2, 0x00, byteArrayOf(1, 2), null))
        assertNull(assembler.feed(2, AapFramer.FLAG_LAST, byteArrayOf(3, 4), null))

        // Start a fragment then clear() -> subsequent LAST must return null
        assertNull(assembler.feed(2, AapFramer.FLAG_FIRST, byteArrayOf(1, 2), 10))
        assembler.clear()
        assertNull(assembler.feed(2, AapFramer.FLAG_LAST, byteArrayOf(3, 4), null))

        // Exceeding MAX_ASSEMBLED_MESSAGE_BYTES must throw IOException
        val oneMb = ByteArray(1024 * 1024)
        assertNull(assembler.feed(2, AapFramer.FLAG_FIRST, oneMb, null))
        assertNull(assembler.feed(2, 0x00, oneMb, null))
        assertNull(assembler.feed(2, 0x00, oneMb, null))
        assertNull(assembler.feed(2, 0x00, oneMb, null))
        try {
            assembler.feed(2, AapFramer.FLAG_LAST, byteArrayOf(0x01), null)
            fail("Expected IOException when assembled size exceeds 4 MiB")
        } catch (_: IOException) {
            // Expected
        }
    }
}
