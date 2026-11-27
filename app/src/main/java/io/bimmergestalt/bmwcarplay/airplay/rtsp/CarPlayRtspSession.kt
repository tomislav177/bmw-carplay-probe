package io.bimmergestalt.bmwcarplay.airplay.rtsp

import android.net.Network
import io.bimmergestalt.bmwcarplay.airplay.BinaryPlist
import io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayCrypto
import io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayPairingClient
import io.bimmergestalt.bmwcarplay.airplay.crypto.RtspControlCipher
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.diag.HexDump
import io.bimmergestalt.bmwcarplay.net.CarWifiConnector
import io.bimmergestalt.bmwcarplay.video.CarPlayVideoSender
import io.bimmergestalt.bmwcarplay.video.TestPatternRenderer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Full Phase 2A Wireless CarPlay RTSP + HomeKit + H.264 Video Session runner for BMW `HU_ENTRYNAV2`
 * (`AirTunes/280.33.8`), strictly bound to `carNetwork` so 5G cellular data remains active on Galaxy S25.
 *
 * Pipeline:
 * 1. Adaptive HomeKit Pairing (Full Pair-Setup + Pair-Verify / Transient Pair-Setup / Fast-Path Pair-Verify)
 * 2. Encrypted `SETUP /setup` (real BT MAC, live UDP `TimingServer` port, full `CONTROLLER_FEATURES`)
 * 3. Bidirectional Event Channel (`eventPort`, `Events-Salt`, immediate `200 OK` on every car request)
 * 4. Encrypted `GET /info` (empty body -> unlocks `displays[0]` width/height/maxFPS & `hidDevices[]`)
 * 5. `POST /command` (`modesChanged`: take mainScreen + mainAudio) + arm `hidSetInputMode`
 * 6. Screen Stream `SETUP /` (`type=110`, `uuid=displayUuid`, `latencyMs=25`) -> open TCP `dataPort` immediately
 * 7. `RECORD` + 3 independent keep-alives (UDP 5s, RTSP `/feedback` 1s, Video `opcode=5`/`2` 1s)
 *    + hardware H.264 `CarPlayVideoSender` + animated `TestPatternRenderer`.
 */
