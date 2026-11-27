package io.bimmergestalt.bmwcarplay.airplay.crypto

import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.diag.HexDump
import java.io.IOException

/**
 * HomeKit AirPlay Pairing Client supporting all three real-world automotive CarPlay modes:
 * 1. **Full Pair-Setup (M1–M6) + Pair-Verify (M1–M4)** — registers persistent Ed25519 identity
 *    via SRP-6a (PIN `"3939"`), then derives Control keys via Curve25519 ephemeral ECDH.
 * 2. **Transient Pair-Setup (M1–M4, `Flags = 0x10`)** — SRP-6a directly yields `sharedSecret`
 *    without M5/M6 or separate Pair-Verify (commonly accepted by AirTunes 280.x/320.x receivers).
 * 3. **Fast-Path Pair-Verify (M1–M4)** — used when a receiver returns `403` on `/pair-setup`
 *    because BT iAP2 MFi already established trust or long-term identity is already paired.
 */
class AirPlayPairingClient(
    private val logger: DiagnosticLogger,
) {
    data class ControllerIdentity(
        val pairingId: String,
        val ed25519PrivateKey: ByteArray,
        val ed25519PublicKey: ByteArray,
    )

    data class PairingResult(
        val modeLabelHr: String,
        val controlWriteKey: ByteArray,
        val controlReadKey: ByteArray,
        val sharedSecret: ByteArray,
        val receiverPairingId: String? = null,
    )

    fun interface TlvExchange {
        @Throws(IOException::class)
        fun postTlv(path: String, requestTlv: ByteArray): ByteArray
    }

    /**
     * Runs HomeKit Transient Pair-Setup (M1–M4 with `Flags = 0x10 00 00 00` = `kPairingFlag_Transient`).
     * Derives Control Channel encryption keys directly from the SRP-6a shared session key `K`.
     */
    @Throws(IOException::class)
    fun runTransientPairSetup(exchange: TlvExchange): PairingResult {
        logger.log("AIRPLAY-PAIR", "Pokrećem Transient /pair-setup (PIN 3939, Flags=0x10, M1–M4)...")
        val m1Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_METHOD, byteArrayOf(0x00)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x01)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_FLAGS, byteArrayOf(0x10, 0x00, 0x00, 0x00)),
            ),
        )
        val m2 = decodeAndCheck("Transient M2", exchange.postTlv("/pair-setup", m1Req), expectedState = 2)
        val salt = m2[Tlv8Codec.TYPE_SALT] ?: throw IOException("Transient M2 bez Salt")
        val serverB = m2[Tlv8Codec.TYPE_PUBLIC_KEY] ?: throw IOException("Transient M2 bez PublicKey B")
        logger.log("AIRPLAY-PAIR", "Transient M2 OK: salt=${salt.size}B, B=${serverB.size}B")

        val srp = HomeKitSrp6a.start(salt, serverB)
        val clientM1 = srp.computeClientProof() ?: throw IOException("SRP javni ključ B auta je nevaljan")
        val m3Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x03)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PUBLIC_KEY, srp.publicKeyA),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PROOF, clientM1),
            ),
        )
        val m4 = decodeAndCheck("Transient M4", exchange.postTlv("/pair-setup", m3Req), expectedState = 4)
        val serverProof = m4[Tlv8Codec.TYPE_PROOF] ?: throw IOException("Transient M4 bez Proof")
        if (!srp.verifyServerProof(serverProof)) {
            throw IOException("Transient M4 SRP provjera dokaza auta nije uspjela")
        }
        val shared = srp.sessionKey ?: throw IOException("Nedostaje SRP sessionKey")
        logger.log("AIRPLAY-PAIR", "✓ Transient /pair-setup M4 uspješan! Deriviram ChaCha20 kontrolne ključeve...")
        return deriveControlKeys("HomeKit Transient Pair-Setup (PIN 3939)", shared)
    }

    /**
     * Runs Full Pair-Setup (M1–M6) registering [identity] with the receiver.
     */
    @Throws(IOException::class)
    fun runFullPairSetup(identity: ControllerIdentity, exchange: TlvExchange): String? {
        logger.log("AIRPLAY-PAIR", "Pokrećem puni /pair-setup (PIN 3939, M1–M6, id=${identity.pairingId})...")
        val m1Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_METHOD, byteArrayOf(0x00)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x01)),
            ),
        )
        val m2 = decodeAndCheck("PairSetup M2", exchange.postTlv("/pair-setup", m1Req), expectedState = 2)
        val salt = m2[Tlv8Codec.TYPE_SALT] ?: throw IOException("PairSetup M2 bez Salt")
        val serverB = m2[Tlv8Codec.TYPE_PUBLIC_KEY] ?: throw IOException("PairSetup M2 bez PublicKey B")
        logger.log("AIRPLAY-PAIR", "PairSetup M2 OK: salt=${salt.size}B, B=${serverB.size}B")

        val srp = HomeKitSrp6a.start(salt, serverB)
        val clientM1 = srp.computeClientProof() ?: throw IOException("SRP javni ključ B auta je nevaljan")
        val m3Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x03)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PUBLIC_KEY, srp.publicKeyA),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PROOF, clientM1),
            ),
        )
        val m4 = decodeAndCheck("PairSetup M4", exchange.postTlv("/pair-setup", m3Req), expectedState = 4)
        val serverProof = m4[Tlv8Codec.TYPE_PROOF] ?: throw IOException("PairSetup M4 bez Proof")
        if (!srp.verifyServerProof(serverProof)) {
            throw IOException("PairSetup M4 SRP provjera dokaza auta nije uspjela")
        }
        val sessionKey = srp.sessionKey ?: throw IOException("Nedostaje SRP sessionKey")
        logger.log("AIRPLAY-PAIR", "PairSetup M4 SRP međusobna provjera OK — šaljem Ed25519 identitet (M5)...")

        val encKey = AirPlayCrypto.hkdfSha512(sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val signKey = AirPlayCrypto.hkdfSha512(
            sessionKey,
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
        )
        val idBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val sig = AirPlayCrypto.ed25519Sign(
            identity.ed25519PrivateKey,
            signKey + idBytes + identity.ed25519PublicKey,
        )
        val subTlv = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_IDENTIFIER, idBytes),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PUBLIC_KEY, identity.ed25519PublicKey),
                Tlv8Codec.Item(Tlv8Codec.TYPE_SIGNATURE, sig),
            ),
        )
        val sealedSub = AirPlayCrypto.chachaSeal(encKey, AirPlayCrypto.nonceLabel("PS-Msg05"), subTlv)
        val m5Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x05)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_ENCRYPTED_DATA, sealedSub),
            ),
        )
        val m6 = decodeAndCheck("PairSetup M6", exchange.postTlv("/pair-setup", m5Req), expectedState = 6)

        val encM6 = m6[Tlv8Codec.TYPE_ENCRYPTED_DATA]
        var receiverId: String? = null
        if (encM6 != null) {
            val inner = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encKey, AirPlayCrypto.nonceLabel("PS-Msg06"), encM6))
            val rIdBytes = inner[Tlv8Codec.TYPE_IDENTIFIER]
            val rPub = inner[Tlv8Codec.TYPE_PUBLIC_KEY]
            val rSig = inner[Tlv8Codec.TYPE_SIGNATURE]
            if (rIdBytes != null && rPub != null && rSig != null) {
                receiverId = rIdBytes.toString(Charsets.UTF_8)
                val accSignKey = AirPlayCrypto.hkdfSha512(
                    sessionKey,
                    "Pair-Setup-Accessory-Sign-Salt",
                    "Pair-Setup-Accessory-Sign-Info",
                )
                val valid = AirPlayCrypto.ed25519Verify(rPub, accSignKey + rIdBytes + rPub, rSig)
                logger.log(
                    "AIRPLAY-PAIR",
                    "✓ PairSetup M6 završen! Auto id=$receiverId pub=${HexDump.toCompactHex(rPub, 8)} sigOK=$valid",
                )
            }
        }
        return receiverId
    }

    /**
     * Runs `/pair-verify` (M1–M4) using ephemeral X25519 ECDH + long-term Ed25519 signature.
     */
    @Throws(IOException::class)
    fun runPairVerify(
        identity: ControllerIdentity,
        exchange: TlvExchange,
        modeLabelHr: String = "HomeKit Pair-Verify",
    ): PairingResult {
        logger.log("AIRPLAY-PAIR", "Pokrećem /pair-verify (X25519 + Ed25519, M1–M4)...")
        val ephemeral = AirPlayCrypto.generateX25519KeyPair()
        val m1Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x01)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PUBLIC_KEY, ephemeral.publicKey),
            ),
        )
        val m2 = decodeAndCheck("PairVerify M2", exchange.postTlv("/pair-verify", m1Req), expectedState = 2)
        val carEphemeralPub = m2[Tlv8Codec.TYPE_PUBLIC_KEY] ?: throw IOException("PairVerify M2 bez PublicKey")
        val encM2 = m2[Tlv8Codec.TYPE_ENCRYPTED_DATA] ?: throw IOException("PairVerify M2 bez EncryptedData")

        val shared = AirPlayCrypto.x25519SharedSecret(ephemeral.privateKey, carEphemeralPub)
        val pvEncKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val innerM2 = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(pvEncKey, AirPlayCrypto.nonceLabel("PV-Msg02"), encM2))
        val carId = innerM2[Tlv8Codec.TYPE_IDENTIFIER]?.toString(Charsets.UTF_8)
        logger.log("AIRPLAY-PAIR", "PairVerify M2 OK: X25519 tajna dogovorena, auto id=${carId ?: "?"}")

        val idBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val sig = AirPlayCrypto.ed25519Sign(
            identity.ed25519PrivateKey,
            ephemeral.publicKey + idBytes + carEphemeralPub,
        )
        val subM3 = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_IDENTIFIER, idBytes),
                Tlv8Codec.Item(Tlv8Codec.TYPE_SIGNATURE, sig),
            ),
        )
        val sealedM3 = AirPlayCrypto.chachaSeal(pvEncKey, AirPlayCrypto.nonceLabel("PV-Msg03"), subM3)
        val m3Req = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x03)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_ENCRYPTED_DATA, sealedM3),
            ),
        )
        decodeAndCheck("PairVerify M4", exchange.postTlv("/pair-verify", m3Req), expectedState = 4)
        logger.log("AIRPLAY-PAIR", "✓ /pair-verify M4 uspješan! Kontrolni kanal prelazi na ChaCha20-Poly1305 (MTU 1422 B)")
        return deriveControlKeys(modeLabelHr, shared, carId)
    }

    private fun deriveControlKeys(
        modeLabelHr: String,
        sharedSecret: ByteArray,
        receiverId: String? = null,
    ): PairingResult {
        val controlWriteKey = AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "Control-Salt",
            "Control-Write-Encryption-Key",
        )
        val controlReadKey = AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "Control-Salt",
            "Control-Read-Encryption-Key",
        )
        return PairingResult(
            modeLabelHr = modeLabelHr,
            controlWriteKey = controlWriteKey,
            controlReadKey = controlReadKey,
            sharedSecret = sharedSecret,
            receiverPairingId = receiverId,
        )
    }

    @Throws(IOException::class)
    private fun decodeAndCheck(stage: String, responseBytes: ByteArray, expectedState: Int): Map<Int, ByteArray> {
        val map = Tlv8Codec.decode(responseBytes)
        logger.log("AIRPLAY-PAIR", "$stage TLV odgovor: ${Tlv8Codec.describeDecoded(map)}")
        val err = map[Tlv8Codec.TYPE_ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
        if (err != null && err != 0) {
            throw IOException("$stage vratio HomeKit grešku: ${Tlv8Codec.errorName(err)}")
        }
        val state = map[Tlv8Codec.TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xFF)
        if (state != expectedState) {
            throw IOException("$stage neočekivano stanje=$state (očekivano $expectedState)")
        }
        return map
    }
}
