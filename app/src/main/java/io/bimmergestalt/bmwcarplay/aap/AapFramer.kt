package io.bimmergestalt.bmwcarplay.aap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Pure-Kotlin wire framing for the Android Auto Protocol (AAP / GAL).
 *
 * Wire frame layout (big-endian):
 * - Standard frame:
 *   `[channelId: u8][flags: u8][payloadLen: u16BE][payload: payloadLen]`
 * - First fragment of a multi-frame message (`FIRST` set and `LAST` clear):
 *   `[channelId: u8][flags: u8][payloadLen: u16BE][totalSizeHint: u32BE][payload: payloadLen]`
 *
 * Note that in AAP, when [FLAG_ENCRYPTED] is set, each individual fragmented wire frame carries
 * its own TLS record. The caller must decrypt each wire frame's payload before passing plaintext
 * chunks to [AapFrameAssembler].
 */
object AapFramer {

    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08

    const val FLAG_PLAIN_BULK = FLAG_FIRST or FLAG_LAST // 0x03
    const val FLAG_ENC_BULK = FLAG_FIRST or FLAG_LAST or FLAG_ENCRYPTED // 0x0B
    const val FLAG_ENC_CONTROL = FLAG_FIRST or FLAG_LAST or FLAG_CONTROL or FLAG_ENCRYPTED // 0x0F

    const val MAX_FRAME_PAYLOAD_BYTES = 0xFFFF
    const val MAX_ASSEMBLED_MESSAGE_BYTES = 4 * 1024 * 1024

    data class RawFrame(
        val channelId: Int,
        val flags: Int,
        val payload: ByteArray,
        val totalSizeHint: Int? = null,
    ) {
        val isFirst: Boolean get() = (flags and FLAG_FIRST) != 0
        val isLast: Boolean get() = (flags and FLAG_LAST) != 0
        val isControl: Boolean get() = (flags and FLAG_CONTROL) != 0
        val isEncrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawFrame) return false
            return channelId == other.channelId &&
                flags == other.flags &&
                totalSizeHint == other.totalSizeHint &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = channelId
            result = 31 * result + flags
            result = 31 * result + (totalSizeHint ?: 0)
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Encodes a single AAP frame with a 4-byte header `[channelId][flags][len_u16BE][payload]`.
     */
    fun encodeFrame(channelId: Int, flags: Int, payload: ByteArray): ByteArray {
        require(channelId in 0..0xFF) { "AAP channelId out of range: $channelId" }
        require(flags in 0..0xFF) { "AAP flags out of range: $flags" }
        require(payload.size <= MAX_FRAME_PAYLOAD_BYTES) {
            "AAP frame payload exceeds 65535 bytes: ${payload.size}"
        }
        val out = ByteArray(4 + payload.size)
        out[0] = (channelId and 0xFF).toByte()
        out[1] = (flags and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 4, payload.size)
        }
        return out
    }

    /**
     * Encodes the first fragment of a multi-frame AAP message (`FIRST` set, `LAST` clear),
     * including the 4-byte big-endian [totalSizeHint] immediately after the 4-byte header.
     */
    fun encodeFirstFragmentFrame(
        channelId: Int,
        flags: Int,
        totalSizeHint: Int,
        payload: ByteArray,
    ): ByteArray {
        require(channelId in 0..0xFF) { "AAP channelId out of range: $channelId" }
        require(flags in 0..0xFF) { "AAP flags out of range: $flags" }
        require((flags and FLAG_FIRST) != 0 && (flags and FLAG_LAST) == 0) {
            "First fragment frame requires FLAG_FIRST set and FLAG_LAST clear (flags=0x%02X)".format(flags)
        }
        require(totalSizeHint in 0..MAX_ASSEMBLED_MESSAGE_BYTES) {
            "AAP totalSizeHint out of range: $totalSizeHint"
        }
        require(payload.size <= MAX_FRAME_PAYLOAD_BYTES) {
            "AAP frame payload exceeds 65535 bytes: ${payload.size}"
        }
        val out = ByteArray(8 + payload.size)
        out[0] = (channelId and 0xFF).toByte()
        out[1] = (flags and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        out[4] = ((totalSizeHint ushr 24) and 0xFF).toByte()
        out[5] = ((totalSizeHint ushr 16) and 0xFF).toByte()
        out[6] = ((totalSizeHint ushr 8) and 0xFF).toByte()
        out[7] = (totalSizeHint and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 8, payload.size)
        }
        return out
    }

    /**
     * Prepends a 2-byte big-endian [messageId] to [body].
     */
    fun prependMessageId(messageId: Int, body: ByteArray): ByteArray {
        require(messageId in 0..0xFFFF) { "AAP messageId out of range: $messageId" }
        val out = ByteArray(2 + body.size)
        out[0] = ((messageId ushr 8) and 0xFF).toByte()
        out[1] = (messageId and 0xFF).toByte()
        if (body.isNotEmpty()) {
            System.arraycopy(body, 0, out, 2, body.size)
        }
        return out
    }

    /**
     * Reads the leading 2-byte big-endian message ID from an assembled AAP message.
     */
    fun readMessageId(messageBytes: ByteArray): Int {
        require(messageBytes.size >= 2) {
            "AAP message too short for 2-byte messageId: ${messageBytes.size}"
        }
        return ((messageBytes[0].toInt() and 0xFF) shl 8) or (messageBytes[1].toInt() and 0xFF)
    }

    /**
     * Reads one complete wire frame from [input].
     *
     * Throws [EOFException] if the stream reaches EOF mid-header or mid-payload, and [IOException]
     * if a fragmented first frame declares an invalid or oversized `totalSizeHint`.
     */
    @Throws(IOException::class)
    fun readRawFrame(input: InputStream): RawFrame {
        val header = ByteArray(4)
        readFully(input, header, 0, 4)
        val channelId = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val payloadLen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

        var totalSizeHint: Int? = null
        val isFirstOnly = (flags and FLAG_FIRST) != 0 && (flags and FLAG_LAST) == 0
        if (isFirstOnly) {
            val sizeBytes = ByteArray(4)
            readFully(input, sizeBytes, 0, 4)
            val total = ((sizeBytes[0].toInt() and 0xFF) shl 24) or
                ((sizeBytes[1].toInt() and 0xFF) shl 16) or
                ((sizeBytes[2].toInt() and 0xFF) shl 8) or
                (sizeBytes[3].toInt() and 0xFF)
            if (total < 0 || total > MAX_ASSEMBLED_MESSAGE_BYTES) {
                throw IOException(
                    "Invalid AAP totalSizeHint=$total (cap=$MAX_ASSEMBLED_MESSAGE_BYTES) on ch=$channelId",
                )
            }
            totalSizeHint = total
        }

        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) {
            readFully(input, payload, 0, payloadLen)
        }
        return RawFrame(
            channelId = channelId,
            flags = flags,
            payload = payload,
            totalSizeHint = totalSizeHint,
        )
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, dest: ByteArray, offset: Int, length: Int) {
        var readSoFar = 0
        while (readSoFar < length) {
            val n = input.read(dest, offset + readSoFar, length - readSoFar)
            if (n < 0) {
                throw EOFException(
                    "Premature EOF while reading AAP frame (read $readSoFar of $length bytes)",
                )
            }
            readSoFar += n
        }
    }
}