class CarPlayRtspSession(
    private val logger: DiagnosticLogger,
) {
    data class DisplayNegotiation(
        val width: Int,
        val height: Int,
        val maxFps: Int,
        val uuid: String?,
        val sourceLabelHr: String,
        val keepAliveSendStatsAsBody: Boolean,
        val hidDevices: List<CarPlayHidDecoder.HidDeviceInfo>,
    ) {
        val hidDeviceUuids: List<Any> get() = hidDevices.map { it.uuid }
    }

    companion object {
        private const val SENDER_SOURCE_VERSION = "566.25.21"
        private const val USER_AGENT = "AirPlay/$SENDER_SOURCE_VERSION"
        private const val SCREEN_STREAM_TYPE = 110L
        private const val SCREEN_LATENCY_MS = 25L
        private const val UDP_KEEPALIVE_INTERVAL_MS = 5_000L
        private const val FEEDBACK_INTERVAL_MS = 1_000L
        private const val DEFAULT_FALLBACK_WIDTH = 800
        private const val DEFAULT_FALLBACK_HEIGHT = 480
        private const val DEFAULT_FALLBACK_FPS = 30

        /**
         * Complete iPhone CarPlay controller feature list proposed in initial SETUP /setup.
         * Receivers intersect this list with their supported capabilities to compute enabledFeatures.
         */
        val CONTROLLER_FEATURES = listOf(
            "uiContext",
            "viewAreas",
            "cornerMasks",
            "focusTransfer",
            "h.264Level5.1",
            "mainBuffered",
            "altScreen",
            "enhancedSiri",
            "hevc",
            "sessionManagement",
            "logTransfer",
            "iAPChannel",
            "vehicleStateProtocol",
            "videoPlayback",
        )
    }

    private val running = AtomicBoolean(false)
    private val rtspLock = Any()
    private val eventWriteLock = Any()
    private val random = SecureRandom()

    private val timingServer = TimingServer(logger)
    private val pairingClient = AirPlayPairingClient(logger)

    @Volatile
    private var controlSocket: Socket? = null

    @Volatile
    private var controlCipher: RtspControlCipher? = null

    @Volatile
    private var controlCseq: Int = 0

    @Volatile
    private var controlPlainLeftover: ByteArray = ByteArray(0)

    @Volatile
    private var controlEncLeftover: ByteArray = ByteArray(0)

    @Volatile
    private var eventSocket: Socket? = null

    @Volatile
    private var eventCipher: RtspControlCipher? = null

    @Volatile
    private var eventCseq: Int = 0

    @Volatile
    private var keepAliveUdpSocket: DatagramSocket? = null

    @Volatile
    private var hidDevicesByUuidKey: Map<String, CarPlayHidDecoder.HidDeviceInfo> = emptyMap()

    @Volatile
    private var negotiatedScreenWidth: Int = DEFAULT_FALLBACK_WIDTH

    @Volatile
    private var negotiatedScreenHeight: Int = DEFAULT_FALLBACK_HEIGHT

    private val videoSenderRef = AtomicReference<CarPlayVideoSender?>(null)
    private val rendererRef = AtomicReference<TestPatternRenderer?>(null)
    private val virtualIap2Ref = AtomicReference<VirtualIap2Tunnel?>(null)

    private val hidEventCount = AtomicInteger(0)
    private val hidDumpCount = AtomicInteger(0)
    private val hidTouchCount = AtomicInteger(0)
    private val hidOtherCount = AtomicInteger(0)
    private val viap2KeepaliveCount = AtomicInteger(0)
    private val lastVirtualIap2Status = AtomicReference<String?>(null)
    private val dumpedHidKinds = ConcurrentHashMap<CarPlayHidDecoder.DeviceKind, AtomicInteger>()

    private enum class HidArmChannel(val labelHr: String) {
        CONTROL_AND_EVENT("Control :7000 + Event"),
        EVENT_ONLY("Event (neblokirajuće)"),
    }

    /**
     * Runs the complete Phase 2A CarPlay video bring-up synchronously on the calling worker thread.
     */
    fun runSession(
        carNetInfo: CarWifiConnector.CarNetworkInfo,
        hostStr: String,
        port: Int,
        phoneBluetoothMac: String,
        identity: AirPlayPairingClient.ControllerIdentity,
        requiresPairing: Boolean,
        initialKeepAliveStatsAsBody: Boolean,
        onStageUpdate: (detailHr: String) -> Unit,
        onVideoStarted: (display: DisplayNegotiation) -> Unit,
        onStatsUpdate: (framesSent: Long, fps: Int) -> Unit,
        onFailure: (reasonHr: String) -> Unit,
        onEnded: (reasonHr: String) -> Unit,
    ) {
        stop()
        resetSessionCounters()
        running.set(true)

        val resolvedAddr = try {
            resolveAddressWithScope(hostStr, carNetInfo.interfaceName)
        } catch (e: Exception) {
            val msg = "Ne mogu razriješiti AirPlay adresu '$hostStr': ${e.message}"
            logger.log("CARPLAY-RTSP", "✗ $msg")
            running.set(false)
            onFailure(msg)
            return
        }

        logger.log(
            "CARPLAY-RTSP",
            "=== Faza 2A: Pokrećem CarPlay RTSP sesiju prema ${resolvedAddr.hostAddress}:$port " +
                "(BT MAC=$phoneBluetoothMac, pairingId=${identity.pairingId}, HomeKitPairing=$requiresPairing) ===",
        )

        try {
            // 1. Pairing & Control Channel encryption
            val pairingResult: AirPlayPairingClient.PairingResult? = if (requiresPairing) {
                onStageUpdate("HomeKit Pairing (ChaCha20-Poly1305)...")
                performAdaptivePairing(carNetInfo.network, resolvedAddr, port, identity)
            } else {
                logger.log("CARPLAY-RTSP", "Bit 38 = 0 → preskačem HomeKit pairing (et=NONE / AES-128-CTR)")
                openControlSocket(carNetInfo.network, resolvedAddr, port)
                null
            }

            if (!running.get()) return

            if (pairingResult != null) {
                controlCipher = RtspControlCipher(
                    readKey = pairingResult.controlReadKey,
                    writeKey = pairingResult.controlWriteKey,
                )
                logger.log(
                    "CARPLAY-RTSP",
                    "✓ Kontrolni kanal kriptiran (${pairingResult.modeLabelHr}, MTU <= ${RtspControlCipher.MAX_PLAINTEXT_CHUNK} B)",
                )
            }

            // 2. Start NTP TimingServer strictly on carNetwork
            onStageUpdate("Pokrećem TimingServer i RTSP SETUP #1...")
            val timingPort = timingServer.start(carNetInfo.network)
            if (timingPort == 0) {
                throw IOException("TimingServer nije uspio otvoriti UDP port na carNetwork")
            }

            // 3. Initial Session SETUP (/setup)
            val setup1 = performInitialSetup(
                phoneBluetoothMac = phoneBluetoothMac,
                timingPort = timingPort,
                etNone = pairingResult == null,
            )
            if (!running.get()) return

            // 4. Encrypted GET /info (empty body) -> unlocks displays[0] & hidDevices[]!
            onStageUpdate("Čitam kriptirani /info (rezolucija zaslona i HID)...")
            val display = fetchEncryptedDisplayAndHidInfo(initialKeepAliveStatsAsBody)
            if (!running.get()) return

            logger.log(
                "CARPLAY-RTSP",
                "✓ Dogovoreni CarPlay zaslon: ${display.width}×${display.height} @ ${display.maxFps}fps " +
                    "(${display.sourceLabelHr}, uuid=${display.uuid ?: "<nema>"}, " +
                    "keepAliveSendStatsAsBody=${display.keepAliveSendStatsAsBody}, HID uređaja=${display.hidDeviceUuids.size})",
            )

            negotiatedScreenWidth = display.width
            negotiatedScreenHeight = display.height
            hidDevicesByUuidKey = display.hidDevices.associateBy { it.uuidKey }

            // 5. Connect bidirectional Event Channel (eventPort) & start virtual iAP2 handoff tunnel
            if (setup1.eventPort > 0) {
                onStageUpdate("Otvaram Event kanal (port ${setup1.eventPort})...")
                val viap2 = VirtualIap2Tunnel(
                    log = logger::log,
                    sendViaControl = { data, label ->
                        val isKeepalive = label.contains("[keepalive]", ignoreCase = true)
                        val kaNum = if (isKeepalive) viap2KeepaliveCount.incrementAndGet() else 0
                        val quiet = isKeepalive && (kaNum % 12 != 0)
                        val effectiveLabel = if (isKeepalive && !quiet) {
                            "iAPSendMessage [$label #$kaNum]"
                        } else {
                            "iAPSendMessage [$label]"
                        }
                        postControlCommand(
                            linkedMapOf(
                                "type" to "iAPSendMessage",
                                "params" to linkedMapOf("data" to data),
                            ),
                            label = effectiveLabel,
                            quiet = quiet,
                        )
                    },
                    sendViaEvent = { data, label ->
                        val isKeepalive = label.contains("[keepalive]", ignoreCase = true)
                        val kaNum = if (isKeepalive) viap2KeepaliveCount.incrementAndGet() else 0
                        val quiet = isKeepalive && (kaNum % 12 != 0)
                        val effectiveLabel = if (isKeepalive && !quiet) {
                            "iAPSendMessage [$label #$kaNum]"
                        } else {
                            "iAPSendMessage [$label]"
                        }
                        postEventCommand(
                            linkedMapOf(
                                "type" to "iAPSendMessage",
                                "params" to linkedMapOf("data" to data),
                            ),
                            label = effectiveLabel,
                            quiet = quiet,
                        )
                    },
                    onStateChanged = { statusHr, ready ->
                        lastVirtualIap2Status.set(statusHr)
                        rendererRef.get()?.updateVirtualIap2Status(statusHr)
                        if (ready && running.get()) {
                            // Re-arm HID devices strictly via non-blocking Event channel once Wi-Fi iAP2 handoff finishes
                            // (never issue synchronous Control :7000 requests here while BMW tears down BT ACL!)
                            armHidDevices(
                                devices = display.hidDevices,
                                channel = HidArmChannel.EVENT_ONLY,
                                reasonTag = "nakon Wi-Fi iAP2",
                            )
                        }
                    },
                )
                virtualIap2Ref.set(viap2)
                viap2.start()

                connectEventChannel(
                    carNetwork = carNetInfo.network,
                    host = resolvedAddr,
                    eventPort = setup1.eventPort,
                    sharedSecret = pairingResult?.sharedSecret,
                )
            } else {
                logger.log("CARPLAY-RTSP", "Napomena: SETUP #1 nije vratio eventPort — nastavljam bez Event kanala")
            }

            // 6. Send modesChanged (take mainScreen resource 1 + mainAudio resource 2)
            onStageUpdate("Preuzimam BMW zaslon (modesChanged)...")
            sendModesChanged()
            if (!running.get()) return

            // 7. Screen Stream SETUP (/ with type=110)
            onStageUpdate("Pregovaram video stream 110 (${display.width}×${display.height})...")
            val screenSetup = performScreenStreamSetup(display.uuid)
            if (!running.get()) return

            // 8. Build video cipher and IMMEDIATELY connect TCP dataPort BEFORE RECORD (<2s watchdog!)
            val videoCipher = if (pairingResult != null) {
                val streamKey = AirPlayCrypto.deriveDataStreamKey(pairingResult.sharedSecret, screenSetup.streamConnectionId)
                logger.log(
                    "VIDEO",
                    "Video stream ključ (ChaCha20-Poly1305) deriviran za connId=${screenSetup.streamConnectionId} " +
                        "(key=${HexDump.toCompactHex(streamKey, 8)}…)",
                )
                CarPlayVideoSender.FrameCipher.ChaCha(streamKey)
            } else {
                val (aesKey, aesIv) = AirPlayCrypto.deriveAesStreamKeys(ByteArray(16), screenSetup.streamConnectionId)
                logger.log("VIDEO", "Video stream ključ (AES-128-CTR zero-key) deriviran za connId=${screenSetup.streamConnectionId}")
                CarPlayVideoSender.FrameCipher.AesCtr(aesKey, aesIv)
            }

            val sender = CarPlayVideoSender(
                carNetwork = carNetInfo.network,
                host = resolvedAddr,
                dataPort = screenSetup.dataPort,
                frameCipher = videoCipher,
                rawWidth = display.width,
                rawHeight = display.height,
                targetFps = display.maxFps,
                keepAliveWithStats = display.keepAliveSendStatsAsBody,
                logger = logger,
                onStatsUpdate = onStatsUpdate,
                onEnded = { reason ->
                    if (running.getAndSet(false)) {
                        stopInternal()
                        onEnded(reason)
                    }
                },
            )
            videoSenderRef.set(sender)

            if (!sender.openDataPort()) {
                throw IOException("Spajanje na BMW video TCP dataPort ${screenSetup.dataPort} nije uspjelo")
            }

            // 9. Send RECORD to activate the CarPlay screen session on BMW!
            onStageUpdate("Šaljem RTSP RECORD — aktiviram BMW zaslon...")
            performRecord(resolvedAddr.hostAddress ?: hostStr, port)
            if (!running.get()) return

            // 9b. Arm all BMW HID input devices AFTER RECORD 200 OK on BOTH Control :7000 and Event channel
            armHidDevices(
                devices = display.hidDevices,
                channel = HidArmChannel.CONTROL_AND_EVENT,
                reasonTag = "nakon RECORD",
            )

            // 10. Start Keep-Alive #1 (UDP 5s) & Keep-Alive #2 (RTSP /feedback 1s)
            if (setup1.keepAlivePort > 0) {
                startUdpKeepAliveLoop(carNetInfo.network, resolvedAddr, setup1.keepAlivePort)
            }
            startFeedbackLoop()

            // 11. Start hardware H.264 MediaCodec encoder + live TestPatternRenderer (Keep-Alive #3 runs inside sender)
            val renderer = TestPatternRenderer(
                width = sender.width,
                height = sender.height,
                targetFps = display.maxFps,
                displaySourceLabel = display.sourceLabelHr,
                logger = logger,
            )
            lastVirtualIap2Status.get()?.let { renderer.updateVirtualIap2Status(it) }
            rendererRef.set(renderer)

            val started = sender.start { surface ->
                renderer.start(surface)
            }
            if (!started) {
                throw IOException("Pokretanje MediaCodec H.264 video enkodera nije uspjelo")
            }

            onVideoStarted(display)
            logger.log(
                "CARPLAY-RTSP",
                "✓✓ FAZA 2A AKTIVNA! Živa H.264 testna slika (${sender.width}×${sender.height} @ ${display.maxFps}fps) šalje se u BMW!",
            )
        } catch (e: Exception) {
            if (running.getAndSet(false)) {
                val msg = e.message ?: e::class.java.simpleName
                logger.log("CARPLAY-RTSP", "✗ Faza 2A prekinuta: $msg")
                stopInternal()
                onFailure(msg)
            }
        }
    }

    fun stop() {
        running.set(false)
        stopInternal()
    }

    private fun stopInternal() {
        virtualIap2Ref.getAndSet(null)?.stop()
        rendererRef.getAndSet(null)?.stop()
        videoSenderRef.getAndSet(null)?.stop()
        runCatching { keepAliveUdpSocket?.close() }
        keepAliveUdpSocket = null
        runCatching { eventSocket?.close() }
        eventSocket = null
        eventCipher = null
        hidDevicesByUuidKey = emptyMap()
        resetSessionCounters()
        timingServer.stop()
        closeControlSocket()
    }

    private fun resetSessionCounters() {
        hidEventCount.set(0)
        hidDumpCount.set(0)
        hidTouchCount.set(0)
        hidOtherCount.set(0)
        viap2KeepaliveCount.set(0)
        lastVirtualIap2Status.set(null)
        dumpedHidKinds.clear()
    }

    // ---- Step 1: Adaptive HomeKit Pairing ----

    @Throws(IOException::class)
    private fun performAdaptivePairing(
        carNetwork: Network,
        host: InetAddress,
        port: Int,
        identity: AirPlayPairingClient.ControllerIdentity,
    ): AirPlayPairingClient.PairingResult {
        val errors = ArrayList<String>()

        // Strategy A: Full /pair-setup (M1-M6) -> /pair-verify (M1-M4) on persistent TCP socket
        try {
            openControlSocket(carNetwork, host, port)
            val exchange = AirPlayPairingClient.TlvExchange { path, body -> postPairingTlv(path, body) }
            pairingClient.runFullPairSetup(identity, exchange)
            return pairingClient.runPairVerify(identity, exchange, "HomeKit Pair-Setup + Pair-Verify")
        } catch (e: Exception) {
            val msg = "Puni pair-setup+verify: ${e.message}"
            logger.log("AIRPLAY-PAIR", "Strategija 1 ($msg) → prelazim na Transient /pair-setup...")
            errors.add(msg)
            closeControlSocket()
        }

        if (!running.get()) throw IOException("Sesija zaustavljena")

        // Strategy B: Transient /pair-setup (Flags=0x10, PIN 3939, M1-M4) on fresh TCP socket
        try {
            openControlSocket(carNetwork, host, port)
            val exchange = AirPlayPairingClient.TlvExchange { path, body -> postPairingTlv(path, body) }
            return pairingClient.runTransientPairSetup(exchange)
        } catch (e: Exception) {
            val msg = "Transient pair-setup: ${e.message}"
            logger.log("AIRPLAY-PAIR", "Strategija 2 ($msg) → prelazim na brzi /pair-verify...")
            errors.add(msg)
            closeControlSocket()
        }

        if (!running.get()) throw IOException("Sesija zaustavljena")

        // Strategy C: Fast-Path /pair-verify (M1-M4) on fresh TCP socket (when iAP2 MFi already established trust)
        try {
            openControlSocket(carNetwork, host, port)
            val exchange = AirPlayPairingClient.TlvExchange { path, body -> postPairingTlv(path, body) }
            return pairingClient.runPairVerify(identity, exchange, "HomeKit Brzi Pair-Verify")
        } catch (e: Exception) {
            val msg = "Brzi pair-verify: ${e.message}"
            logger.log("AIRPLAY-PAIR", "Strategija 3 ($msg) nije uspjela")
            errors.add(msg)
            closeControlSocket()
        }

        throw IOException("Sve 3 HomeKit pairing strategije odbijene: ${errors.joinToString(" | ")}")
    }

    @Throws(IOException::class)
    private fun postPairingTlv(path: String, tlvBody: ByteArray): ByteArray {
        val extra = ArrayList<Pair<String, String>>()
        extra.add("X-Apple-HKP" to "2")
        if (path.endsWith("pair-verify")) {
            extra.add("X-Apple-PD" to "1")
        }
        val (statusLine, headers, respBody) = sendRtspRequest(
            method = "POST",
            path = path,
            body = tlvBody,
            contentType = "application/pairing+tlv8",
            extraHeaders = extra,
            timeoutMs = 8_000,
        )
        logger.log(
            "AIRPLAY-PAIR",
            "POST $path (${tlvBody.size} B) -> [$statusLine] resp=${respBody.size} B headers=$headers" +
                if (respBody.isNotEmpty()) " head=[${HexDump.toCompactHex(respBody, 24)}]" else "",
        )
        if (!statusLine.contains("200")) {
            throw IOException("POST $path odbijen: $statusLine")
        }
        return respBody
    }

    // ---- Step 2: Initial SETUP (/setup) ----

    private data class InitialSetupResult(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int,
        val enabledFeatures: List<String>,
    )

    @Throws(IOException::class)
    private fun performInitialSetup(
        phoneBluetoothMac: String,
        timingPort: Int,
        etNone: Boolean,
    ): InitialSetupResult {
        val dict = linkedMapOf<String, Any?>(
            "deviceID" to phoneBluetoothMac,
            "macAddress" to phoneBluetoothMac,
            "name" to "iPhone",
            "model" to "iPhone14,5",
            "osName" to "iPhone OS",
            "osBuildVersion" to "19A404",
            "osVersion" to "15.0.2",
            "sourceVersion" to SENDER_SOURCE_VERSION,
            "timingPort" to timingPort.toLong(),
            "sessionUUID" to UUID.randomUUID().toString().uppercase(),
            "sessionCorrelationUUID" to UUID.randomUUID().toString().uppercase(),
            "statsCollectionEnabled" to false,
            "keepAliveLowPower" to true,
            "features" to CONTROLLER_FEATURES,
        )
        if (etNone) {
            dict["et"] = 1L
        }
        val reqBody = BinaryPlist.encode(dict)
        logger.log("CARPLAY-RTSP", "TX SETUP /setup (${reqBody.size} B, deviceID=$phoneBluetoothMac, timingPort=$timingPort)")

        val (statusLine, headers, respBody) = sendRtspRequest(
            method = "SETUP",
            path = "/setup",
            body = reqBody,
            contentType = "application/x-apple-binary-plist",
            timeoutMs = 10_000,
        )
        logger.log("CARPLAY-RTSP", "RX SETUP /setup -> [$statusLine] headers=$headers body=${respBody.size} B")
        if (!statusLine.contains("200")) {
            logErrorBody("SETUP /setup", respBody)
            throw IOException("SETUP /setup odbijen: $statusLine")
        }
        val root = (BinaryPlist.parse(respBody) as? Map<*, *>)
            ?: throw IOException("SETUP /setup odgovor nije bplist rječnik")
        logger.log("CARPLAY-RTSP", "SETUP #1 bplist odgovor:\n${BinaryPlist.formatReadable(root, "  ")}")

        val eventPort = readUnsignedPort(root["eventPort"])
        val carTimingPort = readUnsignedPort(root["timingPort"])
        val keepAlivePort = readUnsignedPort(root["keepAlivePort"])
        val enabled = (root["enabledFeatures"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()

        logger.log(
            "CARPLAY-RTSP",
            "✓ SETUP #1 OK: eventPort=$eventPort | carTimingPort=$carTimingPort | keepAlivePort=$keepAlivePort | enabledFeatures=$enabled",
        )
        return InitialSetupResult(eventPort, carTimingPort, keepAlivePort, enabled)
    }

    // ---- Step 3: Encrypted GET /info (unlocks displays[] & hidDevices[]) ----

    private fun fetchEncryptedDisplayAndHidInfo(initialKeepAliveStatsAsBody: Boolean): DisplayNegotiation {
        return try {
            val emptyDictBody = BinaryPlist.encode(emptyMap<String, Any?>())
            var (statusLine, headers, respBody) = sendRtspRequest(
                method = "GET",
                path = "/info",
                body = emptyDictBody,
                contentType = "application/x-apple-binary-plist",
                timeoutMs = 6_000,
            )
            logger.log("CARPLAY-RTSP", "RX kriptirani GET /info (prazan bplist) -> [$statusLine] headers=$headers body=${respBody.size} B")
            if (!statusLine.contains("200") || respBody.isEmpty()) {
                val fallbackResp = sendRtspRequest(
                    method = "GET",
                    path = "/info",
                    body = ByteArray(0),
                    contentType = null,
                    timeoutMs = 6_000,
                )
                statusLine = fallbackResp.first
                headers = fallbackResp.second
                respBody = fallbackResp.third
                logger.log("CARPLAY-RTSP", "RX kriptirani GET /info (0 B fallback) -> [$statusLine] headers=$headers body=${respBody.size} B")
            }
            if (!statusLine.contains("200") || respBody.isEmpty()) {
                logErrorBody("GET /info", respBody)
                return fallbackDisplay(initialKeepAliveStatsAsBody, "Fallback (kriptirani /info vratio $statusLine)")
            }
            val root = BinaryPlist.parse(respBody) as? Map<*, *>
                ?: return fallbackDisplay(initialKeepAliveStatsAsBody, "Fallback (/info nije rječnik)")

            logger.log("CARPLAY-RTSP", "Kriptirani /info puni rječnik:\n${BinaryPlist.formatReadable(root, "  ")}")

            val keepAliveStats = (root["keepAliveSendStatsAsBody"] as? Boolean) ?: initialKeepAliveStatsAsBody

            val displays = root["displays"] as? List<*>
            val d0 = displays?.firstOrNull() as? Map<*, *>
            val negotiatedW = ((d0?.get("widthPixels") as? Number)?.toInt() ?: DEFAULT_FALLBACK_WIDTH)
            val negotiatedH = ((d0?.get("heightPixels") as? Number)?.toInt() ?: DEFAULT_FALLBACK_HEIGHT)

            val parsedHidDevices = ArrayList<CarPlayHidDecoder.HidDeviceInfo>()
            val hidDevices = root["hidDevices"] as? List<*>
            if (!hidDevices.isNullOrEmpty()) {
                logger.log("CARPLAY-HID", "Pronađeno ${hidDevices.size} BMW HID uređaja:")
                hidDevices.forEachIndexed { idx, item ->
                    val h = item as? Map<*, *> ?: return@forEachIndexed
                    val uuidVal = h["uuid"]
                    val nameStr = h["name"]?.toString() ?: "HID-$idx"
                    val desc = h["hidDescriptor"] as? ByteArray
                    val kind = CarPlayHidDecoder.classifyDevice(nameStr, desc)
                    if (uuidVal != null) {
                        parsedHidDevices.add(
                            CarPlayHidDecoder.HidDeviceInfo(
                                uuid = uuidVal,
                                name = nameStr,
                                kind = kind,
                                maxX = negotiatedW,
                                maxY = negotiatedH,
                            ),
                        )
                    }
                    logger.log(
                        "CARPLAY-HID",
                        "  HID[$idx]: naziv=$nameStr ($kind) | uuid=$uuidVal (${uuidVal?.javaClass?.simpleName}) | " +
                            "vendor=${h["hidVendorID"]} | product=${h["hidProductID"]} | " +
                            "descriptor=${desc?.let { "${it.size}B [${HexDump.toCompactHex(it, 64)}]" } ?: "<nema>"}",
                    )
                }
            }

            if (d0 != null) {
                val w = (d0["widthPixels"] as? Number)?.toInt() ?: 0
                val h = (d0["heightPixels"] as? Number)?.toInt() ?: 0
                val fps = ((d0["maxFPS"] as? Number)?.toInt() ?: DEFAULT_FALLBACK_FPS).coerceIn(30, 60)
                val uuid = d0["uuid"]?.toString()
                val physW = (d0["widthPhysical"] as? Number)?.toInt()
                val physH = (d0["heightPhysical"] as? Number)?.toInt()
                logger.log(
                    "CARPLAY-RTSP",
                    "✓ BMW /info displays[0]: ${w}×${h} px (fizički ${physW ?: "?"}×${physH ?: "?"} mm) @ maxFPS=$fps | uuid=$uuid",
                )
                if (w >= 320 && h >= 200) {
                    val modeTag = when {
                        w == 1280 && h == 480 -> "BMW Fullscreen (/info)"
                        w == 800 && h == 480 -> "BMW Split-Screen (/info)"
                        else -> "BMW /info displays[0]"
                    }
                    return DisplayNegotiation(
                        width = (w / 2) * 2,
                        height = (h / 2) * 2,
                        maxFps = fps,
                        uuid = uuid,
                        sourceLabelHr = modeTag,
                        keepAliveSendStatsAsBody = keepAliveStats,
                        hidDevices = parsedHidDevices,
                    )
                }
            } else {
                logger.log("CARPLAY-RTSP", "Napomena: ni kriptirani /info nije vratio displays[] — koristim sigurni BMW fallback ${DEFAULT_FALLBACK_WIDTH}×${DEFAULT_FALLBACK_HEIGHT}")
            }
            DisplayNegotiation(
                width = DEFAULT_FALLBACK_WIDTH,
                height = DEFAULT_FALLBACK_HEIGHT,
                maxFps = DEFAULT_FALLBACK_FPS,
                uuid = null,
                sourceLabelHr = "Fallback 800×480 (nema displays[])",
                keepAliveSendStatsAsBody = keepAliveStats,
                hidDevices = parsedHidDevices,
            )
        } catch (e: Exception) {
            logger.log("CARPLAY-RTSP", "Upozorenje pri kriptiranom GET /info: ${e.message}")
            fallbackDisplay(initialKeepAliveStatsAsBody, "Fallback 800×480")
        }
    }

    private fun fallbackDisplay(keepAliveStats: Boolean, label: String): DisplayNegotiation =
        DisplayNegotiation(
            width = DEFAULT_FALLBACK_WIDTH,
            height = DEFAULT_FALLBACK_HEIGHT,
            maxFps = DEFAULT_FALLBACK_FPS,
            uuid = null,
            sourceLabelHr = label,
            keepAliveSendStatsAsBody = keepAliveStats,
            hidDevices = emptyList(),
        )

    // ---- Step 4: Bidirectional Event Channel ----

    private fun connectEventChannel(
        carNetwork: Network,
        host: InetAddress,
        eventPort: Int,
        sharedSecret: ByteArray?,
    ) {
        try {
            val s = carNetwork.socketFactory.createSocket()
            s.tcpNoDelay = true
            s.soTimeout = 30_000
            s.connect(InetSocketAddress(host, eventPort), 5_000)
            val cipher = sharedSecret?.let { secret ->
                // Direction is OPPOSITE to Control channel: car sends encrypted with Events-Write, reads with Events-Read!
                val phoneWriteKey = AirPlayCrypto.hkdfSha512(secret, "Events-Salt", "Events-Read-Encryption-Key")
                val phoneReadKey = AirPlayCrypto.hkdfSha512(secret, "Events-Salt", "Events-Write-Encryption-Key")
                RtspControlCipher(readKey = phoneReadKey, writeKey = phoneWriteKey)
            }
            eventCipher = cipher
            eventCseq = 0
            eventSocket = s
            logger.log(
                "CARPLAY-EVENT",
                "✓ Event kanal spojen na ${host.hostAddress}:$eventPort (kriptirano=${cipher != null})",
            )

            Thread({ eventReaderLoop(s, cipher) }, "carplay-event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            logger.log("CARPLAY-EVENT", "Upozorenje: spajanje na Event kanal $eventPort nije uspjelo: ${e.message}")
        }
    }

    private fun eventReaderLoop(sock: Socket, cipher: RtspControlCipher?) {
        val input = sock.getInputStream()
        val buf = ByteArray(8192)
        var encPending = ByteArray(0)
        var plainPending = ByteArray(0)

        try {
            while (running.get() && !sock.isClosed) {
                val n = try {
                    input.read(buf)
                } catch (_: SocketTimeoutException) {
                    // Normal idle timeout on Event channel — NEVER exit the reader loop!
                    continue
                }
                if (n < 0) break
                if (n == 0) continue

                val chunk = buf.copyOf(n)
                val decrypted = if (cipher != null) {
                    encPending += chunk
                    val res = cipher.decrypt(encPending)
                    encPending = res.remainingEncrypted
                    res.plaintext
                } else {
                    chunk
                }
                if (decrypted.isEmpty()) continue
                plainPending += decrypted

                val parsedMessages = extractRtspMessages(plainPending)
                plainPending = parsedMessages.remaining
                for (msg in parsedMessages.messages) {
                    // CRITICAL: Immediately reply 200 OK to every incoming car request on Event channel!
                    if (!msg.firstLine.startsWith("RTSP/", ignoreCase = true)) {
                        replyEventOk(sock, cipher, msg.cseq)
                    }
                    handleEventMessage(msg) { eventNum, decodedHid ->
                        if (decodedHid.kind == CarPlayHidDecoder.DeviceKind.TOUCH) {
                            val touchNum = hidTouchCount.incrementAndGet()
                            if (eventNum <= 40 || touchNum % 25 == 0) {
                                logger.log("CARPLAY-HID", "HID #$eventNum (Touch #$touchNum): ${decodedHid.summaryHr}")
                            }
                            rendererRef.get()?.let { r ->
                                r.updateTouchEvent("Touch #$touchNum: ${decodedHid.summaryHr}")
                                decodedHid.touch?.let { r.updateTouchPoint(it) }
                            }
                        } else {
                            val otherNum = hidOtherCount.incrementAndGet()
                            if (eventNum <= 40 || otherNum <= 40 || otherNum % 10 == 0) {
                                logger.log("CARPLAY-HID", "HID #$eventNum (iDrive #$otherNum): ${decodedHid.summaryHr}")
                            }
                            rendererRef.get()?.updateKnobEvent("iDrive #$otherNum: ${decodedHid.summaryHr}")
                        }
                    }
                }
            }
            if (running.get()) {
                logger.log("CARPLAY-EVENT", "Event kanal zatvoren od strane BMW-a (EOF)")
            }
        } catch (e: Exception) {
            if (running.get()) {
                logger.log("CARPLAY-EVENT", "Event kanal prekinut: ${e.message}")
            }
        }
    }

    private fun replyEventOk(sock: Socket, cipher: RtspControlCipher?, incomingCseq: String?) {
        try {
            synchronized(eventWriteLock) {
                val cseqHeader = incomingCseq ?: (++eventCseq).toString()
                val resp = "RTSP/1.0 200 OK\r\nCSeq: $cseqHeader\r\nContent-Length: 0\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
                val wire = cipher?.encrypt(resp) ?: resp
                sock.getOutputStream().apply {
                    write(wire)
                    flush()
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                logger.log("CARPLAY-EVENT", "Greška pri slanju 200 OK na Event kanalu: ${e.message}")
            }
        }
    }

    private fun postControlCommand(
        commandDict: Map<String, Any?>,
        label: String,
        quiet: Boolean = false,
    ): Boolean {
        if (!running.get() || controlSocket?.isClosed != false) return false
        return try {
            val body = BinaryPlist.encode(commandDict)
            val (statusLine, _, respBody) = sendRtspRequest(
                method = "POST",
                path = "/command",
                body = body,
                contentType = "application/x-apple-binary-plist",
                timeoutMs = 5_000,
            )
            val ok = statusLine.contains("200")
            if (!quiet || !ok) {
                logger.log(
                    "CARPLAY-CMD",
                    "TX Control POST /command ($label, ${body.size} B) -> [$statusLine] resp=${respBody.size} B",
                )
            }
            if (!ok) {
                logErrorBody("Control POST /command ($label)", respBody)
            }
            ok
        } catch (e: Exception) {
            if (running.get()) {
                logger.log("CARPLAY-CMD", "Greška pri Control POST /command ($label): ${e.message}")
            }
            false
        }
    }

    private fun postEventCommand(
        commandDict: Map<String, Any?>,
        label: String,
        quiet: Boolean = false,
    ): Boolean {
        val sock = eventSocket ?: return false
        val cipher = eventCipher
        return try {
            synchronized(eventWriteLock) {
                eventCseq++
                val body = BinaryPlist.encode(commandDict)
                val head = "POST /command RTSP/1.0\r\n" +
                    "CSeq: $eventCseq\r\n" +
                    "User-Agent: $USER_AGENT\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
                val plain = head.toByteArray(Charsets.US_ASCII) + body
                val wire = cipher?.encrypt(plain) ?: plain
                sock.getOutputStream().apply {
                    write(wire)
                    flush()
                }
            }
            if (!quiet) {
                logger.log("CARPLAY-EVENT", "TX Event POST /command ($label)")
            }
            true
        } catch (e: Exception) {
            if (running.get()) {
                logger.log("CARPLAY-EVENT", "Greška pri slanju Event komande '$label': ${e.message}")
            }
            false
        }
    }

    private fun armHidDevices(
        devices: List<CarPlayHidDecoder.HidDeviceInfo>,
        channel: HidArmChannel,
        reasonTag: String,
    ) {
        if (devices.isEmpty()) return
        logger.log(
            "CARPLAY-HID",
            "Naoružavam ${devices.size} BMW HID uređaja ($reasonTag, kanal=${channel.labelHr})...",
        )
        for (dev in devices) {
            if (!running.get()) return
            val payload = linkedMapOf<String, Any?>(
                "type" to "hidSetInputMode",
                "params" to linkedMapOf(
                    "hidInputMode" to 0L,
                    "uuid" to dev.uuid,
                ),
            )
            val label = "hidSetInputMode ${dev.name} uuid=${dev.uuid}"
            when (channel) {
                HidArmChannel.CONTROL_AND_EVENT -> {
                    postControlCommand(payload, label)
                    postEventCommand(payload, label)
                }

                HidArmChannel.EVENT_ONLY -> {
                    postEventCommand(payload, label)
                }
            }
        }
    }

    private fun handleEventMessage(
        msg: ParsedRtspMessage,
        onHidEvent: (eventNum: Int, decoded: CarPlayHidDecoder.DecodedReport) -> Unit,
    ) {
        if (msg.firstLine.startsWith("RTSP/", ignoreCase = true)) {
            logger.log("CARPLAY-EVENT", "RX Event odgovor: ${msg.firstLine} (${msg.body.size} B)")
            return
        }
        if (!msg.firstLine.contains("/command", ignoreCase = true) || msg.body.isEmpty()) {
            logger.log(
                "CARPLAY-EVENT",
                "RX Event [${msg.firstLine}] body=${msg.body.size} B" +
                    if (msg.body.isNotEmpty()) " HEX=[${HexDump.toCompactHex(msg.body, 48)}]" else "",
            )
            return
        }

        val cmd = runCatching { BinaryPlist.parse(msg.body) as? Map<*, *> }.getOrNull()
        if (cmd == null) {
            logger.log("CARPLAY-EVENT", "RX Event /command (ne-bplist ${msg.body.size} B)")
            return
        }
        val type = cmd["type"]?.toString() ?: "<nepoznato>"
        val params = cmd["params"] as? Map<*, *>

        when (type) {
            "forceKeyFrame" -> {
                logger.log("CARPLAY-EVENT", "RX BMW forceKeyFrame → odmah tražim IDR okvir!")
                videoSenderRef.get()?.requestKeyFrame()
            }

            "hidSendReport" -> {
                val eventNum = hidEventCount.incrementAndGet()
                val extracted = CarPlayHidDecoder.extractFromCommand(cmd)
                val decoded = CarPlayHidDecoder.decode(
                    uuid = extracted.uuid,
                    report = extracted.report,
                    devicesByUuidKey = hidDevicesByUuidKey,
                    screenWidth = negotiatedScreenWidth,
                    screenHeight = negotiatedScreenHeight,
                )
                val isIncomplete = extracted.uuid == null || extracted.report == null || extracted.report.isEmpty()
                val kindCounter = dumpedHidKinds.computeIfAbsent(decoded.kind) { AtomicInteger(0) }
                val shouldDumpKind = kindCounter.get() < 2
                if (hidDumpCount.get() < 25 &&
                    (eventNum <= 10 || shouldDumpKind || (isIncomplete && hidDumpCount.get() < 15))
                ) {
                    val dumpNum = hidDumpCount.incrementAndGet()
                    kindCounter.incrementAndGet()
                    logger.log(
                        "CARPLAY-HID",
                        "HID sirovi dump #$dumpNum (događaj #$eventNum, tip=${decoded.kind}, izvor=${extracted.sourceNote}, " +
                            "uuid=${extracted.uuid}, report=${extracted.report?.size ?: -1} B, " +
                            "body=${msg.body.size} B HEX=[${HexDump.toCompactHex(msg.body, 96)}]):\n" +
                            BinaryPlist.formatReadable(cmd, "  "),
                    )
                }
                onHidEvent(eventNum, decoded)
            }

            "iAPSendMessage" -> {
                val data = params?.get("data") as? ByteArray
                if (data != null && data.isNotEmpty()) {
                    virtualIap2Ref.get()?.onIncomingCarBytes(data)
                } else {
                    logger.log("CARPLAY-EVENT", "RX BMW Event iAPSendMessage (prazno tijelo)")
                }
            }

            else -> {
                logger.log(
                    "CARPLAY-EVENT",
                    "RX BMW Event /command type='$type' params=${BinaryPlist.formatReadable(params, "  ")}",
                )
            }
        }
    }

    // ---- Step 5: modesChanged, Screen Stream SETUP #2, RECORD ----

    private fun sendModesChanged() {
        val body = BinaryPlist.encode(
            linkedMapOf(
                "type" to "modesChanged",
                "params" to linkedMapOf(
                    "appStates" to listOf(
                        linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to 0L),
                        linkedMapOf<String, Any?>("appStateID" to 3L, "entity" to 0L),
                        linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                    ),
                    "resources" to listOf(
                        linkedMapOf<String, Any?>("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                        linkedMapOf<String, Any?>("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
                    ),
                ),
            ),
        )
        val (statusLine, _, respBody) = sendRtspRequest(
            method = "POST",
            path = "/command",
            body = body,
            contentType = "application/x-apple-binary-plist",
            timeoutMs = 8_000,
        )
        val parsedResp = if (respBody.isNotEmpty()) {
            runCatching { BinaryPlist.formatReadable(BinaryPlist.parse(respBody), "  ") }.getOrNull()
        } else {
            null
        }
        logger.log(
            "CARPLAY-RTSP",
            "POST /command modesChanged (mainScreen=take, mainAudio=take) -> [$statusLine] resp=${respBody.size} B" +
                if (parsedResp != null) " $parsedResp" else "",
        )
        if (!statusLine.contains("200")) {
            logErrorBody("POST /command modesChanged", respBody)
        }
    }

    private data class ScreenStreamSetupResult(
        val streamConnectionId: Long,
        val dataPort: Int,
    )

    @Throws(IOException::class)
    private fun performScreenStreamSetup(displayUuid: String?): ScreenStreamSetupResult {
        val connId = (random.nextLong() and Long.MAX_VALUE) or 1L
        val streamEntry = linkedMapOf<String, Any?>(
            "type" to SCREEN_STREAM_TYPE,
            "streamConnectionID" to connId,
            "latencyMs" to SCREEN_LATENCY_MS,
        )
        if (!displayUuid.isNullOrBlank()) {
            streamEntry["uuid"] = displayUuid
        } else {
            streamEntry["uuid"] = UUID.randomUUID().toString()
        }
        val reqBody = BinaryPlist.encode(linkedMapOf("streams" to listOf(streamEntry)))
        logger.log(
            "CARPLAY-RTSP",
            "TX SETUP / (screen stream type=110, connId=$connId, uuid=${streamEntry["uuid"]}, latencyMs=$SCREEN_LATENCY_MS)",
        )

        val (statusLine, headers, respBody) = sendRtspRequest(
            method = "SETUP",
            path = "/",
            body = reqBody,
            contentType = "application/x-apple-binary-plist",
            timeoutMs = 10_000,
        )
        logger.log("CARPLAY-RTSP", "RX SETUP / (screen 110) -> [$statusLine] headers=$headers body=${respBody.size} B")
        if (!statusLine.contains("200")) {
            logErrorBody("SETUP / (video stream 110)", respBody)
            throw IOException("SETUP / (video stream 110) odbijen: $statusLine")
        }
        val root = (BinaryPlist.parse(respBody) as? Map<*, *>)
            ?: throw IOException("SETUP #2 odgovor nije bplist rječnik")
        logger.log("CARPLAY-RTSP", "SETUP #2 bplist odgovor:\n${BinaryPlist.formatReadable(root, "  ")}")

        val streams = root["streams"] as? List<*>
        val s0 = streams?.firstOrNull() as? Map<*, *>
        val dataPort = readUnsignedPort(s0?.get("dataPort"))
        if (dataPort == 0) {
            throw IOException("SETUP #2 nije vratio valjani video dataPort (streams=$streams)")
        }
        logger.log("CARPLAY-RTSP", "✓ Video stream 110 dogovoren: dataPort=$dataPort, connId=$connId")
        return ScreenStreamSetupResult(connId, dataPort)
    }

    @Throws(IOException::class)
    private fun performRecord(hostStr: String, port: Int) {
        val emptyDict = BinaryPlist.encode(linkedMapOf<String, Any?>())
        val (statusLine, headers, respBody) = sendRtspRequest(
            method = "RECORD",
            path = "rtsp://$hostStr:$port/",
            body = emptyDict,
            contentType = "application/x-apple-binary-plist",
            timeoutMs = 10_000,
        )
        logger.log("CARPLAY-RTSP", "RX RECORD -> [$statusLine] headers=$headers body=${respBody.size} B")
        if (!statusLine.contains("200")) {
            logErrorBody("RTSP RECORD", respBody)
            throw IOException("RTSP RECORD odbijen: $statusLine")
        }
        logger.log("CARPLAY-RTSP", "✓ RTSP RECORD 200 OK — CarPlay video sesija je AKTIVIRANA!")
    }

    private fun logErrorBody(stage: String, body: ByteArray) {
        if (body.isEmpty()) return
        val parsed = runCatching { BinaryPlist.parse(body) }.getOrNull()
        if (parsed != null) {
            logger.log("CARPLAY-RTSP", "$stage tijelo greške (bplist):\n${BinaryPlist.formatReadable(parsed, "  ")}")
        } else {
            logger.log("CARPLAY-RTSP", "$stage tijelo greške (${body.size} B HEX): ${HexDump.toCompactHex(body, 64)}")
        }
    }

    // ---- Keep-Alive #1 (UDP 5s) & Keep-Alive #2 (RTSP /feedback 1s) ----

    private fun startUdpKeepAliveLoop(carNetwork: Network, host: InetAddress, keepAlivePort: Int) {
        Thread({
            var udp: DatagramSocket? = null
            try {
                udp = DatagramSocket()
                carNetwork.bindSocket(udp)
                keepAliveUdpSocket = udp
                var count = 0
                logger.log(
                    "CARPLAY-KA",
                    "UDP Keep-Alive pokrenut prema ${host.hostAddress}:$keepAlivePort (svakih ${UDP_KEEPALIVE_INTERVAL_MS / 1000}s)",
                )
                while (running.get() && !udp.isClosed) {
                    val ping = ByteArray(4).also(random::nextBytes)
                    udp.send(DatagramPacket(ping, ping.size, host, keepAlivePort))
                    count++
                    if (count <= 3 || count % 12 == 0) {
                        logger.log("CARPLAY-KA", "TX UDP Keep-Alive #$count -> ${host.hostAddress}:$keepAlivePort")
                    }
                    try {
                        Thread.sleep(UDP_KEEPALIVE_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    logger.log("CARPLAY-KA", "UDP Keep-Alive prekinut: ${e.message}")
                }
            } finally {
                runCatching { udp?.close() }
                keepAliveUdpSocket = null
            }
        }, "carplay-udp-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    private fun startFeedbackLoop() {
        Thread({
            var count = 0
            while (running.get() && controlSocket?.isClosed == false) {
                try {
                    val (status, _, body) = sendRtspRequest(
                        method = "POST",
                        path = "/feedback",
                        body = ByteArray(0),
                        contentType = null,
                        timeoutMs = 5_000,
                    )
                    count++
                    if (count <= 3 || count % 15 == 0) {
                        logger.log("CARPLAY-KA", "RTSP POST /feedback #$count -> [$status] (${body.size} B)")
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        logger.log("CARPLAY-KA", "RTSP /feedback prekinut: ${e.message}")
                    }
                    break
                }
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "carplay-rtsp-feedback").apply {
            isDaemon = true
            start()
        }
    }

    // ---- Low-level RTSP/1.0 Control Socket I/O ----

    @Throws(IOException::class)
    private fun openControlSocket(carNetwork: Network, host: InetAddress, port: Int) {
        closeControlSocket()
        val s = carNetwork.socketFactory.createSocket()
        s.tcpNoDelay = true
        s.soTimeout = 10_000
        s.connect(InetSocketAddress(host, port), 5_000)
        controlSocket = s
        controlCipher = null
        controlCseq = 0
        controlPlainLeftover = ByteArray(0)
        controlEncLeftover = ByteArray(0)
        logger.log(
            "CARPLAY-RTSP",
            "TCP kontrolna veza otvorena prema ${host.hostAddress}:$port (carNetwork, lokalna=${s.localAddress.hostAddress}:${s.localPort})",
        )
    }

    private fun closeControlSocket() {
        runCatching { controlSocket?.close() }
        controlSocket = null
        controlCipher = null
        controlPlainLeftover = ByteArray(0)
        controlEncLeftover = ByteArray(0)
    }

    @Throws(IOException::class)
    private fun sendRtspRequest(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String?,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        timeoutMs: Int = 10_000,
    ): Triple<String, Map<String, String>, ByteArray> = synchronized(rtspLock) {
        val sock = controlSocket ?: throw IOException("RTSP kontrolni socket nije otvoren")
        sock.soTimeout = timeoutMs

        val head = buildString {
            append("$method $path RTSP/1.0\r\n")
            append("CSeq: ${++controlCseq}\r\n")
            append("User-Agent: $USER_AGENT\r\n")
            if (contentType != null) {
                append("Content-Type: $contentType\r\n")
            }
            for ((k, v) in extraHeaders) {
                append("$k: $v\r\n")
            }
            append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        val plaintext = head.toByteArray(Charsets.US_ASCII) + body
        val cipher = controlCipher
        val wireOut = cipher?.encrypt(plaintext) ?: plaintext

        sock.getOutputStream().apply {
            write(wireOut)
            flush()
        }

        val input = sock.getInputStream()
        val readBuf = ByteArray(8192)
        while (true) {
            val extracted = extractRtspMessages(controlPlainLeftover)
            if (extracted.messages.isNotEmpty()) {
                val first = extracted.messages.first()
                // Preserve any extra pipelined messages after the first one
                val tail = ByteArrayOutputStream()
                for (extraIdx in 1 until extracted.messages.size) {
                    tail.write(extracted.messages[extraIdx].rawBytes)
                }
                tail.write(extracted.remaining)
                controlPlainLeftover = tail.toByteArray()
                return@synchronized Triple(first.firstLine, first.headers, first.body)
            }

            val n = input.read(readBuf)
            if (n < 0) throw IOException("BMW zatvorio RTSP kontrolni socket (EOF) čekajući odgovor na $method $path")
            if (n == 0) continue

            val rawChunk = readBuf.copyOf(n)
            if (cipher != null) {
                controlEncLeftover += rawChunk
                val dec = cipher.decrypt(controlEncLeftover)
                controlEncLeftover = dec.remainingEncrypted
                if (dec.plaintext.isNotEmpty()) {
                    controlPlainLeftover += dec.plaintext
                }
            } else {
                controlPlainLeftover += rawChunk
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Neočekivani izlaz iz RTSP čitanja")
    }

    private data class ParsedRtspMessage(
        val firstLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val cseq: String?,
        val rawBytes: ByteArray,
    )

    private data class ExtractionResult(
        val messages: List<ParsedRtspMessage>,
        val remaining: ByteArray,
    )

    private fun extractRtspMessages(buffer: ByteArray): ExtractionResult {
        val list = ArrayList<ParsedRtspMessage>()
        var offset = 0
        while (offset < buffer.size) {
            val headerEnd = findCrLfCrLf(buffer, offset)
            if (headerEnd < 0) break
            val headerText = String(buffer, offset, headerEnd - offset, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val firstLine = lines.firstOrNull().orEmpty().trim()
            val headers = LinkedHashMap<String, String>()
            var contentLength = 0
            var cseq: String? = null
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    } else if (key.equals("CSeq", ignoreCase = true)) {
                        cseq = value
                    }
                }
            }
            val bodyStart = headerEnd + 4
            val msgEnd = bodyStart + contentLength
            if (msgEnd > buffer.size) break
            val body = if (contentLength > 0) buffer.copyOfRange(bodyStart, msgEnd) else ByteArray(0)
            val raw = buffer.copyOfRange(offset, msgEnd)
            list.add(ParsedRtspMessage(firstLine, headers, body, cseq, raw))
            offset = msgEnd
        }
        val remaining = if (offset < buffer.size) buffer.copyOfRange(offset, buffer.size) else ByteArray(0)
        return ExtractionResult(list, remaining)
    }

    private fun findCrLfCrLf(bytes: ByteArray, start: Int): Int {
        for (i in start..(bytes.size - 4)) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun readUnsignedPort(value: Any?): Int =
        ((value as? Number)?.toInt() ?: 0) and 0xFFFF

    private fun resolveAddressWithScope(host: String, defaultIfaceName: String?): InetAddress {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        val percentIdx = clean.indexOf('%')
        val addrPart = if (percentIdx >= 0) clean.substring(0, percentIdx) else clean
        val scopePart = if (percentIdx >= 0) clean.substring(percentIdx + 1) else defaultIfaceName

        val baseAddr = InetAddress.getByName(addrPart)
        if (baseAddr is Inet6Address && baseAddr.isLinkLocalAddress && !scopePart.isNullOrBlank()) {
            val nif = runCatching { NetworkInterface.getByName(scopePart) }.getOrNull()
            if (nif != null) {
                return Inet6Address.getByAddress(null, baseAddr.address, nif)
            }
        }
        return baseAddr
    }
}
