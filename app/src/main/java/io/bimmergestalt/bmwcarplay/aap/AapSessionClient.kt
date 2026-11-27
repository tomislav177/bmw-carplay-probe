package io.bimmergestalt.bmwcarplay.aap

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Pure-Kotlin Android Auto Protocol (AAP) Head Unit TCP + mTLS client targeting Google's local
 * Head Unit Server (`127.0.0.1:5277`) or a mock server in JVM unit tests.
 *
 * Strictly opens a plain loopback [Socket] (never bound to a car Wi-Fi network), never probes
 * ports blindly, and always performs a graceful encrypted `SHUTDOWN_REQUEST` (ByeBye) before
 * closing so Google's Head Unit Server cleanly returns to its accept loop.
 */
class AapSessionClient(
    private val certStore: AapCertStore,
    private val log: (tag: String, msg: String) -> Unit,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val monotonicTimeNs: () -> Long = System::nanoTime,
) {
    companion object {
        const val TAG = "AAP"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5277

        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val VERSION_READ_TIMEOUT_MS = 2_000
        private const val VERSION_MAX_ATTEMPTS = 3 // initial + up to 2 retries
        private const val TLS_HANDSHAKE_TIMEOUT_MS = 8_000
        private const val LOOP_READ_TIMEOUT_MS = 2_000
        private const val SHUTDOWN_GRACE_MS = 350L
        private const val JOIN_TIMEOUT_MS = 500L
        private const val DIAGNOSTIC_TARGET_VIDEO_FRAMES = 5
    }

    interface Listener {
        fun onStateChanged(titleHr: String, detailHr: String, connected: Boolean)
        fun onVideoConfig(spsPpsAnnexB: ByteArray)
        fun onVideoFrame(timestampUs: Long, annexB: ByteArray)
        fun onVideoStopped()
        fun onSessionEnded(reasonHr: String, certRejectedLikely: Boolean)
    }

    data class LoopbackDiagnosticResult(
        val success: Boolean,
        val summaryHr: String,
        val versionText: String?,
        val tlsOk: Boolean,
        val serviceDiscoveryOk: Boolean,
        val videoFramesReceived: Int,
        val certRejectedLikely: Boolean,
    )

    private val lifecycleLock = Any()
    private val writeLock = Any()

    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeOutput: OutputStream? = null

    @Volatile
    private var activeTls: AapTlsChannel? = null

    @Volatile
    private var shutdownAckLatch: CountDownLatch = CountDownLatch(1)

    @Volatile
    private var currentNightMode: Boolean = false

    @Volatile
    private var sensorChannelOpen: Boolean = false

    @Volatile
    private var videoChannelOpen: Boolean = false

    @Volatile
    private var inputReady: Boolean = false

    @Volatile
    private var videoSessionId: Int = 0

    @Volatile
    private var audioSystemSessionId: Int = 0

    // Telemetry inspected by runLoopbackDiagnosticTest
    @Volatile
    private var lastVersionText: String? = null

    @Volatile
    private var lastTlsOk: Boolean = false

    @Volatile
    private var lastServiceDiscoveryOk: Boolean = false

    /**
     * Starts an asynchronous AAP Head Unit session on a daemon worker thread.
     */
    fun start(
        profile: AapProto.AapDisplayProfile,
        carSerial: String,
        initialNightMode: Boolean,
        listener: Listener,
    ) {
        synchronized(lifecycleLock) {
            if (running.get()) {
                stop()
            }
            stopping.set(false)
            running.set(true)
            shutdownAckLatch = CountDownLatch(1)
            currentNightMode = initialNightMode
            sensorChannelOpen = false
            videoChannelOpen = false
            inputReady = false
            videoSessionId = 0
            audioSystemSessionId = 0
            lastVersionText = null
            lastTlsOk = false
            lastServiceDiscoveryOk = false

            val thread = Thread(
                { runSession(profile, carSerial, listener) },
                "AapSessionClient",
            ).apply {
                isDaemon = true
            }
            workerThread = thread
            thread.start()
        }
    }

    /**
     * Thread-safely sends a mapped BMW input action over `CH_INPUT` once input binding completes.
     */
    fun sendInputAction(action: BmwToAapInputMapper.AapInputAction) {
        if (!inputReady || stopping.get()) return
        val tsNs = monotonicTimeNs()
        val body = when (action) {
            is BmwToAapInputMapper.AapInputAction.Touch -> AapProto.encodeTouchInputEvent(
                timestampNs = tsNs,
                x = action.x,
                y = action.y,
                action = action.action,
                pointerId = action.pointerId,
            )

            is BmwToAapInputMapper.AapInputAction.Key -> AapProto.encodeButtonInputEvent(
                timestampNs = tsNs,
                keycode = action.keycode,
                isPressed = action.isPressed,
                longPress = action.longPress,
            )

            is BmwToAapInputMapper.AapInputAction.Relative -> AapProto.encodeRelativeInputEvent(
                timestampNs = tsNs,
                scanCode = action.scanCode,
                delta = action.delta,
            )
        }
        try {
            sendEncrypted(
                channelId = AapProto.CH_INPUT,
                flags = AapFramer.FLAG_ENC_BULK,
                messageId = AapProto.INPUT_EVENT_INDICATION,
                body = body,
            )
        } catch (e: IOException) {
            if (!stopping.get()) {
                log(TAG, "Greška slanja AAP ulaza: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Updates day/night mode and immediately notifies Android Auto on `CH_SENSOR` if open.
     */
    fun updateNightMode(isNight: Boolean) {
        currentNightMode = isNight
        if (!sensorChannelOpen || stopping.get()) return
        try {
            sendEncrypted(
                channelId = AapProto.CH_SENSOR,
                flags = AapFramer.FLAG_ENC_BULK,
                messageId = AapProto.SENSOR_EVENT_INDICATION,
                body = AapProto.encodeSensorNightMode(isNight),
            )
            log(TAG, "Poslan AAP noćni način rada: ${if (isNight) "NOĆ" else "DAN"}")
        } catch (e: IOException) {
            if (!stopping.get()) {
                log(TAG, "Greška slanja AAP noćnog načina: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Switches Android Auto video projection focus (e.g. when leaving/returning from BMW `MENU`).
     */
    fun setVideoFocus(projected: Boolean) {
        if (!videoChannelOpen || stopping.get()) return
        val mode = if (projected) AapProto.VIDEO_FOCUS_PROJECTED else AapProto.VIDEO_FOCUS_NATIVE
        try {
            sendEncrypted(
                channelId = AapProto.CH_VIDEO,
                flags = AapFramer.FLAG_ENC_BULK,
                messageId = AapProto.VIDEO_FOCUS_INDICATION,
                body = AapProto.encodeVideoFocusIndication(focusMode = mode, unrequested = true),
            )
            log(TAG, "Poslan AAP video fokus: ${if (projected) "PROJECTED" else "NATIVE"}")
        } catch (e: IOException) {
            if (!stopping.get()) {
                log(TAG, "Greška slanja AAP video fokusa: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Gracefully terminates the active session by sending an encrypted `SHUTDOWN_REQUEST`
     * (`USER_SELECTION = 1`) and waiting briefly for `SHUTDOWN_RESPONSE` before closing the socket.
     */
    fun stop() {
        val alreadyStopping = stopping.getAndSet(true)
        if (!alreadyStopping && activeTls != null && activeOutput != null) {
            try {
                log(TAG, "Šaljem AAP SHUTDOWN_REQUEST (ByeBye) za čisti prekid sesije…")
                sendEncrypted(
                    channelId = AapProto.CH_CONTROL,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.SHUTDOWN_REQUEST,
                    body = AapProto.encodeShutdownRequest(AapProto.SHUTDOWN_REASON_USER_SELECTION),
                )
                shutdownAckLatch.await(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // Best-effort graceful ByeBye
            }
        }

        closeTransportQuietly()

        val workerToJoin: Thread? = synchronized(lifecycleLock) {
            val w = workerThread
            if (w != null && w !== Thread.currentThread()) {
                w
            } else {
                workerThread = null
                running.set(false)
                null
            }
        }
        if (workerToJoin != null) {
            try {
                workerToJoin.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            synchronized(lifecycleLock) {
                if (workerThread === workerToJoin) {
                    workerThread = null
                    running.set(false)
                }
            }
        }
    }

    /**
     * Synchronous loopback diagnostic helper ("Testiraj AA doma"): connects to the configured
     * `host:port`, runs Version + TLS + ServiceDiscovery + Video streaming until 5 frames arrive
     * or [timeoutMs] elapses, then cleanly sends ByeBye via [stop].
     */
    fun runLoopbackDiagnosticTest(
        profile: AapProto.AapDisplayProfile,
        timeoutMs: Long = 6_000L,
    ): LoopbackDiagnosticResult {
        val doneLatch = CountDownLatch(1)
        val frameCounter = AtomicInteger(0)
        val endReasonRef = AtomicReference<String?>(null)
        val certRejectedRef = AtomicBoolean(false)

        val diagListener = object : Listener {
            override fun onStateChanged(titleHr: String, detailHr: String, connected: Boolean) {
                // Telemetry tracked directly on client fields
            }

            override fun onVideoConfig(spsPpsAnnexB: ByteArray) {
                log(TAG, "Dijagnostika primila H.264 SPS/PPS konfiguraciju (${spsPpsAnnexB.size} B)")
            }

            override fun onVideoFrame(timestampUs: Long, annexB: ByteArray) {
                val count = frameCounter.incrementAndGet()
                if (count >= DIAGNOSTIC_TARGET_VIDEO_FRAMES) {
                    doneLatch.countDown()
                }
            }

            override fun onVideoStopped() {
                // Ignore during diagnostic teardown
            }

            override fun onSessionEnded(reasonHr: String, certRejectedLikely: Boolean) {
                endReasonRef.compareAndSet(null, reasonHr)
                if (certRejectedLikely) {
                    certRejectedRef.set(true)
                }
                doneLatch.countDown()
            }
        }

        start(
            profile = profile,
            carSerial = "BMW-F48-DIAG",
            initialNightMode = false,
            listener = diagListener,
        )

        try {
            doneLatch.await(timeoutMs.coerceAtLeast(250L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            stop()
        }

        val versionText = lastVersionText
        val tlsOk = lastTlsOk
        val sdpOk = lastServiceDiscoveryOk
        val frames = frameCounter.get()
        val certRejected = certRejectedRef.get()
        val endReason = endReasonRef.get()

        val success = tlsOk && sdpOk && frames > 0
        val summaryHr = when {
            success ->
                "Uspjeh! Android Auto radi (verzija ${versionText ?: "?"}, TLS OK, servisi OK, primljeno $frames H.264 okvira)."

            certRejected ->
                endReason ?: "TLS rukovanje odbijeno — potreban je uvezeni HU certifikat potpisan Google Automotive Link CA lancem."

            sdpOk ->
                "Djelomičan uspjeh: verzija ${versionText ?: "?"}, TLS OK, servisi OK, ali nije stigao nijedan video okvir unutar ${timeoutMs} ms."

            tlsOk ->
                "TLS rukovanje uspjelo (verzija ${versionText ?: "?"}), ali otkrivanje servisa nije dovršeno."

            !endReason.isNullOrBlank() ->
                endReason

            else ->
                "Dijagnostika istekla nakon ${timeoutMs} ms bez potpunog odgovora na $host:$port."
        }

        return LoopbackDiagnosticResult(
            success = success,
            summaryHr = summaryHr,
            versionText = versionText,
            tlsOk = tlsOk,
            serviceDiscoveryOk = sdpOk,
            videoFramesReceived = frames,
            certRejectedLikely = certRejected,
        )
    }

    private fun runSession(
        profile: AapProto.AapDisplayProfile,
        carSerial: String,
        listener: Listener,
    ) {
        val endedNotified = AtomicBoolean(false)
        fun notifyEnded(reasonHr: String, certRejectedLikely: Boolean = false) {
            if (endedNotified.compareAndSet(false, true)) {
                listener.onSessionEnded(reasonHr, certRejectedLikely)
            }
        }

        val assembler = AapFrameAssembler()
        try {
            // Step 1: Plain loopback TCP connect
            listener.onStateChanged(
                titleHr = "Spajanje na Android Auto…",
                detailHr = "TCP $host:$port",
                connected = false,
            )
            log(TAG, "Spajanje na AAP Head Unit Server ($host:$port)…")

            val socket = Socket()
            activeSocket = socket
            socket.tcpNoDelay = true
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: ConnectException) {
                val msg = "Android Auto Head Unit Server ne sluša na $host:$port. " +
                    "Pokrenite ga u Android Auto → Postavke razvojnog programa → ⋮ → Pokreni poslužitelj glavne jedinice."
                log(TAG, msg)
                notifyEnded(msg, certRejectedLikely = false)
                return
            } catch (e: SocketTimeoutException) {
                val msg = "Isteklo vrijeme TCP spajanja na $host:$port (${CONNECT_TIMEOUT_MS} ms)."
                log(TAG, msg)
                notifyEnded(msg, certRejectedLikely = false)
                return
            }

            val input = BufferedInputStream(socket.getInputStream(), 65_536)
            val output = socket.getOutputStream()
            activeOutput = output
            log(TAG, "TCP veza s $host:$port uspostavljena.")

            // Step 2: Version handshake (1.6) with up to 2 retries
            val versionResp = performVersionHandshake(socket, input)
            if (versionResp == null) {
                val msg = if (stopping.get()) {
                    "AAP sesija zaustavljena prije razmjene verzije."
                } else {
                    "Android Auto Head Unit Server prihvatio je TCP vezu na $host:$port, ali nije odgovorio na zahtjev verzije " +
                        "(poslužitelj je vjerojatno zastao — zaustavite ga i ponovno pokrenite)."
                }
                log(TAG, msg)
                notifyEnded(msg, certRejectedLikely = false)
                return
            }
            lastVersionText = "${versionResp.major}.${versionResp.minor}"
            log(
                TAG,
                "AAP verzija poslužitelja: ${versionResp.major}.${versionResp.minor} (status=${versionResp.status})",
            )
            if (!versionResp.isMatch) {
                val msg = "Nepodržana AAP verzija poslužitelja: ${versionResp.major}.${versionResp.minor} (status=${versionResp.status})."
                log(TAG, msg)
                notifyEnded(msg, certRejectedLikely = false)
                return
            }

            // Step 3: Mutual TLS 1.2 handshake over SSL_HANDSHAKE (0x0003)
            val creds = certStore.loadCredentials()
            log(TAG, "Pokrećem TLS 1.2 rukovanje (${creds.info.summaryHr})…")
            listener.onStateChanged(
                titleHr = "Android Auto TLS rukovanje…",
                detailHr = "Verzija ${versionResp.major}.${versionResp.minor}",
                connected = false,
            )

            socket.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
            val tls = AapTlsChannel(creds, log)
            val handshakeAssembler = AapFrameAssembler()
            try {
                tls.performHandshake(
                    sendSslBytes = { sslBytes ->
                        sendPlaintext(
                            channelId = AapProto.CH_CONTROL,
                            flags = AapFramer.FLAG_PLAIN_BULK,
                            messageId = AapProto.SSL_HANDSHAKE,
                            body = sslBytes,
                        )
                    },
                    recvSslBytes = {
                        readNextHandshakePayload(input, handshakeAssembler)
                    },
                )
            } catch (e: Exception) {
                tls.close()
                if (stopping.get()) {
                    notifyEnded("AAP sesija prekinuta na zahtjev korisnika.", certRejectedLikely = false)
                    return
                }
                val isHandshakeFailure = e is SSLHandshakeException || e is SSLException || e is EOFException
                val explicitCertAlert = e.message?.let { m ->
                    m.contains("certificate", ignoreCase = true) ||
                        m.contains("bad_cert", ignoreCase = true) ||
                        m.contains("handshake_failure", ignoreCase = true)
                } == true
                val certRejectedLikely = isHandshakeFailure &&
                    (!creds.info.isImported || creds.info.isExpired || explicitCertAlert)

                val reasonHr = when {
                    isHandshakeFailure && !creds.info.isImported ->
                        "TLS rukovanje odbijeno (${e.message ?: e.javaClass.simpleName}). " +
                            "Google Android Auto zahtijeva HU certifikat potpisan Google Automotive Link CA lancem " +
                            "(lokalni samopotpisani certifikat je odbijen). Uvezite valjani HU certifikat (.p12/.pfx ili .pem+.key) u postavkama aplikacije."

                    isHandshakeFailure && creds.info.isExpired ->
                        "TLS rukovanje odbijeno — uvezeni certifikat je istekao (${creds.info.validUntilIso}). Uvezite važeći HU certifikat."

                    else ->
                        "TLS rukovanje neuspješno: ${e.message ?: e.javaClass.simpleName}"
                }
                log(TAG, reasonHr)
                notifyEnded(reasonHr, certRejectedLikely = certRejectedLikely)
                return
            }

            lastTlsOk = true
            activeTls = tls

            // Step 4: Plaintext AUTH_COMPLETE (status = 0)
            sendPlaintext(
                channelId = AapProto.CH_CONTROL,
                flags = AapFramer.FLAG_PLAIN_BULK,
                messageId = AapProto.AUTH_COMPLETE,
                body = AapProto.encodeAuthComplete(AapProto.STATUS_SUCCESS),
            )
            log(TAG, "Poslan AUTH_COMPLETE (status=0); prijelaz na kriptirane AAP okvire.")
            listener.onStateChanged(
                titleHr = "Android Auto TLS povezan",
                detailHr = "Čekanje otkrivanja servisa…",
                connected = false,
            )

            // Step 5: Encrypted receive & dispatch loop
            socket.soTimeout = LOOP_READ_TIMEOUT_MS
            var cleanEndReason: String? = null

            while (!stopping.get() || (activeSocket != null && shutdownAckLatch.count > 0)) {
                val raw = try {
                    AapFramer.readRawFrame(input)
                } catch (_: SocketTimeoutException) {
                    if (stopping.get() && activeSocket == null) break
                    continue
                } catch (e: EOFException) {
                    if (!stopping.get()) {
                        cleanEndReason = "Android Auto je zatvorio vezu (EOF)."
                        log(TAG, cleanEndReason)
                    }
                    break
                } catch (e: SocketException) {
                    if (!stopping.get()) {
                        cleanEndReason = "Prekinuta AAP TCP veza: ${e.message ?: e.javaClass.simpleName}"
                        log(TAG, cleanEndReason)
                    }
                    break
                }

                // In AAP, each fragmented wire frame carries its OWN TLS record: decrypt FIRST, then reassemble
                val plaintextChunk = if (raw.isEncrypted) {
                    tls.decrypt(raw.payload)
                } else {
                    raw.payload
                }

                val assembled = assembler.feed(
                    channelId = raw.channelId,
                    flags = raw.flags,
                    plaintextChunk = plaintextChunk,
                    totalSizeHint = raw.totalSizeHint,
                ) ?: continue

                if (assembled.size < 2) {
                    log(TAG, "Zanemarena prekratka AAP poruka (${assembled.size} B) na kanalu ${raw.channelId}.")
                    continue
                }

                val msgId = AapFramer.readMessageId(assembled)
                val body = if (assembled.size > 2) {
                    assembled.copyOfRange(2, assembled.size)
                } else {
                    ByteArray(0)
                }

                val keepRunning = dispatchMessage(
                    rawChannelId = raw.channelId,
                    isControlFlag = raw.isControl,
                    msgId = msgId,
                    fullMessage = assembled,
                    body = body,
                    profile = profile,
                    carSerial = carSerial,
                    listener = listener,
                )
                if (!keepRunning) {
                    cleanEndReason = if (stopping.get()) {
                        "AAP sesija uredno zaustavljena."
                    } else {
                        "Android Auto je zatražio uredan prekid sesije (SHUTDOWN)."
                    }
                    break
                }
            }

            notifyEnded(
                reasonHr = cleanEndReason ?: "AAP sesija završena.",
                certRejectedLikely = false,
            )
        } catch (e: Exception) {
            if (stopping.get()) {
                notifyEnded("AAP sesija uredno zaustavljena.", certRejectedLikely = false)
            } else {
                val msg = "Greška u AAP sesiji: ${e.message ?: e.javaClass.simpleName}"
                log(TAG, msg)
                notifyEnded(msg, certRejectedLikely = false)
            }
        } finally {
            assembler.clear()
            inputReady = false
            sensorChannelOpen = false
            videoChannelOpen = false
            closeTransportQuietly()
            running.set(false)
            notifyEnded("AAP sesija završena.", certRejectedLikely = false)
        }
    }

    @Throws(IOException::class)
    private fun performVersionHandshake(
        socket: Socket,
        input: InputStream,
    ): AapProto.VersionResponse? {
        socket.soTimeout = VERSION_READ_TIMEOUT_MS
        val reqBody = AapProto.encodeVersionRequest(AapProto.PROTOCOL_MAJOR, AapProto.PROTOCOL_MINOR)

        for (attempt in 1..VERSION_MAX_ATTEMPTS) {
            if (stopping.get()) return null
            sendPlaintext(
                channelId = AapProto.CH_CONTROL,
                flags = AapFramer.FLAG_PLAIN_BULK,
                messageId = AapProto.VERSION_REQUEST,
                body = reqBody,
            )
            try {
                val raw = AapFramer.readRawFrame(input)
                if (raw.payload.size < 2) {
                    throw IOException("Prekratak odgovor na VERSION_REQUEST (${raw.payload.size} B).")
                }
                val msgId = AapFramer.readMessageId(raw.payload)
                if (msgId != AapProto.VERSION_RESPONSE) {
                    throw IOException("Neočekivan AAP msgId 0x%04X umjesto VERSION_RESPONSE.".format(msgId))
                }
                val body = raw.payload.copyOfRange(2, raw.payload.size)
                return AapProto.decodeVersionResponse(body)
            } catch (_: SocketTimeoutException) {
                if (attempt < VERSION_MAX_ATTEMPTS) {
                    log(
                        TAG,
                        "Nema odgovora na VERSION_REQUEST (pokušaj $attempt/$VERSION_MAX_ATTEMPTS), ponavljam…",
                    )
                }
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun readNextHandshakePayload(
        input: InputStream,
        handshakeAssembler: AapFrameAssembler,
    ): ByteArray {
        while (true) {
            val raw = AapFramer.readRawFrame(input)
            val msg = handshakeAssembler.feed(
                channelId = raw.channelId,
                flags = raw.flags,
                plaintextChunk = raw.payload,
                totalSizeHint = raw.totalSizeHint,
            ) ?: continue

            if (msg.size < 2) {
                throw IOException("Prekratak SSL_HANDSHAKE okvir (${msg.size} B).")
            }
            val msgId = AapFramer.readMessageId(msg)
            when (msgId) {
                AapProto.SSL_HANDSHAKE -> return msg.copyOfRange(2, msg.size)
                AapProto.SHUTDOWN_REQUEST ->
                    throw SSLHandshakeException("Poslužitelj je poslao SHUTDOWN tijekom TLS rukovanja.")
                else ->
                    throw IOException("Neočekivana AAP poruka 0x%04X tijekom TLS rukovanja.".format(msgId))
            }
        }
    }

    /**
     * Dispatches a single reassembled plaintext AAP message.
     * Returns `false` when the receive loop should terminate cleanly (shutdown).
     */
    @Throws(IOException::class)
    private fun dispatchMessage(
        rawChannelId: Int,
        isControlFlag: Boolean,
        msgId: Int,
        fullMessage: ByteArray,
        body: ByteArray,
        profile: AapProto.AapDisplayProfile,
        carSerial: String,
        listener: Listener,
    ): Boolean {
        if (rawChannelId == AapProto.CH_CONTROL || isControlFlag) {
            return dispatchControlMessage(
                rawChannelId = rawChannelId,
                msgId = msgId,
                body = body,
                profile = profile,
                carSerial = carSerial,
                listener = listener,
            )
        }

        when (rawChannelId) {
            AapProto.CH_SENSOR -> dispatchSensorMessage(msgId, body)
            AapProto.CH_VIDEO -> dispatchVideoMessage(msgId, fullMessage, body, listener)
            AapProto.CH_INPUT -> dispatchInputMessage(msgId, body)
            AapProto.CH_AUDIO_SYSTEM -> dispatchAudioSystemMessage(msgId, body)
            AapProto.CH_MIC -> dispatchMicMessage(msgId, body)
            else -> log(TAG, "Nepoznat AAP kanal $rawChannelId (msgId=0x%04X, ${body.size} B).".format(msgId))
        }
        return true
    }

    @Throws(IOException::class)
    private fun dispatchControlMessage(
        rawChannelId: Int,
        msgId: Int,
        body: ByteArray,
        profile: AapProto.AapDisplayProfile,
        carSerial: String,
        listener: Listener,
    ): Boolean {
        when (msgId) {
            AapProto.SERVICE_DISCOVERY_REQUEST -> {
                log(TAG, "Primljen SERVICE_DISCOVERY_REQUEST — šaljem opis BMW zaslona (${profile.activeWidth}×${profile.activeHeight} @ ${profile.targetFps} fps)…")
                val sdpResp = AapProto.encodeServiceDiscoveryResponse(
                    profile = profile,
                    carSerial = carSerial,
                )
                sendEncrypted(
                    channelId = AapProto.CH_CONTROL,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.SERVICE_DISCOVERY_RESPONSE,
                    body = sdpResp,
                )
                lastServiceDiscoveryOk = true
                listener.onStateChanged(
                    titleHr = "Android Auto povezan",
                    detailHr = "Servisi otkriveni (${profile.activeWidth}×${profile.activeHeight} @ ${profile.targetFps} fps)",
                    connected = true,
                )
            }

            AapProto.CHANNEL_OPEN_REQUEST -> {
                val req = AapProto.decodeChannelOpenRequest(body)
                val openedCh = if (rawChannelId != AapProto.CH_CONTROL) rawChannelId else req.channelId
                log(TAG, "Otvaranje AAP kanala ch=$openedCh (prioritet=${req.priority})")
                sendEncrypted(
                    channelId = rawChannelId,
                    flags = AapFramer.FLAG_ENC_CONTROL,
                    messageId = AapProto.CHANNEL_OPEN_RESPONSE,
                    body = AapProto.encodeChannelOpenResponse(AapProto.STATUS_SUCCESS),
                )
                when (openedCh) {
                    AapProto.CH_SENSOR -> {
                        sensorChannelOpen = true
                        sendInitialSensorState()
                    }
                    AapProto.CH_VIDEO -> {
                        videoChannelOpen = true
                    }
                }
            }

            AapProto.PING_REQUEST -> {
                val ping = AapProto.decodePingRequest(body)
                sendEncrypted(
                    channelId = AapProto.CH_CONTROL,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.PING_RESPONSE,
                    body = AapProto.encodePingResponse(ping.timestamp, ping.payload),
                )
            }

            AapProto.AUDIO_FOCUS_REQUEST -> {
                val focusType = AapProto.decodeAudioFocusRequest(body)
                val responseState = when (focusType) {
                    AapProto.AUDIO_FOCUS_RELEASE -> AapProto.AUDIO_FOCUS_STATE_LOSS
                    AapProto.AUDIO_FOCUS_GAIN_TRANSIENT,
                    AapProto.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK,
                    -> AapProto.AUDIO_FOCUS_STATE_GAIN_TRANSIENT
                    else -> AapProto.AUDIO_FOCUS_STATE_GAIN
                }
                sendEncrypted(
                    channelId = AapProto.CH_CONTROL,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.AUDIO_FOCUS_RESPONSE,
                    body = AapProto.encodeAudioFocusResponse(focusState = responseState, granted = true),
                )
                log(TAG, "Odobren AAP audio fokus (zahtjev=$focusType → stanje=$responseState)")
            }

            AapProto.NAV_FOCUS_REQUEST -> {
                sendEncrypted(
                    channelId = AapProto.CH_CONTROL,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.NAV_FOCUS_RESPONSE,
                    body = AapProto.encodeNavFocusResponse(AapProto.NAV_FOCUS_PROJECTED),
                )
                log(TAG, "Odobren AAP navigacijski fokus (PROJECTED)")
            }

            AapProto.SHUTDOWN_REQUEST -> {
                val reason = AapProto.decodeShutdownRequest(body)
                log(TAG, "Primljen SHUTDOWN_REQUEST od Android Auta (razlog=$reason) — šaljem SHUTDOWN_RESPONSE.")
                try {
                    sendEncrypted(
                        channelId = AapProto.CH_CONTROL,
                        flags = AapFramer.FLAG_ENC_BULK,
                        messageId = AapProto.SHUTDOWN_RESPONSE,
                        body = AapProto.encodeShutdownResponse(),
                    )
                } catch (_: IOException) {
                    // Peer may close immediately
                }
                return false
            }

            AapProto.SHUTDOWN_RESPONSE -> {
                log(TAG, "Primljen SHUTDOWN_RESPONSE (ByeBye potvrđen).")
                shutdownAckLatch.countDown()
                return false
            }

            else -> {
                log(TAG, "Zanemarena kontrolna AAP poruka 0x%04X na kanalu $rawChannelId (${body.size} B).".format(msgId))
            }
        }
        return true
    }

    @Throws(IOException::class)
    private fun sendInitialSensorState() {
        sendEncrypted(
            channelId = AapProto.CH_SENSOR,
            flags = AapFramer.FLAG_ENC_BULK,
            messageId = AapProto.SENSOR_EVENT_INDICATION,
            body = AapProto.encodeSensorDrivingStatus(AapProto.DRIVING_STATUS_UNRESTRICTED),
        )
        sendEncrypted(
            channelId = AapProto.CH_SENSOR,
            flags = AapFramer.FLAG_ENC_BULK,
            messageId = AapProto.SENSOR_EVENT_INDICATION,
            body = AapProto.encodeSensorNightMode(currentNightMode),
        )
        sendEncrypted(
            channelId = AapProto.CH_SENSOR,
            flags = AapFramer.FLAG_ENC_BULK,
            messageId = AapProto.SENSOR_EVENT_INDICATION,
            body = AapProto.encodeSensorParkingBrake(true),
        )
        log(TAG, "Poslani početni AAP senzori (vožnja=UNRESTRICTED, noć=$currentNightMode, ručna=true).")
    }

    @Throws(IOException::class)
    private fun dispatchSensorMessage(msgId: Int, body: ByteArray) {
        if (msgId != AapProto.SENSOR_REQUEST) return
        val req = AapProto.decodeSensorStartRequest(body)
        sendEncrypted(
            channelId = AapProto.CH_SENSOR,
            flags = AapFramer.FLAG_ENC_BULK,
            messageId = AapProto.SENSOR_START_RESPONSE,
            body = AapProto.encodeSensorStartResponse(AapProto.STATUS_SUCCESS),
        )
        val eventBody = when (req.sensorType) {
            AapProto.SENSOR_DRIVING_STATUS -> AapProto.encodeSensorDrivingStatus(AapProto.DRIVING_STATUS_UNRESTRICTED)
            AapProto.SENSOR_NIGHT_DATA -> AapProto.encodeSensorNightMode(currentNightMode)
            AapProto.SENSOR_PARKING_BRAKE -> AapProto.encodeSensorParkingBrake(true)
            else -> null
        }
        if (eventBody != null) {
            sendEncrypted(
                channelId = AapProto.CH_SENSOR,
                flags = AapFramer.FLAG_ENC_BULK,
                messageId = AapProto.SENSOR_EVENT_INDICATION,
                body = eventBody,
            )
        }
        log(TAG, "Pretplata na AAP senzor tip=${req.sensorType} potvrđena.")
    }

    @Throws(IOException::class)
    private fun dispatchVideoMessage(
        msgId: Int,
        fullMessage: ByteArray,
        body: ByteArray,
        listener: Listener,
    ) {
        when (msgId) {
            AapProto.AV_SETUP_REQUEST -> {
                val codec = AapProto.decodeAvSetupRequest(body)
                log(TAG, "Video AV_SETUP_REQUEST (codec=$codec) — šaljem OK (maxUnacked=12) i VIDEO_FOCUS=PROJECTED.")
                sendEncrypted(
                    channelId = AapProto.CH_VIDEO,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.AV_SETUP_RESPONSE,
                    body = AapProto.encodeAvSetupResponse(
                        mediaStatus = AapProto.AV_STATUS_OK,
                        maxUnacked = 12,
                        configs = intArrayOf(0),
                    ),
                )
                videoChannelOpen = true
                sendEncrypted(
                    channelId = AapProto.CH_VIDEO,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.VIDEO_FOCUS_INDICATION,
                    body = AapProto.encodeVideoFocusIndication(
                        focusMode = AapProto.VIDEO_FOCUS_PROJECTED,
                        unrequested = true,
                    ),
                )
            }

            AapProto.VIDEO_FOCUS_REQUEST -> {
                val req = AapProto.decodeVideoFocusRequest(body)
                sendEncrypted(
                    channelId = AapProto.CH_VIDEO,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.VIDEO_FOCUS_INDICATION,
                    body = AapProto.encodeVideoFocusIndication(
                        focusMode = req.focusMode,
                        unrequested = false,
                    ),
                )
                log(TAG, "Odgovor na VIDEO_FOCUS_REQUEST (mode=${req.focusMode}).")
            }

            AapProto.AV_START_INDICATION -> {
                val ind = AapProto.decodeAvStartIndication(body)
                videoSessionId = ind.session
                log(TAG, "Android Auto video tok pokrenut (session=${ind.session}, config=${ind.config}).")
            }

            AapProto.AV_MEDIA_INDICATION -> {
                listener.onVideoConfig(body)
                sendEncrypted(
                    channelId = AapProto.CH_VIDEO,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.AV_MEDIA_ACK,
                    body = AapProto.encodeAvMediaAck(sessionId = videoSessionId, ackCount = 1),
                )
            }

            AapProto.AV_MEDIA_WITH_TIMESTAMP -> {
                if (fullMessage.size < 10) {
                    log(TAG, "Prekratak AV_MEDIA_WITH_TIMESTAMP video okvir (${fullMessage.size} B).")
                    return
                }
                val timestampUs = readBeU64(fullMessage, 2)
                val annexB = fullMessage.copyOfRange(10, fullMessage.size)
                listener.onVideoFrame(timestampUs, annexB)
                sendEncrypted(
                    channelId = AapProto.CH_VIDEO,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.AV_MEDIA_ACK,
                    body = AapProto.encodeAvMediaAck(sessionId = videoSessionId, ackCount = 1),
                )
            }

            AapProto.AV_STOP_INDICATION -> {
                log(TAG, "Android Auto video tok zaustavljen (AV_STOP_INDICATION).")
                listener.onVideoStopped()
            }
        }
    }

    @Throws(IOException::class)
    private fun dispatchInputMessage(msgId: Int, body: ByteArray) {
        if (msgId != AapProto.INPUT_BINDING_REQUEST) return
        val keycodes = AapProto.decodeInputBindingRequest(body)
        sendEncrypted(
            channelId = AapProto.CH_INPUT,
            flags = AapFramer.FLAG_ENC_BULK,
            messageId = AapProto.INPUT_BINDING_RESPONSE,
            body = AapProto.encodeInputBindingResponse(AapProto.STATUS_SUCCESS),
        )
        inputReady = true
        log(TAG, "AAP ulazni kanal spreman (povezano ${keycodes.size} tipki + touchscreen).")
    }

    @Throws(IOException::class)
    private fun dispatchAudioSystemMessage(msgId: Int, body: ByteArray) {
        when (msgId) {
            AapProto.AV_SETUP_REQUEST -> {
                sendEncrypted(
                    channelId = AapProto.CH_AUDIO_SYSTEM,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.AV_SETUP_RESPONSE,
                    body = AapProto.encodeAvSetupResponse(
                        mediaStatus = AapProto.AV_STATUS_OK,
                        maxUnacked = 30,
                        configs = intArrayOf(0),
                    ),
                )
                log(TAG, "Sistemski audio kanal postavljen (maxUnacked=30).")
            }

            AapProto.AV_START_INDICATION -> {
                val ind = AapProto.decodeAvStartIndication(body)
                audioSystemSessionId = ind.session
            }

            AapProto.AV_MEDIA_WITH_TIMESTAMP,
            AapProto.AV_MEDIA_INDICATION,
            -> {
                sendEncrypted(
                    channelId = AapProto.CH_AUDIO_SYSTEM,
                    flags = AapFramer.FLAG_ENC_BULK,
                    messageId = AapProto.AV_MEDIA_ACK,
                    body = AapProto.encodeAvMediaAck(sessionId = audioSystemSessionId, ackCount = 1),
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun dispatchMicMessage(msgId: Int, body: ByteArray) {
        if (msgId != AapProto.AV_INPUT_OPEN_REQUEST) return
        val open = AapProto.decodeAvInputOpenRequest(body)
        sendEncrypted(
            channelId = AapProto.CH_MIC,
            flags = AapFramer.FLAG_ENC_BULK,
            messageId = AapProto.AV_INPUT_OPEN_RESPONSE,
            body = AapProto.encodeAvInputOpenResponse(session = 0, value = 0),
        )
        log(TAG, "Odgovor na zahtjev mikrofona (open=$open).")
    }

    @Throws(IOException::class)
    private fun sendPlaintext(
        channelId: Int,
        flags: Int,
        messageId: Int,
        body: ByteArray,
    ) {
        val msg = AapFramer.prependMessageId(messageId, body)
        val wire = AapFramer.encodeFrame(channelId, flags, msg)
        synchronized(writeLock) {
            val out = activeOutput ?: throw IOException("AAP izlazni tok nije otvoren.")
            out.write(wire)
            out.flush()
        }
    }

    @Throws(IOException::class)
    private fun sendEncrypted(
        channelId: Int,
        flags: Int,
        messageId: Int,
        body: ByteArray,
    ) {
        val msg = AapFramer.prependMessageId(messageId, body)
        synchronized(writeLock) {
            val tls = activeTls ?: throw IOException("AAP TLS kanal nije inicijaliziran.")
            val out = activeOutput ?: throw IOException("AAP izlazni tok nije otvoren.")
            val ciphertext = tls.encrypt(msg)
            val wire = AapFramer.encodeFrame(channelId, flags, ciphertext)
            out.write(wire)
            out.flush()
        }
    }

    private fun closeTransportQuietly() {
        synchronized(writeLock) {
            val tls = activeTls
            activeTls = null
            activeOutput = null
            tls?.close()
        }

        val sock = activeSocket
        activeSocket = null
        if (sock != null) {
            try {
                sock.close()
            } catch (_: Exception) {
                // Best-effort close
            }
        }
    }

    private fun readBeU64(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        return result
    }
}
