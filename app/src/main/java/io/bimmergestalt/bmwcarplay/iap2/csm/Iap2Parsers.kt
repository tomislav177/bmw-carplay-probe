package io.bimmergestalt.bmwcarplay.iap2.csm

import io.bimmergestalt.bmwcarplay.diag.HexDump
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * High-level parsers and builders for Wireless CarPlay iAP2 Control Session messages:
 * - 0x1D01 IdentificationInformation (parse car identity + build iPhone identity if car sends 0x1D00)
 * - 0xAA01 AuthenticationCertificate (X.509 DER inspection: length, Subject, CN, Issuer)
 * - 0x5703 AccessoryWiFiConfigurationInformation (SSID, Passphrase, SecurityType, Channel)
 * - 0x4301 StartWirelessCarPlaySession (endpoint IPs, port, public key, optional AP group)
 */
object Iap2Parsers {

    data class BluetoothTransportEntry(
        val id: Int?,
        val name: String?,
        val macAddress: String?,
        val supportsIap2: Boolean,
    )

    data class WirelessCarPlayTransportEntry(
        val id: Int?,
        val name: String?,
        val supportsCarPlay: Boolean,
    )

    data class EaProtocolEntry(
        val protocolId: Int,
        val name: String,
    )

    data class HidComponentEntry(
        val id: Int?,
        val name: String?,
        val function: Int?,
    )

    data class IdentificationInfo(
        val name: String?,
        val modelIdentifier: String?,
        val manufacturer: String?,
        val serialNumber: String?,
        val firmwareVersion: String?,
        val hardwareVersion: String?,
        val ppid: String? = null,
        val messagesSentByAccessory: List<Int>,
        val messagesReceivedFromDevice: List<Int>,
        val currentLanguage: String?,
        val supportedLanguages: List<String>,
        val eaProtocols: List<EaProtocolEntry>,
        val bluetoothTransports: List<BluetoothTransportEntry>,
        val hidComponents: List<HidComponentEntry> = emptyList(),
        val wirelessCarPlayTransports: List<WirelessCarPlayTransportEntry>,
    ) {
        fun formatReportHr(): String {
            val sb = StringBuilder()
            sb.append("Identifikacija auta (0x1D01):")
            sb.append("\n  Naziv (Name): ${name ?: "<nije zadano>"}")
            sb.append("\n  Model (ModelIdentifier): ${modelIdentifier ?: "<nije zadano>"}")
            sb.append("\n  Proizvođač (Manufacturer): ${manufacturer ?: "<nije zadano>"}")
            sb.append("\n  Serijski broj (SerialNumber): ${serialNumber ?: "<nije zadano>"}")
            sb.append("\n  Firmware: ${firmwareVersion ?: "<nije zadano>"}")
            sb.append("\n  Hardver: ${hardwareVersion ?: "<nije zadano>"}")
            if (!ppid.isNullOrBlank()) {
                sb.append("\n  PPID (param 11): $ppid")
            }
            if (currentLanguage != null) {
                sb.append("\n  Jezik: $currentLanguage (podržani: ${supportedLanguages.joinToString(", ")})")
            }
            sb.append(
                "\n  MessagesSentByAccessory (${messagesSentByAccessory.size}): " +
                    messagesSentByAccessory.joinToString(" ") { String.format("0x%04X", it) },
            )
            sb.append(
                "\n  MessagesReceivedFromDevice (${messagesReceivedFromDevice.size}): " +
                    messagesReceivedFromDevice.joinToString(" ") { String.format("0x%04X", it) },
            )
            if (eaProtocols.isNotEmpty()) {
                sb.append(
                    "\n  EA protokoli: " +
                        eaProtocols.joinToString(", ") { "${it.protocolId}:${it.name}" },
                )
            }
            if (bluetoothTransports.isNotEmpty()) {
                bluetoothTransports.forEach { bt ->
                    sb.append(
                        "\n  BT transport: id=${bt.id ?: "?"} naziv=${bt.name ?: "?"} " +
                            "MAC=${bt.macAddress ?: "?"} iAP2=${bt.supportsIap2}",
                    )
                }
            }
            if (hidComponents.isNotEmpty()) {
                hidComponents.forEach { hid ->
                    sb.append(
                        "\n  HID komponenta (param 18): id=${hid.id ?: "?"} naziv=${hid.name ?: "?"} fn=${hid.function ?: "?"}",
                    )
                }
            }
            if (wirelessCarPlayTransports.isNotEmpty()) {
                wirelessCarPlayTransports.forEach { cp ->
                    sb.append(
                        "\n  Wi-Fi/CarPlay transport (param 24): id=${cp.id ?: "?"} naziv=${cp.name ?: "?"} " +
                            "supportsCarPlay=${cp.supportsCarPlay}",
                    )
                }
            } else {
                sb.append("\n  Wi-Fi/CarPlay transport (param 24): <nije najavljeno u ovom 0x1D01>")
            }
            return sb.toString()
        }
    }

