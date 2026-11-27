package io.bimmergestalt.bmwcarplay.iap2.csm

import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.iap2.link.Iap2LinkChannel
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * Executes Phase 1 iAP2 Control Session protocol over an established [Iap2LinkChannel]:
 *
 * 1. One-way MFi Authentication FIRST (0xAA00 -> 0xAA01 X.509 cert -> 0xAA02 challenge with
 *    adaptive 32B/3s -> 20B/5s -> 16B/3s fallback + reconnect/per-car memory -> 0xAA03 -> 0xAA05).
 *    CRITICAL for BMW HU_ENTRYNAV2 (Alpine EntryEvo): negotiates Control Session v1, where
 *    Apple iAP2 spec strictly requires Authentication BEFORE Identification!
 *    (Includes automatic fallback to Ident-first if a non-standard v2 peer stays silent on 0xAA00.)
 * 2. Identification SECOND (0x1D00 -> 0x1D01 -> 0x1D02), also responding if car requests phone 0x1D01.
 * 3. Immediate iPhone greeting burst (0x4E0C, 0x4E0A, 0x4E09, 0x4E0B, 0x4E0D, 0x4E0E with real BT MAC),
 *    followed immediately by 0x5702 (ALWAYS sent even if omitted in 0x1D01) and 0x4300 (real BT MAC).
 * 4. Keeps the iAP2 Bluetooth session alive for the ENTIRE duration (never closes BT
 *    while joining Wi-Fi or probing AirPlay!) and continuously decodes 0x5703, late 0x4301,
 *    0x4E0D, 0x6800, and full HEX/TLV dumps of any unknown 0xXXXX messages.
 */
