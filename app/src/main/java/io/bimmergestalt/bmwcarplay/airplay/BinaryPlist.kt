package io.bimmergestalt.bmwcarplay.airplay

import io.bimmergestalt.bmwcarplay.diag.HexDump
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pure Kotlin Apple Binary Property List (`bplist00`) encoder and parser.
 *
 * Key AirPlay / CarPlay correctness guarantees:
 * 1. **Proper signedness for ephemeral TCP/UDP ports (32768..65535)**:
 *    - When encoding integers, width is chosen by SIGNED bounds (`0..127` -> 1B, `128..32767` -> 2B,
 *      `32768..Int.MAX_VALUE` -> 4B, negative or `> Int.MAX_VALUE` -> 8B). Ports like `35449` or
 *      `49152` are therefore written as 4-byte positive integers (`00 00 C0 00`), preventing Apple's
 *      signed int16 decoders from interpreting them as negative numbers.
 *    - When parsing, 1-byte (`0x10`), 2-byte (`0x11`), and 4-byte (`0x12`) integers are decoded
 *      as UNSIGNED (per CoreFoundation bplist00 spec), while 8-byte (`0x13`) integers are decoded
 *      as signed 64-bit Longs.
 * 2. **Root object placed at byte offset 8**:
 *    - Pre-order traversal assigns object index 0 to the root container so it is always serialized
 *      at byte offset 8 right after the `bplist00` header (required by older CarPlay 210.81 /
 *      EntryNav2 receivers that read offset 8 directly instead of trailer `topObject`).
 */