    data class X509CertInfo(
        val rawLength: Int,
        val subjectDn: String?,
        val commonName: String?,
        val issuerDn: String?,
        val sigAlgName: String? = null,
        val keyAlgorithm: String? = null,
        val parseError: String? = null,
    ) {
        /**
         * Apple MFi 2.0C (CP2.0C) certificates use SHA1withRSA / 1024-bit RSA and the hardware
         * coprocessor strictly expects a 20-byte SHA-1 challenge (MFi 3.0 uses ECDSA/SHA-256 32B).
         */
        val isMfi20Sha1Rsa: Boolean
            get() = sigAlgName?.contains("SHA1", ignoreCase = true) == true ||
                (keyAlgorithm.equals("RSA", ignoreCase = true) && sigAlgName?.contains("SHA256", ignoreCase = true) != true)

        fun formatReportHr(): String = if (subjectDn != null) {
            val algPart = listOfNotNull(sigAlgName, keyAlgorithm).joinToString(" / ")
            "MFi X.509 certifikat auta ($rawLength B${if (algPart.isNotEmpty()) ", $algPart" else ""}): " +
                "CN=${commonName ?: "<nema>"} | Subject=$subjectDn | Issuer=${issuerDn ?: "?"}"
        } else {
            "MFi certifikat auta primljen ($rawLength B), X.509 parsiranje: ${parseError ?: "nepoznat format"}"
        }
    }

    enum class WifiSecurityType(val code: Int, val labelHr: String) {
        NONE(0, "Otvorena mreža (NONE)"),
        WEP(1, "WEP"),
        WPA_WPA2(2, "WPA/WPA2-PSK"),
        WPA3_TRANSITION(3, "WPA2/WPA3-Transition"),
        WPA3(4, "WPA3-SAE"),
        UNKNOWN(-1, "Nepoznato");

        companion object {
            fun fromCode(code: Int): WifiSecurityType =
                entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val securityType: WifiSecurityType,
        val securityRawCode: Int,
        val channel: Int,
    ) {
        fun formatReportHr(): String =
            "BMW Wi-Fi konfiguracija (0x5703): SSID=\"$ssid\", " +
                "Lozinka=\"$passphrase\" (${passphrase.length} znakova), " +
                "Sigurnost=${securityType.labelHr} ($securityRawCode), Kanal=$channel"
    }

    data class CarPlayStartSession(
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKeyHex: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        val embeddedWifi: WifiConfiguration?,
        val sdkVersion: Long?,
        val asset: String?,
        val mutualAuth: Int?,
    ) {
        fun formatReportHr(): String =
            "CarPlay StartSession (0x4301): port=$airPlayPort, IPs=$ipAddresses, " +
                "deviceID=${deviceIdentifier ?: "?"}, srcvers=${sourceVersion ?: "?"}, " +
                "pubKey=${publicKeyHex?.take(24) ?: "?"}…, mutualAuth=${mutualAuth ?: "?"}" +
                (embeddedWifi?.let { "\n  Ugrađeni Wi-Fi: ${it.formatReportHr()}" } ?: "")
    }

