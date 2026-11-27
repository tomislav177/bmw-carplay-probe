package io.bimmergestalt.bmwcarplay.iap2.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkPacketTest {

    @Test
    fun headerAndPayloadChecksumsUseTwosComplement() {
        val sample = byteArrayOf(0xFF.toByte(), 0x5A, 0x00, 0x09, 0x40, 0x64, 0x01, 0x00)
        val csum = Iap2LinkPacket.computeChecksum(sample)
        val withCsum = sample + csum.toByte()

        assertTrue("Header + checksum sum mod 256 must equal 0", Iap2LinkPacket.verifyChecksum(withCsum))

        val sum = withCsum.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFF }
        assertEquals(0, sum)

        // Corrupt a single bit -> verification must fail
        val corrupted = withCsum.copyOf()
        corrupted[4] = (corrupted[4].toInt() xor 0x01).toByte()
        assertFalse(Iap2LinkPacket.verifyChecksum(corrupted))
    }

    @Test
    fun frameEncodeDecodeRoundTripWithAndWithoutPayload() {
        val bareAck = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_ACK,
            seq = 0x63,
            ack = 0x05,
            sessionId = 0x00,
            payload = ByteArray(0),
        )
        val bareBytes = bareAck.encode()
        assertEquals(9, bareBytes.size)
        assertTrue(Iap2LinkPacket.verifyChecksum(bareBytes, 0, 9))
        assertEquals(bareAck, Iap2LinkPacket.decodeFrame(bareBytes))

        val dataPayload = byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1D, 0x00)
        val dataFrame = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_ACK,
            seq = 0x64,
            ack = 0x06,
            sessionId = 0x01,
            payload = dataPayload,
        )
        val dataBytes = dataFrame.encode()
        assertEquals(9 + dataPayload.size + 1, dataBytes.size)
        assertTrue(Iap2LinkPacket.verifyChecksum(dataBytes, 0, 9))
        assertTrue(Iap2LinkPacket.verifyChecksum(dataBytes, 9, dataPayload.size + 1))

        val decoded = Iap2LinkPacket.decodeFrame(dataBytes)
        assertEquals(dataFrame, decoded)

        // Corrupt payload checksum -> decode must return null
        val badPayload = dataBytes.copyOf()
        badPayload[badPayload.lastIndex] = (badPayload.last().toInt() xor 0xFF).toByte()
        assertNull(Iap2LinkPacket.decodeFrame(badPayload))
    }

    @Test
    fun linkSyncPayloadRoundTripWithBmwSessionTable() {
        val lsp = Iap2LinkPacket.LinkSyncPayload(
            maxOutgoingPackets = 4,
            maxPacketLength = 512,
            retransmissionTimeoutMs = 2000,
            ackTimeoutMs = 1000,
            maxRetransmissions = 5,
            maxCumulativeAcks = 3,
            sessions = listOf(
                Iap2LinkPacket.SessionDescriptor(id = 1, type = Iap2LinkPacket.SESSION_TYPE_CONTROL, version = 2),
                Iap2LinkPacket.SessionDescriptor(id = 2, type = Iap2LinkPacket.SESSION_TYPE_EXTERNAL_ACCESSORY, version = 1),
            ),
        )
        val encoded = lsp.encode()
        assertEquals(16, encoded.size) // 10 header + 2 * 3 session descriptors

        val decoded = Iap2LinkPacket.LinkSyncPayload.decode(encoded)
        assertNotNull(decoded)
        assertEquals(lsp, decoded)
        assertEquals(1, decoded!!.controlSessionId())
        assertEquals(listOf(2), decoded.eaSessionIds())
    }

    @Test
    fun frameStreamScannerHandlesMarkerSplitChunksAndGarbageResync() {
        val scanner = Iap2LinkPacket.FrameStreamScanner()
        val f1 = Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_ACK, 10, 20, 1, byteArrayOf(1, 2, 3))
        val wire = byteArrayOf(0x00, 0x11, 0x22) + // leading garbage
            Iap2LinkPacket.DETECT_MARKER +
            byteArrayOf(0xFF.toByte(), 0x5A, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte()) + // bad checksum
            f1.encode()

        val found = ArrayList<Iap2LinkPacket.ParsedElement>()
        // Feed 2 bytes at a time to verify incremental buffering
        var i = 0
        while (i < wire.size) {
            val end = minOf(i + 2, wire.size)
            found.addAll(scanner.feed(wire.copyOfRange(i, end)))
            i = end
        }

        assertEquals(2, found.size)
        assertTrue(found[0] is Iap2LinkPacket.ParsedElement.DetectMarker)
        val parsedFrame = (found[1] as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertEquals(f1, parsedFrame)
        assertArrayEquals(f1.payload, parsedFrame.payload)
    }
}
