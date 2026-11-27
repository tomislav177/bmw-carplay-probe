package io.bimmergestalt.bmwcarplay.iap2.link

import java.io.ByteArrayOutputStream

/**
 * Low-level iAP2 Link Layer framing, checksums, Link Synchronization Payload (LSP),
 * and streaming frame scanner.
 *
 * Packet structure (big-endian):
 * - Header (9 bytes): 0xFF 0x5A | lenHi lenLo | control | seq | ack | sessionId | headerChecksum
 * - Optional payload + 1-byte payloadChecksum (only present when payload is non-empty)
 *
 * Checksum rule (two's complement):
 *   (sum of all covered bytes + checksumByte) mod 256 == 0
 */
object Iap2LinkPacket {

    val DETECT_MARKER: ByteArray = byteArrayOf(
        0xFF.toByte(),
        0x55.toByte(),
        0x02.toByte(),
        0x00.toByte(),
        0xEE.toByte(),
        0x10.toByte(),
    )

    const val START_HI = 0xFF
    const val START_LO = 0x5A
    const val HEADER_SIZE = 9
    const val SYNC_VERSION = 0x01

    // Control byte flags
    const val CTRL_SYN = 0x80
    const val CTRL_ACK = 0x40
    const val CTRL_EAK = 0x20
    const val CTRL_RST = 0x10

    // Session types inside Link Synchronization Payload
    const val SESSION_TYPE_CONTROL = 0x00
    const val SESSION_TYPE_FILE_TRANSFER = 0x01
    const val SESSION_TYPE_EXTERNAL_ACCESSORY = 0x02

