package io.bimmergestalt.bmwcarplay.iap2.csm

import io.bimmergestalt.bmwcarplay.diag.HexDump
import java.io.ByteArrayOutputStream

/**
 * iAP2 Control Session Message (0x4040) framing, TLV encoder/decoder, and streaming reassembler.
 *
 * Frame layout (big-endian):
 *   0x40 0x40 | totalLen (u16, includes 6-byte header) | msgId (u16) | TLV parameters...
 *
 * Parameter (TLV) layout (big-endian):
 *   paramLen (u16, includes 4-byte header) | paramId (u16) | valueBytes...
 *
 * Strings in TLV values are UTF-8 NUL-terminated; when decoding, ALL trailing NUL bytes
 * are stripped (some head units NUL-pad fixed-length fields such as IPv4/IPv6 strings).
 */
object Iap2CsmCodec {

    const val START_HI = 0x40
    const val START_LO = 0x40
    const val HEADER_SIZE = 6
    const val PARAM_HEADER_SIZE = 4

    data class CsmFrame(
        val messageId: Int,
        val body: ByteArray,
    ) {
        fun encode(): ByteArray = encodeFrame(messageId, body)

        fun parseParams(): List<TlvParam> = parseTlvParams(body)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CsmFrame) return false
            return messageId == other.messageId && body.contentEquals(other.body)
        }

        override fun hashCode(): Int = 31 * messageId + body.contentHashCode()
    }

    data class TlvParam(
        val id: Int,
        val value: ByteArray,
    ) {
        /**
         * Decodes a NUL-terminated (or NUL-padded) UTF-8 string, stripping ALL trailing 0x00 bytes.
         */
        fun asString(): String {
            var end = value.size
            while (end > 0 && value[end - 1] == 0.toByte()) {
                end--
            }
            return value.copyOf(end).toString(Charsets.UTF_8)
        }

        fun asU8(): Int? = if (value.size == 1) value[0].toInt() and 0xFF else null

        fun asU16(): Int? = if (value.size == 2) {
            ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        } else {
            null
        }

        fun asU32(): Long? = if (value.size == 4) {
            ((value[0].toLong() and 0xFF) shl 24) or
                ((value[1].toLong() and 0xFF) shl 16) or
                ((value[2].toLong() and 0xFF) shl 8) or
                (value[3].toLong() and 0xFF)
        } else {
            null
        }

        fun asU16List(): List<Int> {
            if (value.size < 2 || value.size % 2 != 0) return emptyList()
            val out = ArrayList<Int>(value.size / 2)
            var i = 0
            while (i + 1 < value.size) {
                out.add(((value[i].toInt() and 0xFF) shl 8) or (value[i + 1].toInt() and 0xFF))
                i += 2
            }
            return out
        }

        fun parseNestedParams(): List<TlvParam> = parseTlvParams(value)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TlvParam) return false
            return id == other.id && value.contentEquals(other.value)
        }

        override fun hashCode(): Int = 31 * id + value.contentHashCode()
    }

    fun encodeFrame(messageId: Int, vararg paramChunks: ByteArray): ByteArray {
        val bodySize = paramChunks.sumOf { it.size }
        val totalLen = HEADER_SIZE + bodySize
        val out = ByteArray(totalLen)
        out[0] = START_HI.toByte()
        out[1] = START_LO.toByte()
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = ((messageId ushr 8) and 0xFF).toByte()
        out[5] = (messageId and 0xFF).toByte()
        var pos = HEADER_SIZE
        for (chunk in paramChunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        return out
    }

    fun paramRaw(id: Int, value: ByteArray): ByteArray {
        val paramLen = PARAM_HEADER_SIZE + value.size
        val out = ByteArray(paramLen)
        out[0] = ((paramLen ushr 8) and 0xFF).toByte()
        out[1] = (paramLen and 0xFF).toByte()
        out[2] = ((id ushr 8) and 0xFF).toByte()
        out[3] = (id and 0xFF).toByte()
        if (value.isNotEmpty()) {
            System.arraycopy(value, 0, out, PARAM_HEADER_SIZE, value.size)
        }
        return out
    }

    fun paramEmpty(id: Int): ByteArray = paramRaw(id, ByteArray(0))

    fun paramU8(id: Int, value: Int): ByteArray = paramRaw(id, byteArrayOf((value and 0xFF).toByte()))

    fun paramU16(id: Int, value: Int): ByteArray =
        paramRaw(id, byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()))

    fun paramU32(id: Int, value: Long): ByteArray = paramRaw(
        id,
        byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        ),
    )

    fun paramU64(id: Int, value: Long): ByteArray = paramRaw(
        id,
        ByteArray(8) { idx -> ((value ushr (8 * (7 - idx))) and 0xFF).toByte() },
    )

    fun paramU16List(id: Int, values: List<Int>): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { idx, v ->
            bytes[idx * 2] = ((v ushr 8) and 0xFF).toByte()
            bytes[idx * 2 + 1] = (v and 0xFF).toByte()
        }
        return paramRaw(id, bytes)
    }

    /**
     * Encodes a UTF-8 string terminated with a single NUL (0x00) byte.
     */
    fun paramString(id: Int, text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val terminated = ByteArray(utf8.size + 1)
        System.arraycopy(utf8, 0, terminated, 0, utf8.size)
        terminated[utf8.size] = 0
        return paramRaw(id, terminated)
    }

    /**
     * Encodes a nested TLV parameter group.
     */
    fun paramGroup(id: Int, vararg children: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (child in children) {
            baos.write(child)
        }
        return paramRaw(id, baos.toByteArray())
    }

    /**
     * Parses TLV parameters from a CSM body or nested group byte slice.
     */
    fun parseTlvParams(bytes: ByteArray): List<TlvParam> {
        val out = ArrayList<TlvParam>()
        var offset = 0
        while (offset + PARAM_HEADER_SIZE <= bytes.size) {
            val len = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            val id = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            if (len < PARAM_HEADER_SIZE || offset + len > bytes.size) {
                break
            }
            val value = bytes.copyOfRange(offset + PARAM_HEADER_SIZE, offset + len)
            out.add(TlvParam(id, value))
            offset += len
        }
        return out
    }

    /**
     * Produces a human-readable multi-line breakdown of all TLV parameters inside a CSM body.
     */
    fun dumpParamsReadable(bytes: ByteArray, indent: String = "  "): String {
        val params = parseTlvParams(bytes)
        if (params.isEmpty()) {
            return if (bytes.isEmpty()) "${indent}<bez parametara>" else "${indent}Sirovo (${bytes.size} B): ${HexDump.toCompactHex(bytes, 128)}"
        }
        val sb = StringBuilder()
        params.forEachIndexed { idx, p ->
            if (idx > 0) sb.append('\n')
            val preview = formatValuePreview(p.value)
            sb.append(indent)
            sb.append(String.format("TLV id=0x%04X (%d) len=%dB -> %s", p.id, p.id, p.value.size, preview))
        }
        return sb.toString()
    }

    private fun formatValuePreview(value: ByteArray): String {
        if (value.isEmpty()) return "<prazno/flag>"
        if (value.size == 1) {
            val v = value[0].toInt() and 0xFF
            return String.format("u8=%d (0x%02X)", v, v)
        }
        if (value.size == 2) {
            val v = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            return String.format("u16=%d (0x%04X)", v, v)
        }
        // Check if printable NUL-terminated UTF-8 string
        val looksLikeString = value.last() == 0.toByte() && run {
            var end = value.size
            while (end > 0 && value[end - 1] == 0.toByte()) end--
            end > 0 && (0 until end).all { i ->
                val c = value[i].toInt() and 0xFF
                c in 0x20..0x7E || c >= 0x80
            }
        }
        if (looksLikeString) {
            var end = value.size
            while (end > 0 && value[end - 1] == 0.toByte()) end--
            val str = value.copyOf(end).toString(Charsets.UTF_8)
            return "\"$str\" [HEX: ${HexDump.toCompactHex(value, 48)}]"
        }
        return "HEX: ${HexDump.toCompactHex(value, 64)}"
    }

    /**
     * Stateful streaming reassembler that combines fragmented CSM payloads across multiple
     * iAP2 link packets and splits multiple CSM frames packed into a single link packet.
     */
    class CsmReassembler {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray): List<CsmFrame> {
            if (chunk.isEmpty()) return emptyList()
            val merged = ByteArray(buffer.size + chunk.size)
            System.arraycopy(buffer, 0, merged, 0, buffer.size)
            System.arraycopy(chunk, 0, merged, buffer.size, chunk.size)
            buffer = merged

            val frames = ArrayList<CsmFrame>()
            var pos = 0
            while (pos + 2 <= buffer.size) {
                val b0 = buffer[pos].toInt() and 0xFF
                val b1 = buffer[pos + 1].toInt() and 0xFF
                if (b0 != START_HI || b1 != START_LO) {
                    pos++
                    continue
                }
                if (pos + HEADER_SIZE > buffer.size) break
                val totalLen = ((buffer[pos + 2].toInt() and 0xFF) shl 8) or (buffer[pos + 3].toInt() and 0xFF)
                if (totalLen < HEADER_SIZE) {
                    pos += 2
                    continue
                }
                if (pos + totalLen > buffer.size) break

                val msgId = ((buffer[pos + 4].toInt() and 0xFF) shl 8) or (buffer[pos + 5].toInt() and 0xFF)
                val body = buffer.copyOfRange(pos + HEADER_SIZE, pos + totalLen)
                frames.add(CsmFrame(msgId, body))
                pos += totalLen
            }

            buffer = if (pos >= buffer.size) {
                ByteArray(0)
            } else {
                buffer.copyOfRange(pos, buffer.size)
            }
            return frames
        }

        fun reset() {
            buffer = ByteArray(0)
        }
    }
}
