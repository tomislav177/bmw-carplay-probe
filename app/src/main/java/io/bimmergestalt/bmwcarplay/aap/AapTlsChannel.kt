package io.bimmergestalt.bmwcarplay.aap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlin.math.max

/**
 * Mutual TLS 1.2 transport engine for AAP over [SSLEngine].
 *
 * - Presents [credentials] via a custom [X509ExtendedKeyManager] (always returning alias `"hu"`
 *   so client authentication succeeds regardless of the server's `CertificateRequest` CA list).
 * - Uses a permissive [X509TrustManager] because Android Auto's phone side uses a custom
 *   Google Automotive Link CA that is not present in the Android/JVM system trust store.
 * - Tunnels the TLS handshake through AAP `SSL_HANDSHAKE` (`0x0003`) payloads via
 *   [performHandshake], then provides thread-safe per-frame [encrypt] and [decrypt].
 */
class AapTlsChannel(
    private val credentials: AapCertStore.LoadedCredentials,
    private val log: (tag: String, msg: String) -> Unit,
) {
    companion object {
        private const val TAG = "AAP-TLS"
        private const val CLIENT_ALIAS = "hu"
        private const val MIN_BUFFER_BYTES = 16_384
        private const val MAX_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MAX_HANDSHAKE_CHUNK_BYTES = 16_384
    }

    @Volatile
    var negotiatedProtocol: String? = null
        private set

    @Volatile
    var negotiatedCipherSuite: String? = null
        private set

    @Volatile
    var serverCertSubject: String? = null
        private set

    private val wrapLock = Any()
    private val unwrapLock = Any()

    private val engine: SSLEngine
    private var encryptOutBuf: ByteBuffer
    private var decryptAppBuf: ByteBuffer
    private var decryptCarryOver: ByteArray = ByteArray(0)

    init {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf(CLIENT_ALIAS)

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String = CLIENT_ALIAS

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ): String = CLIENT_ALIAS

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf(CLIENT_ALIAS)

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String = CLIENT_ALIAS

            override fun chooseEngineServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ): String = CLIENT_ALIAS

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == CLIENT_ALIAS) credentials.certChain.copyOf() else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == CLIENT_ALIAS) credentials.privateKey else null
        }

        // Android Auto uses a private Google Automotive Link root CA (not in Android's system trust store)
        // on the local 127.0.0.1:5277 loopback server, so the HU records the server cert subject cleanly here.
        @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
        val trustManager = object : X509TrustManager {
            @android.annotation.SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Client mode only
            }

            @android.annotation.SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                serverCertSubject = chain?.firstOrNull()?.subjectX500Principal?.name
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        val eng = sslContext.createSSLEngine()
        eng.useClientMode = true
        if ("TLSv1.2" in eng.supportedProtocols) {
            eng.enabledProtocols = arrayOf("TLSv1.2")
        }
        engine = eng

        val packetCap = max(engine.session.packetBufferSize, MIN_BUFFER_BYTES)
        val appCap = max(engine.session.applicationBufferSize, MIN_BUFFER_BYTES)
        encryptOutBuf = ByteBuffer.allocate(packetCap)
        decryptAppBuf = ByteBuffer.allocate(appCap)
    }

    /**
     * Drives the TLS 1.2 handshake to completion using caller-provided callbacks that send and
     * receive raw TLS record bytes carried inside AAP `SSL_HANDSHAKE` (`0x0003`) frames.
     */
    @Throws(IOException::class)
    fun performHandshake(
        sendSslBytes: (ByteArray) -> Unit,
        recvSslBytes: () -> ByteArray,
    ) {
        val emptyAppOut = ByteBuffer.allocate(0)
        var netOutBuf = ByteBuffer.allocate(max(engine.session.packetBufferSize, MIN_BUFFER_BYTES))
        var netInBuf = ByteBuffer.allocate(max(engine.session.packetBufferSize, MIN_BUFFER_BYTES))
        netInBuf.flip() // Initially empty, ready for reading
        var appInBuf = ByteBuffer.allocate(max(engine.session.applicationBufferSize, MIN_BUFFER_BYTES))

        val pendingOut = ByteArrayOutputStream()

        fun flushPendingOut() {
            if (pendingOut.size() == 0) return
            val all = pendingOut.toByteArray()
            pendingOut.reset()
            var offset = 0
            while (offset < all.size) {
                val chunkLen = minOf(MAX_HANDSHAKE_CHUNK_BYTES, all.size - offset)
                val chunk = if (offset == 0 && chunkLen == all.size) {
                    all
                } else {
                    all.copyOfRange(offset, offset + chunkLen)
                }
                sendSslBytes(chunk)
                offset += chunkLen
            }
        }

        engine.beginHandshake()
        var hsStatus = engine.handshakeStatus

        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (hsStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    drainDelegatedTasks()
                    hsStatus = engine.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOutBuf.clear()
                    val res = engine.wrap(emptyAppOut, netOutBuf)
                    when (res.status) {
                        SSLEngineResult.Status.OK -> {
                            netOutBuf.flip()
                            if (netOutBuf.hasRemaining()) {
                                val bytes = ByteArray(netOutBuf.remaining())
                                netOutBuf.get(bytes)
                                pendingOut.write(bytes)
                            }
                            hsStatus = res.handshakeStatus
                        }

                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            netOutBuf = growEmptyBuffer(netOutBuf, engine.session.packetBufferSize)
                        }

                        SSLEngineResult.Status.CLOSED -> {
                            throw SSLHandshakeException("TLS engine zatvoren tijekom slanja (wrap).")
                        }

                        SSLEngineResult.Status.BUFFER_UNDERFLOW, null -> {
                            throw SSLHandshakeException("Neočekivani status TLS slanja: ${res.status}.")
                        }
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // Send any queued client flight before waiting for the peer's response
                    flushPendingOut()

                    if (!netInBuf.hasRemaining()) {
                        val incoming = recvSslBytes()
                        if (incoming.isEmpty()) {
                            throw EOFException("Prazan SSL_HANDSHAKE paket tijekom TLS rukovanja.")
                        }
                        netInBuf = appendToReadableBuffer(netInBuf, incoming)
                    }

                    appInBuf.clear()
                    val res = engine.unwrap(netInBuf, appInBuf)
                    when (res.status) {
                        SSLEngineResult.Status.OK -> {
                            hsStatus = res.handshakeStatus
                        }

                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val incoming = recvSslBytes()
                            if (incoming.isEmpty()) {
                                throw EOFException("Prazan SSL_HANDSHAKE paket kod nastavka TLS zapisa.")
                            }
                            netInBuf = appendToReadableBuffer(netInBuf, incoming)
                        }

                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appInBuf = growEmptyBuffer(appInBuf, engine.session.applicationBufferSize)
                        }

                        SSLEngineResult.Status.CLOSED, null -> {
                            throw SSLHandshakeException("Poslužitelj je zatvorio TLS vezu tijekom rukovanja.")
                        }
                    }
                }

                else -> {
                    break
                }
            }
        }

        // Flush final handshake flight (e.g. client Finished) before returning
        flushPendingOut()

        // Preserve any trailing post-handshake ciphertext bytes for the first decrypt() call
        synchronized(unwrapLock) {
            if (netInBuf.hasRemaining()) {
                val leftover = ByteArray(netInBuf.remaining())
                netInBuf.get(leftover)
                decryptCarryOver = leftover
            } else {
                decryptCarryOver = ByteArray(0)
            }
        }

        negotiatedProtocol = engine.session.protocol
        negotiatedCipherSuite = engine.session.cipherSuite
        val serverSubject = serverCertSubject ?: "<nepoznato>"
        log(
            TAG,
            "TLS rukovanje uspješno: $negotiatedProtocol / $negotiatedCipherSuite, certifikat poslužitelja: $serverSubject",
        )
    }

    /**
     * Encrypts one AAP plaintext message/chunk into TLS record bytes suitable for a single
     * encrypted AAP wire frame.
     */
    @Throws(IOException::class)
    fun encrypt(plaintext: ByteArray): ByteArray = synchronized(wrapLock) {
        val src = ByteBuffer.wrap(plaintext)
        val out = ByteArrayOutputStream(plaintext.size + 128)
        do {
            encryptOutBuf.clear()
            val res = engine.wrap(src, encryptOutBuf)
            when (res.status) {
                SSLEngineResult.Status.OK -> {
                    encryptOutBuf.flip()
                    if (encryptOutBuf.hasRemaining()) {
                        val chunk = ByteArray(encryptOutBuf.remaining())
                        encryptOutBuf.get(chunk)
                        out.write(chunk)
                    }
                    if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        drainDelegatedTasks()
                    }
                }

                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    encryptOutBuf = growEmptyBuffer(encryptOutBuf, engine.session.packetBufferSize)
                }

                SSLEngineResult.Status.CLOSED -> {
                    throw IOException("TLS kanal je zatvoren pri kriptiranju.")
                }

                SSLEngineResult.Status.BUFFER_UNDERFLOW, null -> {
                    throw IOException("Neočekivani status TLS kriptiranja: ${res.status}.")
                }
            }
        } while (src.hasRemaining())
        out.toByteArray()
    }

    /**
     * Decrypts the TLS record(s) from one encrypted AAP wire frame into plaintext bytes.
     * Any trailing incomplete TLS record bytes are buffered cleanly for the subsequent frame.
     */
    @Throws(IOException::class)
    fun decrypt(ciphertext: ByteArray): ByteArray = synchronized(unwrapLock) {
        val combined = if (decryptCarryOver.isEmpty()) {
            ciphertext
        } else {
            val merged = ByteArray(decryptCarryOver.size + ciphertext.size)
            System.arraycopy(decryptCarryOver, 0, merged, 0, decryptCarryOver.size)
            System.arraycopy(ciphertext, 0, merged, decryptCarryOver.size, ciphertext.size)
            decryptCarryOver = ByteArray(0)
            merged
        }
        val inBuf = ByteBuffer.wrap(combined)
        val out = ByteArrayOutputStream(combined.size)

        while (inBuf.hasRemaining()) {
            decryptAppBuf.clear()
            val res = engine.unwrap(inBuf, decryptAppBuf)
            when (res.status) {
                SSLEngineResult.Status.OK -> {
                    decryptAppBuf.flip()
                    if (decryptAppBuf.hasRemaining()) {
                        val plain = ByteArray(decryptAppBuf.remaining())
                        decryptAppBuf.get(plain)
                        out.write(plain)
                    }
                    if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        drainDelegatedTasks()
                    }
                    if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) {
                        break
                    }
                }

                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    decryptAppBuf = growEmptyBuffer(decryptAppBuf, engine.session.applicationBufferSize)
                }

                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    break
                }

                SSLEngineResult.Status.CLOSED, null -> {
                    if (out.size() > 0) {
                        break
                    }
                    throw EOFException("Poslužitelj je poslao TLS close_notify.")
                }
            }
        }

        if (inBuf.hasRemaining()) {
            val rem = ByteArray(inBuf.remaining())
            inBuf.get(rem)
            decryptCarryOver = rem
        }
        out.toByteArray()
    }

    fun close() = synchronized(wrapLock) {
        try {
            engine.closeOutbound()
        } catch (_: Exception) {
            // Best-effort close
        }
    }

    private fun drainDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    private fun growEmptyBuffer(current: ByteBuffer, sessionHint: Int): ByteBuffer {
        val target = max(current.capacity() * 2, sessionHint + 1024)
        if (target > MAX_BUFFER_BYTES) {
            throw IOException("TLS buffer premašio dopuštenu granicu ($target B).")
        }
        return ByteBuffer.allocate(target)
    }

    private fun appendToReadableBuffer(current: ByteBuffer, incoming: ByteArray): ByteBuffer {
        val remaining = current.remaining()
        val needed = remaining + incoming.size
        if (needed > MAX_BUFFER_BYTES) {
            throw IOException("Ulazni TLS buffer premašio dopuštenu granicu ($needed B).")
        }
        val targetCap = max(current.capacity(), needed)
        val next = ByteBuffer.allocate(targetCap)
        if (remaining > 0) {
            next.put(current)
        }
        next.put(incoming)
        next.flip()
        return next
    }
}