    data class Frame(
        val control: Int,
        val seq: Int,
        val ack: Int,
        val sessionId: Int,
        val payload: ByteArray = ByteArray(0),
    ) {
        val isSyn: Boolean get() = (control and CTRL_SYN) != 0
        val isAck: Boolean get() = (control and CTRL_ACK) != 0
        val isEak: Boolean get() = (control and CTRL_EAK) != 0
        val isRst: Boolean get() = (control and CTRL_RST) != 0
        val isPureSyn: Boolean get() = isSyn && !isAck
        val isSynAck: Boolean get() = isSyn && isAck

        fun controlLabel(): String {
            val parts = ArrayList<String>(4)
            if (isSyn) parts.add("SYN")
            if (isAck) parts.add("ACK")
            if (isEak) parts.add("EAK")
            if (isRst) parts.add("RST")
            if (parts.isEmpty()) parts.add("NONE")
            return parts.joinToString("|") + String.format("(0x%02X)", control and 0xFF)
        }

        fun encode(): ByteArray {
            val totalLength = if (payload.isEmpty()) HEADER_SIZE else HEADER_SIZE + payload.size + 1
            val out = ByteArray(totalLength)
            out[0] = START_HI.toByte()
            out[1] = START_LO.toByte()
            out[2] = ((totalLength ushr 8) and 0xFF).toByte()
            out[3] = (totalLength and 0xFF).toByte()
            out[4] = (control and 0xFF).toByte()
            out[5] = (seq and 0xFF).toByte()
            out[6] = (ack and 0xFF).toByte()
            out[7] = (sessionId and 0xFF).toByte()
            out[8] = computeChecksum(out, 0, 8).toByte()

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
                out[totalLength - 1] = computeChecksum(payload, 0, payload.size).toByte()
            }
            return out
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return control == other.control &&
                seq == other.seq &&
                ack == other.ack &&
                sessionId == other.sessionId &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var r = control
            r = 31 * r + seq
            r = 31 * r + ack
            r = 31 * r + sessionId
            r = 31 * r + payload.contentHashCode()
            return r
        }
    }

    data class SessionDescriptor(
        val id: Int,
        val type: Int,
        val version: Int,
    ) {
        fun typeLabel(): String = when (type) {
            SESSION_TYPE_CONTROL -> "Control"
            SESSION_TYPE_FILE_TRANSFER -> "FileTransfer"
            SESSION_TYPE_EXTERNAL_ACCESSORY -> "EA"
            else -> "Type($type)"
        }
    }

    data class LinkSyncPayload(
        val linkVersion: Int = SYNC_VERSION,
        val maxOutgoingPackets: Int,
        val maxPacketLength: Int,
        val retransmissionTimeoutMs: Int,
        val ackTimeoutMs: Int,
        val maxRetransmissions: Int,
        val maxCumulativeAcks: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        fun controlSessionId(): Int? =
            sessions.firstOrNull { it.type == SESSION_TYPE_CONTROL }?.id

        fun eaSessionIds(): List<Int> =
            sessions.filter { it.type == SESSION_TYPE_EXTERNAL_ACCESSORY }.map { it.id }

        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * 3)
            out.write(linkVersion and 0xFF)
            out.write(maxOutgoingPackets and 0xFF)
            out.write((maxPacketLength ushr 8) and 0xFF)
            out.write(maxPacketLength and 0xFF)
            out.write((retransmissionTimeoutMs ushr 8) and 0xFF)
            out.write(retransmissionTimeoutMs and 0xFF)
            out.write((ackTimeoutMs ushr 8) and 0xFF)
            out.write(ackTimeoutMs and 0xFF)
            out.write(maxRetransmissions and 0xFF)
            out.write(maxCumulativeAcks and 0xFF)
            for (s in sessions) {
                out.write(s.id and 0xFF)
                out.write(s.type and 0xFF)
                out.write(s.version and 0xFF)
            }
            return out.toByteArray()
        }

        fun summary(): String {
            val sessStr = sessions.joinToString(", ") { "${it.id}:${it.typeLabel()}(v${it.version})" }
            return "v=$linkVersion maxOut=$maxOutgoingPackets maxLen=$maxPacketLength " +
                "retxMs=$retransmissionTimeoutMs ackMs=$ackTimeoutMs " +
                "maxRetx=$maxRetransmissions maxAck=$maxCumulativeAcks sessions={$sessStr}"
        }

        companion object {
            fun defaultPhoneLsp(): LinkSyncPayload = LinkSyncPayload(
                linkVersion = SYNC_VERSION,
                maxOutgoingPackets = 4,
                maxPacketLength = 1024,
                retransmissionTimeoutMs = 4000,
                ackTimeoutMs = 2000,
                maxRetransmissions = 5,
                maxCumulativeAcks = 3,
                sessions = listOf(
                    SessionDescriptor(id = 10, type = SESSION_TYPE_CONTROL, version = 2),
                    SessionDescriptor(id = 11, type = SESSION_TYPE_EXTERNAL_ACCESSORY, version = 1),
                    SessionDescriptor(id = 12, type = SESSION_TYPE_FILE_TRANSFER, version = 1),
                ),
            )

            fun decode(bytes: ByteArray): LinkSyncPayload? {
                if (bytes.size < 10) return null
                val version = u8(bytes[0])
                if (version != SYNC_VERSION) return null
                val sessions = ArrayList<SessionDescriptor>()
                var idx = 10
                while (idx + 3 <= bytes.size) {
                    sessions.add(
                        SessionDescriptor(
                            id = u8(bytes[idx]),
                            type = u8(bytes[idx + 1]),
                            version = u8(bytes[idx + 2]),
                        ),
                    )
                    idx += 3
                }
                return LinkSyncPayload(
                    linkVersion = version,
                    maxOutgoingPackets = u8(bytes[1]),
                    maxPacketLength = u16(bytes, 2),
                    retransmissionTimeoutMs = u16(bytes, 4),
                    ackTimeoutMs = u16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxCumulativeAcks = u8(bytes[9]),
                    sessions = sessions,
                )
            }
        }
    }

    sealed class ParsedElement {
        data object DetectMarker : ParsedElement()
        data class LinkFrame(val frame: Frame, val rawBytes: ByteArray) : ParsedElement()
    }

    /**
     * Computes two's complement checksum: (-sum(bytes)) & 0xFF.
     */
    fun computeChecksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var sum = 0
        for (i in offset until (offset + length)) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return (-sum) and 0xFF
    }

    /**
     * Verifies two's complement checksum: sum(bytes including checksum) mod 256 == 0.
     */
    fun verifyChecksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        if (length <= 0) return false
        var sum = 0
        for (i in offset until (offset + length)) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return sum == 0
    }

    /**
     * Decodes a single standalone FF 5A packet, verifying header and payload checksums.
     */
    fun decodeFrame(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE) return null
        if (u8(bytes[0]) != START_HI || u8(bytes[1]) != START_LO) return null
        if (!verifyChecksum(bytes, 0, HEADER_SIZE)) return null

        val declaredLength = u16(bytes, 2)
        if (declaredLength < HEADER_SIZE || declaredLength > bytes.size) return null
        if (declaredLength == HEADER_SIZE + 1) return null // Payload requires >= 1 byte + 1 checksum byte

        val payload = if (declaredLength == HEADER_SIZE) {
            ByteArray(0)
        } else {
            val payloadLen = declaredLength - HEADER_SIZE - 1
            if (!verifyChecksum(bytes, HEADER_SIZE, payloadLen + 1)) return null
            bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
        }

        return Frame(
            control = u8(bytes[4]),
            seq = u8(bytes[5]),
            ack = u8(bytes[6]),
            sessionId = u8(bytes[7]),
            payload = payload,
        )
    }

    /**
     * Stateful streaming scanner that consumes arbitrary chunks from RFCOMM and emits
     * validated [ParsedElement.DetectMarker] and [ParsedElement.LinkFrame] items.
     */
    class FrameStreamScanner {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray): List<ParsedElement> {
            if (chunk.isEmpty()) return emptyList()
            val merged = ByteArray(buffer.size + chunk.size)
            System.arraycopy(buffer, 0, merged, 0, buffer.size)
            System.arraycopy(chunk, 0, merged, buffer.size, chunk.size)
            buffer = merged

            val results = ArrayList<ParsedElement>()
            var pos = 0

            while (pos < buffer.size) {
                val b0 = u8(buffer[pos])
                if (b0 != START_HI) {
                    pos++
                    continue
                }
                if (pos + 1 >= buffer.size) break

                val b1 = u8(buffer[pos + 1])
                if (b1 == 0x55) {
                    if (pos + DETECT_MARKER.size > buffer.size) break
                    var matches = true
                    for (i in DETECT_MARKER.indices) {
                        if (buffer[pos + i] != DETECT_MARKER[i]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) {
                        results.add(ParsedElement.DetectMarker)
                        pos += DETECT_MARKER.size
                        continue
                    } else {
                        pos++
                        continue
                    }
                }

                if (b1 == START_LO) {
                    if (pos + HEADER_SIZE > buffer.size) break
                    if (!verifyChecksum(buffer, pos, HEADER_SIZE)) {
                        pos++
                        continue
                    }
                    val totalLen = u16(buffer, pos + 2)
                    if (totalLen < HEADER_SIZE || totalLen == HEADER_SIZE + 1) {
                        pos++
                        continue
                    }
                    if (pos + totalLen > buffer.size) break

                    val slice = buffer.copyOfRange(pos, pos + totalLen)
                    val frame = decodeFrame(slice)
                    if (frame != null) {
                        results.add(ParsedElement.LinkFrame(frame, slice))
                        pos += totalLen
                        continue
                    } else {
                        // Bad payload checksum; advance past start marker and resync.
                        pos++
                        continue
                    }
                }

                pos++
            }

            buffer = if (pos >= buffer.size) {
                ByteArray(0)
            } else {
                buffer.copyOfRange(pos, buffer.size)
            }
            return results
        }
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xFF
    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
