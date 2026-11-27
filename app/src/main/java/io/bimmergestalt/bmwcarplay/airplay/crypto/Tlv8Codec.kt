package io.bimmergestalt.bmwcarplay.airplay.crypto

import java.io.ByteArrayOutputStream

/**
 * Apple HomeKit / AirPlay TLV8 (`application/pairing+tlv8`) encoder and decoder.
 *
 * Values longer than 255 bytes (such as 384-byte SRP-6a public keys) are split into
 * consecutive TLV chunks with the same tag and transparently reassembled on decode.
 */
object Tlv8Codec {

    const val TYPE_METHOD = 0x00
    const val TYPE_IDENTIFIER = 0x01
    const val TYPE_SALT = 0x02
    const val TYPE_PUBLIC_KEY = 0x03
    const val TYPE_PROOF = 0x04
    const val TYPE_ENCRYPTED_DATA = 0x05
    const val TYPE_STATE = 0x06
    const val TYPE_ERROR = 0x07
    const val TYPE_RETRY_DELAY = 0x08
    const val TYPE_CERTIFICATE = 0x09
    const val TYPE_SIGNATURE = 0x0A
    const val TYPE_PERMISSIONS = 0x0B
    const val TYPE_FRAGMENT_DATA = 0x0C
    const val TYPE_FRAGMENT_LAST = 0x0D
    const val TYPE_FLAGS = 0x13
    const val TYPE_SEPARATOR = 0xFF

    data class Item(
        val type: Int,
        val value: ByteArray,
    )

    fun encode(items: List<Item>): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in items) {
            val tag = item.type and 0xFF
            val data = item.value
            if (data.isEmpty()) {
                out.write(tag)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < data.size) {
                val chunkLen = minOf(255, data.size - offset)
                out.write(tag)
                out.write(chunkLen)
                out.write(data, offset, chunkLen)
                offset += chunkLen
            }
        }
        return out.toByteArray()
    }

    /**
     * Decodes a TLV8 payload into a map of `tag -> reassembled value bytes`.
     * Consecutive fragments with the same tag are concatenated automatically.
     */
    fun decode(bytes: ByteArray): Map<Int, ByteArray> {
        val buckets = LinkedHashMap<Int, ByteArrayOutputStream>()
        var offset = 0
        while (offset + 2 <= bytes.size) {
            val tag = bytes[offset].toInt() and 0xFF
            val len = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            val end = minOf(offset + len, bytes.size)
            val stream = buckets.getOrPut(tag) { ByteArrayOutputStream() }
            if (end > offset) {
                stream.write(bytes, offset, end - offset)
            }
            offset = end
        }
        val result = LinkedHashMap<Int, ByteArray>(buckets.size)
        for ((k, v) in buckets) {
            result[k] = v.toByteArray()
        }
        return result
    }

    fun errorName(code: Int): String = when (code) {
        0x01 -> "Unknown(1)"
        0x02 -> "Authentication(2)"
        0x03 -> "Backoff(3)"
        0x04 -> "MaxPeers(4)"
        0x05 -> "MaxTries(5)"
        0x06 -> "Unavailable(6)"
        0x07 -> "Busy(7)"
        else -> "Error($code)"
    }

    fun describeDecoded(map: Map<Int, ByteArray>): String =
        map.entries.joinToString(", ") { (tag, v) ->
            val name = when (tag) {
                TYPE_METHOD -> "Method"
                TYPE_IDENTIFIER -> "Id"
                TYPE_SALT -> "Salt"
                TYPE_PUBLIC_KEY -> "PubKey"
                TYPE_PROOF -> "Proof"
                TYPE_ENCRYPTED_DATA -> "EncData"
                TYPE_STATE -> "State"
                TYPE_ERROR -> "Error"
                TYPE_RETRY_DELAY -> "RetryDelay"
                TYPE_SIGNATURE -> "Sig"
                TYPE_FLAGS -> "Flags"
                else -> "Tag(0x${tag.toString(16)})"
            }
            when {
                tag == TYPE_STATE && v.isNotEmpty() -> "$name=${v[0].toInt() and 0xFF}"
                tag == TYPE_ERROR && v.isNotEmpty() -> "$name=${errorName(v[0].toInt() and 0xFF)}"
                tag == TYPE_METHOD && v.isNotEmpty() -> "$name=${v[0].toInt() and 0xFF}"
                else -> "$name(${v.size}B)"
            }
        }
}
