package io.bimmergestalt.bmwcarplay.aap

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Clean, zero-dependency wire-level Protobuf codec and Android Auto Protocol (AAP / GAL)
 * message builders and parsers.
 *
 * All channel IDs, message IDs, and protobuf field numbers follow the public GAL/AAP wire
 * specification.
 */
object AapProto {

    // ---- Protobuf wire types ----
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_START_GROUP = 3
    const val WIRE_END_GROUP = 4
    const val WIRE_FIXED32 = 5

    // ---- AAP Channel IDs ----
    const val CH_CONTROL = 0
    const val CH_SENSOR = 1
    const val CH_VIDEO = 2
    const val CH_INPUT = 3
    const val CH_AUDIO_SYSTEM = 5
    const val CH_MIC = 7

    // ---- Control Message IDs (CH_CONTROL or FLAG_CONTROL on any channel) ----
    const val VERSION_REQUEST = 0x0001
    const val VERSION_RESPONSE = 0x0002
    const val SSL_HANDSHAKE = 0x0003
    const val AUTH_COMPLETE = 0x0004
    const val SERVICE_DISCOVERY_REQUEST = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val CHANNEL_OPEN_REQUEST = 0x0007
    const val CHANNEL_OPEN_RESPONSE = 0x0008
    const val PING_REQUEST = 0x000B
    const val PING_RESPONSE = 0x000C
    const val NAV_FOCUS_REQUEST = 0x000D
    const val NAV_FOCUS_RESPONSE = 0x000E
    const val SHUTDOWN_REQUEST = 0x000F
    const val SHUTDOWN_RESPONSE = 0x0010
    const val AUDIO_FOCUS_REQUEST = 0x0012
    const val AUDIO_FOCUS_RESPONSE = 0x0013

    // ---- AV Message IDs (CH_VIDEO, CH_AUDIO_SYSTEM, CH_MIC) ----
    const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
    const val AV_MEDIA_INDICATION = 0x0001
    const val AV_SETUP_REQUEST = 0x8000
    const val AV_START_INDICATION = 0x8001
    const val AV_STOP_INDICATION = 0x8002
    const val AV_SETUP_RESPONSE = 0x8003
    const val AV_MEDIA_ACK = 0x8004
    const val AV_INPUT_OPEN_REQUEST = 0x8005
    const val AV_INPUT_OPEN_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_INDICATION = 0x8008

    // ---- Input Message IDs (CH_INPUT) ----
    const val INPUT_EVENT_INDICATION = 0x8001
    const val INPUT_BINDING_REQUEST = 0x8002
    const val INPUT_BINDING_RESPONSE = 0x8003

    // ---- Sensor Message IDs (CH_SENSOR) ----
    const val SENSOR_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val SENSOR_EVENT_INDICATION = 0x8003

    // ---- Enum constants ----
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 6
    const val VERSION_STATUS_MATCH = 0

    const val STATUS_SUCCESS = 0
    const val AV_STATUS_OK = 2

    const val STREAM_TYPE_AUDIO_PCM = 1
    const val STREAM_TYPE_VIDEO_H264_BP = 3

    const val AUDIO_TYPE_NONE = 0
    const val AUDIO_TYPE_SYSTEM = 2

    const val DISPLAY_TYPE_MAIN = 0
    const val DRIVER_POSITION_LEFT = 0

    const val SENSOR_PARKING_BRAKE = 7
    const val SENSOR_NIGHT_DATA = 10
    const val SENSOR_DRIVING_STATUS = 13
    const val DRIVING_STATUS_UNRESTRICTED = 0

    const val SHUTDOWN_REASON_USER_SELECTION = 1
    const val SHUTDOWN_REASON_POWER_DOWN = 7

    const val AUDIO_FOCUS_GAIN = 1
    const val AUDIO_FOCUS_GAIN_TRANSIENT = 2
    const val AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK = 3
    const val AUDIO_FOCUS_RELEASE = 4

    const val AUDIO_FOCUS_STATE_GAIN = 1
    const val AUDIO_FOCUS_STATE_GAIN_TRANSIENT = 2
    const val AUDIO_FOCUS_STATE_LOSS = 3
    const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK = 4

    const val NAV_FOCUS_NATIVE = 1
    const val NAV_FOCUS_PROJECTED = 2

    const val VIDEO_FOCUS_PROJECTED = 1
    const val VIDEO_FOCUS_NATIVE = 2

    const val VIDEO_RES_800x480 = 1
    const val VIDEO_RES_1280x720 = 2
    const val VIDEO_FPS_60 = 1
    const val VIDEO_FPS_30 = 2