    fun parseIdentificationInfo(body: ByteArray): IdentificationInfo {
        val params = Iap2CsmCodec.parseTlvParams(body)
        val firstById = HashMap<Int, Iap2CsmCodec.TlvParam>()
        for (p in params) {
            firstById.putIfAbsent(p.id, p)
        }

        val supportedLangs = params.filter { it.id == 13 }.map { it.asString() }.filter { it.isNotEmpty() }

        val eaProtocols = ArrayList<EaProtocolEntry>()
        for (p in params.filter { it.id == 10 }) {
            val sub = p.parseNestedParams().associateBy { it.id }
            val protoId = sub[0]?.asU8() ?: continue
            val protoName = sub[1]?.asString().orEmpty()
            eaProtocols.add(EaProtocolEntry(protoId, protoName))
        }

        val btTransports = ArrayList<BluetoothTransportEntry>()
        for (p in params.filter { it.id == 17 }) {
            val sub = p.parseNestedParams().associateBy { it.id }
            val macBytes = sub[3]?.value
            val macStr = if (macBytes != null && macBytes.size == 6) {
                macBytes.joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
            } else {
                macBytes?.let { HexDump.toCompactHex(it) }
            }
            btTransports.add(
                BluetoothTransportEntry(
                    id = sub[0]?.asU16(),
                    name = sub[1]?.asString(),
                    macAddress = macStr,
                    supportsIap2 = sub.containsKey(2),
                ),
            )
        }

        val hidComponents = ArrayList<HidComponentEntry>()
        for (p in params.filter { it.id == 18 }) {
            val sub = p.parseNestedParams().associateBy { it.id }
            hidComponents.add(
                HidComponentEntry(
                    id = sub[0]?.asU16(),
                    name = sub[1]?.asString(),
                    function = sub[2]?.asU8(),
                ),
            )
        }

        val carPlayTransports = ArrayList<WirelessCarPlayTransportEntry>()
        for (p in params.filter { it.id == 24 }) {
            val sub = p.parseNestedParams().associateBy { it.id }
            carPlayTransports.add(
                WirelessCarPlayTransportEntry(
                    id = sub[0]?.asU16(),
                    name = sub[1]?.asString(),
                    supportsCarPlay = sub.containsKey(2) || sub.containsKey(4),
                ),
            )
        }

        return IdentificationInfo(
            name = firstById[0]?.asString(),
            modelIdentifier = firstById[1]?.asString(),
            manufacturer = firstById[2]?.asString(),
            serialNumber = firstById[3]?.asString(),
            firmwareVersion = firstById[4]?.asString(),
            hardwareVersion = firstById[5]?.asString(),
            ppid = firstById[11]?.asString(),
            messagesSentByAccessory = firstById[6]?.asU16List().orEmpty(),
            messagesReceivedFromDevice = firstById[7]?.asU16List().orEmpty(),
            currentLanguage = firstById[12]?.asString(),
            supportedLanguages = supportedLangs,
            eaProtocols = eaProtocols,
            bluetoothTransports = btTransports,
            hidComponents = hidComponents,
            wirelessCarPlayTransports = carPlayTransports,
        )
    }