class Iap2SessionCoordinator(
    private val channel: Iap2LinkChannel,
    private val prefs: ProbePrefs,
    private val logger: DiagnosticLogger,
    private val phoneBluetoothMac: String,
    private val peerBluetoothAddress: String,
    private val mfiChallengeMode: ProbePrefs.MfiChallengeMode,
    private val sessionFailedMfiLengths: MutableSet<Int>,
    private val onIdentification: (Iap2Parsers.IdentificationInfo) -> Unit,
    private val onMfiSucceeded: (serialNumber: String?, challengeBytes: Int) -> Unit,
    private val onWifiCredentials: (Iap2Parsers.WifiConfiguration) -> Unit,
    private val onStartCarPlaySession: (Iap2Parsers.CarPlayStartSession) -> Unit,
    private val onError: (stepIndex: Int, messageHr: String) -> Unit,
) {
    companion object {
        private const val LINK_READY_TIMEOUT_MS = 45_000L
        private const val AUTH_FIRST_CERT_TIMEOUT_MS = 6_000L
        private const val CERT_TIMEOUT_MS = 10_000L
        private const val IDENT_TIMEOUT_MS = 15_000L
        private const val WIFI_RETRY_WAIT_MS = 2_500L

        /** Default fallback order for MFi 3.0 / unknown chips: 32B -> 20B -> 16B. */
        private val AUTO_MFI_ATTEMPTS = listOf(
            32 to 3_000L,
            20 to 5_000L,
            16 to 3_000L,
        )

        /** Confirmed MFi 2.0C (CP2.0C, SHA1withRSA/RSA) order: 20B first -> 32B -> 16B. */
        private val AUTO_MFI20_ATTEMPTS = listOf(
            20 to 5_000L,
            32 to 3_000L,
            16 to 3_000L,
        )
    }

    private val random = SecureRandom()
    private val reassembler = Iap2CsmCodec.CsmReassembler()
    private val parsedQueue = ArrayDeque<Iap2CsmCodec.CsmFrame>()
    private val deferredQueue = ArrayDeque<Iap2CsmCodec.CsmFrame>()

    @Volatile
    private var wifiReceived = false

    private var authSerialFromAA06: String? = null

    fun run() {
        logger.log("IAP2-CSM", "Čekam uspostavu iAP2 Link sloja (FF55/FF5A)...")
        if (!channel.awaitReady(LINK_READY_TIMEOUT_MS)) {
            val msg = if (!channel.isAlive) {
                "RFCOMM/iAP2 veza zatvorena prije uspostave Link sloja"
            } else {
                "iAP2 Link sloj nije uspostavljen unutar ${LINK_READY_TIMEOUT_MS / 1000}s"
            }
            logger.log("IAP2-CSM", "GREŠKA: $msg")
            onError(1, msg)
            return
        }

        val controlVer = channel.controlSessionVersion()
        logger.log(
            "IAP2-CSM",
            "✓ iAP2 Link spreman (Control SID=${channel.controlSessionId()} v$controlVer, maxPayload=${channel.maxControlPayloadLength()} B)",
        )

        // --- Step 3A: One-way MFi Authentication FIRST (Apple iAP2 Control v1 mandatory order!) ---
        logger.log(
            "IAP2-CSM",
            "Pokrećem MFi autentifikaciju PRVO (Control Session v$controlVer → 0xAA00 prije 0x1D00)...",
        )
        sendCsm(Iap2MessageIds.REQUEST_AUTH_CERTIFICATE)
        var certFrame = waitForCertificate(AUTH_FIRST_CERT_TIMEOUT_MS)
        val identInfo: Iap2Parsers.IdentificationInfo?
        val succeededChallengeLen: Int?

        if (certFrame != null) {
            // Standard Apple iAP2 v1 + v2 path: Auth FIRST -> Ident SECOND
            val certInfo = Iap2Parsers.parseAuthenticationCertificate(certFrame.body)
            logger.log("IAP2-CSM", certInfo.formatReportHr())

            succeededChallengeLen = runMfiChallengeFlow(
                certInfo = certInfo,
                lookupKeys = listOfNotNull(authSerialFromAA06, peerBluetoothAddress),
            )
            if (!channel.isAlive) {
                val msg = "RFCOMM/iAP2 veza prekinuta tijekom MFi izazova (ako se BMW ponovno spoji, automatski isprobavam sljedeću duljinu)"
                logger.log("IAP2-CSM", "GREŠKA: $msg")
                onError(2, msg)
                return
            }

            sendCsm(Iap2MessageIds.AUTH_SUCCEEDED)
            logger.log("IAP2-CSM", "✓ Poslan 0xAA05 (AuthenticationSucceeded — jednosmjerna MFi provjera dovršena)")
            onMfiSucceeded(authSerialFromAA06, succeededChallengeLen ?: 0)

            // --- Step 3B: Identification SECOND (0x1D00 -> 0x1D01 -> 0x1D02) ---
            identInfo = runIdentificationFlow()
            if (identInfo == null) return

            // Persist winning MFi challenge length under the confirmed head unit serial (e.g. AL07FFL0665583)
            val confirmedSerial = identInfo.serialNumber
            if (succeededChallengeLen != null && !confirmedSerial.isNullOrBlank()) {
                prefs.saveSucceededMfiLength(confirmedSerial, succeededChallengeLen)
                logger.log(
                    "IAP2-MFI",
                    "Spremljena uspješna MFi duljina ${succeededChallengeLen}B za serijski broj $confirmedSerial",
                )
            }
        } else {
            if (!channel.isAlive) {
                val msg = "RFCOMM/iAP2 veza prekinuta dok sam čekao MFi certifikat auta (0xAA01)"
                logger.log("IAP2-CSM", "GREŠKA: $msg")
                onError(2, msg)
                return
            }

            // Safety fallback for non-standard v2 head units that require 0x1D00 before 0xAA00
            logger.log(
                "IAP2-CSM",
                "Nije stigao 0xAA01 unutar ${AUTH_FIRST_CERT_TIMEOUT_MS / 1000}s — pokušavam alternativni redoslijed (0x1D00 Identifikacija pa 0xAA00 MFi)...",
            )
            identInfo = runIdentificationFlow()
            if (identInfo == null) return

            sendCsm(Iap2MessageIds.REQUEST_AUTH_CERTIFICATE)
            certFrame = waitForCertificate(CERT_TIMEOUT_MS)
            if (certFrame == null) {
                val msg = if (!channel.isAlive) {
                    "RFCOMM/iAP2 veza prekinuta dok sam čekao MFi certifikat auta (0xAA01)"
                } else {
                    "Nije primljen MFi certifikat auta (0xAA01)"
                }
                logger.log("IAP2-CSM", "GREŠKA: $msg")
                onError(2, msg)
                return
            }

            val certInfo = Iap2Parsers.parseAuthenticationCertificate(certFrame.body)
            logger.log("IAP2-CSM", certInfo.formatReportHr())

            succeededChallengeLen = runMfiChallengeFlow(
                certInfo = certInfo,
                lookupKeys = listOfNotNull(identInfo.serialNumber, authSerialFromAA06, peerBluetoothAddress),
            )
            if (!channel.isAlive) {
                val msg = "RFCOMM/iAP2 veza prekinuta tijekom MFi izazova"
                logger.log("IAP2-CSM", "GREŠKA: $msg")
                onError(2, msg)
                return
            }

            val confirmedSerial = identInfo.serialNumber
            if (succeededChallengeLen != null && !confirmedSerial.isNullOrBlank()) {
                prefs.saveSucceededMfiLength(confirmedSerial, succeededChallengeLen)
                logger.log(
                    "IAP2-MFI",
                    "Spremljena uspješna MFi duljina ${succeededChallengeLen}B za serijski broj $confirmedSerial",
                )
            }

            sendCsm(Iap2MessageIds.AUTH_SUCCEEDED)
            logger.log("IAP2-CSM", "✓ Poslan 0xAA05 (AuthenticationSucceeded — jednosmjerna MFi provjera dovršena)")
            onMfiSucceeded(confirmedSerial, succeededChallengeLen ?: 0)
        }

        // --- Step 4: Paced iPhone Greeting Burst + CarPlay EA Session (0xEA00) + 0x5702 + 0x4E0E + 0x4300 ---
        // All frames are paced strictly inside BMW's negotiated maxOutgoingPackets window (maxOut=5)
        // by Iap2LinkEngine so frames 6..10 never overflow the head unit's receive buffer.
        sendDeviceGreetingBurst()
        openCarPlayEaSessionIfDeclared(identInfo)

        val declared5702 = Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG in identInfo.messagesReceivedFromDevice
        logger.log(
            "IAP2-CSM",
            "Šaljem 0x5702 (RequestAccessoryWiFiConfigurationInformation) " +
                "[auto ${if (declared5702) "JE" else "NIJE"} naveo 0x5702 u 0x1D01 — šaljemo u svakom slučaju!]",
        )
        sendCsm(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG)
        sendTransportIdentifier0x4E0E()
        sendCarPlayAvailability0x4300()

        // Process incoming 0x5703 / 0x4301 / 0xEA03 / notifications immediately while waiting up to 2.5s
        drainForWindow(WIFI_RETRY_WAIT_MS) { wifiReceived }

        // If 0x5703 hasn't arrived yet, re-trigger with 0x4E0D + 0x4E0E + 0x5702 (fire-and-forget)
        if (!wifiReceived && channel.isAlive) {
            logger.log(
                "IAP2-CSM",
                "0x5703 još nije stigao nakon ${WIFI_RETRY_WAIT_MS}ms — ponovno šaljem 0x4E0D + 0x4E0E + 0x5702...",
            )
            sendCsm(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2CsmCodec.paramU8(0, 1), awaitAck = false)
            sendTransportIdentifier0x4E0E()
            sendCsm(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG, awaitAck = false)
        }

        // --- Continuous session pump (NEVER terminate BT iAP2 while Wi-Fi / AirPlay runs!) ---
        logger.log(
            "IAP2-CSM",
            "Ulazim u trajno slušanje iAP2 Control sesije (BT veza ostaje otvorena za cijelo vrijeme Wi-Fi/AirPlay testa)...",
        )
        pumpForever()
    }

    private fun runIdentificationFlow(): Iap2Parsers.IdentificationInfo? {
        val alreadyQueued = deferredQueue.firstOrNull { it.messageId == Iap2MessageIds.IDENTIFICATION_INFORMATION }
        val identFrame = if (alreadyQueued != null) {
            deferredQueue.remove(alreadyQueued)
            logger.log("IAP2-CSM", "Auto je već samostalno poslao 0x1D01 — obrađujem odmah!")
            alreadyQueued
        } else {
            sendCsm(Iap2MessageIds.START_IDENTIFICATION)
            waitForMessage(Iap2MessageIds.IDENTIFICATION_INFORMATION, IDENT_TIMEOUT_MS)
        }

        if (identFrame == null) {
            val msg = if (!channel.isAlive) {
                "RFCOMM/iAP2 veza prekinuta dok sam čekao identifikaciju auta (0x1D01)"
            } else {
                "Nije primljena identifikacija auta (0x1D01) unutar ${IDENT_TIMEOUT_MS / 1000}s"
            }
            logger.log("IAP2-CSM", "GREŠKA: $msg")
            onError(2, msg)
            return null
        }

        val identInfo = Iap2Parsers.parseIdentificationInfo(identFrame.body)
        logger.log("IAP2-CSM", identInfo.formatReportHr())
        onIdentification(identInfo)

        sendCsm(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        return identInfo
    }

    private fun waitForCertificate(timeoutMs: Long): Iap2CsmCodec.CsmFrame? {
        val queued = deferredQueue.firstOrNull { it.messageId == Iap2MessageIds.AUTH_CERTIFICATE }
        if (queued != null) {
            deferredQueue.remove(queued)
            return queued
        }
        val deadline = nowMs() + timeoutMs
        while (channel.isAlive && nowMs() < deadline) {
            val frame = nextFrame(minOf(300L, deadline - nowMs())) ?: continue
            when (frame.messageId) {
                Iap2MessageIds.AUTH_CERTIFICATE -> return frame
                Iap2MessageIds.AUTH_SERIAL_NUMBER -> {
                    val parsedSerial = Iap2Parsers.parseAuthSerialNumber(frame.body)
                    if (parsedSerial != null) authSerialFromAA06 = parsedSerial
                    logger.log(
                        "IAP2-CSM",
                        "Primljen 0xAA06 (AuthenticationSerialNumber=${parsedSerial ?: "?"}) — ponovno šaljem 0xAA00:\n" +
                            Iap2CsmCodec.dumpParamsReadable(frame.body),
                    )
                    sendCsm(Iap2MessageIds.REQUEST_AUTH_CERTIFICATE)
                }

                else -> handleSidebandFrame(frame, deferIfUnhandled = true)
            }
        }
        return null
    }

    private fun runMfiChallengeFlow(
        certInfo: Iap2Parsers.X509CertInfo,
        lookupKeys: List<String>,
    ): Int? {
        val attempts: List<Pair<Int, Long>> = when (mfiChallengeMode) {
            ProbePrefs.MfiChallengeMode.AUTO -> buildAutoMfiAttempts(certInfo, lookupKeys)

            else -> {
                val fixed = mfiChallengeMode.fixedLength ?: 20
                logger.log("IAP2-MFI", "Ručno odabrana MFi duljina izazova: ${fixed}B")
                listOf(fixed to 5_000L)
            }
        }

        for ((idx, pair) in attempts.withIndex()) {
            val (lengthBytes, waitMs) = pair
            if (!channel.isAlive) return null

            val challenge = ByteArray(lengthBytes).also { random.nextBytes(it) }
            logger.log(
                "IAP2-MFI",
                "Šaljem 0xAA02 izazov (${lengthBytes} B, pokušaj ${idx + 1}/${attempts.size}, čekam ${waitMs}ms)...",
            )
            sendCsm(
                Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE,
                Iap2CsmCodec.paramRaw(0, challenge),
            )

            val deadline = nowMs() + waitMs
            var rejectedOrFailed = false
            while (channel.isAlive && nowMs() < deadline) {
                val frame = nextFrame(minOf(250L, deadline - nowMs())) ?: continue
                when (frame.messageId) {
                    Iap2MessageIds.AUTH_RESPONSE -> {
                        val sigBytes = frame.parseParams().firstOrNull { it.id == 0 }?.value ?: frame.body
                        logger.log(
                            "IAP2-MFI",
                            "✓ Primljen 0xAA03 (AuthenticationResponse) na izazov od ${lengthBytes}B! Potpis=${sigBytes.size} B",
                        )
                        sessionFailedMfiLengths.remove(lengthBytes)
                        for (key in lookupKeys.distinct()) {
                            if (key.isNotBlank()) {
                                prefs.saveSucceededMfiLength(key, lengthBytes)
                            }
                        }
                        logger.log(
                            "IAP2-MFI",
                            "Spremljena uspješna MFi duljina ${lengthBytes}B (ključevi: ${lookupKeys.distinct().joinToString(", ")})",
                        )
                        return lengthBytes
                    }

                    Iap2MessageIds.AUTH_FAILED -> {
                        logger.log("IAP2-MFI", "Auto je odbio MFi izazov od ${lengthBytes}B (0xAA04 AuthenticationFailed)")
                        sessionFailedMfiLengths.add(lengthBytes)
                        rejectedOrFailed = true
                        break
                    }

                    else -> handleSidebandFrame(frame, deferIfUnhandled = true)
                }
            }

            if (!channel.isAlive) {
                if (idx == 0) {
                    // Only blacklist the first length sent on a clean link; later lengths may have been
                    // blocked behind an unacknowledged earlier seq!
                    sessionFailedMfiLengths.add(lengthBytes)
                    logger.log(
                        "IAP2-MFI",
                        "BMW zatvorio RFCOMM nakon prvog izazova od ${lengthBytes}B — pamtim za sljedeće spajanje da preskočim ${lengthBytes}B",
                    )
                } else {
                    logger.log(
                        "IAP2-MFI",
                        "BMW zatvorio RFCOMM tijekom pričuvnog izazova od ${lengthBytes}B (ne odbacujem ${lengthBytes}B jer ga je možda blokirao prethodni paket)",
                    )
                }
                return null
            }

            if (!rejectedOrFailed && idx == 0) {
                sessionFailedMfiLengths.add(lengthBytes)
            }

            if (idx < attempts.lastIndex && channel.isAlive) {
                logger.log(
                    "IAP2-MFI",
                    "Bez 0xAA03 odgovora na ${lengthBytes}B unutar ${waitMs}ms → prelazim na ${attempts[idx + 1].first}B",
                )
            }
        }

        logger.log(
            "IAP2-MFI",
            "UPOZORENJE: Nije primljen 0xAA03 ni na jednu duljinu izazova — svejedno šaljem 0xAA05 i nastavljam prema 0x1D00 / 0x5702",
        )
        return null
    }

    private fun buildAutoMfiAttempts(
        certInfo: Iap2Parsers.X509CertInfo,
        lookupKeys: List<String>,
    ): List<Pair<Int, Long>> {
        val baseAttempts = if (certInfo.isMfi20Sha1Rsa) {
            logger.log(
                "IAP2-MFI",
                "Detektiran Apple MFi 2.0C certifikat (${certInfo.sigAlgName ?: "?"} / ${certInfo.keyAlgorithm ?: "?"}) → CP2.0C zahtijeva 20B izazov (redoslijed: 20B → 32B → 16B)",
            )
            AUTO_MFI20_ATTEMPTS
        } else {
            AUTO_MFI_ATTEMPTS
        }

        val remembered = lookupKeys.firstNotNullOfOrNull { key -> prefs.getSucceededMfiLength(key) }
        val failedSnapshot = synchronized(sessionFailedMfiLengths) { sessionFailedMfiLengths.toSet() }

        if (remembered != null && remembered !in failedSnapshot) {
            val primaryWindow = baseAttempts.firstOrNull { it.first == remembered }?.second ?: 4_000L
            val rest = baseAttempts.filter { it.first != remembered }
            logger.log(
                "IAP2-MFI",
                "Zapamćena uspješna MFi duljina za auto (${lookupKeys.joinToString("/")}): ${remembered}B → isprobavam prvo ${remembered}B",
            )
            return listOf(remembered to primaryWindow) + rest
        }

        val fresh = baseAttempts.filter { it.first !in failedSnapshot }
        val previouslyFailed = baseAttempts.filter { it.first in failedSnapshot }
        return if (fresh.isNotEmpty() && previouslyFailed.isNotEmpty()) {
            logger.log(
                "IAP2-MFI",
                "Prethodno neuspjele MFi duljine u ovoj sesiji: ${previouslyFailed.map { "${it.first}B" }} → krećem odmah s ${fresh.first().first}B",
            )
            fresh + previouslyFailed
        } else {
            if (fresh.isEmpty()) {
                sessionFailedMfiLengths.clear()
            }
            baseAttempts
        }
    }

    private fun sendDeviceGreetingBurst() {
        logger.log(
            "IAP2-CSM",
            "Šaljem iPhone pozdravni niz (0x4E0C/A/9/B/D, stvarna BT MAC=$phoneBluetoothMac, awaitAck=false)...",
        )

        // 0x4E0C DeviceUUIDUpdate (deterministic UUID derived from real phone BT MAC)
        val deviceUuid = UUID.nameUUIDFromBytes(phoneBluetoothMac.toByteArray(Charsets.UTF_8))
            .toString()
            .uppercase()
        sendCsm(
            Iap2MessageIds.DEVICE_UUID_UPDATE,
            Iap2CsmCodec.paramString(0, deviceUuid),
            awaitAck = false,
        )

        // 0x4E0A DeviceLanguageUpdate
        sendCsm(
            Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
            Iap2CsmCodec.paramString(0, "en"),
            awaitAck = false,
        )

        // 0x4E09 DeviceInformationUpdate
        sendCsm(
            Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
            Iap2CsmCodec.paramString(0, "iPhone"),
            awaitAck = false,
        )

        // 0x4E0B DeviceTimeUpdate
        val epochSec = System.currentTimeMillis() / 1000L
        val tzOffsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val dstFlag = if (TimeZone.getDefault().inDaylightTime(java.util.Date())) 1 else 0
        sendCsm(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2CsmCodec.paramU64(0, epochSec),
            Iap2CsmCodec.paramU16(1, tzOffsetMinutes and 0xFFFF),
            Iap2CsmCodec.paramU8(2, dstFlag),
            awaitAck = false,
        )

        // 0x4E0D WirelessCarPlayUpdate (status = 1 = Available)
        sendCsm(
            Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
            Iap2CsmCodec.paramU8(0, 1),
            awaitAck = false,
        )
    }

    /**
     * Opens the CarPlay External Accessory session (0xEA00) ONLY for declared CarPlay EA
     * protocols (e.g. 17:com.bmw.carplay on BMW Entry Evo — never flooding 16x com.bmw.a4a*).
     * Per Apple iAP2 spec: param 0 = ExternalAccessoryProtocolIdentifier (u8),
     * param 1 = ExternalAccessoryProtocolSessionIdentifier (u16).
     */
    private fun openCarPlayEaSessionIfDeclared(identInfo: Iap2Parsers.IdentificationInfo) {
        if (Iap2MessageIds.START_EXTERNAL_ACCESSORY_PROTOCOL_SESSION !in identInfo.messagesReceivedFromDevice) {
            return
        }
        val carPlayEa = identInfo.eaProtocols.firstOrNull {
            it.name.contains("carplay", ignoreCase = true)
        } ?: return
        logger.log(
            "IAP2-CSM",
            "Otvaram CarPlay EA sesiju (0xEA00): protocolId=${carPlayEa.protocolId} (${carPlayEa.name}), sessionId=1",
        )
        sendCsm(
            Iap2MessageIds.START_EXTERNAL_ACCESSORY_PROTOCOL_SESSION,
            Iap2CsmCodec.paramU8(0, carPlayEa.protocolId),
            Iap2CsmCodec.paramU16(1, 1),
            awaitAck = false,
        )
    }

    private fun sendTransportIdentifier0x4E0E() {
        // 0x4E0E DeviceTransportIdentifierNotification (real phone BT MAC!)
        sendCsm(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2CsmCodec.paramString(0, phoneBluetoothMac),
            awaitAck = false,
        )
    }

    private fun sendCarPlayAvailability0x4300() {
        // 0x4300 CarPlayAvailability (group 1: available=1, btMac=real phone BT MAC!)
        sendCsm(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2CsmCodec.paramGroup(
                1,
                Iap2CsmCodec.paramU8(0, 1),
                Iap2CsmCodec.paramString(1, phoneBluetoothMac),
            ),
            awaitAck = false,
        )
    }

    private fun waitForMessage(expectedId: Int, timeoutMs: Long): Iap2CsmCodec.CsmFrame? {
        val fromDeferred = deferredQueue.firstOrNull { it.messageId == expectedId }
        if (fromDeferred != null) {
            deferredQueue.remove(fromDeferred)
            return fromDeferred
        }
        val deadline = nowMs() + timeoutMs
        while (channel.isAlive && nowMs() < deadline) {
            val frame = nextFrame(minOf(300L, deadline - nowMs())) ?: continue
            if (frame.messageId == expectedId) return frame
            handleSidebandFrame(frame, deferIfUnhandled = true)
        }
        return null
    }

    private fun drainForWindow(windowMs: Long, stopCondition: () -> Boolean) {
        val deadline = nowMs() + windowMs
        while (channel.isAlive && !stopCondition() && nowMs() < deadline) {
            val frame = nextFrame(minOf(200L, deadline - nowMs())) ?: continue
            dispatchLiveFrame(frame)
        }
    }

    private fun pumpForever() {
        // First process anything deferred during initial handshake
        while (deferredQueue.isNotEmpty() && channel.isAlive) {
            dispatchLiveFrame(deferredQueue.removeFirst())
        }
        while (channel.isAlive) {
            val frame = nextFrame(1_000L) ?: continue
            dispatchLiveFrame(frame)
        }
        logger.log("IAP2-CSM", "iAP2 Control sesija završena (BT link zatvoren)")
    }

    private fun handleSidebandFrame(frame: Iap2CsmCodec.CsmFrame, deferIfUnhandled: Boolean) {
        when (frame.messageId) {
            Iap2MessageIds.START_IDENTIFICATION -> {
                logger.log("IAP2-CSM", "Auto poslao 0x1D00 (traži identifikaciju mobitela) → šaljem iPhone 0x1D01")
                val phoneIdent = Iap2Parsers.buildPhoneIdentification0x1D01("iPhone", phoneBluetoothMac)
                logger.logPacket("IAP2-CSM", "TX 0x1D01 (iPhone identifikacija)", phoneIdent)
                channel.sendControl(phoneIdent, awaitAck = true)
            }

            Iap2MessageIds.ACCESSORY_WIFI_CONFIG,
            Iap2MessageIds.START_WIRELESS_CARPLAY_SESSION,
            Iap2MessageIds.START_HID,
            -> dispatchLiveFrame(frame)

            else -> {
                logUnknownOrGenericFrame(frame, note = if (deferIfUnhandled) "odgođeno" else "primljeno")
                if (deferIfUnhandled && deferredQueue.size < 128) {
                    deferredQueue.addLast(frame)
                }
            }
        }
    }

    private fun dispatchLiveFrame(frame: Iap2CsmCodec.CsmFrame) {
        when (frame.messageId) {
            Iap2MessageIds.START_IDENTIFICATION -> {
                logger.log("IAP2-CSM", "Auto poslao 0x1D00 → odgovaram s iPhone 0x1D01")
                val phoneIdent = Iap2Parsers.buildPhoneIdentification0x1D01("iPhone", phoneBluetoothMac)
                logger.logPacket("IAP2-CSM", "TX 0x1D01 (iPhone identifikacija)", phoneIdent)
                channel.sendControl(phoneIdent, awaitAck = true)
            }

            Iap2MessageIds.IDENTIFICATION_ACCEPTED -> {
                logger.log("IAP2-CSM", "Auto je prihvatio našu 0x1D01 identifikaciju (0x1D02)")
            }

            Iap2MessageIds.ACCESSORY_WIFI_CONFIG -> {
                val cfg = Iap2Parsers.parseWifiConfiguration(frame.body)
                wifiReceived = true
                logger.log(
                    "IAP2-WIFI",
                    "✓ ${cfg.formatReportHr()}\nTLV parametri:\n${Iap2CsmCodec.dumpParamsReadable(frame.body)}",
                )
                onWifiCredentials(cfg)
            }

            Iap2MessageIds.START_WIRELESS_CARPLAY_SESSION -> {
                val startSess = Iap2Parsers.parseCarPlayStartSession(frame.body)
                logger.log(
                    "IAP2-CARPLAY",
                    "✓ ${startSess.formatReportHr()}\nTLV parametri:\n${Iap2CsmCodec.dumpParamsReadable(frame.body)}",
                )
                if (startSess.embeddedWifi != null && !wifiReceived) {
                    wifiReceived = true
                    onWifiCredentials(startSess.embeddedWifi)
                }
                onStartCarPlaySession(startSess)
            }

            Iap2MessageIds.WIRELESS_CARPLAY_UPDATE -> {
                logger.log(
                    "IAP2-CARPLAY",
                    "Primljen 0x4E0D (WirelessCarPlayUpdate):\n${Iap2CsmCodec.dumpParamsReadable(frame.body)}",
                )
            }

            Iap2MessageIds.START_HID -> {
                logger.log(
                    "IAP2-CSM",
                    "Primljen 0x6800 (StartHID) → odgovaram s 0x6801:\n${Iap2CsmCodec.dumpParamsReadable(frame.body)}",
                )
                sendCsm(
                    Iap2MessageIds.DEVICE_HID_REPORT,
                    Iap2CsmCodec.paramU16(0, 0),
                    Iap2CsmCodec.paramRaw(1, byteArrayOf(0)),
                    awaitAck = false,
                )
            }

            Iap2MessageIds.STATUS_EXTERNAL_ACCESSORY_PROTOCOL_SESSION -> {
                logger.log(
                    "IAP2-EA",
                    "✓ Primljen 0xEA03 (StatusExternalAccessoryProtocolSession):\n${Iap2CsmCodec.dumpParamsReadable(frame.body)}",
                )
            }

            Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG -> {
                logger.log("IAP2-CSM", "Primljen dolazni 0x5702 (neobično od auta)")
            }

            else -> {
                logUnknownOrGenericFrame(frame, note = "trajno slušanje")
            }
        }
    }

    private fun logUnknownOrGenericFrame(frame: Iap2CsmCodec.CsmFrame, note: String) {
        val header = "RX CSM ${Iap2MessageIds.formatId(frame.messageId)} [$note, ${frame.body.size} B]"
        val tlvDump = Iap2CsmCodec.dumpParamsReadable(frame.body)
        logger.logPacket("IAP2-CSM", "$header\nTLV analiza:\n$tlvDump", frame.encode())
    }

    private fun sendCsm(
        messageId: Int,
        vararg params: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean {
        val raw = Iap2CsmCodec.encodeFrame(messageId, *params)
        logger.logPacket(
            "IAP2-CSM",
            "TX CSM ${Iap2MessageIds.formatId(messageId)} (awaitAck=$awaitAck)",
            raw,
        )
        return channel.sendControl(raw, awaitAck = awaitAck)
    }

    private fun nextFrame(timeoutMs: Long): Iap2CsmCodec.CsmFrame? {
        if (parsedQueue.isNotEmpty()) return parsedQueue.removeFirst()
        if (timeoutMs <= 0L) return null
        val chunk = channel.pollControlData(timeoutMs) ?: return null
        val frames = reassembler.feed(chunk)
        if (frames.isEmpty()) return null
        for (frame in frames) {
            logger.logPacket(
                "IAP2-CSM",
                "RX CSM ${Iap2MessageIds.formatId(frame.messageId)}",
                frame.encode(),
            )
            parsedQueue.addLast(frame)
        }
        return parsedQueue.removeFirst()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
