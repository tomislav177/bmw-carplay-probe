package io.bimmergestalt.bmwcarplay.iap2.link

import java.io.ByteArrayOutputStream

/**
 * Pure, deterministic iAP2 Link Layer state machine tailored for real BMW HU_ENTRYNAV2
 * (and compatible with Audi/testbench accessories).
 *
 * Critical BMW behaviors enforced here:
 * 1. Sends FF 55 02 00 EE 10 on start and responds to incoming marker ONLY while in DETECTING.
 *    Never re-emits the marker once NEGOTIATING/NORMAL is entered (prevents resetting BMW).
 * 2. Pure incoming SYN ((control & 0x80) != 0 && (control & 0x40) == 0):
 *    - NEVER sends a bare ACK before SYN|ACK (avoids sentSequence collision).
 *    - If byte-for-byte identical to the previous pure SYN (same seq & payload): repeats the
 *      exact previous SYN|ACK without incrementing sentSequence.
 *    - If a new pure SYN (even a renegotiated SYN with a new seq number): increments sentSequence
 *      and sends SYN|ACK (control = 0xC0, ack = peerSynSeq) faithfully echoing the peer's session
 *      table (e.g. {1: Control, 2: EA}).
 * 3. Incoming SYN|ACK ((control & 0x80) != 0 && (control & 0x40) != 0):
 *    - NEVER replies with SYN|ACK (eliminates infinite SYN|ACK ping-pong!).
 *    - Adopts peer's session table, replies with a single ACK, and enters NORMAL.
 * 4. Dynamically uses the negotiated Control Session ID (e.g. 1 from BMW's table, not hardcoded 10).
 * 5. Fragments outgoing Control Session payloads according to peer's maxPacketLength and supports
 *    both tracked (awaitAck = true) and fire-and-forget (awaitAck = false) transmissions.
 */
