package io.bimmergestalt.bmwcarplay.session

import android.content.Context
import io.bimmergestalt.bmwcarplay.airplay.AirPlayInfoClient
import io.bimmergestalt.bmwcarplay.airplay.AirPlayMdnsBrowser
import io.bimmergestalt.bmwcarplay.airplay.CarPlayCtrlServer
import io.bimmergestalt.bmwcarplay.bt.BluetoothEventMonitor
import io.bimmergestalt.bmwcarplay.bt.RfcommTransport
import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2SessionCoordinator
import io.bimmergestalt.bmwcarplay.iap2.link.Iap2LinkChannel
import io.bimmergestalt.bmwcarplay.net.CarWifiConnector
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_AIRPLAY_INFO
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_BT_RFCOMM
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_IAP2_LINK
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_IDENT_MFI
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_WIFI_CONNECTED
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_WIFI_CREDS
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.StepStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Master orchestrator for Phase 1 Wireless CarPlay diagnostics:
 * Step 1 (BT RFCOMM) -> Step 2 (iAP2 Link) -> Step 3 (MFi & Ident) ->
 * Step 4 (0x5703 Wi-Fi Creds) -> Step 5 (BMW 5 GHz Wi-Fi Join) ->
 * Step 6 (mDNS _carplay-ctrl / _airplay + AirPlay GET /info).
 *
 * Strictly preserves the Bluetooth iAP2 RFCOMM session while joining Wi-Fi and
 * querying AirPlay (closing BT causes BMW HU_ENTRYNAV2 to drop CarPlay immediately).
 */
