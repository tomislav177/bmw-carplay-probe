package io.bimmergestalt.bmwcarplay.airplay.crypto

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * HomeKit / AirPlay ChaCha20-Poly1305 framed transport cipher for RTSP Control and Event sockets.
 *
 * Wire frame layout:
 *   `[u16 LE plaintextLength (2B AAD)] [ciphertext (plaintextLength)] [Poly1305 tag (16B)]`
 *
 * Strictly enforces AirPlay automotive MTU:
 * - Max wire frame = `1440` bytes (`AIRPLAY_CIPHER_MTU_MAX`)
 * - Max plaintext per frame = `1440 - 2 - 16 = 1422` bytes (`MAX_PLAINTEXT_CHUNK`)
 *   (larger chunks cause strict automotive receivers such as AirTunes/280.33.8 to reject the frame).
 *
 * Read and write directions maintain independent 64-bit LE nonce counters starting at 0.
 */
class RtspControlCipher(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val AIRPLAY_CIPHER_MTU_MAX = 1440
        const val MAX_PLAINTEXT_CHUNK = AIRPLAY_CIPHER_MTU_MAX - HEADER_SIZE - TAG_SIZE // 1422 bytes
    }

    data class DecryptResult(
        val plaintext: ByteArray,
        val remainingEncrypted: ByteArray,
    )

    private val readLock = Any()
    private val writeLock = Any()
    private var readCounter = 0L
    private var writeCounter = 0L

    fun encrypt(plaintext: ByteArray): ByteArray = synchronized(writeLock) {
        val out = ByteArrayOutputStream(plaintext.size + 32)
        var offset = 0
        do {
            val end = minOf(offset + MAX_PLAINTEXT_CHUNK, plaintext.size)
            val chunk = plaintext.copyOfRange(offset, end)
            val aadHeader = byteArrayOf(
                (chunk.size and 0xFF).toByte(),
                ((chunk.size ushr 8) and 0xFF).toByte(),
            )
            val sealed = AirPlayCrypto.chachaSeal(
                key = writeKey,
                nonce = AirPlayCrypto.nonce64(writeCounter),
                plaintext = chunk,
                aad = aadHeader,
            )
            writeCounter++
            out.write(aadHeader)
            out.write(sealed)
            offset = end
        } while (offset < plaintext.size)
        out.toByteArray()
    }

    @Throws(IOException::class)
    fun decrypt(buffer: ByteArray): DecryptResult = synchronized(readLock) {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aadHeader = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            val plain = AirPlayCrypto.chachaOpen(
                key = readKey,
                nonce = AirPlayCrypto.nonce64(readCounter),
                ciphertextAndTag = ciphertextAndTag,
                aad = aadHeader,
            )
            readCounter++
            out.write(plain)
            offset = frameEnd
        }
        DecryptResult(
            plaintext = out.toByteArray(),
            remainingEncrypted = if (offset < buffer.size) buffer.copyOfRange(offset, buffer.size) else ByteArray(0),
        )
    }
}