class Iap2LinkEngine(
    initialSequence: Int = INITIAL_SEQUENCE,
) {
    enum class State {
        IDLE,
        DETECTING,
        NEGOTIATING,
        NORMAL,
        CLOSED,
    }

    sealed class Event {
        data class StateChanged(val state: State, val detailHr: String) : Event()
        data class SyncNegotiated(
            val peerLsp: Iap2LinkPacket.LinkSyncPayload,
            val controlSessionId: Int,
            val eaSessionIds: List<Int>,
        ) : Event()

        data class ControlDataReceived(val sessionId: Int, val payload: ByteArray) : Event()
        data class EaDataReceived(val sessionId: Int, val payload: ByteArray) : Event()
        data class UnknownSessionData(val sessionId: Int, val payload: ByteArray) : Event()
        data class DiagnosticNote(val messageHr: String) : Event()
        data class LinkClosed(val reasonHr: String) : Event()
    }

    private data class InFlightPacket(
        val frame: Iap2LinkPacket.Frame,
        val encoded: ByteArray,
        var retransmissions: Int,
        var nextRetryAtMs: Long,
    )

    companion object {
        const val INITIAL_SEQUENCE = 0x63 // 99
        private const val DEFAULT_MAX_PACKET_LENGTH = 512
        private const val DEFAULT_RETX_TIMEOUT_MS = 3_000L
        private const val DEFAULT_ACK_TIMEOUT_MS = 1_500L
        private const val DEFAULT_MAX_RETX = 5
        private const val DETECT_INITIATE_SYN_DELAY_MS = 1_200L
    }

    var state: State = State.IDLE
        private set

    var peerLsp: Iap2LinkPacket.LinkSyncPayload? = null
        private set

    var controlSessionId: Int = 10
        private set

    var eaSessionIds: Set<Int> = emptySet()
        private set

    var sentSequence: Int = initialSequence and 0xFF
        private set

    var lastReceivedSeq: Int = 0
        private set

    private var hasReceivedPeerPacket: Boolean = false
    private var markerRespondedInDetecting: Boolean = false
    private var detectStartedAtMs: Long = 0L
    private var initiatedOwnSyn: Boolean = false

    // Last pure SYN tracking for idempotent duplicate handling vs new BMW SYN handling
    private var lastPeerSynSeq: Int? = null
    private var lastPeerSynPayload: ByteArray? = null
    private var lastSynAckFrame: Iap2LinkPacket.Frame? = null

    private var lastTxAtMs: Long = 0L
    private var lastRxAtMs: Long = 0L

    private val scanner = Iap2LinkPacket.FrameStreamScanner()
    private val outQueue = ByteArrayOutputStream()
    private val outFramesLog = ArrayList<Pair<String, ByteArray>>()
    private val pendingEvents = ArrayDeque<Event>()
    private val inFlight = ArrayList<InFlightPacket>()

    /**
     * Maximum Control Session payload bytes per single FF 5A packet:
     * totalPacketLen = 9 (header) + payloadLen + 1 (payload checksum).
     */
    fun maxControlPayloadLength(): Int {
        val peerMax = peerLsp?.maxPacketLength ?: DEFAULT_MAX_PACKET_LENGTH
        return (peerMax - Iap2LinkPacket.HEADER_SIZE - 1).coerceIn(32, 65_500)
    }

    fun ackTimeoutMs(): Long {
        val peerAck = peerLsp?.ackTimeoutMs ?: DEFAULT_ACK_TIMEOUT_MS.toInt()
        return peerAck.toLong().coerceIn(500L, 10_000L)
    }

    fun retransmissionTimeoutMs(): Long {
        val peerRetx = peerLsp?.retransmissionTimeoutMs ?: DEFAULT_RETX_TIMEOUT_MS.toInt()
        return peerRetx.toLong().coerceIn(800L, 15_000L)
    }

    fun maxRetransmissions(): Int {
        val peerMax = peerLsp?.maxRetransmissions ?: DEFAULT_MAX_RETX
        return peerMax.coerceIn(2, 15)
    }

    /**
     * Starts the link layer: enters DETECTING and queues the initial FF 55 02 00 EE 10 marker.
     */
    fun start(nowMs: Long) {
        if (state != State.IDLE) return
        detectStartedAtMs = nowMs
        lastTxAtMs = nowMs
        lastRxAtMs = nowMs
        transitionTo(State.DETECTING, "Šaljem iAP2 detekcijski marker (FF 55 02 00 EE 10)")
        queueRawBytes("TX DETECT_MARKER", Iap2LinkPacket.DETECT_MARKER)
    }

    /**
     * Feeds raw bytes received from Bluetooth RFCOMM into the link state machine.
     */
    fun onBytesReceived(chunk: ByteArray, nowMs: Long) {
        if (state == State.CLOSED || chunk.isEmpty()) return
        lastRxAtMs = nowMs
        val elements = scanner.feed(chunk)
        for (element in elements) {
            when (element) {
                is Iap2LinkPacket.ParsedElement.DetectMarker -> handleDetectMarker(nowMs)
                is Iap2LinkPacket.ParsedElement.LinkFrame -> handleLinkFrame(element.frame, nowMs)
            }
        }
    }

    /**
     * Queues a Control Session (CSM) message payload on the negotiated [controlSessionId],
     * automatically fragmenting if larger than [maxControlPayloadLength].
     *
     * @param awaitAck if false (e.g. 0x4E0E / 0x4300), packet is sent with valid seq/ack
     *                 but will NOT trigger retransmission timeouts or link tear-down.
     */
    fun sendControlPayload(
        payload: ByteArray,
        nowMs: Long,
        awaitAck: Boolean = true,
    ): Boolean {
        if (state != State.NORMAL || payload.isEmpty()) return false
        val maxChunk = maxControlPayloadLength()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxChunk, payload.size)
            val slice = payload.copyOfRange(offset, end)
            val seq = allocateNextSequence()
            val frame = Iap2LinkPacket.Frame(
                control = Iap2LinkPacket.CTRL_ACK,
                seq = seq,
                ack = lastReceivedSeq,
                sessionId = controlSessionId,
                payload = slice,
            )
            queueFrame(frame, nowMs, trackForRetransmit = awaitAck)
            offset = end
        }
        return true
    }

    /**
     * Periodic timer tick handling:
     * - Optional phone-initiated SYN if peer stays silent in DETECTING
     * - Retransmission of unacknowledged tracked packets
     * - Periodic heartbeat ACK respecting peer's ackTimeoutMs
     */
    fun tick(nowMs: Long) {
        if (state == State.IDLE || state == State.CLOSED) return

        // If peer responded with marker (or stayed quiet) and hasn't sent its own SYN yet,
        // politely initiate our SYN once after DETECT_INITIATE_SYN_DELAY_MS.
        if (state == State.DETECTING && !initiatedOwnSyn &&
            nowMs - detectStartedAtMs >= DETECT_INITIATE_SYN_DELAY_MS
        ) {
            initiatedOwnSyn = true
            val lsp = Iap2LinkPacket.LinkSyncPayload.defaultPhoneLsp()
            val seq = allocateNextSequence()
            val synFrame = Iap2LinkPacket.Frame(
                control = Iap2LinkPacket.CTRL_SYN,
                seq = seq,
                ack = 0,
                sessionId = 0,
                payload = lsp.encode(),
            )
            transitionTo(State.NEGOTIATING, "Auto nije poslao SYN unutar ${DETECT_INITIATE_SYN_DELAY_MS}ms — iniciram SYN (seq=$seq)")
            queueFrame(synFrame, nowMs, trackForRetransmit = true)
            return
        }

        // Retransmit unacknowledged tracked packets
        val retxTimeout = retransmissionTimeoutMs()
        val maxRetx = maxRetransmissions()
        for (pkt in inFlight.toList()) {
            if (nowMs >= pkt.nextRetryAtMs) {
                if (pkt.retransmissions >= maxRetx) {
                    inFlight.clear()
                    transitionTo(State.CLOSED, "Istekao broj ponovnih slanja za paket seq=${pkt.frame.seq}")
                    pendingEvents.addLast(Event.LinkClosed("iAP2 link timeout (bez ACK za seq=${pkt.frame.seq})"))
                    return
                }
                pkt.retransmissions++
                pkt.nextRetryAtMs = nowMs + retxTimeout
                pendingEvents.addLast(
                    Event.DiagnosticNote(
                        "Ponovno slanje paketa seq=${pkt.frame.seq} (${pkt.frame.controlLabel()}, pokušaj ${pkt.retransmissions}/$maxRetx)",
                    ),
                )
                queueRawBytes("TX RETX seq=${pkt.frame.seq}", pkt.encoded)
                lastTxAtMs = nowMs
            }
        }

        // Heartbeat ACK in NORMAL state respecting peer ackTimeoutMs
        if (state == State.NORMAL && hasReceivedPeerPacket) {
            val heartbeatInterval = (ackTimeoutMs() / 2L).coerceIn(500L, 4_000L)
            if (nowMs - lastTxAtMs >= heartbeatInterval) {
                sendBareAck(nowMs)
            }
        }
    }

    /**
     * Drains all outbound bytes queued since the last call.
     */
    fun drainOutgoingBytes(): ByteArray {
        if (outQueue.size() == 0) return ByteArray(0)
        val bytes = outQueue.toByteArray()
        outQueue.reset()
        return bytes
    }

    /**
     * Drains descriptive labels + raw byte arrays for each queued outgoing frame (for HEX logging).
     */
    fun drainOutgoingFrameLogs(): List<Pair<String, ByteArray>> {
        if (outFramesLog.isEmpty()) return emptyList()
        val copy = outFramesLog.toList()
        outFramesLog.clear()
        return copy
    }

    /**
     * Drains all emitted state-machine events.
     */
    fun drainEvents(): List<Event> {
        if (pendingEvents.isEmpty()) return emptyList()
        val copy = pendingEvents.toList()
        pendingEvents.clear()
        return copy
    }

    private fun handleDetectMarker(nowMs: Long) {
        when (state) {
            State.IDLE, State.DETECTING -> {
                if (state == State.IDLE) {
                    detectStartedAtMs = nowMs
                    transitionTo(State.DETECTING, "Primljen dolazni iAP2 marker (FF 55 02 00 EE 10)")
                } else {
                    pendingEvents.addLast(Event.DiagnosticNote("Primljen dolazni iAP2 detekcijski marker (FF 55 02 00 EE 10)"))
                }
                if (!markerRespondedInDetecting) {
                    markerRespondedInDetecting = true
                    queueRawBytes("TX DETECT_MARKER (odgovor)", Iap2LinkPacket.DETECT_MARKER)
                    lastTxAtMs = nowMs
                }
            }

            State.NEGOTIATING, State.NORMAL -> {
                // CRITICAL: Never send DETECT_MARKER once in NEGOTIATING/NORMAL!
                // Doing so would kick the BMW head unit back to Detecting state.
                pendingEvents.addLast(
                    Event.DiagnosticNote(
                        "Ignoriram ponovljeni FF 55 marker u stanju $state (ne odgovaram da ne resetiram BMW)",
                    ),
                )
            }

            State.CLOSED -> Unit
        }
    }

    private fun handleLinkFrame(frame: Iap2LinkPacket.Frame, nowMs: Long) {
        if (frame.isRst) {
            inFlight.clear()
            transitionTo(State.CLOSED, "Auto je poslao RST (0x10) — zatvaram iAP2 link")
            pendingEvents.addLast(Event.LinkClosed("Auto je poslao RST (Reset)"))
            return
        }

        // Case 1: Pure SYN (SYN=1, ACK=0) — typical BMW head unit negotiation & re-negotiation!
        if (frame.isPureSyn) {
            handlePureSyn(frame, nowMs)
            return
        }

        // Case 2: SYN|ACK (SYN=1, ACK=1) — response to our own SYN. NEVER reply with SYN|ACK!
        if (frame.isSynAck) {
            handleSynAck(frame, nowMs)
            return
        }

        // Case 3: ACK / Data packet (control has ACK=1, SYN=0)
        if (frame.isAck) {
            acknowledgeUpTo(frame.ack)

            // If we were waiting for peer ACK on our SYN|ACK, transition to NORMAL immediately.
            if (state == State.NEGOTIATING) {
                transitionTo(
                    State.NORMAL,
                    "iAP2 Link uspostavljen (NORMAL) — Control Session ID=$controlSessionId, maxPayload=${maxControlPayloadLength()} B",
                )
            }

            if (frame.payload.isNotEmpty()) {
                handleDataPayload(frame, nowMs)
            }
            return
        }

        pendingEvents.addLast(
            Event.DiagnosticNote("Primljen neuobičajen iAP2 okvir: ${frame.controlLabel()} seq=${frame.seq} ack=${frame.ack} sid=${frame.sessionId}"),
        )
    }

    private fun handlePureSyn(frame: Iap2LinkPacket.Frame, nowMs: Long) {
        val decodedLsp = Iap2LinkPacket.LinkSyncPayload.decode(frame.payload)
        if (decodedLsp == null) {
            pendingEvents.addLast(
                Event.DiagnosticNote("Primljen SYN bez valjanog LSP payloada (${frame.payload.size} B), ignoriram"),
            )
            return
        }

        // Check byte-for-byte duplicate of the immediately preceding pure SYN
        val prevSeq = lastPeerSynSeq
        val prevPayload = lastPeerSynPayload
        val prevSynAck = lastSynAckFrame
        if (prevSeq != null &&
            prevSeq == frame.seq &&
            prevPayload != null &&
            prevPayload.contentEquals(frame.payload) &&
            prevSynAck != null
        ) {
            pendingEvents.addLast(
                Event.DiagnosticNote(
                    "Duplikat identičnog SYN paketa (seq=${frame.seq}) — ponavljam isti SYN|ACK (seq=${prevSynAck.seq}) bez povećanja sentSequence",
                ),
            )
            queueFrame(prevSynAck, nowMs, trackForRetransmit = false)
            return
        }

        // New pure SYN (first SYN or BMW renegotiated SYN with new seq!)
        adoptPeerLsp(decodedLsp)
        lastReceivedSeq = frame.seq
        hasReceivedPeerPacket = true
        lastPeerSynSeq = frame.seq
        lastPeerSynPayload = frame.payload.copyOf()

        // Drop any stale unacknowledged SYN frames from an earlier negotiation round
        inFlight.removeAll { it.frame.isSyn }

        val echoLsp = buildEchoLsp(decodedLsp)
        val synAckSeq = allocateNextSequence()
        val synAckFrame = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_SYN or Iap2LinkPacket.CTRL_ACK,
            seq = synAckSeq,
            ack = frame.seq,
            sessionId = 0,
            payload = echoLsp.encode(),
        )
        lastSynAckFrame = synAckFrame

        // Note: Do NOT send a bare ACK before SYN|ACK (would cause sequence collision!).
        if (state != State.NORMAL) {
            transitionTo(
                State.NEGOTIATING,
                "Primljen čisti SYN (seq=${frame.seq}), šaljem SYN|ACK (seq=$synAckSeq, ack=${frame.seq}) s odjeknutom tablicom sesija",
            )
        } else {
            pendingEvents.addLast(
                Event.DiagnosticNote(
                    "BMW poslao ponovni SYN (seq=${frame.seq}) — odgovaram novim SYN|ACK (seq=$synAckSeq, ack=${frame.seq})",
                ),
            )
        }

        // Send SYN|ACK; track it in case BMW drops it, and immediately allow ACK or data transition.
        queueFrame(synAckFrame, nowMs, trackForRetransmit = true)
    }

    private fun handleSynAck(frame: Iap2LinkPacket.Frame, nowMs: Long) {
        val decodedLsp = Iap2LinkPacket.LinkSyncPayload.decode(frame.payload)
        if (decodedLsp != null) {
            adoptPeerLsp(decodedLsp)
        }
        acknowledgeUpTo(frame.ack)
        lastReceivedSeq = frame.seq
        hasReceivedPeerPacket = true

        // CRITICAL: Never reply with SYN|ACK to a peer's SYN|ACK (prevents ping-pong!).
        // Send a pure ACK and transition to NORMAL.
        sendBareAck(nowMs)
        if (state != State.NORMAL) {
            transitionTo(
                State.NORMAL,
                "Primljen SYN|ACK (seq=${frame.seq}, ack=${frame.ack}) — šaljem ACK i ulazim u NORMAL (Control SID=$controlSessionId)",
            )
        } else {
            pendingEvents.addLast(
                Event.DiagnosticNote(
                    "Primljen ponovljeni SYN|ACK (seq=${frame.seq}) u NORMAL stanju — šaljem samo ACK (bez SYN|ACK ping-ponga)",
                ),
            )
        }
    }

    private fun handleDataPayload(frame: Iap2LinkPacket.Frame, nowMs: Long) {
        val isDuplicate = hasReceivedPeerPacket && frame.seq == lastReceivedSeq
        if (isDuplicate) {
            pendingEvents.addLast(
                Event.DiagnosticNote("Duplikat podatkovnog okvira seq=${frame.seq} — šaljem ACK i preskačem duplu obradu"),
            )
            sendBareAck(nowMs)
            return
        }

        lastReceivedSeq = frame.seq
        hasReceivedPeerPacket = true
        sendBareAck(nowMs)

        when {
            frame.sessionId == controlSessionId -> {
                pendingEvents.addLast(Event.ControlDataReceived(frame.sessionId, frame.payload))
            }

            frame.sessionId in eaSessionIds -> {
                pendingEvents.addLast(Event.EaDataReceived(frame.sessionId, frame.payload))
            }

            else -> {
                pendingEvents.addLast(Event.UnknownSessionData(frame.sessionId, frame.payload))
            }
        }
    }

    private fun adoptPeerLsp(lsp: Iap2LinkPacket.LinkSyncPayload) {
        peerLsp = lsp
        val negotiatedControlId = lsp.controlSessionId()
        if (negotiatedControlId != null) {
            controlSessionId = negotiatedControlId
        }
        eaSessionIds = lsp.eaSessionIds().toSet()
        pendingEvents.addLast(
            Event.SyncNegotiated(
                peerLsp = lsp,
                controlSessionId = controlSessionId,
                eaSessionIds = eaSessionIds.toList(),
            ),
        )
    }

    /**
     * Faithfully echoes the peer's proposed session table (e.g. {1: Control, 2: EA})
     * and negotiated packet/timeout parameters.
     */
    private fun buildEchoLsp(peer: Iap2LinkPacket.LinkSyncPayload): Iap2LinkPacket.LinkSyncPayload =
        Iap2LinkPacket.LinkSyncPayload(
            linkVersion = peer.linkVersion,
            maxOutgoingPackets = peer.maxOutgoingPackets.coerceIn(4, 127),
            maxPacketLength = peer.maxPacketLength.coerceAtLeast(128),
            retransmissionTimeoutMs = peer.retransmissionTimeoutMs.coerceAtLeast(1000),
            ackTimeoutMs = peer.ackTimeoutMs.coerceAtLeast(500),
            maxRetransmissions = peer.maxRetransmissions.coerceAtLeast(3),
            maxCumulativeAcks = peer.maxCumulativeAcks.coerceAtLeast(1),
            sessions = peer.sessions,
        )

    private fun sendBareAck(nowMs: Long) {
        val ackFrame = Iap2LinkPacket.Frame(
            control = Iap2LinkPacket.CTRL_ACK,
            seq = sentSequence,
            ack = lastReceivedSeq,
            sessionId = 0,
            payload = ByteArray(0),
        )
        queueFrame(ackFrame, nowMs, trackForRetransmit = false)
    }

    private fun queueFrame(
        frame: Iap2LinkPacket.Frame,
        nowMs: Long,
        trackForRetransmit: Boolean,
    ) {
        val encoded = frame.encode()
        queueRawBytes(
            "TX ${frame.controlLabel()} seq=${frame.seq} ack=${frame.ack} sid=${frame.sessionId} payload=${frame.payload.size}B",
            encoded,
        )
        lastTxAtMs = nowMs
        if (trackForRetransmit) {
            inFlight.add(
                InFlightPacket(
                    frame = frame,
                    encoded = encoded,
                    retransmissions = 0,
                    nextRetryAtMs = nowMs + retransmissionTimeoutMs(),
                ),
            )
        }
    }

    private fun queueRawBytes(label: String, bytes: ByteArray) {
        outQueue.write(bytes)
        outFramesLog.add(label to bytes)
    }

    private fun acknowledgeUpTo(ackSeq: Int) {
        if (inFlight.isEmpty()) return
        val iter = inFlight.iterator()
        while (iter.hasNext()) {
            val pkt = iter.next()
            val diff = (ackSeq - pkt.frame.seq) and 0xFF
            // diff in 0..127 means ackSeq >= pkt.seq in mod-256 arithmetic
            if (diff in 0..127) {
                iter.remove()
            }
        }
    }

    private fun allocateNextSequence(): Int {
        sentSequence = (sentSequence + 1) and 0xFF
        return sentSequence
    }

    private fun transitionTo(newState: State, detailHr: String) {
        state = newState
        pendingEvents.addLast(Event.StateChanged(newState, detailHr))
    }
}