object BinaryPlist {

    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)
    private const val TRAILER_SIZE = 32

    /** Apple CoreData epoch offset: seconds between 1970-01-01 and 2001-01-01. */
    private const val APPLE_EPOCH_OFFSET_SEC = 978_307_200.0

    data class DateValue(val appleSecondsSince2001: Double) {
        fun unixEpochMillis(): Long = ((appleSecondsSince2001 + APPLE_EPOCH_OFFSET_SEC) * 1000.0).toLong()
    }

    data class UidValue(val value: Long)

    /**
     * Encodes an arbitrary Kotlin value tree (`Map`, `List`, `String`, `ByteArray`, `Boolean`,
     * `Int`, `Long`, `Float`, `Double`, `DateValue`, `UidValue`, `null`) into a valid `bplist00`.
     */
    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<EncodedNode>()
        // Pre-order collection guarantees root object has object index 0 -> byte offset 8!
        collectNodePreOrder(root, nodes)

        val numObjects = nodes.size
        val objectRefSize = byteSizeForUnsigned(numObjects.toLong())

        val body = ByteArrayOutputStream()
        body.write(MAGIC)

        val offsets = LongArray(numObjects)
        for (i in 0 until numObjects) {
            offsets[i] = body.size().toLong()
            writeNode(body, nodes[i], objectRefSize)
        }

        val offsetTableOffset = body.size().toLong()
        val offsetIntSize = byteSizeForUnsigned(offsetTableOffset)
        for (offset in offsets) {
            writeBigEndianUnsigned(body, offset, offsetIntSize)
        }

        // 32-byte trailer:
        // [0..5] unused (6 bytes)
        // [6]    offsetIntSize
        // [7]    objectRefSize
        // [8..15]  numObjects (uint64 BE)
        // [16..23] topObject (uint64 BE) = 0
        // [24..31] offsetTableOffset (uint64 BE)
        body.write(ByteArray(6))
        body.write(offsetIntSize)
        body.write(objectRefSize)
        writeBigEndianLong(body, numObjects.toLong())
        writeBigEndianLong(body, 0L) // root is always object 0 at offset 8
        writeBigEndianLong(body, offsetTableOffset)

        return body.toByteArray()
    }

    /**
     * Parses a `bplist00` byte array into Kotlin objects (`LinkedHashMap<String, Any?>`,
     * `ArrayList<Any?>`, `String`, `Long`, `Double`, `Boolean`, `ByteArray`, `DateValue`, `UidValue`, `null`).
     */
    @Throws(IOException::class)
    fun parse(data: ByteArray): Any? {
        if (data.size < MAGIC.size + TRAILER_SIZE) {
            throw IOException("BinaryPlist je prekratak (${data.size} B)")
        }
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) {
                throw IOException("Neispravan bplist00 potpis")
            }
        }

        val trailerStart = data.size - TRAILER_SIZE
        val offsetIntSize = data[trailerStart + 6].toInt() and 0xFF
        val objectRefSize = data[trailerStart + 7].toInt() and 0xFF
        val numObjects = readBigEndianLong(data, trailerStart + 8)
        val topObject = readBigEndianLong(data, trailerStart + 16)
        val offsetTableOffset = readBigEndianLong(data, trailerStart + 24)

        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
            throw IOException("Nevaljana veličina offset/ref polja ($offsetIntSize / $objectRefSize)")
        }
        if (numObjects <= 0 || numObjects > 1_000_000) {
            throw IOException("Nevaljan broj bplist objekata: $numObjects")
        }
        if (topObject < 0 || topObject >= numObjects) {
            throw IOException("Nevaljan topObject indeks: $topObject")
        }
        val offsetTableBytes = numObjects * offsetIntSize
        if (offsetTableOffset < MAGIC.size || offsetTableOffset + offsetTableBytes > trailerStart) {
            throw IOException("Nevaljan offsetTableOffset: $offsetTableOffset")
        }

        val offsets = LongArray(numObjects.toInt())
        var pos = offsetTableOffset.toInt()
        for (i in offsets.indices) {
            offsets[i] = readBigEndianUnsigned(data, pos, offsetIntSize)
            pos += offsetIntSize
        }

        val ctx = ParseContext(data, offsets, objectRefSize, trailerStart)
        return ctx.readObject(topObject.toInt(), depth = 0)
    }

    /**
     * Formats any parsed bplist object tree into a readable multi-line diagnostic string.
     */
    fun formatReadable(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> "\"$value\""
        is DateValue -> "Date(appleSec=${value.appleSecondsSince2001})"
        is UidValue -> "UID(${value.value})"
        is ByteArray -> "<Data ${value.size} B: ${HexDump.toCompactHex(value, 32)}>"
        is List<*> -> {
            if (value.isEmpty()) {
                "[]"
            } else {
                val sb = StringBuilder("[\n")
                value.forEachIndexed { idx, item ->
                    sb.append(indent).append("  [").append(idx).append("] ")
                    sb.append(formatReadable(item, "$indent  ")).append('\n')
                }
                sb.append(indent).append("]")
                sb.toString()
            }
        }

        is Map<*, *> -> {
            if (value.isEmpty()) {
                "{}"
            } else {
                val sb = StringBuilder("{\n")
                for ((k, v) in value) {
                    sb.append(indent).append("  ").append(k).append(": ")
                    sb.append(formatReadable(v, "$indent  ")).append('\n')
                }
                sb.append(indent).append("}")
                sb.toString()
            }
        }

        else -> value.toString()
    }

    // ---- Encoding internals ----

    private sealed class EncodedNode {
        data object NullNode : EncodedNode()
        data class BoolNode(val value: Boolean) : EncodedNode()
        data class IntNode(val value: Long) : EncodedNode()
        data class DoubleNode(val value: Double) : EncodedNode()
        data class DateNode(val value: DateValue) : EncodedNode()
        data class UidNode(val value: UidValue) : EncodedNode()
        data class DataNode(val bytes: ByteArray) : EncodedNode()
        data class StringNode(val text: String) : EncodedNode()
        data class ArrayNode(val childIndices: IntArray) : EncodedNode()
        data class DictNode(val keyIndices: IntArray, val valueIndices: IntArray) : EncodedNode()
    }

    private fun collectNodePreOrder(obj: Any?, nodes: MutableList<EncodedNode>): Int {
        val myIndex = nodes.size
        when (obj) {
            null -> nodes.add(EncodedNode.NullNode)
            is Boolean -> nodes.add(EncodedNode.BoolNode(obj))
            is Byte -> nodes.add(EncodedNode.IntNode(obj.toLong()))
            is Short -> nodes.add(EncodedNode.IntNode(obj.toLong()))
            is Int -> nodes.add(EncodedNode.IntNode(obj.toLong()))
            is Long -> nodes.add(EncodedNode.IntNode(obj))
            is Float -> nodes.add(EncodedNode.DoubleNode(obj.toDouble()))
            is Double -> nodes.add(EncodedNode.DoubleNode(obj))
            is DateValue -> nodes.add(EncodedNode.DateNode(obj))
            is UidValue -> nodes.add(EncodedNode.UidNode(obj))
            is ByteArray -> nodes.add(EncodedNode.DataNode(obj))
            is String -> nodes.add(EncodedNode.StringNode(obj))
            is List<*> -> {
                val childIndices = IntArray(obj.size)
                nodes.add(EncodedNode.ArrayNode(childIndices))
                obj.forEachIndexed { idx, item ->
                    childIndices[idx] = collectNodePreOrder(item, nodes)
                }
            }

            is Map<*, *> -> {
                val entries = obj.entries.toList()
                val keyIndices = IntArray(entries.size)
                val valueIndices = IntArray(entries.size)
                nodes.add(EncodedNode.DictNode(keyIndices, valueIndices))
                entries.forEachIndexed { idx, (k, _) ->
                    keyIndices[idx] = collectNodePreOrder(k?.toString().orEmpty(), nodes)
                }
                entries.forEachIndexed { idx, (_, v) ->
                    valueIndices[idx] = collectNodePreOrder(v, nodes)
                }
            }

            else -> nodes.add(EncodedNode.StringNode(obj.toString()))
        }
        return myIndex
    }

    private fun writeNode(
        out: ByteArrayOutputStream,
        node: EncodedNode,
        objectRefSize: Int,
    ) {
        when (node) {
            EncodedNode.NullNode -> out.write(0x00)
            is EncodedNode.BoolNode -> out.write(if (node.value) 0x09 else 0x08)
            is EncodedNode.IntNode -> writeIntObject(out, node.value)
            is EncodedNode.DoubleNode -> {
                out.write(0x23) // 8-byte IEEE 754 float64
                val bits = java.lang.Double.doubleToRawLongBits(node.value)
                writeBigEndianLong(out, bits)
            }

            is EncodedNode.DateNode -> {
                out.write(0x33)
                val bits = java.lang.Double.doubleToRawLongBits(node.value.appleSecondsSince2001)
                writeBigEndianLong(out, bits)
            }

            is EncodedNode.UidNode -> {
                val byteCount = byteSizeForUnsigned(node.value.value.coerceAtLeast(0))
                out.write(0x80 or ((byteCount - 1) and 0x0F))
                writeBigEndianUnsigned(out, node.value.value, byteCount)
            }

            is EncodedNode.DataNode -> {
                writeTypeAndLength(out, 0x40, node.bytes.size)
                out.write(node.bytes)
            }

            is EncodedNode.StringNode -> {
                if (isPureAscii(node.text)) {
                    val bytes = node.text.toByteArray(StandardCharsets.US_ASCII)
                    writeTypeAndLength(out, 0x50, bytes.size)
                    out.write(bytes)
                } else {
                    val bytes = node.text.toByteArray(StandardCharsets.UTF_16BE)
                    writeTypeAndLength(out, 0x60, node.text.length)
                    out.write(bytes)
                }
            }

            is EncodedNode.ArrayNode -> {
                writeTypeAndLength(out, 0xA0, node.childIndices.size)
                for (childIdx in node.childIndices) {
                    writeBigEndianUnsigned(out, childIdx.toLong(), objectRefSize)
                }
            }

            is EncodedNode.DictNode -> {
                writeTypeAndLength(out, 0xD0, node.keyIndices.size)
                for (keyIdx in node.keyIndices) {
                    writeBigEndianUnsigned(out, keyIdx.toLong(), objectRefSize)
                }
                for (valIdx in node.valueIndices) {
                    writeBigEndianUnsigned(out, valIdx.toLong(), objectRefSize)
                }
            }
        }
    }

    /**
     * Encodes integers using SIGNED bounds so values in 32768..65535 (ephemeral ports!)
     * are written in 4 bytes (0x12) rather than 2 bytes (0x11), avoiding negative sign extension
     * on Apple/car receivers that decode 2-byte bplist ints as signed int16.
     */
    private fun writeIntObject(out: ByteArrayOutputStream, value: Long) {
        when {
            value in 0L..Byte.MAX_VALUE.toLong() -> {
                out.write(0x10) // 2^0 = 1 byte
                out.write(value.toInt() and 0xFF)
            }

            value in (Byte.MAX_VALUE.toLong() + 1)..Short.MAX_VALUE.toLong() -> {
                out.write(0x11) // 2^1 = 2 bytes
                out.write(((value ushr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }

            value in (Short.MAX_VALUE.toLong() + 1)..Int.MAX_VALUE.toLong() -> {
                out.write(0x12) // 2^2 = 4 bytes (covers 32768..65535 safely!)
                out.write(((value ushr 24) and 0xFF).toInt())
                out.write(((value ushr 16) and 0xFF).toInt())
                out.write(((value ushr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }

            else -> {
                out.write(0x13) // 2^3 = 8 bytes signed (negative values & > Int.MAX_VALUE)
                writeBigEndianLong(out, value)
            }
        }
    }

    private fun writeTypeAndLength(out: ByteArrayOutputStream, typeNibble: Int, count: Int) {
        if (count < 15) {
            out.write(typeNibble or count)
        } else {
            out.write(typeNibble or 0x0F)
            writeIntObject(out, count.toLong())
        }
    }

    private fun isPureAscii(s: String): Boolean {
        for (c in s) {
            if (c.code > 0x7F) return false
        }
        return true
    }

    private fun byteSizeForUnsigned(maxValue: Long): Int = when {
        maxValue <= 0xFFL -> 1
        maxValue <= 0xFFFFL -> 2
        maxValue <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun writeBigEndianUnsigned(out: ByteArrayOutputStream, value: Long, byteCount: Int) {
        for (shift in (byteCount - 1) downTo 0) {
            out.write(((value ushr (shift * 8)) and 0xFF).toInt())
        }
    }

    private fun writeBigEndianLong(out: ByteArrayOutputStream, value: Long) {
        for (shift in 7 downTo 0) {
            out.write(((value ushr (shift * 8)) and 0xFF).toInt())
        }
    }

    // ---- Parsing internals ----

    private class ParseContext(
        private val data: ByteArray,
        private val offsets: LongArray,
        private val objectRefSize: Int,
        private val endLimit: Int,
    ) {
        private val maxDepth = 64

        @Throws(IOException::class)
        fun readObject(objIndex: Int, depth: Int): Any? {
            if (depth > maxDepth) throw IOException("Prevelika dubina bplist ugniježđenja")
            if (objIndex < 0 || objIndex >= offsets.size) {
                throw IOException("Nevaljan bplist indeks objekta: $objIndex")
            }
            val offset = offsets[objIndex].toInt()
            if (offset < MAGIC.size || offset >= endLimit) {
                throw IOException("Nevaljan offset objekta $objIndex: $offset")
            }

            val marker = data[offset].toInt() and 0xFF
            val type = marker and 0xF0
            val info = marker and 0x0F

            return when (type) {
                0x00 -> when (info) {
                    0x00 -> null
                    0x08 -> false
                    0x09 -> true
                    0x0F -> null // fill byte
                    else -> throw IOException("Nepoznat bplist singleton marker: 0x${marker.toString(16)}")
                }

                0x10 -> {
                    val byteCount = 1 shl info
                    checkBounds(offset + 1, byteCount)
                    readBplistInteger(offset + 1, byteCount)
                }

                0x20 -> {
                    val byteCount = 1 shl info
                    checkBounds(offset + 1, byteCount)
                    val bb = ByteBuffer.wrap(data, offset + 1, byteCount).order(ByteOrder.BIG_ENDIAN)
                    when (byteCount) {
                        4 -> bb.float.toDouble()
                        8 -> bb.double
                        else -> throw IOException("Nepodržana veličina float polja: $byteCount")
                    }
                }

                0x30 -> {
                    checkBounds(offset + 1, 8)
                    val bits = readBigEndianLong(data, offset + 1)
                    DateValue(java.lang.Double.longBitsToDouble(bits))
                }

                0x40 -> {
                    val (count, payloadOffset) = readCount(offset, info)
                    checkBounds(payloadOffset, count)
                    data.copyOfRange(payloadOffset, payloadOffset + count)
                }

                0x50 -> {
                    val (count, payloadOffset) = readCount(offset, info)
                    checkBounds(payloadOffset, count)
                    String(data, payloadOffset, count, StandardCharsets.US_ASCII)
                }

                0x60 -> {
                    val (charCount, payloadOffset) = readCount(offset, info)
                    val byteCount = charCount * 2
                    checkBounds(payloadOffset, byteCount)
                    String(data, payloadOffset, byteCount, StandardCharsets.UTF_16BE)
                }

                0x80 -> {
                    val byteCount = info + 1
                    checkBounds(offset + 1, byteCount)
                    UidValue(readBigEndianUnsigned(data, offset + 1, byteCount))
                }

                0xA0, 0xC0 -> { // Array or Set
                    val (count, refsStart) = readCount(offset, info)
                    checkBounds(refsStart, count * objectRefSize)
                    val list = ArrayList<Any?>(count)
                    var refPos = refsStart
                    for (i in 0 until count) {
                        val ref = readBigEndianUnsigned(data, refPos, objectRefSize).toInt()
                        list.add(readObject(ref, depth + 1))
                        refPos += objectRefSize
                    }
                    list
                }

                0xD0 -> { // Dictionary
                    val (count, refsStart) = readCount(offset, info)
                    val keyRefsLen = count * objectRefSize
                    checkBounds(refsStart, keyRefsLen * 2)
                    val map = LinkedHashMap<String, Any?>(count)
                    var keyPos = refsStart
                    var valPos = refsStart + keyRefsLen
                    for (i in 0 until count) {
                        val keyRef = readBigEndianUnsigned(data, keyPos, objectRefSize).toInt()
                        val valRef = readBigEndianUnsigned(data, valPos, objectRefSize).toInt()
                        val key = readObject(keyRef, depth + 1)?.toString() ?: "null"
                        val value = readObject(valRef, depth + 1)
                        map[key] = value
                        keyPos += objectRefSize
                        valPos += objectRefSize
                    }
                    map
                }

                else -> throw IOException("Nepoznat bplist tip objekta: 0x${marker.toString(16)} na offsetu $offset")
            }
        }

        /**
         * Per Apple bplist00 specification:
         * - 1, 2, 4 byte integers are unsigned (so ports 32768..65535 encoded in 2 bytes
         *   by a car head unit decode cleanly as positive integers!).
         * - 8 byte integers are signed two's complement 64-bit Longs.
         */
        private fun readBplistInteger(offset: Int, byteCount: Int): Long = when (byteCount) {
            1, 2, 4 -> readBigEndianUnsigned(data, offset, byteCount)
            8 -> readBigEndianLong(data, offset)
            16 -> readBigEndianLong(data, offset + 8) // lower 64 bits of 128-bit int
            else -> throw IOException("Nepodržana veličina bplist cijelog broja: $byteCount")
        }

        private fun readCount(markerOffset: Int, info: Int): Pair<Int, Int> {
            if (info != 0x0F) {
                return info to (markerOffset + 1)
            }
            val lenMarkerOffset = markerOffset + 1
            checkBounds(lenMarkerOffset, 1)
            val lenMarker = data[lenMarkerOffset].toInt() and 0xFF
            if ((lenMarker and 0xF0) != 0x10) {
                throw IOException("Očekivan bplist int marker za duljinu, dobiveno 0x${lenMarker.toString(16)}")
            }
            val byteCount = 1 shl (lenMarker and 0x0F)
            checkBounds(lenMarkerOffset + 1, byteCount)
            val count = readBplistInteger(lenMarkerOffset + 1, byteCount)
            if (count < 0 || count > Int.MAX_VALUE) {
                throw IOException("Nevaljana duljina bplist polja: $count")
            }
            return count.toInt() to (lenMarkerOffset + 1 + byteCount)
        }

        private fun checkBounds(offset: Int, length: Int) {
            if (length < 0 || offset < 0 || offset + length > endLimit) {
                throw IOException("bplist granice prekoračene (offset=$offset, len=$length, limit=$endLimit)")
            }
        }
    }

    private fun readBigEndianUnsigned(data: ByteArray, offset: Int, byteCount: Int): Long {
        var acc = 0L
        for (i in 0 until byteCount) {
            acc = (acc shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return acc
    }

    private fun readBigEndianLong(data: ByteArray, offset: Int): Long {
        var acc = 0L
        for (i in 0 until 8) {
            acc = (acc shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return acc
    }
}
