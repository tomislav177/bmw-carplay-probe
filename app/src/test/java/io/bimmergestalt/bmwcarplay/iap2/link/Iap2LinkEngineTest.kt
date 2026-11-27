package io.bimmergestalt.bmwcarplay.iap2.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkEngineTest {

    private fun bmwLsp(maxLen: Int = 512) = Iap2LinkPacket.LinkSyncPayload(
        maxOutgoingPackets = 4,
        maxPacketLength = maxLen,
        retransmissionTimeoutMs = 2000,
        ackTimeoutMs = 1000,
        maxRetransmissions = 5,
        maxCumulativeAcks = 3,
        sessions = listOf(
            Iap2LinkPacket.SessionDescriptor(id = 1, type = Iap2LinkPacket.SESSION_TYPE_CONTROL, version = 2),
            Iap2LinkPacket.SessionDescriptor(id = 2, type = Iap2LinkPacket.SESSION_TYPE_EXTERNAL_ACCESSORY, version = 1),
        ),
    )

    private fun decodeAllFrames(bytes: ByteArray): List<Iap2LinkPacket.ParsedElement> =
        Iap2LinkPacket.FrameStreamScanner().feed(bytes)

    @Test
    fun bmwMultiplePureSynsWithNewSeqIncrementSentSeqWithoutBareAckBeforeSynAck() {
        val engine = Iap2LinkEngine(initialSequence = 0x63)
        engine.start(nowMs = 1000L)
        val initialOut = decodeAllFrames(engine.drainOutgoingBytes())
        assertEquals(1, initialOut.size)
        assertTrue(initialOut[0] is Iap2LinkPacket.ParsedElement.DetectMarker)

        // First pure SYN from BMW with seq = 0x10 and session table {1: Control, 2: EA}
        val syn1 = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN,
            seq = 0x10,
            ack = 0,
            sessionId = 0,
            payload = bmwLsp().encode(),
        )
        engine.onBytesReceived(syn1.encode(), nowMs = 1100L)

        val resp1 = decodeAllFrames(engine.drainOutgoingBytes())
        // MUST emit ONLY SYN|ACK, NEVER a bare ACK before SYN|ACK
        assertEquals(1, resp1.size)
        val synAck1 = (resp1[0] as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertEquals(Iap2LinkPacket.CTRL_SYN or Iap2LinkPacket.CTRL_ACK, synAck1.control)
        assertEquals(0x64, synAck1.seq)
        assertEquals(0x10, synAck1.ack)
        val echoed1 = Iap2LinkPacket.LinkSyncPayload.decode(synAck1.payload)!!
        assertEquals(1, echoed1.controlSessionId())
        assertEquals(listOf(2), echoed1.eaSessionIds())

        // Second pure SYN from BMW with a NEW seq = 0x11 (BMW renegotiation!)
        val syn2 = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN,
            seq = 0x11,
            ack = 0,
            sessionId = 0,
            payload = bmwLsp().encode(),
        )
        engine.onBytesReceived(syn2.encode(), nowMs = 1300L)

        val resp2 = decodeAllFrames(engine.drainOutgoingBytes())
        assertEquals(1, resp2.size)
        val synAck2 = (resp2[0] as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertEquals(Iap2LinkPacket.CTRL_SYN or Iap2LinkPacket.CTRL_ACK, synAck2.control)
        assertEquals(0x65, synAck2.seq) // MUST increment sentSequence for new SYN
        assertEquals(0x11, synAck2.ack)
    }

    @Test
    fun identicalDuplicatePureSynRepeatsExactSynAckWithoutIncrementingSentSequence() {
        val engine = Iap2LinkEngine(initialSequence = 0x20)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()

        val syn = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN,
            seq = 0x42,
            ack = 0,
            sessionId = 0,
            payload = bmwLsp().encode(),
        )
        val synBytes = syn.encode()

        engine.onBytesReceived(synBytes, nowMs = 1100L)
        val firstResp = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertEquals(0x21, firstResp.seq)

        // Exact byte-for-byte duplicate SYN -> MUST repeat seq=0x21 without incrementing
        engine.onBytesReceived(synBytes, nowMs = 1200L)
        val dupResp = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertEquals(firstResp.seq, dupResp.seq)
        assertEquals(firstResp.ack, dupResp.ack)
        assertEquals(0x21, engine.sentSequence)
    }

    @Test
    fun detectingResendsMarkerEverySecondBeforeProbingSyn() {
        val engine = Iap2LinkEngine(initialSequence = 0x63)
        engine.start(nowMs = 1000L)
        val first = decodeAllFrames(engine.drainOutgoingBytes())
        assertEquals(1, first.size)
        assertTrue(first[0] is Iap2LinkPacket.ParsedElement.DetectMarker)

        // At +1000ms (2000ms) -> resends DETECT_MARKER (stays in DETECTING!)
        engine.tick(nowMs = 2000L)
        assertEquals(Iap2LinkEngine.State.DETECTING, engine.state)
        val resend = decodeAllFrames(engine.drainOutgoingBytes())
        assertEquals(1, resend.size)
        assertTrue(resend[0] is Iap2LinkPacket.ParsedElement.DetectMarker)

        // At +6000ms (7000ms) with no response -> sends probe SYN
        engine.tick(nowMs = 7000L)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, engine.state)
        val probe = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertTrue(probe.isPureSyn)
    }

    @Test
    fun incomingSynAckNeverTriggersSynAckPingPongAndEntersNormal() {
        val engine = Iap2LinkEngine(initialSequence = 0x63)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()

        // Peer responds with DETECT_MARKER; engine waits 1.2s then initiates SYN
        engine.onBytesReceived(Iap2LinkPacket.DETECT_MARKER, nowMs = 1100L)
        engine.drainOutgoingBytes()
        engine.tick(nowMs = 2400L)
        val ownSyn = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertTrue(ownSyn.isPureSyn)

        // Peer responds with SYN|ACK proposing Control SID = 1
        val peerSynAck = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN or Iap2LinkPacket.CTRL_ACK,
            seq = 0x07,
            ack = ownSyn.seq,
            sessionId = 0,
            payload = bmwLsp().encode(),
        )
        engine.onBytesReceived(peerSynAck.encode(), nowMs = 2600L)

        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        assertEquals(1, engine.controlSessionId)

        val out = decodeAllFrames(engine.drainOutgoingBytes())
        assertEquals(1, out.size)
        val reply = (out[0] as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertFalse("Must NEVER reply with SYN bit set on peer SYN|ACK", reply.isSyn)
        assertTrue(reply.isAck)
        assertEquals(0x07, reply.ack)
    }

    @Test
    fun negotiatedControlSessionIdIsUsedAndMarkerIsNeverResentAfterNegotiating() {
        val engine = Iap2LinkEngine(initialSequence = 0x10)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()

        // BMW SYN proposing Control Session ID = 1 (not hardcoded 10!)
        val bmwSyn = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN,
            seq = 0x05,
            ack = 0,
            sessionId = 0,
            payload = bmwLsp(maxLen = 50).encode(),
        )
        engine.onBytesReceived(bmwSyn.encode(), nowMs = 1100L)
        val synAck = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame

        // BMW repeats FF 55 marker while in NEGOTIATING -> engine must NOT echo FF 55!
        engine.onBytesReceived(Iap2LinkPacket.DETECT_MARKER, nowMs = 1150L)
        assertEquals(0, engine.drainOutgoingBytes().size)

        // BMW ACKs our SYN|ACK -> enters NORMAL
        val bmwAck = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_ACK,
            seq = 0x05,
            ack = synAck.seq,
            sessionId = 0,
        )
        engine.onBytesReceived(bmwAck.encode(), nowMs = 1200L)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        assertEquals(1, engine.controlSessionId)

        // Send a 95-byte CSM payload with maxPacketLength = 50 (maxControlPayload = 40 -> 3 fragments: 40 + 40 + 15)
        val bigPayload = ByteArray(95) { (it and 0xFF).toByte() }
        assertTrue(engine.sendControlPayload(bigPayload, nowMs = 1300L, awaitAck = true))

        val outFrames = decodeAllFrames(engine.drainOutgoingBytes())
            .map { (it as Iap2LinkPacket.ParsedElement.LinkFrame).frame }
        assertEquals(3, outFrames.size)
        assertEquals(1, outFrames[0].sessionId)
        assertEquals(1, outFrames[1].sessionId)
        assertEquals(1, outFrames[2].sessionId)

        val recombined = outFrames[0].payload + outFrames[1].payload + outFrames[2].payload
        assertArrayEquals(bigPayload, recombined)
    }

    @Test
    fun fireAndForgetControlPacketDoesNotKillLinkWhenUnacknowledged() {
        val engine = Iap2LinkEngine(initialSequence = 0x10)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()

        val bmwSyn = Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_SYN, 1, 0, 0, bmwLsp().encode())
        engine.onBytesReceived(bmwSyn.encode(), nowMs = 1100L)
        val synAck = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        engine.onBytesReceived(Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_ACK, 1, synAck.seq, 0).encode(), nowMs = 1200L)
        engine.drainOutgoingBytes()

        // Send 0x4E0E style fire-and-forget packet
        assertTrue(engine.sendControlPayload(byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x4E, 0x0E), nowMs = 1300L, awaitAck = false))
        engine.drainOutgoingBytes()

        // Advance time well past retransmission timeout without any peer ACK -> link stays NORMAL!
        for (t in 2000L..30_000L step 1000L) {
            engine.tick(t)
        }
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
    }
}