class ProbeOrchestrator(
    private val context: Context,
    private val prefs: ProbePrefs,
    private val logger: DiagnosticLogger,
) {
    private val _state = MutableStateFlow(ProbeSessionState())
    val state: StateFlow<ProbeSessionState> = _state.asStateFlow()

    private val sessionGen = AtomicLong(0)
    private val wifiJoinStarted = AtomicBoolean(false)
    private val airPlayProbeRunning = AtomicBoolean(false)
    private val airPlayInfoSucceeded = AtomicBoolean(false)
    private val sessionFailedMfiLengths: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    private val btEventMonitor = BluetoothEventMonitor(context, logger)
    private val rfcommTransport = RfcommTransport(
        context = context,
        logger = logger,
        onConnected = ::handleBtPeerConnected,
        onError = ::handleBtError,
    )
    private val wifiConnector = CarWifiConnector(context, logger)
    private val ctrlServer = CarPlayCtrlServer(context, logger, onCarInvite = ::handleCarCtrlInvite)
    private val mdnsBrowser = AirPlayMdnsBrowser(context, logger, onEndpointDiscovered = ::handleMdnsAirPlayDiscovered)
    private val infoClient = AirPlayInfoClient(logger)

    private val activeLinkChannel = AtomicReference<Iap2LinkChannel?>(null)
    private val activeCarNetInfo = AtomicReference<CarWifiConnector.CarNetworkInfo?>(null)

    @Volatile
    private var lastCarPlayStartSession: Iap2Parsers.CarPlayStartSession? = null

    /**
     * Starts a complete Phase 1 diagnostic session from Step 1 (Bluetooth RFCOMM).
     */
    @Synchronized
    fun startFullProbe(): Boolean {
        stopInternal(logStop = false)
        val gen = sessionGen.incrementAndGet()
        wifiJoinStarted.set(false)
        airPlayProbeRunning.set(false)
        airPlayInfoSucceeded.set(false)
        sessionFailedMfiLengths.clear()
        lastCarPlayStartSession = null
        activeCarNetInfo.set(null)

        logger.fullHexEnabled = prefs.fullHexEnabled
        logger.startNewSession("BMW CarPlay Probe v${io.bimmergestalt.bmwcarplay.BuildConfig.VERSION_NAME} — Faza 1 (gen=$gen)")

        val phoneMac = prefs.phoneBluetoothMac
        val target = prefs.targetDevice
        val rfcommMode = prefs.rfcommMode
        val mfiMode = prefs.mfiChallengeMode
        val outbound = prefs.outboundClientEnabled

        logger.log(
            "POSTAVKE",
            "BT MAC mobitela=$phoneMac | Ciljani auto=${target?.let { "${it.name} (${it.address})" } ?: "Automatski BMW*"} | " +
                "RFCOMM=${rfcommMode.labelHr} | MFi=${mfiMode.labelHr} | Odlazni klijent=$outbound | Puni HEX=${prefs.fullHexEnabled}",
        )

        _state.value = ProbeSessionState(
            isRunning = true,
            statusHeadlineHr = "Čekam Bluetooth RFCOMM vezu BMW-a...",
        ).updateStep(
            STEP_BT_RFCOMM,
            StepStatus.IN_PROGRESS,
            "Slušam ${if (rfcommMode.secure) "Secure" else "Insecure"} RFCOMM (...decacafe)",
        )

        btEventMonitor.start()
        val started = rfcommTransport.start(
            useSecureRfcomm = rfcommMode.secure,
            enableOutboundClient = outbound,
            selectedTargetMac = target?.address,
        )
        if (!started) {
            _state.update {
                it.copy(
                    isRunning = false,
                    statusHeadlineHr = "Pokretanje Bluetooth RFCOMM-a nije uspjelo",
                ).updateStep(STEP_BT_RFCOMM, StepStatus.ERROR, "Provjeri Bluetooth i dozvole")
            }
            btEventMonitor.stop()
            return false
        }
        return true
    }

    /**
     * Manual Wi-Fi + mDNS + AirPlay /info test bypassing Bluetooth/iAP2 Steps 1-3.
     * Useful when testing BMW Wi-Fi credentials already extracted earlier.
     */
    @Synchronized
    fun startManualWifiTest(
        ssid: String,
        passphrase: String,
        securityType: Iap2Parsers.WifiSecurityType,
    ) {
        stopInternal(logStop = false)
        val gen = sessionGen.incrementAndGet()
        wifiJoinStarted.set(false)
        airPlayProbeRunning.set(false)
        airPlayInfoSucceeded.set(false)
        lastCarPlayStartSession = null
        activeCarNetInfo.set(null)

        logger.fullHexEnabled = prefs.fullHexEnabled
        logger.startNewSession("BMW CarPlay Probe v${io.bimmergestalt.bmwcarplay.BuildConfig.VERSION_NAME} — Ručni Wi-Fi / AirPlay test (gen=$gen)")
        logger.log(
            "WIFI",
            "Ručni test spajanja: SSID=\"$ssid\", sigurnost=${securityType.labelHr}, BT MAC mobitela=${prefs.phoneBluetoothMac}",
        )

        val cfg = Iap2Parsers.WifiConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            securityType = securityType,
            securityRawCode = securityType.code,
            channel = 0,
        )

        _state.value = ProbeSessionState(
            isRunning = true,
            statusHeadlineHr = "Ručni test: spajam se na Wi-Fi \"$ssid\"...",
            detectedWifiSsid = ssid,
        )
            .updateStep(STEP_BT_RFCOMM, StepStatus.WAITING, "Preskočeno (ručni Wi-Fi test)")
            .updateStep(STEP_IAP2_LINK, StepStatus.WAITING, "Preskočeno (ručni Wi-Fi test)")
            .updateStep(STEP_IDENT_MFI, StepStatus.WAITING, "Preskočeno (ručni Wi-Fi test)")
            .updateStep(STEP_WIFI_CREDS, StepStatus.SUCCESS, "Ručni unos: $ssid")

        beginWifiAndAirPlayStages(gen, cfg)
    }

    /**
     * Immediately stops all active sockets, mDNS registrations/browsers, Wi-Fi request,
     * and AirPlay probe threads with zero leftover background work.
     */
    @Synchronized
    fun stop() {
        stopInternal(logStop = true)
    }

    private fun stopInternal(logStop: Boolean) {
        sessionGen.incrementAndGet()
        infoClient.stop()
        mdnsBrowser.stop()
        ctrlServer.stop()
        wifiConnector.release()
        activeLinkChannel.getAndSet(null)?.close()
        rfcommTransport.stop()
        btEventMonitor.stop()
        activeCarNetInfo.set(null)
        wifiJoinStarted.set(false)
        airPlayProbeRunning.set(false)
        airPlayInfoSucceeded.set(false)

        if (logStop) {
            logger.log("SESIJA", "=== Test zaustavljen — svi resursi i socketi oslobođeni ===")
        }
        _state.update {
            it.copy(
                isRunning = false,
                statusHeadlineHr = "Zaustavljeno",
            )
        }
    }

    private fun handleBtError(messageHr: String) {
        _state.update {
            it.updateStep(STEP_BT_RFCOMM, StepStatus.ERROR, messageHr)
                .copy(statusHeadlineHr = "BT greška: $messageHr")
        }
    }

    private fun handleBtPeerConnected(peer: RfcommTransport.ConnectedPeer) {
        val gen = sessionGen.get()
        val mfiDone = AtomicBoolean(false)
        val identDone = AtomicBoolean(false)
        val mfiLabelRef = AtomicReference("0xAA05 OK")
        val serialRef = AtomicReference<String?>(null)

        _state.update {
            it.copy(statusHeadlineHr = "BT spojen s ${peer.deviceName} — pokrećem iAP2 Link...")
                .updateStep(STEP_BT_RFCOMM, StepStatus.SUCCESS, "${peer.deviceName} (${peer.deviceAddress})")
                .updateStep(STEP_IAP2_LINK, StepStatus.IN_PROGRESS, "Pregovaram FF55/FF5A...")
                .updateStep(STEP_IDENT_MFI, StepStatus.WAITING, "Čeka iAP2 Link")
        }

        val linkChannel = Iap2LinkChannel(
            stream = peer.stream,
            logger = logger,
            onLinkReady = { controlSid, lsp ->
                if (sessionGen.get() != gen) return@Iap2LinkChannel
                val ctrlVer = lsp?.sessions?.firstOrNull { it.id == controlSid }?.version ?: 1
                val detail = "NORMAL · Control SID=$controlSid (v$ctrlVer) · maxLen=${lsp?.maxPacketLength ?: "?"}"
                _state.update {
                    it.copy(statusHeadlineHr = "iAP2 Link aktivan — pokrećem MFi (0xAA00) i identifikaciju...")
                        .updateStep(STEP_IAP2_LINK, StepStatus.SUCCESS, detail)
                        .updateStep(STEP_IDENT_MFI, StepStatus.IN_PROGRESS, "0xAA00 MFi → 0x1D00 Ident...")
                }
            },
            onLinkClosed = { reasonHr ->
                if (sessionGen.get() != gen) return@Iap2LinkChannel
                logger.log("SESIJA", "BT/iAP2 link zatvoren: $reasonHr")
                // Mark step 2 error only if link never succeeded
                _state.update { curr ->
                    val linkStep = curr.steps[STEP_IAP2_LINK]
                    if (linkStep.status == StepStatus.IN_PROGRESS) {
                        curr.updateStep(STEP_IAP2_LINK, StepStatus.ERROR, reasonHr)
                    } else {
                        curr
                    }
                }
            },
        )
        activeLinkChannel.getAndSet(linkChannel)?.close()
        linkChannel.start()

        val coordinator = Iap2SessionCoordinator(
            channel = linkChannel,
            prefs = prefs,
            logger = logger,
            phoneBluetoothMac = prefs.phoneBluetoothMac,
            peerBluetoothAddress = peer.deviceAddress,
            mfiChallengeMode = prefs.mfiChallengeMode,
            sessionFailedMfiLengths = sessionFailedMfiLengths,
            onMfiSucceeded = { serial, challengeLen ->
                if (sessionGen.get() != gen) return@Iap2SessionCoordinator
                val lenLabel = if (challengeLen > 0) "${challengeLen}B OK" else "0xAA05 poslan"
                mfiLabelRef.set(lenLabel)
                if (!serial.isNullOrBlank() && serialRef.get().isNullOrBlank()) {
                    serialRef.set(serial)
                }
                mfiDone.set(true)
                if (identDone.get()) {
                    val sn = serialRef.get() ?: "?"
                    _state.update {
                        it.copy(statusHeadlineHr = "MFi & Ident OK ($sn) — tražim BMW Wi-Fi (0x5702)...")
                            .updateStep(STEP_IDENT_MFI, StepStatus.SUCCESS, "SN: $sn · MFi $lenLabel")
                            .updateStep(STEP_WIFI_CREDS, StepStatus.IN_PROGRESS, "Čekam 0x5703 Wi-Fi podatke...")
                    }
                } else {
                    _state.update {
                        it.copy(statusHeadlineHr = "MFi OK ($lenLabel) — čitam identifikaciju auta (0x1D01)...")
                            .updateStep(
                                STEP_IDENT_MFI,
                                StepStatus.IN_PROGRESS,
                                "MFi $lenLabel — čekam 0x1D01 identifikaciju...",
                            )
                    }
                }
            },
            onIdentification = { ident ->
                if (sessionGen.get() != gen) return@Iap2SessionCoordinator
                val serial = ident.serialNumber ?: ident.name ?: "nepoznato"
                serialRef.set(serial)
                identDone.set(true)
                if (mfiDone.get()) {
                    val lenLabel = mfiLabelRef.get()
                    _state.update {
                        it.copy(
                            detectedSerial = ident.serialNumber,
                            statusHeadlineHr = "Auto identificiran ($serial) — tražim BMW Wi-Fi (0x5702)...",
                        ).updateStep(
                            STEP_IDENT_MFI,
                            StepStatus.SUCCESS,
                            "SN: $serial · MFi $lenLabel",
                        ).updateStep(
                            STEP_WIFI_CREDS,
                            StepStatus.IN_PROGRESS,
                            "Poslan pozdrav + 0x5702 — čekam 0x5703...",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            detectedSerial = ident.serialNumber,
                            statusHeadlineHr = "Identificiran auto ($serial) — MFi autentifikacija...",
                        ).updateStep(
                            STEP_IDENT_MFI,
                            StepStatus.IN_PROGRESS,
                            "Auto: ${ident.name ?: "?"} | SN: $serial — MFi...",
                        )
                    }
                }
            },
            onWifiCredentials = { wifiCfg ->
                if (sessionGen.get() != gen) return@Iap2SessionCoordinator
                _state.update {
                    it.copy(
                        detectedWifiSsid = wifiCfg.ssid,
                        statusHeadlineHr = "Primljen BMW Wi-Fi \"${wifiCfg.ssid}\" — spajam se (BT ostaje otvoren)...",
                    ).updateStep(
                        STEP_WIFI_CREDS,
                        StepStatus.SUCCESS,
                        "SSID: ${wifiCfg.ssid} (ch ${wifiCfg.channel}, ${wifiCfg.securityType.labelHr})",
                    )
                }
                // Immediately begin Wi-Fi join without closing BT iAP2!
                if (wifiJoinStarted.compareAndSet(false, true)) {
                    beginWifiAndAirPlayStages(gen, wifiCfg)
                } else {
                    logger.log("WIFI", "Ponovljeni 0x5703 za \"${wifiCfg.ssid}\" — Wi-Fi spajanje je već pokrenuto")
                }
            },
            onStartCarPlaySession = { startSess ->
                if (sessionGen.get() != gen) return@Iap2SessionCoordinator
                lastCarPlayStartSession = startSess
                val port = if (startSess.airPlayPort > 0) startSess.airPlayPort else AirPlayInfoClient.DEFAULT_AIRPLAY_PORT
                for (ip in startSess.ipAddresses) {
                    infoClient.addCandidate(ip, port, "iAP2 0x4301")
                }
                val carNet = activeCarNetInfo.get()
                if (carNet != null && !airPlayInfoSucceeded.get()) {
                    logger.log("AIRPLAY-INFO", "Primljen 0x4301 (${startSess.ipAddresses}:$port) — odmah pokrećem/ponavljam /info upit!")
                    ensureAirPlayProbeRunning(gen, carNet)
                }
            },
            onError = { stepIndex, msgHr ->
                if (sessionGen.get() != gen) return@Iap2SessionCoordinator
                _state.update {
                    it.updateStep(stepIndex, StepStatus.ERROR, msgHr)
                        .copy(statusHeadlineHr = "Greška: $msgHr")
                }
            },
        )

        Thread({ coordinator.run() }, "iap2-csm-gen$gen").apply {
            isDaemon = true
            start()
        }
    }

    private fun beginWifiAndAirPlayStages(gen: Long, wifiCfg: Iap2Parsers.WifiConfiguration) {
        _state.update {
            it.updateStep(
                STEP_WIFI_CONNECTED,
                StepStatus.IN_PROGRESS,
                "Spajam WifiNetworkSpecifier na \"${wifiCfg.ssid}\"...",
            )
        }

        wifiConnector.connect(
            config = wifiCfg,
            onConnected = { carNetInfo ->
                if (sessionGen.get() != gen) return@connect
                activeCarNetInfo.set(carNetInfo)
                val ipLabel = buildString {
                    append(carNetInfo.localIpv4 ?: carNetInfo.localIpv6Addresses.firstOrNull() ?: "spojeno")
                    carNetInfo.gatewayIpv4?.let { append(" · GW $it") }
                }
                _state.update {
                    it.copy(statusHeadlineHr = "Spojen na BMW Wi-Fi — pokrećem mDNS i AirPlay /info...")
                        .updateStep(STEP_WIFI_CONNECTED, StepStatus.SUCCESS, ipLabel)
                        .updateStep(STEP_AIRPLAY_INFO, StepStatus.IN_PROGRESS, "mDNS _carplay-ctrl & _airplay + /info...")
                }

                // 1. Advertise _carplay-ctrl._tcp & _device-info._tcp on carNetwork
                ctrlServer.start(carNetInfo.network, prefs.phoneBluetoothMac)

                // 2. Browse _airplay._tcp strictly on carNetwork
                mdnsBrowser.start(carNetInfo)

                // 3. Seed any IP/port already received via 0x4301
                lastCarPlayStartSession?.let { startSess ->
                    val port = if (startSess.airPlayPort > 0) startSess.airPlayPort else AirPlayInfoClient.DEFAULT_AIRPLAY_PORT
                    startSess.ipAddresses.forEach { ip ->
                        infoClient.addCandidate(ip, port, "iAP2 0x4301")
                    }
                }

                // 4. Launch RTSP GET /info retry loop bound strictly to carNetwork
                ensureAirPlayProbeRunning(gen, carNetInfo)
            },
            onUnavailable = { reasonHr ->
                if (sessionGen.get() != gen) return@connect
                wifiJoinStarted.set(false)
                _state.update {
                    it.copy(statusHeadlineHr = "Wi-Fi spajanje nije uspjelo: $reasonHr")
                        .updateStep(STEP_WIFI_CONNECTED, StepStatus.ERROR, reasonHr)
                }
            },
            onLost = { reasonHr ->
                if (sessionGen.get() != gen) return@connect
                activeCarNetInfo.set(null)
                _state.update { curr ->
                    if (curr.steps[STEP_AIRPLAY_INFO].status != StepStatus.SUCCESS) {
                        curr.updateStep(STEP_WIFI_CONNECTED, StepStatus.ERROR, reasonHr)
                    } else {
                        curr
                    }
                }
            },
        )
    }

    private fun ensureAirPlayProbeRunning(gen: Long, carNetInfo: CarWifiConnector.CarNetworkInfo) {
        if (airPlayInfoSucceeded.get()) return
        if (!airPlayProbeRunning.compareAndSet(false, true)) {
            infoClient.triggerImmediateRetry()
            return
        }
        _state.update {
            it.updateStep(STEP_AIRPLAY_INFO, StepStatus.IN_PROGRESS, "Tražim _airplay._tcp i šaljem GET /info...")
        }
        Thread({
            try {
                infoClient.probeWithRetries(
                    carNetInfo = carNetInfo,
                    onSuccess = { infoResult ->
                        if (sessionGen.get() != gen) return@probeWithRetries
                        airPlayInfoSucceeded.set(true)
                        val verdict = extractVerdictFromInfoOrMdns(infoResult)
                        _state.update {
                            it.copy(
                                statusHeadlineHr = "✓ Faza 1 KOMPLETNO USPJELA! AirPlay /info 200 OK",
                                phase2VerdictHr = verdict,
                            ).updateStep(
                                STEP_AIRPLAY_INFO,
                                StepStatus.SUCCESS,
                                "200 OK (${infoResult.host}:${infoResult.port}, ${infoResult.rawBody.size} B)",
                            )
                        }
                    },
                    onFailure = { reasonHr ->
                        if (sessionGen.get() != gen) return@probeWithRetries
                        _state.update {
                            it.copy(statusHeadlineHr = "AirPlay /info nije uspio — vidi log za detalje")
                                .updateStep(STEP_AIRPLAY_INFO, StepStatus.ERROR, reasonHr)
                        }
                    },
                )
            } finally {
                airPlayProbeRunning.set(false)
            }
        }, "airplay-info-gen$gen").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleCarCtrlInvite(peerIp: String) {
        logger.log(
            "AIRPLAY-INFO",
            "BMW poslao /ctrl-int/1/connect s $peerIp — dodajem kao kandidata (:7000) i odmah okidam /info pokušaj!",
        )
        infoClient.addCandidate(peerIp, AirPlayInfoClient.DEFAULT_AIRPLAY_PORT, "BMW /ctrl-int/1/connect")
        val carNet = activeCarNetInfo.get()
        if (carNet != null && !airPlayInfoSucceeded.get()) {
            ensureAirPlayProbeRunning(sessionGen.get(), carNet)
        } else {
            infoClient.triggerImmediateRetry()
        }
    }

    private fun handleMdnsAirPlayDiscovered(service: AirPlayMdnsBrowser.DiscoveredAirPlayService) {
        service.featuresAnalysis?.let { analysis ->
            _state.update { it.copy(phase2VerdictHr = analysis.phase2VerdictHr()) }
        }
        for (addr in service.addresses) {
            infoClient.addCandidate(addr, service.port, "mDNS _airplay._tcp (${service.serviceName})")
        }
        val carNet = activeCarNetInfo.get()
        if (carNet != null && !airPlayInfoSucceeded.get()) {
            ensureAirPlayProbeRunning(sessionGen.get(), carNet)
        } else {
            infoClient.triggerImmediateRetry()
        }
    }

    private fun extractVerdictFromInfoOrMdns(result: AirPlayInfoClient.InfoResult): String? {
        val fromMdns = mdnsBrowser.currentServices().firstOrNull()?.featuresAnalysis?.phase2VerdictHr()
        if (fromMdns != null) return fromMdns
        val featuresVal = result.parsedRoot["features"] as? Number ?: return null
        return io.bimmergestalt.bmwcarplay.airplay.AirPlayFeatures
            .analyze("0x${featuresVal.toLong().toULong().toString(16)}")
            ?.phase2VerdictHr()
    }
}