    /**
     * Builds an iPhone Wireless CarPlay 0x1D01 IdentificationInformation payload in case
     * the head unit sends 0x1D00 requesting the phone to identify itself.
     */
    fun buildPhoneIdentification0x1D01(
        deviceName: String,
        bluetoothMac: String,
    ): ByteArray {
        val macBytes = parseMacBytes(bluetoothMac)
        val btComponent = if (macBytes != null) {
            Iap2CsmCodec.paramGroup(
                17,
                Iap2CsmCodec.paramU16(0, 1),
                Iap2CsmCodec.paramString(1, deviceName),
                Iap2CsmCodec.paramEmpty(2),
                Iap2CsmCodec.paramRaw(3, macBytes),
            )
        } else {
            Iap2CsmCodec.paramGroup(
                17,
                Iap2CsmCodec.paramU16(0, 1),
                Iap2CsmCodec.paramString(1, deviceName),
                Iap2CsmCodec.paramEmpty(2),
            )
        }

        val carPlayComponent = Iap2CsmCodec.paramGroup(
            24,
            Iap2CsmCodec.paramU16(0, 2),
            Iap2CsmCodec.paramString(1, "WirelessCarPlay"),
            Iap2CsmCodec.paramEmpty(2),
            Iap2CsmCodec.paramEmpty(4),
        )

        return Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.IDENTIFICATION_INFORMATION,
            Iap2CsmCodec.paramString(0, deviceName),
            Iap2CsmCodec.paramString(1, "iPhone14,5"),
            Iap2CsmCodec.paramString(2, "Apple Inc."),
            Iap2CsmCodec.paramString(3, bluetoothMac.replace(":", "")),
            Iap2CsmCodec.paramString(4, "17.5"),
            Iap2CsmCodec.paramString(5, "D84AP"),
            Iap2CsmCodec.paramU16List(
                6,
                listOf(
                    Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG,
                    Iap2MessageIds.CARPLAY_AVAILABILITY,
                    Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
                    Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
                    Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
                    Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
                    Iap2MessageIds.DEVICE_TIME_UPDATE,
                    Iap2MessageIds.DEVICE_UUID_UPDATE,
                ),
            ),
            Iap2CsmCodec.paramU16List(
                7,
                listOf(
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIG,
                    Iap2MessageIds.START_WIRELESS_CARPLAY_SESSION,
                    Iap2MessageIds.START_HID,
                ),
            ),
            Iap2CsmCodec.paramU8(8, 0),
            Iap2CsmCodec.paramU16(9, 0),
            Iap2CsmCodec.paramString(12, "en"),
            Iap2CsmCodec.paramString(13, "en"),
            btComponent,
            carPlayComponent,
        )
    }