    object AapKeyCode {
        const val HOME = 0x03
        const val BACK = 0x04
        const val CALL = 0x05
        const val ENDCALL = 0x06
        const val DPAD_UP = 0x13
        const val DPAD_DOWN = 0x14
        const val DPAD_LEFT = 0x15
        const val DPAD_RIGHT = 0x16
        const val DPAD_CENTER = 0x17
        const val SEARCH = 0x54
        const val MEDIA_PLAY_PAUSE = 0x55
        const val MEDIA_NEXT = 0x57
        const val MEDIA_PREVIOUS = 0x58
        const val MEDIA_PLAY = 0x7E
        const val MEDIA_PAUSE = 0x7F
        const val VOICE_ASSIST = 0xE7
        const val SCROLL_WHEEL = 0x10000

        val ALL_SUPPORTED = intArrayOf(
            HOME,
            BACK,
            CALL,
            ENDCALL,
            DPAD_UP,
            DPAD_DOWN,
            DPAD_LEFT,
            DPAD_RIGHT,
            DPAD_CENTER,
            SEARCH,
            MEDIA_PLAY_PAUSE,
            MEDIA_NEXT,
            MEDIA_PREVIOUS,
            MEDIA_PLAY,
            MEDIA_PAUSE,
            VOICE_ASSIST,
            SCROLL_WHEEL,
        )
    }

    object TouchAction {
        const val PRESS = 0
        const val RELEASE = 1
        const val DRAG = 2
    }

    /**
     * Negotiated Android Auto video stream geometry and active viewport crop for a BMW display.
     */
    data class AapDisplayProfile(
        val videoResolutionEnum: Int,
        val videoWidth: Int,
        val videoHeight: Int,
        val marginWidth: Int,
        val marginHeight: Int,
        val targetFps: Int,
        val dpi: Int,
    ) {
        val activeWidth: Int get() = videoWidth - marginWidth
        val activeHeight: Int get() = videoHeight - marginHeight
        val cropLeft: Int get() = marginWidth / 2
        val cropTop: Int get() = marginHeight / 2
        val videoFpsEnum: Int get() = if (targetFps >= 60) VIDEO_FPS_60 else VIDEO_FPS_30

        companion object {
            /**
             * Picks the optimal AAP display profile for the reported BMW CarPlay screen dimensions.
             *
             * - Wide BMW screens (`> 800 px`, e.g. `1280×480` on `HU_ENTRYNAV2`): requests `1280×720`
             *   with `marginHeight = 240` so Android Auto renders a sharp `1280×480` active band
             *   centered at `Y = 120..600`.
             * - Smaller screens (`<= 800 px`): requests `800×480` with zero margins.
             */
            fun forBmwScreen(
                bmwWidth: Int,
                @Suppress("UNUSED_PARAMETER") bmwHeight: Int,
                maxFps: Int,
            ): AapDisplayProfile {
                val fps = if (maxFps >= 60) 60 else 30
                return if (bmwWidth > 800) {
                    AapDisplayProfile(
                        videoResolutionEnum = VIDEO_RES_1280x720,
                        videoWidth = 1280,
                        videoHeight = 720,
                        marginWidth = 0,
                        marginHeight = 240,
                        targetFps = fps,
                        dpi = 180,
                    )
                } else {
                    AapDisplayProfile(
                        videoResolutionEnum = VIDEO_RES_800x480,
                        videoWidth = 800,
                        videoHeight = 480,
                        marginWidth = 0,
                        marginHeight = 0,
                        targetFps = fps,
                        dpi = 160,
                    )
                }
            }
        }
    }

    // ---- ZigZag helpers ----

    fun encodeZigZag32(value: Int): Long =
        ((value shl 1) xor (value shr 31)).toLong() and 0xFFFF_FFFFL

    fun decodeZigZag32(raw: Long): Int {
        val v = raw.toInt()
        return (v ushr 1) xor -(v and 1)
    }

    // ---- Hand-written Protobuf wire writer ----

    class ProtoWriter(initialCapacity: Int = 64) {
        private val out = ByteArrayOutputStream(initialCapacity)

        fun writeVarint(value: Long) {
            var v = value
            while ((v and 0x7FL.inv()) != 0L) {
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
            out.write(v.toInt() and 0x7F)
        }

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            require(fieldNumber > 0) { "Protobuf fieldNumber must be positive: $fieldNumber" }
            writeVarint(((fieldNumber.toLong() shl 3) or (wireType.toLong() and 0x07L)))
        }

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(value)
        }

        fun writeVarintField(fieldNumber: Int, value: Int) {
            writeVarintField(fieldNumber, value.toLong())
        }

        fun writeSint32Field(fieldNumber: Int, value: Int) {
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(encodeZigZag32(value))
        }

        fun writeBoolField(fieldNumber: Int, value: Boolean) {
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(if (value) 1L else 0L)
        }

