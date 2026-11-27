package io.bimmergestalt.bmwcarplay.airplay.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.IOException
import java.security.SecureRandom

/**
 * Lightweight BouncyCastle cryptographic primitives for AirPlay / Wireless CarPlay:
 * - SHA-512 & HKDF-SHA512 (32-byte keys)
 * - IETF ChaCha20-Poly1305 (RFC 8439, 96-bit nonce, 128-bit Poly1305 tag)
 * - Curve25519 (X25519) ECDH & Ed25519 signing/verification
 * - Continuous AES-128-CTR stream cipher (`et=NONE` fallback)
 *
 * Uses BouncyCastle's direct lightweight API (no JCA Security Provider registration),
 * guaranteeing identical behavior across JVM unit tests and Android 12–17 devices.
 */
object AirPlayCrypto {

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    private const val MAC_BITS = TAG_SIZE * 8

    private val random = SecureRandom()

    data class KeyPairBytes(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = SHA512Digest()
        for (part in parts) {
            if (part.isNotEmpty()) {
                digest.update(part, 0, part.size)
            }
        }
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    fun hkdfSha512(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = KEY_SIZE,
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA512Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    fun hkdfSha512(
        ikm: ByteArray,
        saltAscii: String,
        infoAscii: String,
        length: Int = KEY_SIZE,
    ): ByteArray = hkdfSha512(
        ikm = ikm,
        salt = saltAscii.toByteArray(Charsets.US_ASCII),
        info = infoAscii.toByteArray(Charsets.US_ASCII),
        length = length,
    )

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        val fin = cipher.doFinal(out, len)
        val total = len + fin
        return if (total == out.size) out else out.copyOf(total)
    }

    @Throws(IOException::class)
    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray = try {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
        val fin = cipher.doFinal(out, len)
        val total = len + fin
        if (total == out.size) out else out.copyOf(total)
    } catch (e: Exception) {
        throw IOException("ChaCha20-Poly1305 provjera potpisa nije uspjela: ${e.message}", e)
    }

    /** 12-byte counter nonce: 4 zero bytes + 8-byte little-endian unsigned counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var v = counter
        for (i in 4 until NONCE_SIZE) {
            nonce[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        return nonce
    }

    /** 12-byte pairing label nonce: 4 zero bytes + 8-byte ASCII label (e.g. "PS-Msg05", "PV-Msg02"). */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.toByteArray(Charsets.US_ASCII)
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, 8))
        return nonce
    }

    fun generateX25519KeyPair(): KeyPairBytes {
        val priv = ByteArray(X25519.SCALAR_SIZE)
        val pub = ByteArray(X25519.POINT_SIZE)
        X25519.generatePrivateKey(random, priv)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return KeyPairBytes(priv, pub)
    }

    @Throws(IOException::class)
    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        if (privateKey.size != X25519.SCALAR_SIZE || peerPublicKey.size != X25519.POINT_SIZE) {
            throw IOException("Neispravna veličina X25519 ključa (${privateKey.size}/${peerPublicKey.size})")
        }
        val shared = ByteArray(X25519.POINT_SIZE)
        val ok = X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, shared, 0)
        if (!ok) throw IOException("X25519 izračun dijeljene tajne nije uspio (slaba točka)")
        return shared
    }

    fun generateEd25519KeyPair(): KeyPairBytes {
        val priv = ByteArray(Ed25519.SECRET_KEY_SIZE)
        val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePrivateKey(random, priv)
        Ed25519.generatePublicKey(priv, 0, pub, 0)
        return KeyPairBytes(priv, pub)
    }

    fun ed25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePublicKey(privateKey, 0, pub, 0)
        return pub
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(privateKey, 0, message, 0, message.size, sig, 0)
        return sig
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519.PUBLIC_KEY_SIZE || signature.size != Ed25519.SIGNATURE_SIZE) {
            return false
        }
        return Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
    }

    /** Derives the 32-byte ChaCha20-Poly1305 key for a CarPlay screen/audio stream (`type=110`/`100`). */
    fun deriveDataStreamKey(sharedSecret: ByteArray, streamConnectionId: Long): ByteArray =
        hkdfSha512(
            ikm = sharedSecret,
            saltAscii = "DataStream-Salt$streamConnectionId",
            infoAscii = "DataStream-Output-Encryption-Key",
        )

    /** Derives AES-128-CTR `(key, iv)` for legacy `et=NONE` screen sessions (16-byte all-zero session key). */
    fun deriveAesStreamKeys(sessionKey: ByteArray, streamConnectionId: Long): Pair<ByteArray, ByteArray> {
        val connBytes = streamConnectionId.toString().toByteArray(Charsets.US_ASCII)
        val key = sha512("AirPlayStreamKey".toByteArray(Charsets.US_ASCII), connBytes, sessionKey).copyOfRange(0, 16)
        val iv = sha512("AirPlayStreamIV".toByteArray(Charsets.US_ASCII), connBytes, sessionKey).copyOfRange(0, 16)
        return key to iv
    }

    /**
     * Stateful continuous AES-128-CTR keystream across video frames (must NEVER reset per frame).
     */
    class Aes128CtrStream(key: ByteArray, iv: ByteArray) {
        private val cipher = SICBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(true, ParametersWithIV(KeyParameter(key), iv))
        }

        fun process(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            cipher.processBytes(data, 0, data.size, out, 0)
            return out
        }
    }
}
