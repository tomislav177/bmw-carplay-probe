package io.bimmergestalt.bmwcarplay.airplay.rtsp

import io.bimmergestalt.bmwcarplay.diag.HexDump
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2CsmCodec
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2MessageIds
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import io.bimmergestalt.bmwcarplay.iap2.link.Iap2LinkEngine
import io.bimmergestalt.bmwcarplay.iap2.link.Iap2LinkPacket
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Virtual iAP2-over-AirPlay handoff tunnel carried inside CarPlay `iAPSendMessage` commands.
 *
 * Why this is mandatory on BMW `HU_ENTRYNAV2` (`AirTunes/280.33.8`):
 * - Immediately after RTSP `RECORD 200 OK`, BMW initiates a virtual iAP2 link over the AirPlay
 *   Event channel by sending `iAPSendMessage (FF 55 02 00 EE 10)` once per second for 10 seconds.
 * - If unanswered, BMW declares an iAP2 handoff timeout at exactly `T + 10.0s`, drops video TCP
 *   `dataPort`, drops BT RFCOMM, and rolls back first-time CarPlay device registration!
 *
 * Transport auto-detection:
 * - Starts by sending outbound `iAPSendMessage` on the canonical RTSP Control channel (port 7000).
 * - If Control returns non-200 OR BMW repeats `FF 55 02 00 EE 10` (~1s later) without progressing
 *   to `SYN`, automatically fails over to the Event channel (`eventPort`) and replies `DETECT` once.
 * - Once BMW sends `SYN`, locks the winning TX channel and NEVER re-emits `DETECT` (re-emitting
 *   `DETECT` would reset BMW back to Detecting!).
 *
 * Runs all processing and outbound RTSP calls on its own single-threaded FIFO worker so the Event
 * channel reader loop NEVER blocks or deadlocks while replying `RTSP/1.0 200 OK`.
 */