        fun writeFixed64Field(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, WIRE_FIXED64)
            for (shift in 0 until 64 step 8) {
                out.write(((value ushr shift) and 0xFFL).toInt())
            }
        }

        fun writeFixed32Field(fieldNumber: Int, value: Int) {
            writeTag(fieldNumber, WIRE_FIXED32)
            for (shift in 0 until 32 step 8) {
                out.write((value ushr shift) and 0xFF)
            }
        }

        fun writeBytesField(fieldNumber: Int, bytes: ByteArray) {
            writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
            writeVarint(bytes.size.toLong())
            if (bytes.isNotEmpty()) {
                out.write(bytes)
            }
        }

        fun writeStringField(fieldNumber: Int, value: String) {
            writeBytesField(fieldNumber, value.toByteArray(Charsets.UTF_8))
        }

        fun writeSubmessageField(fieldNumber: Int, block: ProtoWriter.() -> Unit) {
            val child = ProtoWriter()
            child.block()
            writeBytesField(fieldNumber, child.toByteArray())
        }

        fun writePackedVarintsField(fieldNumber: Int, values: IntArray) {
            if (values.isEmpty()) return
            val packed = ProtoWriter(values.size * 2)
            for (v in values) {
                packed.writeVarint(v.toLong())
            }
            writeBytesField(fieldNumber, packed.toByteArray())
        }

        fun writePackedVarintsField(fieldNumber: Int, values: Iterable<Int>) {
            val packed = ProtoWriter()
            var hasAny = false
            for (v in values) {
                packed.writeVarint(v.toLong())
                hasAny = true
            }
            if (hasAny) {
                writeBytesField(fieldNumber, packed.toByteArray())
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    // ---- Hand-written Protobuf wire reader ----

    data class ProtoField(
        val fieldNumber: Int,
        val wireType: Int,
        val varint: Long = 0L,
        val bytes: ByteArray? = null,
        val fixed64: Long = 0L,
        val fixed32: Int = 0,
    ) {
        fun asInt(): Int = varint.toInt()
        fun asLong(): Long = varint
        fun asSint32(): Int = decodeZigZag32(varint)
        fun asBool(): Boolean = varint != 0L
        fun asBytes(): ByteArray = bytes ?: ByteArray(0)
        fun asString(): String = asBytes().toString(Charsets.UTF_8)

        @Throws(IOException::class)
        fun asSubmessageFields(): List<ProtoField> = ProtoReader(asBytes()).readAllFields()

        @Throws(IOException::class)
        fun asPackedInts(): List<Int> {
            val raw = bytes ?: return emptyList()
            val reader = ProtoReader(raw)
            return reader.readRawPackedVarints().map { it.toInt() }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProtoField) return false
            val bytesEq = when {
                bytes == null && other.bytes == null -> true
                bytes != null && other.bytes != null -> bytes.contentEquals(other.bytes)
                else -> false
            }
            return fieldNumber == other.fieldNumber &&
                wireType == other.wireType &&
                varint == other.varint &&
                fixed64 == other.fixed64 &&
                fixed32 == other.fixed32 &&
                bytesEq
        }

        override fun hashCode(): Int {
            var result = fieldNumber
            result = 31 * result + wireType
            result = 31 * result + varint.hashCode()
            result = 31 * result + (bytes?.contentHashCode() ?: 0)
            result = 31 * result + fixed64.hashCode()
            result = 31 * result + fixed32
            return result
        }
    }

    class ProtoReader(
        private val data: ByteArray,
        startOffset: Int = 0,
        private val endOffset: Int = data.size,
    ) {
        private var pos: Int = startOffset

        @Throws(IOException::class)
        fun readAllFields(): List<ProtoField> {
            val fields = ArrayList<ProtoField>()
            while (pos < endOffset) {
                val tag = readVarint()
                val fieldNumber = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()
                if (fieldNumber <= 0) {
                    throw IOException("Invalid protobuf field number: $fieldNumber")
                }
                when (wireType) {
                    WIRE_VARINT -> {
                        val v = readVarint()
                        fields.add(ProtoField(fieldNumber = fieldNumber, wireType = wireType, varint = v))
                    }

                    WIRE_FIXED64 -> {
                        val v = readFixed64()
                        fields.add(ProtoField(fieldNumber = fieldNumber, wireType = wireType, fixed64 = v))
                    }

                    WIRE_LENGTH_DELIMITED -> {
                        val lenLong = readVarint()
                        if (lenLong < 0L || lenLong > (endOffset - pos).toLong()) {
                            throw IOException(
                                "Truncated protobuf length-delimited field #$fieldNumber (len=$lenLong, rem=${endOffset - pos})",
                            )
                        }
                        val len = lenLong.toInt()
                        val slice = data.copyOfRange(pos, pos + len)
                        pos += len
                        fields.add(ProtoField(fieldNumber = fieldNumber, wireType = wireType, bytes = slice))
                    }

                    WIRE_FIXED32 -> {
                        val v = readFixed32()
                        fields.add(ProtoField(fieldNumber = fieldNumber, wireType = wireType, fixed32 = v))
                    }

                    WIRE_START_GROUP -> {
                        skipGroup(fieldNumber)
                    }

                    else -> {
                        throw IOException("Unsupported protobuf wire type $wireType on field #$fieldNumber")
                    }
                }
            }
            return fields
        }

        @Throws(IOException::class)
        internal fun readRawPackedVarints(): List<Long> {
            val out = ArrayList<Long>()
            while (pos < endOffset) {
                out.add(readVarint())
            }
            return out
        }

        @Throws(IOException::class)
        private fun skipGroup(groupFieldNumber: Int) {
            while (pos < endOffset) {
                val tag = readVarint()
                val fn = (tag ushr 3).toInt()
                val wt = (tag and 0x07L).toInt()
                if (wt == WIRE_END_GROUP) {
                    if (fn == groupFieldNumber) return
                    throw IOException("Mismatched END_GROUP #$fn inside group #$groupFieldNumber")
                }
                when (wt) {
                    WIRE_VARINT -> readVarint()
                    WIRE_FIXED64 -> readFixed64()
                    WIRE_LENGTH_DELIMITED -> {
                        val lenLong = readVarint()
                        if (lenLong < 0L || lenLong > (endOffset - pos).toLong()) {
                            throw IOException("Truncated length-delimited field while skipping group #$groupFieldNumber")
                        }
                        pos += lenLong.toInt()
                    }
                    WIRE_FIXED32 -> readFixed32()
                    WIRE_START_GROUP -> skipGroup(fn)
                    else -> throw IOException("Unsupported wire type $wt while skipping group #$groupFieldNumber")
                }
            }
            throw IOException("Unterminated protobuf group #$groupFieldNumber")
        }

        @Throws(IOException::class)
        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= endOffset) {
                    throw IOException("Truncated protobuf varint at offset $pos")
                }
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
            throw IOException("Malformed protobuf varint (>10 bytes)")
        }

        @Throws(IOException::class)
        private fun readFixed64(): Long {
            if (endOffset - pos < 8) {
                throw IOException("Truncated protobuf fixed64 at offset $pos")
            }
            var v = 0L
            for (i in 0 until 8) {
                v = v or ((data[pos++].toLong() and 0xFFL) shl (i * 8))
            }
            return v
        }

        @Throws(IOException::class)
        private fun readFixed32(): Int {
            if (endOffset - pos < 4) {
                throw IOException("Truncated protobuf fixed32 at offset $pos")
            }
            var v = 0
            for (i in 0 until 4) {
                v = v or ((data[pos++].toInt() and 0xFF) shl (i * 8))
            }
            return v
        }
    }

    // ---- Version Request / Response (raw binary, not protobuf) ----

    data class VersionResponse(
        val major: Int,
        val minor: Int,
        val status: Int,
    ) {
        val isMatch: Boolean get() = status == VERSION_STATUS_MATCH
    }

    fun encodeVersionRequest(major: Int = PROTOCOL_MAJOR, minor: Int = PROTOCOL_MINOR): ByteArray {
        return byteArrayOf(
            ((major ushr 8) and 0xFF).toByte(),
            (major and 0xFF).toByte(),
            ((minor ushr 8) and 0xFF).toByte(),
            (minor and 0xFF).toByte(),
        )
    }

    fun encodeVersionResponse(major: Int, minor: Int, status: Int = VERSION_STATUS_MATCH): ByteArray {
        return byteArrayOf(
            ((major ushr 8) and 0xFF).toByte(),
            (major and 0xFF).toByte(),
            ((minor ushr 8) and 0xFF).toByte(),
            (minor and 0xFF).toByte(),
            ((status ushr 8) and 0xFF).toByte(),
            (status and 0xFF).toByte(),
        )
    }

    fun decodeVersionResponse(body: ByteArray): VersionResponse {
        require(body.size >= 4) { "VERSION_RESPONSE too short: ${body.size} bytes" }
        val major = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val minor = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        val status = if (body.size >= 6) {
            ((body[4].toInt() and 0xFF) shl 8) or (body[5].toInt() and 0xFF)
        } else {
            VERSION_STATUS_MATCH
        }
        return VersionResponse(major = major, minor = minor, status = status)
    }

    // ---- AuthCompleteIndication ----

    fun encodeAuthComplete(status: Int = STATUS_SUCCESS): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, status)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAuthComplete(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: STATUS_SUCCESS
    }

    // ---- ServiceDiscoveryResponse ----

    fun encodeServiceDiscoveryResponse(
        profile: AapDisplayProfile,
        carSerial: String = "BMW-F48-CP",
        supportedKeycodes: IntArray = AapKeyCode.ALL_SUPPORTED,
    ): ByteArray = ProtoWriter(384).apply {
        // Channel 1: Sensor (DRIVING_STATUS=13, NIGHT_DATA=10, PARKING_BRAKE=7)
        writeSubmessageField(1) {
            writeVarintField(1, CH_SENSOR)
            writeSubmessageField(2) {
                writeSubmessageField(1) { writeVarintField(1, SENSOR_DRIVING_STATUS) }
                writeSubmessageField(1) { writeVarintField(1, SENSOR_NIGHT_DATA) }
                writeSubmessageField(1) { writeVarintField(1, SENSOR_PARKING_BRAKE) }
            }
        }

        // Channel 2: Video H.264 Baseline/High sink
        writeSubmessageField(1) {
            writeVarintField(1, CH_VIDEO)
            writeSubmessageField(3) {
                writeVarintField(1, STREAM_TYPE_VIDEO_H264_BP)
                writeSubmessageField(4) {
                    writeVarintField(1, profile.videoResolutionEnum)
                    writeVarintField(2, profile.videoFpsEnum)
                    writeVarintField(3, profile.marginWidth)
                    writeVarintField(4, profile.marginHeight)
                    writeVarintField(5, profile.dpi)
                    writeVarintField(8, 10000) // pixel_aspect_ratio_e4
                    writeVarintField(10, STREAM_TYPE_VIDEO_H264_BP) // codec = H264_BP
                }
                writeVarintField(6, 0) // display_id = 0
                writeVarintField(7, DISPLAY_TYPE_MAIN) // display_type = MAIN
            }
        }

        // Channel 3: Input (Touchscreen + Rotary/Keys)
        writeSubmessageField(1) {
            writeVarintField(1, CH_INPUT)
            writeSubmessageField(4) {
                writePackedVarintsField(1, supportedKeycodes)
                writeSubmessageField(2) {
                    writeVarintField(1, profile.videoWidth)
                    writeVarintField(2, profile.videoHeight)
                    writeVarintField(3, DISPLAY_TYPE_MAIN)
                }
                writeVarintField(5, 0) // display_id = 0
            }
        }

        // Channel 5: System Audio sink (16 kHz / 16-bit / mono PCM)
        writeSubmessageField(1) {
            writeVarintField(1, CH_AUDIO_SYSTEM)
            writeSubmessageField(3) {
                writeVarintField(1, STREAM_TYPE_AUDIO_PCM)
                writeVarintField(2, AUDIO_TYPE_SYSTEM)
                writeSubmessageField(3) {
                    writeVarintField(1, 16000)
                    writeVarintField(2, 16)
                    writeVarintField(3, 1)
                }
            }
        }

        // Channel 7: Mic / AV Input (16 kHz / 16-bit / mono PCM)
        writeSubmessageField(1) {
            writeVarintField(1, CH_MIC)
            writeSubmessageField(5) {
                writeVarintField(1, STREAM_TYPE_AUDIO_PCM)
                writeSubmessageField(2) {
                    writeVarintField(1, 16000)
                    writeVarintField(2, 16)
                    writeVarintField(3, 1)
                }
            }
        }

        // Head Unit metadata
        writeStringField(2, "BMW")
        writeStringField(3, "X1 F48")
        writeStringField(4, "2020")
        writeStringField(5, carSerial)
        writeVarintField(6, DRIVER_POSITION_LEFT)
        writeStringField(7, "BMW")
        writeStringField(8, "HU_ENTRYNAV2")
        writeStringField(9, "1")
        writeStringField(10, "0.3.0")
        writeBoolField(11, false) // can_play_native_media_during_vr
        writeVarintField(13, 0) // session_configuration
        writeStringField(14, "BMW X1")

        // HeadUnitInfo (field 17)
        writeSubmessageField(17) {
            writeStringField(1, "BMW")
            writeStringField(2, "X1 F48")
            writeStringField(3, "2020")
            writeStringField(4, carSerial)
            writeStringField(5, "BMW")
            writeStringField(6, "HU_ENTRYNAV2")
            writeStringField(7, "1")
            writeStringField(8, "0.3.0")
            writeVarintField(9, 0) // vehicle_type = 0
        }
    }.toByteArray()

    // ---- ChannelOpen ----

    data class ChannelOpenRequest(
        val priority: Int,
        val channelId: Int,
    )

    fun encodeChannelOpenRequest(priority: Int, channelId: Int): ByteArray = ProtoWriter(16).apply {
        writeSint32Field(1, priority)
        writeVarintField(2, channelId)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeChannelOpenRequest(body: ByteArray): ChannelOpenRequest {
        val fields = ProtoReader(body).readAllFields()
        val priority = fields.firstOrNull { it.fieldNumber == 1 }?.asSint32() ?: 0
        val channelId = fields.firstOrNull { it.fieldNumber == 2 }?.asInt() ?: 0
        return ChannelOpenRequest(priority = priority, channelId = channelId)
    }

    fun encodeChannelOpenResponse(status: Int = STATUS_SUCCESS): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, status)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeChannelOpenResponse(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: STATUS_SUCCESS
    }

    // ---- Ping ----

    data class PingRequest(
        val timestamp: Long,
        val bugreportRequest: Boolean = false,
        val payload: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PingRequest) return false
            val payloadEq = when {
                payload == null && other.payload == null -> true
                payload != null && other.payload != null -> payload.contentEquals(other.payload)
                else -> false
            }
            return timestamp == other.timestamp &&
                bugreportRequest == other.bugreportRequest &&
                payloadEq
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + bugreportRequest.hashCode()
            result = 31 * result + (payload?.contentHashCode() ?: 0)
            return result
        }
    }

    data class PingResponse(
        val timestamp: Long,
        val payload: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PingResponse) return false
            val payloadEq = when {
                payload == null && other.payload == null -> true
                payload != null && other.payload != null -> payload.contentEquals(other.payload)
                else -> false
            }
            return timestamp == other.timestamp && payloadEq
        }

        override fun hashCode(): Int = 31 * timestamp.hashCode() + (payload?.contentHashCode() ?: 0)
    }

    fun encodePingRequest(
        timestamp: Long,
        bugreportRequest: Boolean = false,
        payload: ByteArray? = null,
    ): ByteArray = ProtoWriter(32).apply {
        writeVarintField(1, timestamp)
        if (bugreportRequest) {
            writeBoolField(2, true)
        }
        if (payload != null) {
            writeBytesField(3, payload)
        }
    }.toByteArray()

    @Throws(IOException::class)
    fun decodePingRequest(body: ByteArray): PingRequest {
        val fields = ProtoReader(body).readAllFields()
        val ts = fields.firstOrNull { it.fieldNumber == 1 }?.asLong() ?: 0L
        val bug = fields.firstOrNull { it.fieldNumber == 2 }?.asBool() ?: false
        val payload = fields.firstOrNull { it.fieldNumber == 3 }?.asBytes()
        return PingRequest(timestamp = ts, bugreportRequest = bug, payload = payload)
    }

    fun encodePingResponse(timestamp: Long, payload: ByteArray? = null): ByteArray = ProtoWriter(32).apply {
        writeVarintField(1, timestamp)
        if (payload != null) {
            writeBytesField(2, payload)
        }
    }.toByteArray()

    @Throws(IOException::class)
    fun decodePingResponse(body: ByteArray): PingResponse {
        val fields = ProtoReader(body).readAllFields()
        val ts = fields.firstOrNull { it.fieldNumber == 1 }?.asLong() ?: 0L
        val payload = fields.firstOrNull { it.fieldNumber == 2 }?.asBytes()
        return PingResponse(timestamp = ts, payload = payload)
    }

    // ---- Shutdown (ByeBye) ----

    fun encodeShutdownRequest(reason: Int = SHUTDOWN_REASON_USER_SELECTION): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, reason)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeShutdownRequest(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: SHUTDOWN_REASON_USER_SELECTION
    }

    fun encodeShutdownResponse(): ByteArray = ByteArray(0)

    // ---- Audio Focus ----

    data class AudioFocusResponse(
        val focusState: Int,
        val granted: Boolean,
    )

    fun encodeAudioFocusRequest(focusType: Int): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, focusType)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAudioFocusRequest(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: AUDIO_FOCUS_GAIN
    }

    fun encodeAudioFocusResponse(focusState: Int, granted: Boolean = true): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, focusState)
        writeBoolField(2, granted)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAudioFocusResponse(body: ByteArray): AudioFocusResponse {
        val fields = ProtoReader(body).readAllFields()
        val state = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: AUDIO_FOCUS_STATE_GAIN
        val granted = fields.firstOrNull { it.fieldNumber == 2 }?.asBool() ?: false
        return AudioFocusResponse(focusState = state, granted = granted)
    }

    // ---- Navigation Focus ----

    fun encodeNavFocusRequest(type: Int): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, type)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeNavFocusRequest(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: NAV_FOCUS_PROJECTED
    }

    fun encodeNavFocusResponse(type: Int = NAV_FOCUS_PROJECTED): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, type)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeNavFocusResponse(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: NAV_FOCUS_PROJECTED
    }

    // ---- Sensor ----

    data class SensorStartRequest(
        val sensorType: Int,
        val refreshInterval: Long,
    )

    fun encodeSensorStartRequest(sensorType: Int, refreshInterval: Long = 0L): ByteArray = ProtoWriter(16).apply {
        writeVarintField(1, sensorType)
        writeVarintField(2, refreshInterval)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeSensorStartRequest(body: ByteArray): SensorStartRequest {
        val fields = ProtoReader(body).readAllFields()
        val sensorType = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: 0
        val refreshInterval = fields.firstOrNull { it.fieldNumber == 2 }?.asLong() ?: 0L
        return SensorStartRequest(sensorType = sensorType, refreshInterval = refreshInterval)
    }

    fun encodeSensorStartResponse(status: Int = STATUS_SUCCESS): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, status)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeSensorStartResponse(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: STATUS_SUCCESS
    }

    fun encodeSensorParkingBrake(engaged: Boolean): ByteArray = ProtoWriter(16).apply {
        writeSubmessageField(SENSOR_PARKING_BRAKE) {
            writeBoolField(1, engaged)
        }
    }.toByteArray()

    fun encodeSensorNightMode(isNight: Boolean): ByteArray = ProtoWriter(16).apply {
        writeSubmessageField(SENSOR_NIGHT_DATA) {
            writeBoolField(1, isNight)
        }
    }.toByteArray()

    fun encodeSensorDrivingStatus(status: Int = DRIVING_STATUS_UNRESTRICTED): ByteArray = ProtoWriter(16).apply {
        writeSubmessageField(SENSOR_DRIVING_STATUS) {
            writeVarintField(1, status)
        }
    }.toByteArray()

    // ---- AV Setup / Start / Ack / InputOpen / VideoFocus ----

    data class AvSetupResponse(
        val mediaStatus: Int,
        val maxUnacked: Int,
        val configs: List<Int>,
    )

    data class AvStartIndication(
        val session: Int,
        val config: Int,
    )

    data class AvMediaAck(
        val sessionId: Int,
        val ackCount: Int,
    )

    data class AvInputOpenResponse(
        val session: Int,
        val value: Int,
    )

    data class VideoFocusRequest(
        val focusMode: Int,
        val focusReason: Int,
    )

    data class VideoFocusIndication(
        val focusMode: Int,
        val unrequested: Boolean,
    )

    fun encodeAvSetupRequest(mediaCodecType: Int): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, mediaCodecType)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAvSetupRequest(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: 0
    }

    fun encodeAvSetupResponse(
        mediaStatus: Int = AV_STATUS_OK,
        maxUnacked: Int,
        configs: IntArray = intArrayOf(0),
    ): ByteArray = ProtoWriter(24).apply {
        writeVarintField(1, mediaStatus)
        writeVarintField(2, maxUnacked)
        for (c in configs) {
            writeVarintField(3, c)
        }
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAvSetupResponse(body: ByteArray): AvSetupResponse {
        val fields = ProtoReader(body).readAllFields()
        val status = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: 0
        val maxUnacked = fields.firstOrNull { it.fieldNumber == 2 }?.asInt() ?: 0
        val configs = ArrayList<Int>()
        for (f in fields) {
            if (f.fieldNumber == 3) {
                if (f.wireType == WIRE_LENGTH_DELIMITED) {
                    configs.addAll(f.asPackedInts())
                } else if (f.wireType == WIRE_VARINT) {
                    configs.add(f.asInt())
                }
            }
        }
        return AvSetupResponse(mediaStatus = status, maxUnacked = maxUnacked, configs = configs)
    }

    fun encodeAvStartIndication(session: Int, config: Int = 0): ByteArray = ProtoWriter(16).apply {
        writeVarintField(1, session)
        writeVarintField(2, config)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAvStartIndication(body: ByteArray): AvStartIndication {
        val fields = ProtoReader(body).readAllFields()
        val session = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: 0
        val config = fields.firstOrNull { it.fieldNumber == 2 }?.asInt() ?: 0
        return AvStartIndication(session = session, config = config)
    }

    fun encodeAvMediaAck(sessionId: Int, ackCount: Int = 1): ByteArray = ProtoWriter(16).apply {
        writeVarintField(1, sessionId)
        writeVarintField(2, ackCount)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAvMediaAck(body: ByteArray): AvMediaAck {
        val fields = ProtoReader(body).readAllFields()
        val sessionId = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: 0
        val ackCount = fields.firstOrNull { it.fieldNumber == 2 }?.asInt() ?: 0
        return AvMediaAck(sessionId = sessionId, ackCount = ackCount)
    }

    fun encodeAvInputOpenRequest(open: Boolean): ByteArray = ProtoWriter(8).apply {
        writeBoolField(1, open)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAvInputOpenRequest(body: ByteArray): Boolean {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asBool() ?: false
    }

    fun encodeAvInputOpenResponse(session: Int = 0, value: Int = 0): ByteArray = ProtoWriter(16).apply {
        writeVarintField(1, session)
        writeVarintField(2, value)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeAvInputOpenResponse(body: ByteArray): AvInputOpenResponse {
        val fields = ProtoReader(body).readAllFields()
        val session = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: 0
        val value = fields.firstOrNull { it.fieldNumber == 2 }?.asInt() ?: 0
        return AvInputOpenResponse(session = session, value = value)
    }

    fun encodeVideoFocusRequest(focusMode: Int, focusReason: Int = 0): ByteArray = ProtoWriter(16).apply {
        writeVarintField(2, focusMode)
        writeVarintField(3, focusReason)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeVideoFocusRequest(body: ByteArray): VideoFocusRequest {
        val fields = ProtoReader(body).readAllFields()
        val mode = fields.firstOrNull { it.fieldNumber == 2 }?.asInt() ?: VIDEO_FOCUS_PROJECTED
        val reason = fields.firstOrNull { it.fieldNumber == 3 }?.asInt() ?: 0
        return VideoFocusRequest(focusMode = mode, focusReason = reason)
    }

    fun encodeVideoFocusIndication(focusMode: Int, unrequested: Boolean): ByteArray = ProtoWriter(16).apply {
        writeVarintField(1, focusMode)
        writeBoolField(2, unrequested)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeVideoFocusIndication(body: ByteArray): VideoFocusIndication {
        val fields = ProtoReader(body).readAllFields()
        val mode = fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: VIDEO_FOCUS_PROJECTED
        val unrequested = fields.firstOrNull { it.fieldNumber == 2 }?.asBool() ?: false
        return VideoFocusIndication(focusMode = mode, unrequested = unrequested)
    }

    // ---- Input Binding & Input Event ----

    fun encodeInputBindingRequest(keycodes: IntArray, packed: Boolean = true): ByteArray = ProtoWriter(32).apply {
        if (packed) {
            writePackedVarintsField(1, keycodes)
        } else {
            for (k in keycodes) {
                writeVarintField(1, k)
            }
        }
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeInputBindingRequest(body: ByteArray): List<Int> {
        val fields = ProtoReader(body).readAllFields()
        val keycodes = ArrayList<Int>()
        for (f in fields) {
            if (f.fieldNumber == 1) {
                when (f.wireType) {
                    WIRE_LENGTH_DELIMITED -> keycodes.addAll(f.asPackedInts())
                    WIRE_VARINT -> keycodes.add(f.asInt())
                }
            }
        }
        return keycodes
    }

    fun encodeInputBindingResponse(status: Int = STATUS_SUCCESS): ByteArray = ProtoWriter(8).apply {
        writeVarintField(1, status)
    }.toByteArray()

    @Throws(IOException::class)
    fun decodeInputBindingResponse(body: ByteArray): Int {
        val fields = ProtoReader(body).readAllFields()
        return fields.firstOrNull { it.fieldNumber == 1 }?.asInt() ?: STATUS_SUCCESS
    }

    fun encodeTouchInputEvent(
        timestampNs: Long,
        x: Int,
        y: Int,
        action: Int,
        pointerId: Int = 0,
    ): ByteArray = ProtoWriter(48).apply {
        writeVarintField(1, timestampNs)
        writeSubmessageField(3) {
            writeSubmessageField(1) {
                writeVarintField(1, x)
                writeVarintField(2, y)
                writeVarintField(3, pointerId)
            }
            writeVarintField(2, 0) // action_index
            writeVarintField(3, action) // touch_action
        }
    }.toByteArray()

    fun encodeButtonInputEvent(
        timestampNs: Long,
        keycode: Int,
        isPressed: Boolean,
        metaState: Int = 0,
        longPress: Boolean = false,
    ): ByteArray = ProtoWriter(32).apply {
        writeVarintField(1, timestampNs)
        writeSubmessageField(4) {
            writeSubmessageField(1) {
                writeVarintField(1, keycode)
                writeBoolField(2, isPressed)
                writeVarintField(3, metaState)
                writeBoolField(4, longPress)
            }
        }
    }.toByteArray()

    fun encodeRelativeInputEvent(
        timestampNs: Long,
        scanCode: Int,
        delta: Int,
    ): ByteArray = ProtoWriter(32).apply {
        writeVarintField(1, timestampNs)
        writeSubmessageField(6) {
            writeSubmessageField(1) {
                writeVarintField(1, scanCode)
                writeVarintField(2, delta)
            }
        }
    }.toByteArray()
}
