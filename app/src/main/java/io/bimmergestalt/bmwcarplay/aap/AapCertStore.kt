package io.bimmergestalt.bmwcarplay.aap

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey as JcaEcPrivateKey
import java.security.interfaces.ECPublicKey as JcaEcPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey as JcaRsaPrivateKey
import java.security.interfaces.RSAPublicKey as JcaRsaPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date

/**
 * File-backed X.509 + private key store for the AAP Head Unit mTLS client.
 *
 * Strictly contains ZERO embedded OEM/Google keys or certificates:
 * - Supports user-imported PKCS#12 (`.p12`/`.pfx`) and PEM (`.pem`/`.crt` + `.key`,
 *   including PKCS#8 `PRIVATE KEY`, PKCS#1 `RSA PRIVATE KEY`, and SEC1 `EC PRIVATE KEY`).
 * - Falls back to a locally generated, self-signed RSA-2048 X.509 v3 certificate persisted
 *   inside [baseDir] (built purely with `bcprov` ASN.1, without `bcpkix`).
 */
class AapCertStore(
    private val baseDir: File,
) {

    data class CertInfo(
        val isImported: Boolean,
        val subject: String,
        val issuer: String,
        val validUntilIso: String,
        val isExpired: Boolean,
        val summaryHr: String,
    )

    data class LoadedCredentials(
        val certChain: Array<X509Certificate>,
        val privateKey: PrivateKey,
        val info: CertInfo,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LoadedCredentials) return false
            return certChain.contentEquals(other.certChain) &&
                privateKey == other.privateKey &&
                info == other.info
        }

        override fun hashCode(): Int {
            var result = certChain.contentHashCode()
            result = 31 * result + privateKey.hashCode()
            result = 31 * result + info.hashCode()
            return result
        }
    }

    private val lock = Any()

    private val importedCertFile: File get() = File(baseDir, IMPORTED_CERT_FILENAME)
    private val importedKeyFile: File get() = File(baseDir, IMPORTED_KEY_FILENAME)
    private val fallbackCertFile: File get() = File(baseDir, FALLBACK_CERT_FILENAME)
    private val fallbackKeyFile: File get() = File(baseDir, FALLBACK_KEY_FILENAME)

    /**
     * Imports a PKCS#12 (`.p12`/`.pfx`) keystore, verifies that the private key matches the leaf
     * certificate, and persists the full certificate chain + PKCS#8 private key into [baseDir].
     */
    fun importPkcs12(bytes: ByteArray, password: String): CertInfo = synchronized(lock) {
        require(bytes.isNotEmpty()) { "PKCS#12 datoteka je prazna." }
        val ks = KeyStore.getInstance("PKCS12")
        val pwdChars = password.toCharArray()
        try {
            ks.load(ByteArrayInputStream(bytes), pwdChars)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Neispravna PKCS#12 datoteka ili lozinka: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        var selectedKey: PrivateKey? = null
        var selectedChain: List<X509Certificate>? = null

        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!ks.isKeyEntry(alias)) continue
            val key = ks.getKey(alias, pwdChars) as? PrivateKey ?: continue
            val rawChain = ks.getCertificateChain(alias)
                ?.mapNotNull { it as? X509Certificate }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(ks.getCertificate(alias) as? X509Certificate)
            if (rawChain.isNotEmpty()) {
                selectedKey = key
                selectedChain = rawChain
                break
            }
        }

        val privateKey = selectedKey
            ?: throw IllegalArgumentException("PKCS#12 datoteka ne sadrži privatni ključ i certifikat.")
        val orderedChain = orderChainForKey(selectedChain!!, privateKey)
        verifyKeyMatchesCertificate(privateKey, orderedChain[0])

        persistCredentials(importedCertFile, importedKeyFile, orderedChain, privateKey)
        buildCertInfo(orderedChain[0], isImported = true)
    }

    /**
     * Imports a PEM certificate chain (1 or more X.509 certificates) and matching private key
     * (PKCS#8 `BEGIN PRIVATE KEY`, PKCS#1 `BEGIN RSA PRIVATE KEY`, or SEC1 `BEGIN EC PRIVATE KEY`).
     */
    fun importPem(certPemBytes: ByteArray, keyPemBytes: ByteArray): CertInfo = synchronized(lock) {
        require(certPemBytes.isNotEmpty()) { "Datoteka certifikata je prazna." }
        require(keyPemBytes.isNotEmpty()) { "Datoteka privatnog ključa je prazna." }

        val certs = parseX509Certificates(certPemBytes)
        if (certs.isEmpty()) {
            throw IllegalArgumentException("U datoteci nije pronađen nijedan valjani X.509 certifikat.")
        }
        val privateKey = parsePrivateKey(keyPemBytes)
        val orderedChain = orderChainForKey(certs, privateKey)
        verifyKeyMatchesCertificate(privateKey, orderedChain[0])

        persistCredentials(importedCertFile, importedKeyFile, orderedChain, privateKey)
        buildCertInfo(orderedChain[0], isImported = true)
    }

    /**
     * Removes any user-imported certificate and private key, reverting to the local fallback cert.
     */
    fun clearImported() = synchronized(lock) {
        if (importedCertFile.exists()) importedCertFile.delete()
        if (importedKeyFile.exists()) importedKeyFile.delete()
    }

    /**
     * Returns metadata about the currently active credentials (imported if present, otherwise local fallback).
     */
    fun getCertInfo(): CertInfo = loadCredentials().info

    /**
     * Loads the active mTLS credentials (imported if present and valid; otherwise loads or generates
     * the persistent local self-signed X.509 v3 fallback certificate).
     */
    fun loadCredentials(): LoadedCredentials = synchronized(lock) {
        if (importedCertFile.exists() && importedKeyFile.exists()) {
            try {
                val certs = parseX509Certificates(importedCertFile.readBytes())
                val key = parsePkcs8Key(importedKeyFile.readBytes())
                if (certs.isNotEmpty()) {
                    verifyKeyMatchesCertificate(key, certs[0])
                    val chain = certs.toTypedArray()
                    return LoadedCredentials(
                        certChain = chain,
                        privateKey = key,
                        info = buildCertInfo(chain[0], isImported = true),
                    )
                }
            } catch (_: Exception) {
                // Fall back cleanly if imported files were manually corrupted on disk
            }
        }

        if (fallbackCertFile.exists() && fallbackKeyFile.exists()) {
            try {
                val certs = parseX509Certificates(fallbackCertFile.readBytes())
                val key = parsePkcs8Key(fallbackKeyFile.readBytes())
                if (certs.isNotEmpty()) {
                    val leaf = certs[0]
                    val now = Date()
                    if (!now.after(leaf.notAfter) && !now.before(leaf.notBefore)) {
                        verifyKeyMatchesCertificate(key, leaf)
                        val chain = certs.toTypedArray()
                        return LoadedCredentials(
                            certChain = chain,
                            privateKey = key,
                            info = buildCertInfo(leaf, isImported = false),
                        )
                    }
                }
            } catch (_: Exception) {
                // Regenerate fallback below
            }
        }

        val (generatedChain, generatedKey) = generateSelfSignedCredentials(
            subjectDn = LOCAL_SUBJECT_DN,
            validityDays = LOCAL_VALIDITY_DAYS,
        )
        persistCredentials(fallbackCertFile, fallbackKeyFile, generatedChain.toList(), generatedKey)
        LoadedCredentials(
            certChain = generatedChain,
            privateKey = generatedKey,
            info = buildCertInfo(generatedChain[0], isImported = false),
        )
    }

    private fun persistCredentials(
        certFile: File,
        keyFile: File,
        chain: List<X509Certificate>,
        privateKey: PrivateKey,
    ) {
        if (!baseDir.exists() && !baseDir.mkdirs() && !baseDir.isDirectory) {
            throw IllegalStateException("Nije moguće stvoriti mapu za certifikate: ${baseDir.absolutePath}")
        }
        val pemText = encodeCertificatesToPem(chain)
        val pkcs8Bytes = privateKey.encoded
            ?: throw IllegalArgumentException("Privatni ključ ne podržava PKCS#8 kodiranje.")

        writeAtomically(certFile, pemText.toByteArray(Charsets.UTF_8), restrictPermissions = false)
        writeAtomically(keyFile, pkcs8Bytes, restrictPermissions = true)
    }

    private fun writeAtomically(target: File, bytes: ByteArray, restrictPermissions: Boolean) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (restrictPermissions) {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
        }
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        if (restrictPermissions) {
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        }
    }

    companion object {
        private const val IMPORTED_CERT_FILENAME = "imported_cert.pem"
        private const val IMPORTED_KEY_FILENAME = "imported_key.pkcs8"
        private const val FALLBACK_CERT_FILENAME = "local_fallback_cert.pem"
        private const val FALLBACK_KEY_FILENAME = "local_fallback_key.pkcs8"

        const val LOCAL_SUBJECT_DN = "CN=BMW CarPlay Probe Local, O=BimmerGestalt"
        private const val LOCAL_VALIDITY_DAYS = 3650

        private val PEM_BLOCK_REGEX = Regex(
            "-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----",
        )
        private val KEY_VERIFICATION_CHALLENGE =
            "BMW-CarPlay-Probe-AAP-KeyCheck-v1".toByteArray(Charsets.UTF_8)

        /**
         * Generates a fresh RSA-2048 self-signed X.509 v3 certificate using only `bcprov` ASN.1
         * structures (`V3TBSCertificateGenerator`) and JCA `SHA256withRSA`, then parses it back
         * through [CertificateFactory] to guarantee RFC 5280 compliance.
         */
        fun generateSelfSignedCredentials(
            subjectDn: String = LOCAL_SUBJECT_DN,
            validityDays: Int = LOCAL_VALIDITY_DAYS,
        ): Pair<Array<X509Certificate>, PrivateKey> {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val keyPair = kpg.generateKeyPair()

            val nowMs = System.currentTimeMillis()
            val notBefore = Date(nowMs - 24L * 60L * 60L * 1000L) // backdate 24h for clock skew
            val notAfter = Date(nowMs + validityDays.toLong() * 24L * 60L * 60L * 1000L)

            val serial = BigInteger(127, SecureRandom()).add(BigInteger.ONE)
            val dn = X500Name(subjectDn)
            val sigAlgId = AlgorithmIdentifier(
                PKCSObjectIdentifiers.sha256WithRSAEncryption,
                DERNull.INSTANCE,
            )

            val extGen = ExtensionsGenerator().apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(
                        KeyUsage.digitalSignature or
                            KeyUsage.keyEncipherment or
                            KeyUsage.keyCertSign,
                    ),
                )
            }

            val tbsGen = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(serial))
                setSignature(sigAlgId)
                setIssuer(dn)
                setStartDate(Time(notBefore))
                setEndDate(Time(notAfter))
                setSubject(dn)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keyPair.public.encoded))
                setExtensions(extGen.generate())
            }

            val tbsCert = tbsGen.generateTBSCertificate()
            val tbsDer = tbsCert.encoded

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(keyPair.private)
            signature.update(tbsDer)
            val sigBytes = signature.sign()

            val certSeq = DERSequence(
                ASN1EncodableVector().apply {
                    add(tbsCert)
                    add(sigAlgId)
                    add(DERBitString(sigBytes))
                },
            )
            val certDer = certSeq.encoded
            val certFactory = CertificateFactory.getInstance("X.509")
            val x509 = certFactory.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
            x509.verify(keyPair.public)

            return arrayOf(x509) to keyPair.private
        }

        fun encodeCertificatesToPem(chain: List<X509Certificate>): String {
            val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            val sb = StringBuilder()
            for (cert in chain) {
                sb.append("-----BEGIN CERTIFICATE-----\n")
                sb.append(encoder.encodeToString(cert.encoded))
                sb.append("\n-----END CERTIFICATE-----\n")
            }
            return sb.toString()
        }

        internal fun parseX509Certificates(bytes: ByteArray): List<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509")
            val text = bytes.toString(Charsets.UTF_8)
            val pemMatches = PEM_BLOCK_REGEX.findAll(text)
                .filter { it.groupValues[1].trim() == "CERTIFICATE" }
                .toList()

            if (pemMatches.isNotEmpty()) {
                val decoder = Base64.getMimeDecoder()
                val out = ArrayList<X509Certificate>(pemMatches.size)
                for (m in pemMatches) {
                    val der = decoder.decode(m.groupValues[2])
                    val cert = cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                    out.add(cert)
                }
                return out
            }

            // Fallback: raw DER certificate(s)
            return cf.generateCertificates(ByteArrayInputStream(bytes))
                .mapNotNull { it as? X509Certificate }
        }

        internal fun parsePrivateKey(bytes: ByteArray): PrivateKey {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.contains("ENCRYPTED PRIVATE KEY") || text.contains("Proc-Type: 4,ENCRYPTED")) {
                throw IllegalArgumentException(
                    "Šifrirani PEM privatni ključ nije podržan. Koristite nešifrirani PEM ili PKCS#12 (.p12/.pfx) s lozinkom.",
                )
            }

            val match = PEM_BLOCK_REGEX.findAll(text).firstOrNull { m ->
                val label = m.groupValues[1].trim()
                label == "PRIVATE KEY" || label == "RSA PRIVATE KEY" || label == "EC PRIVATE KEY"
            }

            if (match != null) {
                val label = match.groupValues[1].trim()
                val der = Base64.getMimeDecoder().decode(match.groupValues[2])
                return when (label) {
                    "PRIVATE KEY" -> parsePkcs8Key(der)
                    "RSA PRIVATE KEY" -> parsePkcs1RsaKey(der)
                    "EC PRIVATE KEY" -> parseSec1EcKey(der)
                    else -> throw IllegalArgumentException("Nepodržan PEM format ključa: $label")
                }
            }

            // Fallback: raw PKCS#8 DER
            return parsePkcs8Key(bytes)
        }

        internal fun parsePkcs8Key(der: ByteArray): PrivateKey {
            val spec = PKCS8EncodedKeySpec(der)
            for (alg in arrayOf("RSA", "EC")) {
                try {
                    return KeyFactory.getInstance(alg).generatePrivate(spec)
                } catch (_: Exception) {
                    // Try next algorithm
                }
            }
            throw IllegalArgumentException("Neispravan PKCS#8 privatni ključ (podržani su RSA i EC ključevi).")
        }

        internal fun parsePkcs1RsaKey(der: ByteArray): PrivateKey {
            try {
                val rsaAsn1 = RSAPrivateKey.getInstance(ASN1Sequence.getInstance(der))
                val crtSpec = RSAPrivateCrtKeySpec(
                    rsaAsn1.modulus,
                    rsaAsn1.publicExponent,
                    rsaAsn1.privateExponent,
                    rsaAsn1.prime1,
                    rsaAsn1.prime2,
                    rsaAsn1.exponent1,
                    rsaAsn1.exponent2,
                    rsaAsn1.coefficient,
                )
                return KeyFactory.getInstance("RSA").generatePrivate(crtSpec)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Neispravan PKCS#1 RSA privatni ključ: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
        }

        internal fun parseSec1EcKey(der: ByteArray): PrivateKey {
            try {
                val ecAsn1 = ECPrivateKey.getInstance(ASN1Sequence.getInstance(der))
                val params = ecAsn1.parametersObject
                    ?: throw IllegalArgumentException("EC ključ ne sadrži parametre krivulje.")
                val algId = AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, params)
                val pkcs8Der = PrivateKeyInfo(algId, ecAsn1).encoded
                return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Neispravan SEC1 EC privatni ključ: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
        }

        internal fun orderChainForKey(
            certs: List<X509Certificate>,
            privateKey: PrivateKey,
        ): List<X509Certificate> {
            if (certs.size <= 1) return certs
            val leafIdx = certs.indexOfFirst { cert -> keyMatchesPublicKey(privateKey, cert.publicKey) }
            if (leafIdx <= 0) return certs
            val reordered = ArrayList<X509Certificate>(certs.size)
            reordered.add(certs[leafIdx])
            for (i in certs.indices) {
                if (i != leafIdx) reordered.add(certs[i])
            }
            return reordered
        }

        internal fun verifyKeyMatchesCertificate(privateKey: PrivateKey, leaf: X509Certificate) {
            if (!keyMatchesPublicKey(privateKey, leaf.publicKey)) {
                throw IllegalArgumentException(
                    "Privatni ključ ne odgovara javnom ključu u certifikatu (${leaf.subjectX500Principal.name}).",
                )
            }
        }

        private fun keyMatchesPublicKey(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
            if (privateKey is JcaRsaPrivateKey && publicKey is JcaRsaPublicKey) {
                if (privateKey.modulus != publicKey.modulus) return false
                if (privateKey is RSAPrivateCrtKey && privateKey.publicExponent != publicKey.publicExponent) {
                    return false
                }
                return verifyBySignature("SHA256withRSA", privateKey, publicKey)
            }
            if (privateKey is JcaEcPrivateKey && publicKey is JcaEcPublicKey) {
                return verifyBySignature("SHA256withECDSA", privateKey, publicKey)
            }
            return false
        }

        private fun verifyBySignature(
            algorithm: String,
            privateKey: PrivateKey,
            publicKey: PublicKey,
        ): Boolean = try {
            val signer = Signature.getInstance(algorithm)
            signer.initSign(privateKey)
            signer.update(KEY_VERIFICATION_CHALLENGE)
            val sig = signer.sign()

            val verifier = Signature.getInstance(algorithm)
            verifier.initVerify(publicKey)
            verifier.update(KEY_VERIFICATION_CHALLENGE)
            verifier.verify(sig)
        } catch (_: Exception) {
            false
        }

        internal fun buildCertInfo(leaf: X509Certificate, isImported: Boolean): CertInfo {
            val subject = leaf.subjectX500Principal.name
            val issuer = leaf.issuerX500Principal.name
            val validUntilIso = DateTimeFormatter.ISO_INSTANT.format(
                leaf.notAfter.toInstant().truncatedTo(ChronoUnit.SECONDS),
            )
            val validDateShort = validUntilIso.substringBefore('T')
            val isExpired = Date().after(leaf.notAfter)
            val cn = extractCommonName(subject)
            val summaryHr = when {
                isImported && isExpired ->
                    "Uvezeni certifikat ($cn) — ISTEKAO ($validDateShort)"
                isImported ->
                    "Uvezeni certifikat ($cn), vrijedi do $validDateShort"
                else ->
                    "Lokalni samopotpisani certifikat ($cn) — za Google Android Auto može biti potreban uvezeni HU certifikat"
            }
            return CertInfo(
                isImported = isImported,
                subject = subject,
                issuer = issuer,
                validUntilIso = validUntilIso,
                isExpired = isExpired,
                summaryHr = summaryHr,
            )
        }

        private fun extractCommonName(dn: String): String {
            val parts = dn.split(',')
            for (p in parts) {
                val trimmed = p.trim()
                if (trimmed.startsWith("CN=", ignoreCase = true)) {
                    return trimmed.substring(3).trim()
                }
            }
            return dn
        }
    }
}
