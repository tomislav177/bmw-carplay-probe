package io.bimmergestalt.bmwcarplay.airplay.rtsp

import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2CsmCodec
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2MessageIds
import io.bimmergestalt.bmwcarplay.iap2.link.Iap2LinkPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualIap2TunnelTest {

    private fun bmwSynPacket(seq: Int = 73): ByteArray {
        val lsp = Iap2LinkPacket.LinkSyncPayload(
            linkVersion = 1,
            maxOutgoingPackets = 5,
            maxPacketLength = 2048,
            retransmissionTimeoutMs = 1500,
            ackTimeoutMs = 73,
            maxRetransmissions = 30,
            maxCumulativeAcks = 3,
            sessions = listOf(
                Iap2LinkPacket.SessionDescriptor(id = 1, type = 0, version = 2),
                Iap2LinkPacket.SessionDescriptor(id = 3, type = 2, version = 1),
            ),
        )
        return Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN,
            seq = seq,
            ack = 0,
            sessionId = 0,
            payload = lsp.encode(),
        ).encode()
    }

    private fun carAckPacket(carSeq: Int, phoneAck: Int, payload: ByteArray = ByteArray(0), sid: Int = 0): ByteArray =
        Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_ACK,
            seq = carSeq,
            ack = phoneAck,
            sessionId = sid,
            payload = payload,
        ).encode()

    @Test
    fun completesFullBmwVirtualIap2HandshakeOverControlChannel() {
        val controlSent = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        var readyCount = 0

        val tunnel = VirtualIap2Tunnel(
            log = { _, _ -> },
            sendViaControl = { data, _ ->
                controlSent.add(data)
                true
            },
            sendViaEvent = { data, _ ->
                eventSent.add(data)
                true
            },
            onStateChanged = { _, ready -> if (ready) readyCount++ },
            spawnWorkerThread = false,
        )

        // 1. BMW sends DETECT -> phone replies once via Control :7000
        tunnel.processIncomingNow(Iap2LinkPacket.DETECT_MARKER, 1_000L)
        assertEquals(1, controlSent.size)
        assertArrayEquals(Iap2LinkPacket.DETECT_MARKER, controlSent[0])
        assertEquals(0, eventSent.size)

        // 2. BMW sends pure SYN (seq=73) -> phone echoes SYN|ACK (seq=100, ack=73)
        tunnel.processIncomingNow(bmwSynPacket(seq = 73), 1_050L)
        assertEquals(2, controlSent.size)
        val synAck = Iap2LinkPacket.decodeFrame(controlSent[1])!!
        assertTrue(synAck.isSynAck)
        assertEquals(73, synAck.ack)
        assertEquals(VirtualIap2Tunnel.TxChannel.CONTROL, tunnel.activeTxChannel)

        // 3. BMW ACKs SYN|ACK -> link enters NORMAL and phone immediately sends 0xAA00
        tunnel.processIncomingNow(carAckPacket(carSeq = 73, phoneAck = synAck.seq), 1_100L)
        assertEquals(3, controlSent.size)
        val aa00Link = Iap2LinkPacket.decodeFrame(controlSent[2])!!
        val aa00Csm = Iap2CsmCodec.CsmReassembler().feed(aa00Link.payload).single()
        assertEquals(Iap2MessageIds.REQUEST_AUTH_CERTIFICATE, aa00Csm.messageId)

        // 4. BMW sends 0xAA01 (dummy cert) -> phone ACKs + sends 0xAA02 challenge
        val certCsm = Iap2CsmCodec.encodeFrame(Iap2MessageIds.AUTH_CERTIFICATE, Iap2CsmCodec.paramRaw(0, byteArrayOf(0x30, 0x00)))
        tunnel.processIncomingNow(carAckPacket(carSeq = 74, phoneAck = aa00Link.seq, payload = certCsm, sid = 1), 1_150L)
        val aa02Csm = controlSent.drop(3)
            .mapNotNull { Iap2LinkPacket.decodeFrame(it) }
            .filter { it.payload.isNotEmpty() }
            .flatMap { Iap2CsmCodec.CsmReassembler().feed(it.payload) }
            .first { it.messageId == Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE }
        assertEquals(Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE, aa02Csm.messageId)
        val aa02Seq = Iap2LinkPacket.decodeFrame(controlSent.last())!!.seq

        // 5. BMW sends 0xAA03 -> phone sends 0xAA05 + 0x1D00
        val beforeCount = controlSent.size
        val sigCsm = Iap2CsmCodec.encodeFrame(Iap2MessageIds.AUTH_RESPONSE, Iap2CsmCodec.paramRaw(0, ByteArray(64)))
        tunnel.processIncomingNow(carAckPacket(carSeq = 75, phoneAck = aa02Seq, payload = sigCsm, sid = 1), 1_200L)
        val postSigIds = controlSent.drop(beforeCount)
            .mapNotNull { Iap2LinkPacket.decodeFrame(it) }
            .filter { it.payload.isNotEmpty() }
            .flatMap { Iap2CsmCodec.CsmReassembler().feed(it.payload) }
            .map { it.messageId }
        assertTrue(Iap2MessageIds.AUTH_SUCCEEDED in postSigIds)
        assertTrue(Iap2MessageIds.START_IDENTIFICATION in postSigIds)
        val lastPhoneSeq = Iap2LinkPacket.decodeFrame(controlSent.last())!!.seq

        // 6. BMW sends 0x1D01 -> phone sends 0x1D02 + greeting and marks handoff complete!
        val beforeIdent = controlSent.size
        val identCsm = Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.IDENTIFICATION_INFORMATION,
            Iap2CsmCodec.paramString(0, "BMW 04990"),
            Iap2CsmCodec.paramString(1, "F48-EntryEVO-0317"),
        )
        tunnel.processIncomingNow(carAckPacket(carSeq = 76, phoneAck = lastPhoneSeq, payload = identCsm, sid = 1), 1_250L)
        val postIdentIds = controlSent.drop(beforeIdent)
            .mapNotNull { Iap2LinkPacket.decodeFrame(it) }
            .filter { it.payload.isNotEmpty() }
            .flatMap { Iap2CsmCodec.CsmReassembler().feed(it.payload) }
            .map { it.messageId }
        assertTrue(Iap2MessageIds.IDENTIFICATION_ACCEPTED in postIdentIds)
        assertTrue(tunnel.isHandoffCompleted)
        assertEquals(1, readyCount)
        assertEquals(0, eventSent.size)

        // 7. Stray repeated DETECT after NORMAL must NEVER re-emit DETECT!
        val countAfterDone = controlSent.size
        tunnel.processIncomingNow(Iap2LinkPacket.DETECT_MARKER, 2_000L)
        assertEquals(countAfterDone, controlSent.size)
    }

    @Test
    fun failsOverFromControlToEventWhenSecondDetectArrivesAfterOneSecond() {
        val controlSent = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()

        val tunnel = VirtualIap2Tunnel(
            log = { _, _ -> },
            sendViaControl = { data, _ ->
                controlSent.add(data)
                true // Simulates "fake 200 OK" where car ignores Control iAPSendMessage
            },
            sendViaEvent = { data, _ ->
                eventSent.add(data)
                true
            },
            spawnWorkerThread = false,
        )

        // 1st DETECT at T=1000ms -> sent on Control
        tunnel.processIncomingNow(Iap2LinkPacket.DETECT_MARKER, 1_000L)
        assertEquals(1, controlSent.size)
        assertEquals(0, eventSent.size)
        assertEquals(VirtualIap2Tunnel.TxChannel.CONTROL, tunnel.activeTxChannel)

        // 2nd DETECT at T=2000ms -> automatically switches to Event and sends 1x DETECT!
        tunnel.processIncomingNow(Iap2LinkPacket.DETECT_MARKER, 2_000L)
        assertEquals(1, controlSent.size)
        assertEquals(1, eventSent.size)
        assertArrayEquals(Iap2LinkPacket.DETECT_MARKER, eventSent[0])
        assertEquals(VirtualIap2Tunnel.TxChannel.EVENT, tunnel.activeTxChannel)

        // 3rd DETECT at T=3000ms -> must NOT keep spamming DETECT
        tunnel.processIncomingNow(Iap2LinkPacket.DETECT_MARKER, 3_000L)
        assertEquals(1, eventSent.size)

        // BMW responds with SYN -> SYN|ACK goes out on locked Event channel!
        tunnel.processIncomingNow(bmwSynPacket(seq = 42), 3_050L)
        assertEquals(2, eventSent.size)
        val synAck = Iap2LinkPacket.decodeFrame(eventSent[1])!!
        assertTrue(synAck.isSynAck)
        assertEquals(42, synAck.ack)
        assertFalse(tunnel.isHandoffCompleted)
    }

    @Test
    fun immediatelyFailsOverToEventWhenControlReturnsNon200() {
        val eventSent = ArrayList<ByteArray>()
        val tunnel = VirtualIap2Tunnel(
            log = { _, _ -> },
            sendViaControl = { _, _ -> false },
            sendViaEvent = { data, _ ->
                eventSent.add(data)
                true
            },
            spawnWorkerThread = false,
        )

        tunnel.processIncomingNow(Iap2LinkPacket.DETECT_MARKER, 1_000L)
        assertEquals(VirtualIap2Tunnel.TxChannel.EVENT, tunnel.activeTxChannel)
        assertEquals(1, eventSent.size)
        assertArrayEquals(Iap2LinkPacket.DETECT_MARKER, eventSent[0])
    }
}
