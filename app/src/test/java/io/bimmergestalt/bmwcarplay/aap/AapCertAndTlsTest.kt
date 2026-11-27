package io.bimmergestalt.bmwcarplay.aap

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.RSAPrivateKey as BcRsaPrivateKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

class AapCertAndTlsTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun fallbackSelfSignedCertIsGeneratedValidAndPersistedAcrossReloads() {
        val dir = tempFolder.newFolder("certs-fallback")
        val store = AapCertStore(dir)

        val creds1 = store.loadCredentials()
        assertFalse(creds1.info.isImported)
        assertFalse(creds1.info.isExpired)
        assertTrue(creds1.info.subject.contains("BMW CarPlay Probe Local"))
        assertTrue(creds1.info.summaryHr.contains("Lokalni samopotpisani certifikat"))
        assertEquals(1, creds1.certChain.size)
        creds1.certChain[0].verify(creds1.certChain[0].publicKey)

        // Reload from disk — must keep the exact same certificate serial
        val store2 = AapCertStore(dir)
        val creds2 = store2.loadCredentials()
        assertEquals(creds1.certChain[0].serialNumber, creds2.certChain[0].serialNumber)
        assertFalse(creds2.info.isImported)
    }

    @Test
    fun importPemSupportsPkcs8AndPkcs1RejectsMismatchAndClearsCleanly() {
        val dir = tempFolder.newFolder("certs-pem")
        val store = AapCertStore(dir)

        val (chain1, key1) = AapCertStore.generateSelfSignedCredentials("CN=Test Imported HU, O=BMW")
        val certPem = AapCertStore.encodeCertificatesToPem(chain1.toList()).toByteArray(Charsets.UTF_8)

        // 1) PKCS#8 PEM import
        val pkcs8Pem = toPem("PRIVATE KEY", key1.encoded).toByteArray(Charsets.UTF_8)
        val info8 = store.importPem(certPem, pkcs8Pem)
        assertTrue(info8.isImported)
        assertFalse(info8.isExpired)
        assertTrue(info8.subject.contains("Test Imported HU"))

        val loaded8 = store.loadCredentials()
        assertTrue(loaded8.info.isImported)
        assertEquals(chain1[0].serialNumber, loaded8.certChain[0].serialNumber)

        // 2) PKCS#1 (BEGIN RSA PRIVATE KEY) PEM import
        val crtKey = key1 as RSAPrivateCrtKey
        val pkcs1Der = BcRsaPrivateKey(
            crtKey.modulus,
            crtKey.publicExponent,
            crtKey.privateExponent,
            crtKey.primeP,
            crtKey.primeQ,
            crtKey.primeExponentP,
            crtKey.primeExponentQ,
            crtKey.crtCoefficient,
        ).encoded
        val pkcs1Pem = toPem("RSA PRIVATE KEY", pkcs1Der).toByteArray(Charsets.UTF_8)

        store.clearImported()
        assertFalse(store.getCertInfo().isImported)

        val info1 = store.importPem(certPem, pkcs1Pem)
        assertTrue(info1.isImported)
        assertEquals(chain1[0].serialNumber, store.loadCredentials().certChain[0].serialNumber)

        // 3) Mismatched key must be rejected
        val (_, unrelatedKey) = AapCertStore.generateSelfSignedCredentials("CN=Other, O=Other")
        val unrelatedPem = toPem("PRIVATE KEY", unrelatedKey.encoded).toByteArray(Charsets.UTF_8)
        try {
            store.importPem(certPem, unrelatedPem)
            fail("Expected IllegalArgumentException for mismatched private key")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // 4) clearImported reverts to local fallback
        store.clearImported()
        val fallbackInfo = store.getCertInfo()
        assertFalse(fallbackInfo.isImported)
        assertTrue(fallbackInfo.subject.contains("BMW CarPlay Probe Local"))
    }

    @Test
    fun importPemSupportsEcPkcs8Certificate() {
        val dir = tempFolder.newFolder("certs-ec")
        val store = AapCertStore(dir)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()

        val dn = X500Name("CN=EC Test HU, O=BMW")
        val sigAlgId = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val now = System.currentTimeMillis()
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(127, SecureRandom()).add(BigInteger.ONE)))
            setSignature(sigAlgId)
            setIssuer(dn)
            setStartDate(Time(Date(now - 60_000L)))
            setEndDate(Time(Date(now + 365L * 86_400_000L)))
            setSubject(dn)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.public.encoded))
        }.generateTBSCertificate()

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(tbs.encoded)
        val certDer = DERSequence(
            ASN1EncodableVector().apply {
                add(tbs)
                add(sigAlgId)
                add(DERBitString(sig.sign()))
            },
        ).encoded
        val ecCert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        val certPem = AapCertStore.encodeCertificatesToPem(listOf(ecCert)).toByteArray(Charsets.UTF_8)
        val keyPem = toPem("PRIVATE KEY", kp.private.encoded).toByteArray(Charsets.UTF_8)

        val info = store.importPem(certPem, keyPem)
        assertTrue(info.isImported)
        assertTrue(info.subject.contains("EC Test HU"))
        assertEquals("EC", store.loadCredentials().privateKey.algorithm)
    }

    @Test
    fun importPkcs12LoadsChainAndRejectsWrongPassword() {
        val dir = tempFolder.newFolder("certs-p12")
        val store = AapCertStore(dir)

        val (chain, key) = AapCertStore.generateSelfSignedCredentials("CN=PKCS12 HU, O=BMW")
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("hu", key, "tajna123".toCharArray(), chain)
        val baos = ByteArrayOutputStream()
        ks.store(baos, "tajna123".toCharArray())
        val p12Bytes = baos.toByteArray()

        try {
            store.importPkcs12(p12Bytes, "kriva-lozinka")
            fail("Expected IllegalArgumentException for wrong PKCS#12 password")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        val info = store.importPkcs12(p12Bytes, "tajna123")
        assertTrue(info.isImported)
        assertTrue(info.subject.contains("PKCS12 HU"))
        assertEquals(chain[0].serialNumber, store.loadCredentials().certChain[0].serialNumber)
    }

    @Test
    fun mutualTls12HandshakeAndApplicationDataRoundTrip() {
        val dir = tempFolder.newFolder("certs-tls")
        val store = AapCertStore(dir)
        val clientCreds = store.loadCredentials()

        val (serverChain, serverKey) = AapCertStore.generateSelfSignedCredentials("CN=Mock Phone AA Server, O=Google")
        val serverEngine = createServerSslEngine(serverChain, serverKey, acceptClientCert = true)

        val logs = mutableListOf<String>()
        val clientTls = AapTlsChannel(clientCreds) { tag, msg -> logs.add("$tag: $msg") }

        val clientToServer = LinkedBlockingQueue<ByteArray>()
        val serverToClient = LinkedBlockingQueue<ByteArray>()

        val exec = Executors.newSingleThreadExecutor()
        val serverFuture = exec.submit<X509Certificate?> {
            driveServerHandshake(
                engine = serverEngine,
                recv = { clientToServer.poll(5, TimeUnit.SECONDS) ?: throw EOFException("Timeout waiting for client") },
                send = { bytes ->
                    // Split server flight into small 256-byte pieces to exercise BUFFER_UNDERFLOW handling
                    var off = 0
                    while (off < bytes.size) {
                        val n = minOf(256, bytes.size - off)
                        serverToClient.put(bytes.copyOfRange(off, off + n))
                        off += n
                    }
                },
            )
            serverEngine.session.peerCertificates.firstOrNull() as? X509Certificate
        }

        try {
            clientTls.performHandshake(
                sendSslBytes = { clientToServer.put(it) },
                recvSslBytes = { serverToClient.poll(5, TimeUnit.SECONDS) ?: throw EOFException("Timeout waiting for server") },
            )
            val peerCertSeenByServer = serverFuture.get(5, TimeUnit.SECONDS)
            assertNotNull("Server must receive client mTLS certificate", peerCertSeenByServer)
            assertEquals(clientCreds.certChain[0].serialNumber, peerCertSeenByServer!!.serialNumber)
            assertEquals("TLSv1.2", clientTls.negotiatedProtocol)
            assertNotNull(clientTls.negotiatedCipherSuite)
            assertTrue(clientTls.serverCertSubject.orEmpty().contains("Mock Phone AA Server"))

            // Client -> Server encryption round-trip (small + 24 KB large message)
            val smallMsg = "Pozdrav iz BMW X1".toByteArray(Charsets.UTF_8)
            val smallCipher = clientTls.encrypt(smallMsg)
            assertArrayEquals(smallMsg, serverUnwrapAll(serverEngine, smallCipher))

            val largeMsg = ByteArray(24_576) { ((it * 13 + 5) and 0xFF).toByte() }
            val largeCipher = clientTls.encrypt(largeMsg)
            assertArrayEquals(largeMsg, serverUnwrapAll(serverEngine, largeCipher))

            // Server -> Client encryption round-trip (including fragmented single TLS record)
            val fromServer = ByteArray(20_000) { ((it * 7) and 0xFF).toByte() }
            val serverCipher = serverWrapAll(serverEngine, fromServer)
            val half = serverCipher.size / 2
            val p1 = clientTls.decrypt(serverCipher.copyOfRange(0, half))
            val p2 = clientTls.decrypt(serverCipher.copyOfRange(half, serverCipher.size))
            assertArrayEquals(fromServer, p1 + p2)
        } finally {
            clientTls.close()
            exec.shutdownNow()
        }
    }

    @Test
    fun mutualTlsPropagatesExceptionWhenServerRejectsClientCert() {
        val dir = tempFolder.newFolder("certs-tls-reject")
        val store = AapCertStore(dir)
        val clientCreds = store.loadCredentials()

        val (serverChain, serverKey) = AapCertStore.generateSelfSignedCredentials("CN=Strict Google AA Server")
        val serverEngine = createServerSslEngine(serverChain, serverKey, acceptClientCert = false)

        val clientTls = AapTlsChannel(clientCreds) { _, _ -> }
        val clientToServer = LinkedBlockingQueue<ByteArray>()
        val serverToClient = LinkedBlockingQueue<ByteArray>()

        val exec = Executors.newSingleThreadExecutor()
        exec.submit {
            try {
                driveServerHandshake(
                    engine = serverEngine,
                    recv = { clientToServer.poll(5, TimeUnit.SECONDS) ?: throw EOFException() },
                    send = { serverToClient.put(it) },
                )
            } catch (_: Exception) {
                // Flush any TLS alert bytes emitted by serverEngine, then signal EOF
                val alert = ByteBuffer.allocate(4096)
                try {
                    val res = serverEngine.wrap(ByteBuffer.allocate(0), alert)
                    alert.flip()
                    if (alert.hasRemaining()) {
                        val b = ByteArray(alert.remaining())
                        alert.get(b)
                        serverToClient.put(b)
                    }
                    assertNotNull(res)
                } catch (_: Exception) {
                    // Ignore
                }
                serverToClient.put(ByteArray(0))
            }
        }

        try {
            clientTls.performHandshake(
                sendSslBytes = { clientToServer.put(it) },
                recvSslBytes = { serverToClient.poll(5, TimeUnit.SECONDS) ?: throw EOFException() },
            )
            fail("Expected SSLException or EOFException when server rejects client certificate")
        } catch (e: Exception) {
            assertTrue(
                "Expected SSLException or EOFException, got ${e.javaClass.name}: ${e.message}",
                e is SSLException || e is EOFException,
            )
        } finally {
            clientTls.close()
            exec.shutdownNow()
        }
    }

    private fun toPem(label: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN $label-----\n$b64\n-----END $label-----\n"
    }

    private fun createServerSslEngine(
        serverChain: Array<X509Certificate>,
        serverKey: PrivateKey,
        acceptClientCert: Boolean,
    ): SSLEngine {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("server", serverKey, CharArray(0), serverChain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!acceptClientCert) {
                    throw CertificateException("Client certificate not signed by trusted CA")
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(kmf.keyManagers, arrayOf(tm), SecureRandom())
        return ctx.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
            if ("TLSv1.2" in supportedProtocols) {
                enabledProtocols = arrayOf("TLSv1.2")
            }
        }
    }

    private fun driveServerHandshake(
        engine: SSLEngine,
        recv: () -> ByteArray,
        send: (ByteArray) -> Unit,
    ) {
        val empty = ByteBuffer.allocate(0)
        var netOut = ByteBuffer.allocate(32_768)
        var netIn = ByteBuffer.allocate(32_768).apply { flip() }
        var appIn = ByteBuffer.allocate(32_768)
        val pendingOut = ByteArrayOutputStream()

        fun flushOut() {
            if (pendingOut.size() > 0) {
                send(pendingOut.toByteArray())
                pendingOut.reset()
            }
        }

        engine.beginHandshake()
        var status = engine.handshakeStatus
        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) {
                        val t = engine.delegatedTask ?: break
                        t.run()
                    }
                    status = engine.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val res = engine.wrap(empty, netOut)
                    if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                        continue
                    }
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        val b = ByteArray(netOut.remaining())
                        netOut.get(b)
                        pendingOut.write(b)
                    }
                    status = res.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    flushOut()
                    if (!netIn.hasRemaining()) {
                        val incoming = recv()
                        val next = ByteBuffer.allocate(maxOf(netIn.capacity(), incoming.size))
                        next.put(incoming)
                        next.flip()
                        netIn = next
                    }
                    appIn.clear()
                    val res = engine.unwrap(netIn, appIn)
                    when (res.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val incoming = recv()
                            val next = ByteBuffer.allocate(maxOf(netIn.capacity(), netIn.remaining() + incoming.size))
                            next.put(netIn)
                            next.put(incoming)
                            next.flip()
                            netIn = next
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                        }
                        SSLEngineResult.Status.CLOSED, null -> throw EOFException("Closed")
                        SSLEngineResult.Status.OK -> status = res.handshakeStatus
                    }
                }

                else -> break
            }
        }
        flushOut()
    }

    private fun serverWrapAll(engine: SSLEngine, plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        var dst = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, 32_768))
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            dst.clear()
            val res = engine.wrap(src, dst)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                dst = ByteBuffer.allocate(dst.capacity() * 2)
                continue
            }
            dst.flip()
            val b = ByteArray(dst.remaining())
            dst.get(b)
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun serverUnwrapAll(engine: SSLEngine, cipher: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(cipher)
        var dst = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, 32_768))
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            dst.clear()
            val res = engine.unwrap(src, dst)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                dst = ByteBuffer.allocate(dst.capacity() * 2)
                continue
            }
            dst.flip()
            val b = ByteArray(dst.remaining())
            dst.get(b)
            out.write(b)
        }
        return out.toByteArray()
    }
}
