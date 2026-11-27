package io.bimmergestalt.bmwcarplay.session

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.bimmergestalt.bmwcarplay.airplay.AirPlayInfoClient
import io.bimmergestalt.bmwcarplay.airplay.AirPlayMdnsBrowser
import io.bimmergestalt.bmwcarplay.airplay.CarPlayCtrlServer
import io.bimmergestalt.bmwcarplay.airplay.rtsp.CarPlayRtspSession
import io.bimmergestalt.bmwcarplay.bt.BluetoothEventMonitor
import io.bimmergestalt.bmwcarplay.bt.BluetoothProfileGuard
import io.bimmergestalt.bmwcarplay.bt.DeviceFilter
import io.bimmergestalt.bmwcarplay.bt.RfcommTransport
import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2SessionCoordinator
import io.bimmergestalt.bmwcarplay.iap2.link.Iap2LinkChannel
import io.bimmergestalt.bmwcarplay.net.CarWifiConnector
import io.bimmergestalt.bmwcarplay.service.ProbeForegroundService
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_AIRPLAY_INFO
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_BT_RFCOMM
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState.Companion.STEP_CARPLAY_VIDEO
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
 * Step 4 (0x5703 Wi-Fi Creds) -> Step 5 (BMW Wi-Fi Join) ->
 * Step 6 (mDNS _carplay-ctrl / _airplay + AirPlay GET /info).
 *
 * Strictly preserves the Bluetooth iAP2 RFCOMM session while joining Wi-Fi and
 * querying AirPlay (closing BT causes BMW HU_ENTRYNAV2 to drop CarPlay immediately).
 *
 * Foreground-aware Step 5: Android 14–17 / One UI blocks WifiNetworkSpecifier's
 * system confirmation sheet if requestNetwork() is called while MainActivity is in
 * the background (e.g. while pairing in Samsung Bluetooth Settings). If 0x5703 arrives
 * while backgrounded, we post a high-priority heads-up notification, watch passively
 * for manual Wi-Fi connection, and fire WifiNetworkSpecifier the instant MainActivity resumes.
 */