class VirtualIap2Tunnel(
    private val log: (tag: String, message: String) -> Unit,
    private val sendViaControl: (data: ByteArray, label: String) -> Boolean,
    private val sendViaEvent: (data: ByteArray, label: String) -> Boolean,
    private val onStateChanged: (statusHr: String, ready: Boolean) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val spawnWorkerThread: Boolean = true,
) {
    enum class TxChannel(val labelHr: String) {
        CONTROL("Control :7000"),
        EVENT("Event kanal"),
    }

    companion object {
        private const val AUTH_CERT_FALLBACK_DELAY_MS = 2_500L
        private const val POLL_INTERVAL_MS = 250L
    }

    private val engine = Iap2LinkEngine()
    private val csmReassembler = Iap2CsmCodec.CsmReassembler()
    private val random = SecureRandom()
    private val inboundQueue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    var activeTxChannel: TxChannel = TxChannel.CONTROL
        private set

    @Volatile
    var isHandoffCompleted: Boolean = false
        private set

    private var detectCountFromCar = 0
    private var firstDetectSentAtMs = 0L
    private var fallbackEventDetectSent = false
    private var channelLocked = false

    private var normalEnteredAtMs: Long = 0L
    private var authCertRequested = false
    private var authCertReceived = false
    private var authChallengeSent = false
    private var authChallengeLenUsed = 20
    private var authSucceededSent = false
    private var identRequested = false
    private var identAcceptedSent = false
    private var greetingSent = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (spawnWorkerThread) {
            workerThread = Thread({ workerLoop() }, "carplay-viap2-tunnel").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        running.set(false)
        workerThread?.interrupt()
        workerThread = null
        inboundQueue.clear()
    }

    /**
     * Non-blocking enqueue called directly from `CarPlayRtspSession.eventReaderLoop`.
     */
    fun onIncomingCarBytes(data: ByteArray) {
        if (data.isEmpty()) return
        if (!spawnWorkerThread) {
            processIncomingNow(data, clockMs())
            return
        }
        if (!running.get()) {
            start()
        }
        inboundQueue.offer(data.copyOf())
    }

    /**
     * Synchronous processing entry point (also used directly by deterministic JVM unit tests).
     */
    internal fun processIncomingNow(data: ByteArray, nowMs: Long) {
        val isDetectMarker = data.contentEquals(Iap2LinkPacket.DETECT_MARKER)
        log(
            "CARPLAY-VIAP2",
            "RX iAPSendMessage (${data.size} B, stanje=${engine.state}): ${HexDump.toCompactHex(data, 40)}",
        )

        if (isDetectMarker) {
            detectCountFromCar++
            if (detectCountFromCar == 1 && engine.state == Iap2LinkEngine.State.IDLE) {
                firstDetectSentAtMs = nowMs
                // First DETECT from BMW -> feed into Iap2LinkEngine (emits ONE DETECT reply via activeTxChannel)
                engine.onBytesReceived(data, nowMs)
                flushEngine(nowMs)
                return
            }

            if (engine.state == Iap2LinkEngine.State.DETECTING &&
                !channelLocked &&
                activeTxChannel == TxChannel.CONTROL &&
                !fallbackEventDetectSent &&
                nowMs - firstDetectSentAtMs >= 500L
            ) {
                // BMW repeated DETECT ~1s later -> Control channel DETECT did not reach BMW's iAP2 handler.
                // Switch automatically to Event channel and send DETECT ONCE!
                fallbackEventDetectSent = true
                activeTxChannel = TxChannel.EVENT
                log(
                    "CARPLAY-VIAP2",
                    "BMW ponovio DETECT (#$detectCountFromCar) → automatski prebacujem virtualni iAP2 TX na Event kanal i šaljem 1× DETECT!",
                )
                dispatchRawFrame("TX DETECT_MARKER (Event fallback)", Iap2LinkPacket.DETECT_MARKER)
                onStateChanged("Wi-Fi iAP2: DETECT (Event)", false)
                return
            }

            // Never re-emit DETECT once already sent on fallback or once NEGOTIATING/NORMAL!
            engine.onBytesReceived(data, nowMs)
            flushEngine(nowMs)
            return
        }

        // Any non-DETECT frame (e.g., FF 5A SYN / ACK / Data) proves the current TX channel worked!
        if (!channelLocked && data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x5A.toByte()) {
            channelLocked = true
            log(
                "CARPLAY-VIAP2",
                "✓ BMW odgovorio FF 5A okvirom → virtualni iAP2 TX kanal potvrđen: ${activeTxChannel.labelHr}",
            )
        }

        engine.onBytesReceived(data, nowMs)
        flushEngine(nowMs)
    }

    /**
     * Periodic tick (called every 250 ms by worker thread or manually in tests).
     * Intentionally skips `engine.tick()` in `DETECTING` so BMW (client) drives `SYN` cleanly.
     */
    internal fun tickNow(nowMs: Long) {
        if (engine.state == Iap2LinkEngine.State.NEGOTIATING || engine.state == Iap2LinkEngine.State.NORMAL) {
            engine.tick(nowMs)
            // If car entered NORMAL and never replied to 0xAA00 within 2.5s, proactively request 0x1D00
            if (engine.state == Iap2LinkEngine.State.NORMAL &&
                authCertRequested &&
                !authCertReceived &&
                !identRequested &&
                normalEnteredAtMs > 0L &&
                nowMs - normalEnteredAtMs >= AUTH_CERT_FALLBACK_DELAY_MS
            ) {
                log(
                    "CARPLAY-VIAP2",
                    "Nije stigao 0xAA01 u virtualnom tunelu nakon ${AUTH_CERT_FALLBACK_DELAY_MS}ms → šaljem 0x1D00 (StartIdentification)",
                )
                identRequested = true
                sendCsm(Iap2MessageIds.START_IDENTIFICATION, nowMs = nowMs)
            }
            flushEngine(nowMs)
        }
    }

    private fun workerLoop() {
        while (running.get()) {
            try {
                val packet = inboundQueue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                val now = clockMs()
                if (packet != null) {
                    processIncomingNow(packet, now)
                } else {
                    tickNow(now)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    log("CARPLAY-VIAP2", "Greška u virtualnom iAP2 tunelu: ${e.message}")
                }
            }
        }
    }

    private fun flushEngine(nowMs: Long) {
        // First emit all queued link frames (FIFO)
        val frames = engine.drainOutgoingFrameLogs()
        engine.drainOutgoingBytes() // Clear combined byte buffer in lockstep
        for ((label, frameBytes) in frames) {
            dispatchRawFrame(label, frameBytes)
        }

        // Then process all state-machine events
        val events = engine.drainEvents()
        for (ev in events) {
            handleLinkEvent(ev, nowMs)
        }

        // Flush any follow-up frames queued while handling link/CSM events (e.g. 0xAA00 / 0x1D02!)
        val followUps = engine.drainOutgoingFrameLogs()
        engine.drainOutgoingBytes()
        for ((label, frameBytes) in followUps) {
            dispatchRawFrame(label, frameBytes)
        }
    }

    private fun handleLinkEvent(ev: Iap2LinkEngine.Event, nowMs: Long) {
        when (ev) {
            is Iap2LinkEngine.Event.StateChanged -> {
                log("CARPLAY-VIAP2", "Stanje [${ev.state}]: ${ev.detailHr}")
                when (ev.state) {
                    Iap2LinkEngine.State.DETECTING -> onStateChanged("Wi-Fi iAP2: DETECT", false)
                    Iap2LinkEngine.State.NEGOTIATING -> onStateChanged("Wi-Fi iAP2: SYN|ACK...", false)
                    Iap2LinkEngine.State.NORMAL -> {
                        normalEnteredAtMs = nowMs
                        onStateChanged("Wi-Fi iAP2: NORMAL (MFi/Ident...)", false)
                        if (!authCertRequested) {
                            authCertRequested = true
                            log(
                                "CARPLAY-VIAP2",
                                "✓ Virtualni iAP2 link NORMAL (Control SID=${engine.controlSessionId} v${engine.controlSessionVersion()}) → šaljem 0xAA00",
                            )
                            sendCsm(Iap2MessageIds.REQUEST_AUTH_CERTIFICATE, nowMs = nowMs)
                        }
                    }

                    Iap2LinkEngine.State.CLOSED -> onStateChanged("Wi-Fi iAP2: ZATVOREN", false)
                    Iap2LinkEngine.State.IDLE -> Unit
                }
            }

            is Iap2LinkEngine.Event.SyncNegotiated -> {
                log(
                    "CARPLAY-VIAP2",
                    "Dogovoren virtualni LSP: maxOut=${ev.peerLsp.maxOutgoingPackets}, maxLen=${ev.peerLsp.maxPacketLength}, " +
                        "Control SID=${ev.controlSessionId}, EA SID=${ev.eaSessionIds}",
                )
            }

            is Iap2LinkEngine.Event.ControlDataReceived -> {
                val csmFrames = csmReassembler.feed(ev.payload)
                for (csm in csmFrames) {
                    handleCsmFrame(csm, nowMs)
                }
            }

            is Iap2LinkEngine.Event.EaDataReceived -> {
                log(
                    "CARPLAY-VIAP2",
                    "RX Virtual EA (sid=${ev.sessionId}, ${ev.payload.size} B): ${HexDump.toCompactHex(ev.payload, 32)}",
                )
            }

            is Iap2LinkEngine.Event.UnknownSessionData -> {
                log(
                    "CARPLAY-VIAP2",
                    "RX Virtual nepoznata sesija sid=${ev.sessionId} (${ev.payload.size} B)",
                )
            }

            is Iap2LinkEngine.Event.DiagnosticNote -> {
                log("CARPLAY-VIAP2", ev.messageHr)
            }

            is Iap2LinkEngine.Event.LinkClosed -> {
                log("CARPLAY-VIAP2", "Virtualni iAP2 zatvoren: ${ev.reasonHr}")
            }
        }
    }

    private fun handleCsmFrame(frame: Iap2CsmCodec.CsmFrame, nowMs: Long) {
        log(
            "CARPLAY-VIAP2",
            "RX Virtual CSM ${Iap2MessageIds.formatId(frame.messageId)} (${frame.body.size} B)",
        )
        when (frame.messageId) {
            Iap2MessageIds.AUTH_CERTIFICATE -> {
                authCertReceived = true
                val certInfo = Iap2Parsers.parseAuthenticationCertificate(frame.body)
                log("CARPLAY-VIAP2", "Virtual MFi certifikat: ${certInfo.formatReportHr()}")
                if (!authChallengeSent) {
                    authChallengeSent = true
                    authChallengeLenUsed = if (certInfo.isMfi20Sha1Rsa) 20 else 32
                    val challenge = ByteArray(authChallengeLenUsed).also(random::nextBytes)
                    log("CARPLAY-VIAP2", "Šaljem virtualni 0xAA02 MFi izazov (${authChallengeLenUsed} B)...")
                    sendCsm(
                        Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE,
                        Iap2CsmCodec.paramRaw(0, challenge),
                        nowMs = nowMs,
                    )
                }
            }

            Iap2MessageIds.AUTH_SERIAL_NUMBER -> {
                val serial = Iap2Parsers.parseAuthSerialNumber(frame.body)
                log("CARPLAY-VIAP2", "Virtual 0xAA06 serijski broj=${serial ?: "?"} — ponovno šaljem 0xAA00")
                sendCsm(Iap2MessageIds.REQUEST_AUTH_CERTIFICATE, nowMs = nowMs)
            }

            Iap2MessageIds.AUTH_RESPONSE -> {
                log("CARPLAY-VIAP2", "✓ Primljen virtualni 0xAA03 (MFi potpis) → šaljem 0xAA05 + 0x1D00!")
                if (!authSucceededSent) {
                    authSucceededSent = true
                    sendCsm(Iap2MessageIds.AUTH_SUCCEEDED, nowMs = nowMs)
                }
                if (!identRequested) {
                    identRequested = true
                    sendCsm(Iap2MessageIds.START_IDENTIFICATION, nowMs = nowMs)
                }
            }

            Iap2MessageIds.AUTH_FAILED -> {
                log("CARPLAY-VIAP2", "Virtual 0xAA04 na ${authChallengeLenUsed}B — šaljem pričuvni izazov ili 0xAA05")
                if (authChallengeLenUsed != 32) {
                    authChallengeLenUsed = 32
                    val challenge = ByteArray(32).also(random::nextBytes)
                    sendCsm(
                        Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE,
                        Iap2CsmCodec.paramRaw(0, challenge),
                        nowMs = nowMs,
                    )
                } else if (!authSucceededSent) {
                    authSucceededSent = true
                    sendCsm(Iap2MessageIds.AUTH_SUCCEEDED, nowMs = nowMs)
                    if (!identRequested) {
                        identRequested = true
                        sendCsm(Iap2MessageIds.START_IDENTIFICATION, nowMs = nowMs)
                    }
                }
            }

            Iap2MessageIds.IDENTIFICATION_INFORMATION -> {
                val info = Iap2Parsers.parseIdentificationInfo(frame.body)
                log("CARPLAY-VIAP2", "Virtual 0x1D01 identifikacija:\n${info.formatReportHr()}")
                if (!identAcceptedSent) {
                    identAcceptedSent = true
                    sendCsm(Iap2MessageIds.IDENTIFICATION_ACCEPTED, nowMs = nowMs)
                    sendVirtualGreeting(nowMs)
                    isHandoffCompleted = true
                    log(
                        "CARPLAY-VIAP2",
                        "✓✓ VIRTUALNI iAP2 HANDOFF DOVRŠEN (${activeTxChannel.labelHr})! BMW sada drži trajnu CarPlay sesiju!",
                    )
                    onStateChanged("Wi-Fi iAP2: AKTIVAN ✓ (${activeTxChannel.labelHr})", true)
                }
            }

            Iap2MessageIds.START_IDENTIFICATION -> {
                // If accessory ever asks phone to identify, accept cleanly
                log("CARPLAY-VIAP2", "Auto poslao 0x1D00 u virtualnom tunelu — šaljem 0x1D02")
                sendCsm(Iap2MessageIds.IDENTIFICATION_ACCEPTED, nowMs = nowMs)
            }

            else -> {
                val readable = Iap2CsmCodec.dumpParamsReadable(frame.body)
                log(
                    "CARPLAY-VIAP2",
                    "Virtual CSM ${Iap2MessageIds.formatId(frame.messageId)} parametri:\n$readable",
                )
            }
        }
    }

    private fun sendVirtualGreeting(nowMs: Long) {
        if (greetingSent) return
        greetingSent = true
        val uuid = UUID.randomUUID().toString().uppercase()
        sendCsm(Iap2MessageIds.DEVICE_UUID_UPDATE, Iap2CsmCodec.paramString(0, uuid), awaitAck = false, nowMs = nowMs)
        sendCsm(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2CsmCodec.paramString(0, "en"), awaitAck = false, nowMs = nowMs)
        sendCsm(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2CsmCodec.paramString(0, "iPhone"), awaitAck = false, nowMs = nowMs)

        val nowSec = nowMs / 1000L
        val tzMin = TimeZone.getDefault().getOffset(nowMs) / 60_000
        val dstFlag = if (TimeZone.getDefault().inDaylightTime(java.util.Date(nowMs))) 1 else 0
        sendCsm(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2CsmCodec.paramU64(0, nowSec),
            Iap2CsmCodec.paramU16(1, tzMin and 0xFFFF),
            Iap2CsmCodec.paramU8(2, dstFlag),
            awaitAck = false,
            nowMs = nowMs,
        )
    }

    private fun sendCsm(
        messageId: Int,
        vararg params: ByteArray,
        awaitAck: Boolean = true,
        nowMs: Long,
    ) {
        val raw = Iap2CsmCodec.encodeFrame(messageId, *params)
        log(
            "CARPLAY-VIAP2",
            "TX Virtual CSM ${Iap2MessageIds.formatId(messageId)} (${raw.size} B, awaitAck=$awaitAck)",
        )
        engine.sendControlPayload(raw, nowMs, awaitAck = awaitAck)
    }

    private fun dispatchRawFrame(label: String, bytes: ByteArray) {
        val ok = when (activeTxChannel) {
            TxChannel.CONTROL -> sendViaControl(bytes, label)
            TxChannel.EVENT -> sendViaEvent(bytes, label)
        }
        if (!ok && activeTxChannel == TxChannel.CONTROL && !channelLocked) {
            // Immediate failover if Control channel rejected POST /command iAPSendMessage (e.g. 400/404/455)
            log(
                "CARPLAY-VIAP2",
                "Control :7000 odbio $label → odmah prebacujem virtualni iAP2 TX na Event kanal!",
            )
            activeTxChannel = TxChannel.EVENT
            if (bytes.contentEquals(Iap2LinkPacket.DETECT_MARKER)) {
                fallbackEventDetectSent = true
            }
            sendViaEvent(bytes, "$label (Event failover)")
        }
    }
}
