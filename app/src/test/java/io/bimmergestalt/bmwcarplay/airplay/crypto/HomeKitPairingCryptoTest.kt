package io.bimmergestalt.bmwcarplay.airplay.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.security.SecureRandom

class HomeKitPairingCryptoTest {

    @Test
    fun tlv8FragmentsAndReassemblesValuesLargerThan255Bytes() {
        val largePubKey = ByteArray(384) { (it * 7 + 13).toByte() }
        val encoded = Tlv8Codec.encode(
            listOf(
                Tlv8Codec.Item(Tlv8Codec.TYPE_STATE, byteArrayOf(0x03)),
                Tlv8Codec.Item(Tlv8Codec.TYPE_PUBLIC_KEY, largePubKey),
                Tlv8Codec.Item(Tlv8Codec.TYPE_FLAGS, byteArrayOf(0x10, 0x00, 0x00, 0x00)),
            ),
        )
        // 384 bytes splits into 255 + 129 -> 2 TLV headers (4 extra bytes)
        assertTrue(encoded.size > 384 + 10)
        val decoded = Tlv8Codec.decode(encoded)
        assertEquals(0x03, decoded[Tlv8Codec.TYPE_STATE]!![0].toInt())
        assertArrayEquals(largePubKey, decoded[Tlv8Codec.TYPE_PUBLIC_KEY])
        assertArrayEquals(byteArrayOf(0x10, 0x00, 0x00, 0x00), decoded[Tlv8Codec.TYPE_FLAGS])
    }

    @Test
    fun srp6aFullRoundTripMatchesServerSessionKeyAndProofs() {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val n = HomeKitSrp6a.N
        val g = HomeKitSrp6a.G
        val k = HomeKitSrp6a.K

        // Simulate a real HomeKit AirPlay receiver (username="Pair-Setup", pin="3939")
        val idBytes = HomeKitSrp6a.USERNAME.toByteArray(Charsets.UTF_8)
        val pinBytes = HomeKitSrp6a.DEFAULT_PIN.toByteArray(Charsets.UTF_8)
        val x = HomeKitSrp6a.toPositiveBigInt(
            AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512(idBytes, byteArrayOf(':'.code.toByte()), pinBytes)),
        )
        val v = g.modPow(x, n)
        val b = HomeKitSrp6a.toPositiveBigInt(ByteArray(32).also(rnd::nextBytes))
        val bigB = (k.multiply(v).add(g.modPow(b, n))).mod(n)
        val paddedB = HomeKitSrp6a.pad384(bigB)

        // Client computes A and M1
        val client = HomeKitSrp6a.start(salt, paddedB)
        val clientM1 = client.computeClientProof()
        assertNotNull(clientM1)
        assertEquals(64, clientM1!!.size)

        // Server verifies M1 and computes S, K, M2
        val bigA = HomeKitSrp6a.toPositiveBigInt(client.publicKeyA)
        val u = HomeKitSrp6a.toPositiveBigInt(AirPlayCrypto.sha512(client.publicKeyA, paddedB))
        val serverS = bigA.multiply(v.modPow(u, n)).mod(n).modPow(b, n)
        val serverKey = AirPlayCrypto.sha512(HomeKitSrp6a.toUnsignedBytes(serverS))
        assertArrayEquals(serverKey, client.sessionKey)

        val serverM2 = AirPlayCrypto.sha512(client.publicKeyA, clientM1, serverKey)
        assertTrue(client.verifyServerProof(serverM2))
        assertFalse(client.verifyServerProof(serverM2.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }))
    }

    @Test
    fun x25519Ed25519AndAesCtrStreamPrimitivesWorkConsistently() {
        val alice = AirPlayCrypto.generateX25519KeyPair()
        val bob = AirPlayCrypto.generateX25519KeyPair()
        assertArrayEquals(
            AirPlayCrypto.x25519SharedSecret(alice.privateKey, bob.publicKey),
            AirPlayCrypto.x25519SharedSecret(bob.privateKey, alice.publicKey),
        )

        val ed = AirPlayCrypto.generateEd25519KeyPair()
        val msg = "BMW F48-EntryEVO-0317".toByteArray(Charsets.UTF_8)
        val sig = AirPlayCrypto.ed25519Sign(ed.privateKey, msg)
        assertTrue(AirPlayCrypto.ed25519Verify(ed.publicKey, msg, sig))
        assertFalse(AirPlayCrypto.ed25519Verify(ed.publicKey, msg + byteArrayOf(1), sig))

        val (aesKey, aesIv) = AirPlayCrypto.deriveAesStreamKeys(ByteArray(16), 987654321L)
        val enc = AirPlayCrypto.Aes128CtrStream(aesKey, aesIv)
        val dec = AirPlayCrypto.Aes128CtrStream(aesKey, aesIv)
        val f1 = "Frame-1-payload".toByteArray()
        val f2 = "Frame-2-continuous-stream".toByteArray()
        assertArrayEquals(f1, dec.process(enc.process(f1)))
        assertArrayEquals(f2, dec.process(enc.process(f2)))
    }

    @Test
    fun rtspControlCipherEnforces1422BytePlaintextMtuAndRoundTripsMultiChunkMessages() {
        val keyA = ByteArray(32) { (it + 1).toByte() }
        val keyB = ByteArray(32) { (it + 65).toByte() }
        val sender = RtspControlCipher(readKey = keyB, writeKey = keyA)
        val receiver = RtspControlCipher(readKey = keyA, writeKey = keyB)

        // 3500 bytes must split into 3 chunks: 1422 + 1422 + 656 (each wire frame <= 1440 bytes!)
        val largePlaintext = ByteArray(3500) { (it * 13 + 5).toByte() }
        val wire = sender.encrypt(largePlaintext)

        val expectedWireLen = (2 + 1422 + 16) + (2 + 1422 + 16) + (2 + 656 + 16)
        assertEquals(expectedWireLen, wire.size)

        // Verify first frame's 2-byte LE header == 1422
        val firstLen = (wire[0].toInt() and 0xFF) or ((wire[1].toInt() and 0xFF) shl 8)
        assertEquals(RtspControlCipher.MAX_PLAINTEXT_CHUNK, firstLen)

        // Feed split across arbitrary TCP segment boundaries
        val part1 = receiver.decrypt(wire.copyOfRange(0, 700))
        assertEquals(0, part1.plaintext.size)
        val part2 = receiver.decrypt(part1.remainingEncrypted + wire.copyOfRange(700, wire.size))
        assertEquals(0, part2.remainingEncrypted.size)
        assertArrayEquals(largePlaintext, part2.plaintext)

        // Tampered ciphertext must throw IOException
        val corrupted = wire.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        val freshReceiver = RtspControlCipher(readKey = keyA, writeKey = keyB)
        try {
            freshReceiver.decrypt(corrupted)
            fail("Tampered ChaCha20-Poly1305 frame must fail authentication")
        } catch (_: IOException) {
            // expected
        }
    }
}