/**
 * Per-channel plaintext reassembler for fragmented AAP messages.
 *
 * In AAP, each wire fragment carries its own TLS record, so the caller MUST decrypt each frame's
 * payload first and feed the resulting plaintext chunk into [feed].
 */
class AapFrameAssembler {

    private val partialByChannel = HashMap<Int, ByteArrayOutputStream>()

    /**
     * Feeds a decrypted (or plaintext) chunk belonging to [channelId] with wire [flags].
     *
     * Returns the complete reassembled message once a `LAST` fragment (or single `FIRST|LAST`
     * frame) completes, or `null` when waiting for more fragments or dropping an orphan fragment.
     *
     * Throws [IOException] if an assembled message exceeds [AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES].
     */
    @Throws(IOException::class)
    fun feed(
        channelId: Int,
        flags: Int,
        plaintextChunk: ByteArray,
        totalSizeHint: Int? = null,
    ): ByteArray? {
        val isFirst = (flags and AapFramer.FLAG_FIRST) != 0
        val isLast = (flags and AapFramer.FLAG_LAST) != 0

        if (plaintextChunk.size > AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES) {
            partialByChannel.remove(channelId)
            throw IOException(
                "AAP chunk exceeds maximum message size (${plaintextChunk.size} > ${AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES})",
            )
        }

        return when {
            isFirst && isLast -> {
                partialByChannel.remove(channelId)
                plaintextChunk
            }

            isFirst && !isLast -> {
                if (totalSizeHint != null &&
                    (totalSizeHint < 0 || totalSizeHint > AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES)
                ) {
                    partialByChannel.remove(channelId)
                    throw IOException("Invalid AAP totalSizeHint=$totalSizeHint on ch=$channelId")
                }
                val initialCapacity = (totalSizeHint ?: plaintextChunk.size).coerceIn(64, 65_536)
                val out = ByteArrayOutputStream(initialCapacity)
                out.write(plaintextChunk)
                partialByChannel[channelId] = out
                null
            }

            !isFirst && !isLast -> {
                val out = partialByChannel[channelId] ?: return null
                if (out.size() + plaintextChunk.size > AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES) {
                    partialByChannel.remove(channelId)
                    throw IOException(
                        "AAP assembled message exceeded ${AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES} bytes on ch=$channelId",
                    )
                }
                out.write(plaintextChunk)
                null
            }

            else -> {
                // !isFirst && isLast
                val out = partialByChannel.remove(channelId) ?: return null
                if (out.size() + plaintextChunk.size > AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES) {
                    throw IOException(
                        "AAP assembled message exceeded ${AapFramer.MAX_ASSEMBLED_MESSAGE_BYTES} bytes on ch=$channelId",
                    )
                }
                out.write(plaintextChunk)
                out.toByteArray()
            }
        }
    }

    /**
     * Discards all in-progress channel fragment buffers.
     */
    fun clear() {
        partialByChannel.clear()
    }
}