class ProbeOrchestrator(
    private val context: Context,
    private val prefs: ProbePrefs,
    private val logger: DiagnosticLogger,
) {
    private data class PendingWifiJoin(
        val gen: Long,
        val config: Iap2Parsers.WifiConfiguration,
    )

    private val _state = MutableStateFlow(ProbeSessionState())
    val state: StateFlow<ProbeSessionState> = _state.asStateFlow()

    private val sessionGen = AtomicLong(0)
    private val activityInForeground = AtomicBoolean(false)
    private val airPlayProbeRunning = AtomicBoolean(false)
    private val airPlayInfoSucceeded = AtomicBoolean(false)
    private val videoSessionRunning = AtomicBoolean(false)
    private val sessionFailedMfiLengths: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    private val btProfileGuard = BluetoothProfileGuard(context, logger)
    private val btEventMonitor = BluetoothEventMonitor(
        context = context,
        logger = logger,
        onBondStateChanged = { device, _, _, bondState ->
            if (device != null && bondState == BluetoothDevice.BOND_BONDED && isEligibleBmw(device)) {
                guardBmwProfilesAsync(device, "Nakon uparivanja", disconnect = false)
            }
        },
    )
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
    private val carPlayRtspSession = CarPlayRtspSession(logger)

    private val activeLinkChannel = AtomicReference<Iap2LinkChannel?>(null)
    private val activeCarNetInfo = AtomicReference<CarWifiConnector.CarNetworkInfo?>(null)
    private val lastWifiConfig = AtomicReference<Iap2Parsers.WifiConfiguration?>(null)
    private val pendingForegroundWifi = AtomicReference<PendingWifiJoin?>(null)

    @Volatile
    private var lastCarPlayStartSession: Iap2Parsers.CarPlayStartSession? = null

    /**
     * Called by [io.bimmergestalt.bmwcarplay.ui.MainActivity] from `onResume` / `onPause`.
     * Immediately triggers deferred foreground `WifiNetworkSpecifier` if 0x5703 arrived
     * while the user was still on Samsung Bluetooth Settings.
     */
    fun setActivityForeground(inForeground: Boolean) {
        val prev = activityInForeground.getAndSet(inForeground)
        if (!inForeground) return

        ProbeForegroundService.cancelWifiReadyAlert(context)
        val pending = pendingForegroundWifi.getAndSet(null)
        if (pending != null &&
            sessionGen.get() == pending.gen &&
            _state.value.isRunning &&
            activeCarNetInfo.get() == null
        ) {
            logger.log(
                "WIFI",
                "✓ Aplikacija je ponovno u prvom planu — odmah pokrećem WifiNetworkSpecifier za \"${pending.config.ssid}\"!",
            )
            launchWifiSpecifierConnect(pending.gen, pending.config)
        } else if (!prev && _state.value.isRunning) {
            // If Step 5 previously timed out (onUnavailable) while backgrounded, let user retry cleanly
            val st = _state.value
            val cfg = lastWifiConfig.get()
            if (cfg != null &&
                activeCarNetInfo.get() == null &&
                st.steps[STEP_WIFI_CONNECTED].status == StepStatus.ERROR
            ) {
                logger.log(
                    "WIFI",
                    "Povratak u aplikaciju nakon neuspjelog Wi-Fi pokušaja — automatski ponavljam WifiNetworkSpecifier za \"${cfg.ssid}\"...",
                )
                launchWifiSpecifierConnect(sessionGen.get(), cfg)
            }
        }
    }

    /**
     * Starts a complete Phase 1 diagnostic session from Step 1 (Bluetooth RFCOMM).
     */
    @Synchronized
    fun startFullProbe(): Boolean {
        stopInternal(logStop = false)
        val gen = sessionGen.incrementAndGet()
        airPlayProbeRunning.set(false)
        airPlayInfoSucceeded.set(false)
        sessionFailedMfiLengths.clear()
        lastCarPlayStartSession = null
        activeCarNetInfo.set(null)
        lastWifiConfig.set(null)
        pendingForegroundWifi.set(null)
        infoClient.clearCandidates()

        logger.fullHexEnabled = prefs.fullHexEnabled
        logger.startNewSession("BMW CarPlay Probe v${io.bimmergestalt.bmwcarplay.BuildConfig.VERSION_NAME} — Faza 2A (gen=$gen)")

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
            "Slušam ${if (rfcommMode.secure) "Secure" else "Insecure"} RFCOMM (...cafe / ...291a / ...0a77)",
        )

        btEventMonitor.start()
        findBondedBmw()?.let { guardBmwProfilesAsync(it, "Prije pokretanja RFCOMM-a", disconnect = true) }
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
        airPlayProbeRunning.set(false)
        airPlayInfoSucceeded.set(false)
        lastCarPlayStartSession = null
        activeCarNetInfo.set(null)
        pendingForegroundWifi.set(null)
        infoClient.clearCandidates()

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
        lastWifiConfig.set(cfg)

        _state.value = ProbeSessionState(
            isRunning = true,
            statusHeadlineHr = "Ručni test: spajam se na Wi-Fi \"$ssid\"...",
            detectedWifiSsid = ssid,
            detectedWifiPassphrase = passphrase,
            detectedWifiChannel = 0,
            detectedWifiSecurityHr = securityType.labelHr,
        )
            .updateStep(STEP_BT_RFCOMM, StepStatus.WAITING, "Preskočeno (ručni Wi-Fi test)")
            .updateStep(STEP_IAP2_LINK, StepStatus.WAITING, "Preskočeno (ručni Wi-Fi test)")
            .updateStep(STEP_IDENT_MFI, StepStatus.WAITING, "Preskočeno (ručni Wi-Fi test)")
            .updateStep(STEP_WIFI_CREDS, StepStatus.SUCCESS, "Ručni unos: $ssid")

        beginWifiStage(gen, cfg, forceImmediate = true)
    }

    /**
     * Triggered by the user tapping "Ponovi Wi-Fi spajanje" in MainActivity (guaranteed foreground).
     */
    fun retryWifiConnect() {
        val cfg = lastWifiConfig.get()
        if (cfg == null) {
            logger.log("WIFI", "Nema zaprimljenih BMW Wi-Fi podataka (0x5703) za ponovno spajanje")
            return
        }
        val gen = sessionGen.get()
        pendingForegroundWifi.set(null)
        ProbeForegroundService.cancelWifiReadyAlert(context)
        logger.log("WIFI", "Korisnik zatražio ponovno foreground spajanje na \"${cfg.ssid}\"...")
        launchWifiSpecifierConnect(gen, cfg)
    }

    /**
     * Triggered by the user tapping "Već sam spojen → Korak 6" after manually joining
     * BMW's hotspot in Android Wi-Fi settings (works even if Android redacted the SSID).
     */
    fun adoptCurrentWifiForStep6() {
        val cfg = lastWifiConfig.get()
        val targetSsid = cfg?.ssid ?: _state.value.detectedWifiSsid.orEmpty()
        val gen = sessionGen.get()
        pendingForegroundWifi.set(null)
        ProbeForegroundService.cancelWifiReadyAlert(context)
        wifiConnector.adoptObservedWifi(
            targetSsid = targetSsid,
            onConnected = { carNetInfo -> handleWifiConnected(gen, carNetInfo) },
            onLinkUpdated = { updated -> handleWifiLinkUpdated(gen, updated) },
            onUnavailable = { reasonHr ->
                if (sessionGen.get() != gen) return@adoptObservedWifi
                _state.update {
                    it.copy(statusHeadlineHr = reasonHr)
                        .updateStep(STEP_WIFI_CONNECTED, StepStatus.ERROR, reasonHr)
                }
            },
        )
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
        ProbeForegroundService.cancelWifiReadyAlert(context)
        pendingForegroundWifi.set(null)
        carPlayRtspSession.stop()
        videoSessionRunning.set(false)
        infoClient.stop()
        mdnsBrowser.stop()
        ctrlServer.stop()
        wifiConnector.release()
        activeLinkChannel.getAndSet(null)?.close()
        rfcommTransport.stop()
        btEventMonitor.stop()
        btProfileGuard.close()
        activeCarNetInfo.set(null)
        airPlayProbeRunning.set(false)
        airPlayInfoSucceeded.set(false)

        if (logStop) {
            logger.log("SESIJA", "=== Test zaustavljen — svi resursi i socketi oslobođeni ===")
        }
        _state.update { current ->
            val stoppedSteps = current.steps.map { step ->
                if (step.status == StepStatus.IN_PROGRESS) {
                    step.copy(status = StepStatus.WAITING, detailHr = "Zaustavljeno (${step.detailHr})")
                } else {
                    step
                }
            }
            current.copy(
                isRunning = false,
                statusHeadlineHr = "Zaustavljeno",
                waitingForForegroundWifi = false,
                steps = stoppedSteps,
            )
        }
    }

    private fun handleBtError(messageHr: String) {
        _state.update {
            it.updateStep(STEP_BT_RFCOMM, StepStatus.ERROR, messageHr)
                .copy(statusHeadlineHr = "BT greška: $messageHr")
        }
    }

    private fun isEligibleBmw(device: BluetoothDevice): Boolean = runCatching {
        DeviceFilter.isEligibleOutboundTarget(toCandidate(device), prefs.targetDevice?.address)
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun toCandidate(device: BluetoothDevice): DeviceFilter.DeviceCandidate {
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        return DeviceFilter.DeviceCandidate(
            name = runCatching { device.name }.getOrNull(),
            address = device.address,
            majorDeviceClass = btClass?.majorDeviceClass ?: 0,
            deviceClass = btClass?.deviceClass ?: 0,
            uuids = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull().orEmpty(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun findBondedBmw(): BluetoothDevice? = runCatching {
        val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        adapter?.bondedDevices?.firstOrNull { isEligibleBmw(it) }
    }.getOrNull()

    /**
     * Inspects SDP UUIDs and (optionally) disconnects ONLY HFP/A2DP/LE Audio profiles of the
     * target BMW so iDrive can switch from BT Phone/Audio mode to Wireless CarPlay.
     * Never touches watches/headsets and never disconnects ACL/RFCOMM.
     */
    private fun guardBmwProfilesAsync(device: BluetoothDevice, contextHr: String, disconnect: Boolean) {
        val gen = sessionGen.get()
        Thread({
            runCatching {
                if (sessionGen.get() != gen) return@runCatching
                btProfileGuard.logProfileState(device, contextHr)
                btProfileGuard.inspectSdpUuids(device, contextHr, fetchIfMissing = true)
                if (disconnect && sessionGen.get() == gen) {
                    btProfileGuard.disconnectAudioProfiles(device, contextHr)
                }
            }.onFailure { logger.log("BT-PROFILE", "Provjera profila nije uspjela: ${it.message}") }
        }, "bmw-profile-guard").apply { isDaemon = true }.start()
    }

    private fun handleBtPeerConnected(peer: RfcommTransport.ConnectedPeer) {
        val gen = sessionGen.get()
        // Never call HFP/A2DP disconnect mid-RFCOMM handshake (v0.1.4 log 12:55:18 showed it
        // tore down BMW's first RFCOMM/ACL socket 200ms later during CarPlay verification).
        peer.remoteDevice?.takeIf { isEligibleBmw(it) }?.let {
            guardBmwProfilesAsync(it, "Nakon RFCOMM veze", disconnect = false)
        }
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
                val prevCfg = lastWifiConfig.getAndSet(wifiCfg)
                val sameAlreadyActive = prevCfg == wifiCfg &&
                    (activeCarNetInfo.get() != null || wifiConnector.isSpecifierActive())

                _state.update {
                    it.copy(
                        detectedWifiSsid = wifiCfg.ssid,
                        detectedWifiPassphrase = wifiCfg.passphrase,
                        detectedWifiChannel = wifiCfg.channel,
                        detectedWifiSecurityHr = wifiCfg.securityType.labelHr,
                        statusHeadlineHr = "Primljen BMW Wi-Fi \"${wifiCfg.ssid}\" — spajam se (BT ostaje otvoren)...",
                    ).updateStep(
                        STEP_WIFI_CREDS,
                        StepStatus.SUCCESS,
                        "SSID: ${wifiCfg.ssid} (ch ${wifiCfg.channel}, ${wifiCfg.securityType.labelHr})",
                    )
                }

                if (sameAlreadyActive) {
                    logger.log("WIFI", "Ponovljeni 0x5703 za \"${wifiCfg.ssid}\" — Wi-Fi spajanje za iste podatke je već aktivno")
                } else {
                    beginWifiStage(gen, wifiCfg, forceImmediate = false)
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
                    ensureAirPlayProbeRunning(gen)
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

    private fun beginWifiStage(
        gen: Long,
        wifiCfg: Iap2Parsers.WifiConfiguration,
        forceImmediate: Boolean,
    ) {
        // Always start passive Wi-Fi observer right away so manual connection in One UI Wi-Fi panel
        // is detected & bound automatically regardless of foreground/background state.
        wifiConnector.startPassiveObserver(
            targetSsid = wifiCfg.ssid,
            onConnected = { carNetInfo -> handleWifiConnected(gen, carNetInfo) },
            onLinkUpdated = { updated -> handleWifiLinkUpdated(gen, updated) },
            onLost = { reasonHr -> handleWifiLost(gen, reasonHr) },
        )

        if (forceImmediate || activityInForeground.get()) {
            pendingForegroundWifi.set(null)
            ProbeForegroundService.cancelWifiReadyAlert(context)
            logger.log(
                "WIFI",
                "Aplikacija je u prvom planu — odmah pokrećem WifiNetworkSpecifier za \"${wifiCfg.ssid}\"...",
            )
            launchWifiSpecifierConnect(gen, wifiCfg)
        } else {
            // Android 14–17 / Samsung One UI blocks WifiNetworkSpecifier's system confirmation popup
            // when requestNetwork() is called while the Activity is in the background!
            pendingForegroundWifi.set(PendingWifiJoin(gen, wifiCfg))
            logger.log(
                "WIFI",
                "Aplikacija je trenutno u POZADINI (npr. Samsung BT postavke) — Android/One UI blokira Wi-Fi prozor iz pozadine! " +
                    "Šaljem hitnu obavijest; čim se vratiš u BMW CarPlay Probe, odmah pokrećem spajanje na \"${wifiCfg.ssid}\"!",
            )
            _state.update {
                it.copy(
                    statusHeadlineHr = "BMW Wi-Fi spreman! Vrati se u aplikaciju (BMW čeka ~60 s)",
                    waitingForForegroundWifi = true,
                ).updateStep(
                    STEP_WIFI_CONNECTED,
                    StepStatus.IN_PROGRESS,
                    "Vrati se u aplikaciju (ili dodirni obavijest) za spajanje na \"${wifiCfg.ssid}\"...",
                )
            }
            ProbeForegroundService.showWifiReadyAlert(context, wifiCfg.ssid)
        }
    }

    private fun launchWifiSpecifierConnect(gen: Long, wifiCfg: Iap2Parsers.WifiConfiguration) {
        if (sessionGen.get() != gen) return
        _state.update {
            it.copy(
                statusHeadlineHr = "Spajam se na BMW Wi-Fi \"${wifiCfg.ssid}\" — klikni Poveži u Samsung prozoru...",
                waitingForForegroundWifi = false,
            ).updateStep(
                STEP_WIFI_CONNECTED,
                StepStatus.IN_PROGRESS,
                "Spajam WifiNetworkSpecifier na \"${wifiCfg.ssid}\"...",
            )
        }

        wifiConnector.connect(
            config = wifiCfg,
            onConnected = { carNetInfo -> handleWifiConnected(gen, carNetInfo) },
            onLinkUpdated = { updated -> handleWifiLinkUpdated(gen, updated) },
            onUnavailable = { reasonHr ->
                if (sessionGen.get() != gen) return@connect
                if (activeCarNetInfo.get() != null) return@connect
                _state.update {
                    it.copy(
                        statusHeadlineHr = "Wi-Fi spajanje nije uspjelo — klikni Ponovi ili kopiraj lozinku",
                        waitingForForegroundWifi = false,
                    ).updateStep(STEP_WIFI_CONNECTED, StepStatus.ERROR, reasonHr)
                }
            },
            onLost = { reasonHr -> handleWifiLost(gen, reasonHr) },
        )
    }

    private fun handleWifiConnected(gen: Long, carNetInfo: CarWifiConnector.CarNetworkInfo) {
        if (sessionGen.get() != gen) return
        pendingForegroundWifi.set(null)
        ProbeForegroundService.cancelWifiReadyAlert(context)
        activeCarNetInfo.set(carNetInfo)

        _state.update {
            it.copy(
                statusHeadlineHr = "Spojen na BMW Wi-Fi — pokrećem mDNS i AirPlay /info...",
                waitingForForegroundWifi = false,
            ).updateStep(STEP_WIFI_CONNECTED, StepStatus.SUCCESS, formatIpLabel(carNetInfo))
                .updateStep(STEP_AIRPLAY_INFO, StepStatus.IN_PROGRESS, "mDNS _carplay-ctrl & _airplay + /info...")
        }

        // 1. Advertise _carplay-ctrl._tcp & _device-info._tcp on carNetwork
        ctrlServer.start(carNetInfo.network, prefs.phoneBluetoothMac)

        // 2. Browse _airplay._tcp strictly on carNetwork using live LinkProperties provider
        mdnsBrowser.start { activeCarNetInfo.get() ?: carNetInfo }

        // 3. Seed any IP/port already received via 0x4301
        lastCarPlayStartSession?.let { startSess ->
            val port = if (startSess.airPlayPort > 0) startSess.airPlayPort else AirPlayInfoClient.DEFAULT_AIRPLAY_PORT
            startSess.ipAddresses.forEach { ip ->
                infoClient.addCandidate(ip, port, "iAP2 0x4301")
            }
        }

        // 4. Launch RTSP GET /info retry loop bound strictly to carNetwork
        ensureAirPlayProbeRunning(gen)
    }

    private fun handleWifiLinkUpdated(gen: Long, updatedInfo: CarWifiConnector.CarNetworkInfo) {
        if (sessionGen.get() != gen) return
        activeCarNetInfo.set(updatedInfo)
        _state.update { curr ->
            if (curr.steps[STEP_WIFI_CONNECTED].status == StepStatus.SUCCESS) {
                curr.updateStep(STEP_WIFI_CONNECTED, StepStatus.SUCCESS, formatIpLabel(updatedInfo))
            } else {
                curr
            }
        }
        infoClient.triggerImmediateRetry()
    }

    private fun handleWifiLost(gen: Long, reasonHr: String) {
        if (sessionGen.get() != gen) return
        activeCarNetInfo.set(null)
        _state.update { curr ->
            if (curr.steps[STEP_AIRPLAY_INFO].status != StepStatus.SUCCESS) {
                curr.updateStep(STEP_WIFI_CONNECTED, StepStatus.ERROR, reasonHr)
            } else {
                curr
            }
        }
    }

    private fun formatIpLabel(info: CarWifiConnector.CarNetworkInfo): String = buildString {
        append(info.localIpv4 ?: info.localIpv6Addresses.firstOrNull() ?: "spojeno")
        info.gatewayIpv4?.let { append(" · GW $it") }
    }

    private fun ensureAirPlayProbeRunning(gen: Long) {
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
                    carNetInfoProvider = { activeCarNetInfo.get() },
                    onSuccess = { infoResult ->
                        if (sessionGen.get() != gen) return@probeWithRetries
                        airPlayInfoSucceeded.set(true)
                        val verdict = extractVerdictFromInfoOrMdns(infoResult)
                        _state.update {
                            it.copy(
                                statusHeadlineHr = "AirPlay /info 200 OK — pokrećem Fazu 2A (CarPlay Video Stream)...",
                                phase2VerdictHr = verdict,
                            ).updateStep(
                                STEP_AIRPLAY_INFO,
                                StepStatus.SUCCESS,
                                "200 OK (${infoResult.host}:${infoResult.port}, ${infoResult.rawBody.size} B)",
                            ).updateStep(
                                STEP_CARPLAY_VIDEO,
                                StepStatus.IN_PROGRESS,
                                "Pokrećem HomeKit Pairing i H.264 stream...",
                            )
                        }
                        startPhase2VideoSession(gen, infoResult)
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

    private fun startPhase2VideoSession(gen: Long, infoResult: AirPlayInfoClient.InfoResult) {
        if (!videoSessionRunning.compareAndSet(false, true)) return
        val carNet = activeCarNetInfo.get()
        if (carNet == null) {
            videoSessionRunning.set(false)
            return
        }

        val requiresPairing = resolveRequiresHomeKitPairing(infoResult)
        val initialKeepAliveStats = (infoResult.parsedRoot["keepAliveSendStatsAsBody"] as? Boolean) ?: true
        val identity = prefs.loadOrCreateControllerIdentity()

        Thread({
            try {
                var activeDisplayLabel = ""
                carPlayRtspSession.runSession(
                    carNetInfo = carNet,
                    hostStr = infoResult.host,
                    port = infoResult.port,
                    phoneBluetoothMac = prefs.phoneBluetoothMac,
                    identity = identity,
                    requiresPairing = requiresPairing,
                    initialKeepAliveStatsAsBody = initialKeepAliveStats,
                    onStageUpdate = { stageHr ->
                        if (sessionGen.get() != gen) return@runSession
                        _state.update {
                            it.copy(statusHeadlineHr = "Faza 2A: $stageHr")
                                .updateStep(STEP_CARPLAY_VIDEO, StepStatus.IN_PROGRESS, stageHr)
                        }
                    },
                    onVideoStarted = { display ->
                        if (sessionGen.get() != gen) return@runSession
                        activeDisplayLabel = "${display.width}×${display.height} @ ${display.maxFps}fps (${display.sourceLabelHr})"
                        _state.update {
                            it.copy(
                                statusHeadlineHr = "✓ SLIKA AKTIVNA U BMW-u! $activeDisplayLabel",
                            ).updateStep(
                                STEP_CARPLAY_VIDEO,
                                StepStatus.SUCCESS,
                                "AKTIVAN · $activeDisplayLabel",
                            )
                        }
                    },
                    onStatsUpdate = { framesSent, fps ->
                        if (sessionGen.get() != gen) return@runSession
                        if (activeDisplayLabel.isNotEmpty()) {
                            _state.update {
                                it.updateStep(
                                    STEP_CARPLAY_VIDEO,
                                    StepStatus.SUCCESS,
                                    "$activeDisplayLabel · ${fps} fps · $framesSent sličica",
                                )
                            }
                        }
                    },
                    onFailure = { reasonHr ->
                        if (sessionGen.get() != gen) return@runSession
                        _state.update {
                            it.copy(statusHeadlineHr = "Faza 2A greška: $reasonHr")
                                .updateStep(STEP_CARPLAY_VIDEO, StepStatus.ERROR, reasonHr)
                        }
                    },
                    onEnded = { reasonHr ->
                        if (sessionGen.get() != gen) return@runSession
                        _state.update {
                            it.copy(statusHeadlineHr = "CarPlay video završen: $reasonHr")
                                .updateStep(STEP_CARPLAY_VIDEO, StepStatus.ERROR, reasonHr)
                        }
                    },
                )
            } finally {
                videoSessionRunning.set(false)
            }
        }, "carplay-video-session-gen$gen").apply {
            isDaemon = true
            start()
        }
    }

    private fun resolveRequiresHomeKitPairing(infoResult: AirPlayInfoClient.InfoResult): Boolean {
        val fromMdns = mdnsBrowser.currentServices().firstOrNull()?.featuresAnalysis
        if (fromMdns != null) return fromMdns.hasBit38HkPairing
        val featuresVal = infoResult.parsedRoot["features"] as? Number
        if (featuresVal != null) {
            val analysis = io.bimmergestalt.bmwcarplay.airplay.AirPlayFeatures
                .analyze("0x${featuresVal.toLong().toULong().toString(16)}")
            if (analysis != null) return analysis.hasBit38HkPairing
        }
        return true
    }

    private fun handleCarCtrlInvite(peerIp: String) {
        logger.log(
            "AIRPLAY-INFO",
            "BMW poslao /ctrl-int/1/connect s $peerIp — dodajem kao kandidata (:7000) i odmah okidam /info pokušaj!",
        )
        infoClient.addCandidate(peerIp, AirPlayInfoClient.DEFAULT_AIRPLAY_PORT, "BMW /ctrl-int/1/connect")
        if (activeCarNetInfo.get() != null && !airPlayInfoSucceeded.get()) {
            ensureAirPlayProbeRunning(sessionGen.get())
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
        if (activeCarNetInfo.get() != null && !airPlayInfoSucceeded.get()) {
            ensureAirPlayProbeRunning(sessionGen.get())
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
