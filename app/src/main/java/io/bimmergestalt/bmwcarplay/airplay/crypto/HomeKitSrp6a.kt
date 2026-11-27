package io.bimmergestalt.bmwcarplay.airplay.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Apple HomeKit / AirPlay SRP-6a client (RFC 5054 3072-bit safe prime group, SHA-512).
 *
 * Fixed HomeKit AirPlay credentials:
 * - Username `I = "Pair-Setup"`
 * - PIN `P = "3939"` (standard transient / anonymous AirPlay CarPlay setup code)
 * - `k = H(pad(N) || pad(g))`
 * - `x = H(salt || H(I || ":" || P))`
 * - `K = H(bytes(S))`
 * - `M1 = H(H(N) XOR H(g) || H(I) || salt || pad(A) || pad(B) || K)`
 * - `M2 = H(pad(A) || M1 || K)`
 */
object HomeKitSrp6a {

    const val USERNAME = "Pair-Setup"
    const val DEFAULT_PIN = "3939"
    private const val MODULUS_BYTES = 384
    private val COLON = byteArrayOf(':'.code.toByte())
    private val random = SecureRandom()

    // RFC 5054 Appendix A — 3072-bit Group
    private const val N_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
            "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
            "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
            "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

    internal val N: BigInteger = BigInteger(N_HEX, 16)
    internal val G: BigInteger = BigInteger.valueOf(5L)
    internal val K: BigInteger = toPositiveBigInt(AirPlayCrypto.sha512(pad384(N), pad384(G)))

    class ClientSession internal constructor(
        val publicKeyA: ByteArray,
        private val privateA: BigInteger,
        private val serverPublicB: ByteArray,
        private val salt: ByteArray,
        private val username: ByteArray,
        private val pin: ByteArray,
    ) {
        var sessionKey: ByteArray? = null
            private set

        private var cachedM1: ByteArray? = null

        /** Computes the 64-byte SRP client proof `M1` (or returns null if server public key B is invalid). */
        fun computeClientProof(): ByteArray? {
            cachedM1?.let { return it }
            val bigB = toPositiveBigInt(serverPublicB)
            if (bigB.mod(N).signum() == 0) return null

            val paddedB = pad384(bigB)
            val u = toPositiveBigInt(AirPlayCrypto.sha512(publicKeyA, paddedB))
            if (u.signum() == 0) return null

            val innerCredHash = AirPlayCrypto.sha512(username, COLON, pin)
            val x = toPositiveBigInt(AirPlayCrypto.sha512(salt, innerCredHash))
            val verifier = G.modPow(x, N)
            val base = (bigB - K.multiply(verifier)).mod(N)
            if (base.signum() == 0) return null

            val exp = privateA.add(u.multiply(x))
            val sharedS = base.modPow(exp, N)
            val kBytes = AirPlayCrypto.sha512(toUnsignedBytes(sharedS))
            sessionKey = kBytes

            val hashN = AirPlayCrypto.sha512(toUnsignedBytes(N))
            val hashG = AirPlayCrypto.sha512(toUnsignedBytes(G))
            val hashXor = ByteArray(hashN.size) { i ->
                (hashN[i].toInt() xor hashG[i].toInt()).toByte()
            }
            val m1 = AirPlayCrypto.sha512(
                hashXor,
                AirPlayCrypto.sha512(username),
                salt,
                publicKeyA,
                paddedB,
                kBytes,
            )
            cachedM1 = m1
            return m1
        }

        /** Verifies the 64-byte server proof `M2 = H(pad(A) || M1 || K)`. */
        fun verifyServerProof(serverProof: ByteArray): Boolean {
            val m1 = computeClientProof() ?: return false
            val key = sessionKey ?: return false
            val expected = AirPlayCrypto.sha512(publicKeyA, m1, key)
            return expected.contentEquals(serverProof)
        }
    }

    fun start(
        salt: ByteArray,
        serverPublicB: ByteArray,
        pin: String = DEFAULT_PIN,
        username: String = USERNAME,
    ): ClientSession {
        var a: BigInteger
        var publicA: ByteArray
        do {
            val privBytes = ByteArray(32).also(random::nextBytes)
            a = toPositiveBigInt(privBytes)
            publicA = toUnsignedBytes(G.modPow(a, N))
        } while (a.signum() == 0 || publicA.size != MODULUS_BYTES)
        return ClientSession(
            publicKeyA = publicA,
            privateA = a,
            serverPublicB = serverPublicB,
            salt = salt,
            username = username.toByteArray(Charsets.UTF_8),
            pin = pin.toByteArray(Charsets.UTF_8),
        )
    }

    internal fun toUnsignedBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return byteArrayOf(0)
        val raw = value.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    internal fun toPositiveBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    internal fun pad384(value: BigInteger): ByteArray {
        val raw = toUnsignedBytes(value)
        if (raw.size >= MODULUS_BYTES) return raw
        return ByteArray(MODULUS_BYTES).also { raw.copyInto(it, MODULUS_BYTES - raw.size) }
    }
}
