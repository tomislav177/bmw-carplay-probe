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

    @Test
    fun bmwEntryNav2LspEchoesAckMs100AndControlVersion1Faithfully() {
        val engine = Iap2LinkEngine(initialSequence = 0x63)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()

        // Exact SYN bytes observed from real BMW X1 F48 (HU_ENTRYNAV2) in session_20261127_112839.log
        val realBmwSynBytes = byteArrayOf(
            0xFF.toByte(), 0x5A, 0x00, 0x1D, 0x80.toByte(), 0x66, 0x00, 0x00, 0xA4.toByte(),
            0x01, 0x05, 0x08, 0x0A, 0x05, 0xDC.toByte(), 0x00, 0x64, 0x1E, 0x03,
            0x01, 0x00, 0x01, 0x02, 0x01, 0x01, 0x03, 0x02, 0x01, 0x76,
        )
        engine.onBytesReceived(realBmwSynBytes, nowMs = 1100L)

        val synAck = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertEquals(Iap2LinkPacket.CTRL_SYN or Iap2LinkPacket.CTRL_ACK, synAck.control)
        assertEquals(100, synAck.seq)
        assertEquals(102, synAck.ack)

        val echoed = Iap2LinkPacket.LinkSyncPayload.decode(synAck.payload)!!
        assertEquals(5, echoed.maxOutgoingPackets)
        assertEquals(2058, echoed.maxPacketLength)
        assertEquals(1500, echoed.retransmissionTimeoutMs)
        assertEquals(100, echoed.ackTimeoutMs)
        assertEquals(1, engine.controlSessionId)
        assertEquals(1, engine.controlSessionVersion())
        assertEquals(setOf(3), engine.eaSessionIds)
    }

    @Test
    fun normalIdleDoesNotSpamBareAcksEvery600msButSendsGentle5sKeepalive() {
        val engine = Iap2LinkEngine(initialSequence = 0x63)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()

        val realBmwSyn = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN,
            seq = 0x66,
            ack = 0,
            sessionId = 0,
            payload = Iap2LinkPacket.LinkSyncPayload(
                maxOutgoingPackets = 5,
                maxPacketLength = 2058,
                retransmissionTimeoutMs = 1500,
                ackTimeoutMs = 100,
                maxRetransmissions = 30,
                maxCumulativeAcks = 3,
                sessions = listOf(
                    Iap2LinkPacket.SessionDescriptor(1, Iap2LinkPacket.SESSION_TYPE_CONTROL, 1),
                ),
            ).encode(),
        )
        engine.onBytesReceived(realBmwSyn.encode(), nowMs = 1100L)
        val synAck = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        engine.onBytesReceived(Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_ACK, 0x66, synAck.seq, 0).encode(), nowMs = 1200L)
        engine.drainOutgoingBytes()
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)

        // Between 1200ms and 6199ms (< 5000ms idle) -> ZERO unsolicited bare ACKs!
        for (t in 1400L..6100L step 200L) {
            engine.tick(t)
        }
        assertEquals(0, engine.drainOutgoingBytes().size)

        // At +5000ms of silence (6200ms) -> exactly 1 gentle keepalive ACK!
        engine.tick(6200L)
        val keepalive = decodeAllFrames(engine.drainOutgoingBytes())
        assertEquals(1, keepalive.size)
        val ackFrame = (keepalive[0] as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        assertTrue(ackFrame.isAck && ackFrame.payload.isEmpty())
    }

    private fun normalBmwEngine(maxOut: Int): Iap2LinkEngine {
        val engine = Iap2LinkEngine(initialSequence = 0x63)
        engine.start(nowMs = 1000L)
        engine.drainOutgoingBytes()
        val lsp = bmwLsp().copy(maxOutgoingPackets = maxOut)
        engine.onBytesReceived(
            Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_SYN, 0x10, 0, 0, lsp.encode()).encode(),
            nowMs = 1100L,
        )
        val synAck = (decodeAllFrames(engine.drainOutgoingBytes()).single() as Iap2LinkPacket.ParsedElement.LinkFrame).frame
        engine.onBytesReceived(Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_ACK, 0x11, synAck.seq, 0).encode(), nowMs = 1200L)
        engine.drainOutgoingBytes()
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        return engine
    }

    private fun dataFrames(bytes: ByteArray): List<Iap2LinkPacket.Frame> =
        decodeAllFrames(bytes)
            .filterIsInstance<Iap2LinkPacket.ParsedElement.LinkFrame>()
            .map { it.frame }
            .filter { it.payload.isNotEmpty() }

    @Test
    fun sendControlPayloadPacesStrictlyWithinBmwMaxOutgoingWindowOf5() {
        val engine = normalBmwEngine(maxOut = 5)
        assertEquals(5, engine.maxOutgoingWindow())

        repeat(9) { i -> engine.sendControlPayload(byteArrayOf(i.toByte(), 1, 2), nowMs = 1300L) }
        val first = dataFrames(engine.drainOutgoingBytes())
        assertEquals(5, first.size)

        // Peer ACKs the first 3 frames -> exactly 3 more may be emitted.
        engine.onBytesReceived(
            Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_ACK, 0x12, first[2].seq, 0).encode(),
            nowMs = 1400L,
        )
        val second = dataFrames(engine.drainOutgoingBytes())
        assertEquals(3, second.size)

        // ACK everything sent so far -> last frame drains.
        engine.onBytesReceived(
            Iap2LinkPacket.Frame(Iap2LinkPacket.CTRL_ACK, 0x13, second.last().seq, 0).encode(),
            nowMs = 1500L,
        )
        val third = dataFrames(engine.drainOutgoingBytes())
        assertEquals(1, third.size)
    }

    @Test
    fun fireAndForgetPacketsReleaseWindowSlotAfterTimeoutWithoutKillingLink() {
        val engine = normalBmwEngine(maxOut = 5)
        repeat(7) { i -> engine.sendControlPayload(byteArrayOf(i.toByte(), 9), nowMs = 1300L, awaitAck = false) }
        assertEquals(5, dataFrames(engine.drainOutgoingBytes()).size)

        // Before the 1.5s fire-and-forget timeout: nothing more, no retransmission.
        engine.tick(2000L)
        assertEquals(0, dataFrames(engine.drainOutgoingBytes()).size)

        // After the timeout: slots are released silently and the remaining 2 frames drain.
        engine.tick(3000L)
        val rest = dataFrames(engine.drainOutgoingBytes())
        assertEquals(2, rest.size)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
    }
}