    /**
     * Parses X.509 certificate details from 0xAA01 AuthenticationCertificate body.
     */
    fun parseAuthenticationCertificate(body: ByteArray): X509CertInfo {
        val params = Iap2CsmCodec.parseTlvParams(body)
        val certBytes = params.firstOrNull { it.id == 0 }?.value ?: body
        if (certBytes.isEmpty()) {
            return X509CertInfo(0, null, null, null, parseError = "Prazan certifikat")
        }
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val certs = factory.generateCertificates(ByteArrayInputStream(certBytes))
            val x509 = certs.firstOrNull() as? X509Certificate
                ?: (factory.generateCertificate(ByteArrayInputStream(certBytes)) as? X509Certificate)
            if (x509 != null) {
                val subject = x509.subjectX500Principal?.name
                val cn = extractCn(subject)
                val issuer = x509.issuerX500Principal?.name
                val sigAlg = x509.sigAlgName
                val keyAlg = x509.publicKey?.algorithm
                X509CertInfo(
                    rawLength = certBytes.size,
                    subjectDn = subject,
                    commonName = cn,
                    issuerDn = issuer,
                    sigAlgName = sigAlg,
                    keyAlgorithm = keyAlg,
                    parseError = null,
                )
            } else {
                X509CertInfo(certBytes.size, null, null, null, parseError = "Nema X.509 zapisa u DER podacima")
            }
        } catch (e: Exception) {
            X509CertInfo(certBytes.size, null, null, null, parseError = e.message)
        }
    }

    /**
     * Parses accessory authentication serial string from 0xAA06 AuthenticationSerialNumber (param 0).
     */
    fun parseAuthSerialNumber(body: ByteArray): String? {
        val params = Iap2CsmCodec.parseTlvParams(body)
        return params.firstOrNull { it.id == 0 }?.asString()?.takeIf { it.isNotBlank() }
    }

    fun parseWifiConfiguration(body: ByteArray): WifiConfiguration {
        val params = Iap2CsmCodec.parseTlvParams(body).associateBy { it.id }
        val ssid = params[1]?.asString().orEmpty()
        val passphrase = params[2]?.asString().orEmpty()
        val secCode = params[3]?.asU8() ?: if (passphrase.isEmpty()) 0 else 2
        val channel = params[4]?.asU8() ?: 0
        return WifiConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            securityType = WifiSecurityType.fromCode(secCode),
            securityRawCode = secCode,
            channel = channel,
        )
    }

    fun parseCarPlayStartSession(body: ByteArray): CarPlayStartSession {
        val params = Iap2CsmCodec.parseTlvParams(body)
        val byId = params.associateBy { it.id }

        val wirelessParams = byId[1]?.parseNestedParams().orEmpty()
        val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        val ipAddresses = wirelessParams
            .filter { it.id == 3 }
            .map { it.asString().trim() }
            .filter { it.isNotEmpty() }

        // Some newer head units embed SSID/passphrase/channel/security inside group 1 of 0x4301
        val nonIpStrings = wirelessParams
            .sortedBy { it.id }
            .map { it.asString().trim() }
            .filter { it.isNotEmpty() && !ipv4Regex.matches(it) && !it.contains(':') }

        val embeddedWifi = if (nonIpStrings.isNotEmpty() && nonIpStrings[0].length <= 32) {
            val singleBytes = wirelessParams.filter { it.value.size == 1 }
                .associate { it.id to (it.value[0].toInt() and 0xFF) }
            val secCode = singleBytes[4]?.takeIf { it in 0..4 }
                ?: singleBytes[3]?.takeIf { it in 0..4 }
                ?: if (nonIpStrings.getOrNull(1).isNullOrEmpty()) 0 else 2
            val ch = singleBytes[2]?.takeIf { it in 1..196 }
                ?: singleBytes[4]?.takeIf { it in 1..196 && it != secCode }
                ?: 0
            WifiConfiguration(
                ssid = nonIpStrings[0],
                passphrase = nonIpStrings.getOrNull(1).orEmpty(),
                securityType = WifiSecurityType.fromCode(secCode),
                securityRawCode = secCode,
                channel = ch,
            )
        } else {
            null
        }

        val port = byId[2]?.asU32()?.toInt() ?: byId[2]?.asU16() ?: 0
        val deviceId = byId[3]?.asString()?.takeIf { it.isNotEmpty() }
        val pubKeyParam = byId[4]
        val pubKeyHex = pubKeyParam?.let { p ->
            val asStr = p.asString()
            if (asStr.isNotEmpty() && asStr.all { it.isLetterOrDigit() || it in "+/=:-" }) {
                asStr
            } else {
                HexDump.toCompactHex(p.value)
            }
        }
        val sourceVersion = byId[5]?.asString()?.takeIf { it.isNotEmpty() }
        val sdkVersion = byId[6]?.asU32()
        val asset = byId[7]?.asString()?.takeIf { it.isNotEmpty() }
        val mutualAuth = byId[8]?.asU8()

        return CarPlayStartSession(
            airPlayPort = port,
            deviceIdentifier = deviceId,
            publicKeyHex = pubKeyHex,
            sourceVersion = sourceVersion,
            ipAddresses = ipAddresses,
            embeddedWifi = embeddedWifi,
            sdkVersion = sdkVersion,
            asset = asset,
            mutualAuth = mutualAuth,
        )
    }

    private fun extractCn(dn: String?): String? {
        if (dn.isNullOrBlank()) return null
        val match = Regex("(?:^|,)\\s*CN=([^,]+)", RegexOption.IGNORE_CASE).find(dn)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseMacBytes(mac: String): ByteArray? {
        val parts = mac.trim().split(":")
        if (parts.size != 6) return null
        return try {
            ByteArray(6) { i -> parts[i].toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
