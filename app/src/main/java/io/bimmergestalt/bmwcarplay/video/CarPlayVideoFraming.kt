package io.bimmergestalt.bmwcarplay.video

import io.bimmergestalt.bmwcarplay.airplay.BinaryPlist
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin wire-framing helpers for AirPlay / Wireless CarPlay Screen Stream (`type = 110`).
 *
 * Every message on the screen TCP socket begins with a 128-byte header:
 * - `[0..3]`   u32 LE: payload body length (includes 16-byte Poly1305 tag ONLY for ChaCha-sealed `opcode = 0`)
 * - `[4]`      u8:     opcode (`0` = VideoFrame, `1` = VideoConfig, `2` = KeepAlive empty, `5` = KeepAlive + stats bplist)
 * - `[5..7]`   u8[3]:  small params (`[6] = 0x04` `ScreenFlag.Encrypted` on `VideoConfig`)
 * - `[8..127]` u64[15]: 15 × 8-byte parameter slots
 *   - `VideoConfig` (`opcode = 1`): `params[0] = 0`, `params[1]` (`[16..23]`) = `(width f32 LE, height f32 LE)`
 *   - `VideoFrame`  (`opcode = 0`): `params[0]` (`[8..15]`) = NTP 32.32 LE presentation timestamp
 *   - `KeepAliveWithStats` (`opcode = 5`): `params[14]` (`[120..127]`) = `(0.0f LE, bodySize.toFloat() LE)`
 */
object CarPlayVideoFraming {

    const val HEADER_LEN = 128
    const val OP_VIDEO_FRAME = 0
    const val OP_VIDEO_CONFIG = 1
    const val OP_KEEP_ALIVE_EMPTY = 2
    const val OP_KEEP_ALIVE_WITH_STATS = 5
    const val SCREEN_FLAG_ENCRYPTED = 0x04
    const val CHACHA_TAG_SIZE = 16
    const val MIN_H264_LEVEL_IDC = 40 // H.264 Level 4.0 (prevents strict automotive decoders from black-screening 30fps)

    /**
     * Builds the 128-byte plaintext `VideoConfig` (`opcode = 1`) header.
     *
     * Automotive decoders strictly require:
     * - `header[6]` = `0x04` (`Encrypted`) when VideoFrames are encrypted
     * - `header[8..15]` = all zeros (no PTS in config)
     * - `header[16..23]` = `(width, height)` as two little-endian IEEE 754 float32 values
     */
    fun buildVideoConfigHeader(
        avccBodySize: Int,
        width: Int,
        height: Int,
        encryptedFrames: Boolean = true,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        writeU32Le(header, 0, avccBodySize)
        header[4] = OP_VIDEO_CONFIG.toByte()
        if (encryptedFrames) {
            header[6] = SCREEN_FLAG_ENCRYPTED.toByte()
        }
        writeF32Le(header, 16, width.toFloat())
        writeF32Le(header, 20, height.toFloat())
        return header
    }

    /**
     * Builds the 128-byte `VideoFrame` (`opcode = 0`) header.
     * For ChaCha20-Poly1305 sessions, [wireBodySize] MUST be `plaintextLen + 16` AND this exact
     * 128-byte header MUST be passed as AAD to `chachaSeal(...)`.
     */
    fun buildVideoFrameHeader(
        wireBodySize: Int,
        monotonicNanos: Long,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        writeU32Le(header, 0, wireBodySize)
        header[4] = OP_VIDEO_FRAME.toByte()
        val secs = monotonicNanos / 1_000_000_000L
        val frac = ((monotonicNanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (secs shl 32) or (frac and 0xFFFF_FFFFL)
        writeU64Le(header, 8, ntp)
        return header
    }

    /**
     * Builds a plaintext Video Stream Keep-Alive frame (`opcode = 2` empty, or `opcode = 5` with
     * `bplist00` sender statistics when the head unit declared `keepAliveSendStatsAsBody = true`).
     * Never encrypted and never increments the ChaCha frame counter!
     */
    fun buildKeepAliveFrame(
        withStats: Boolean,
        framesSentWindow: Int,
        bytesPerSecond: Double,
        framesPerSecond: Int,
    ): ByteArray {
        val body = if (withStats) {
            BinaryPlist.encode(
                linkedMapOf(
                    "txUsageAvg" to bytesPerSecond,
                    "encoderCurrentFPS" to framesPerSecond.toLong(),
                    "sentFramesAvg" to framesSentWindow.toLong(),
                    "queuedFramesAvg" to 0L,
                    "lossAvg" to 0.0,
                ),
            )
        } else {
            ByteArray(0)
        }
        val header = ByteArray(HEADER_LEN)
        writeU32Le(header, 0, body.size)
        header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE_EMPTY else OP_KEEP_ALIVE_WITH_STATS).toByte()
        if (body.isNotEmpty()) {
            writeF32Le(header, 120, 0f)
            writeF32Le(header, 124, body.size.toFloat())
        }
        return if (body.isEmpty()) header else header + body
    }

    /** Splits an Annex-B H.264 stream (`00 00 01` or `00 00 00 01` start codes) into raw NAL units. */
    fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        val prefixLens = ArrayList<Int>()
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (bytes[i + 2] == 1.toByte()) {
                    starts.add(i)
                    prefixLens.add(3)
                    i += 3
                    continue
                }
                if (i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    starts.add(i)
                    prefixLens.add(4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) {
            return if (bytes.isEmpty()) emptyList() else listOf(bytes)
        }
        val result = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val bodyStart = starts[idx] + prefixLens[idx]
            val bodyEnd = if (idx + 1 < starts.size) starts[idx + 1] else bytes.size
            if (bodyEnd > bodyStart) {
                result.add(bytes.copyOfRange(bodyStart, bodyEnd))
            }
        }
        return result
    }

    /**
     * Patches H.264 SPS `level_idc` upward to at least 4.0 (`40`) without lowering higher levels.
     * Strict automotive decoders sometimes silently drop streams tagged Level 3.1/3.2 at 30fps.
     */
    fun patchSpsLevelTo40(sps: ByteArray): ByteArray {
        if (sps.size < 4) return sps
        val nalType = sps[0].toInt() and 0x1F
        if (nalType != 7) return sps
        val currentLevel = sps[3].toInt() and 0xFF
        if (currentLevel >= MIN_H264_LEVEL_IDC) return sps
        return sps.copyOf().also { it[3] = MIN_H264_LEVEL_IDC.toByte() }
    }

    /** Builds ISO/IEC 14496-15 `AVCDecoderConfigurationRecord` (`avcC`) from raw SPS & PPS NALs. */
    fun buildAvcc(rawSps: ByteArray, pps: ByteArray): ByteArray {
        val sps = patchSpsLevelTo40(rawSps)
        val out = ByteArrayOutputStream(11 + sps.size + pps.size)
        out.write(0x01) // configurationVersion = 1
        out.write(if (sps.size > 1) sps[1].toInt() and 0xFF else 0x42) // AVCProfileIndication
        out.write(if (sps.size > 2) sps[2].toInt() and 0xFF else 0x00) // profile_compatibility
        out.write(if (sps.size > 3) sps[3].toInt() and 0xFF else MIN_H264_LEVEL_IDC) // AVCLevelIndication
        out.write(0xFF) // 6 bits reserved + lengthSizeMinusOne = 3 (4-byte NAL length prefixes)
        out.write(0xE1) // 3 bits reserved + numOfSequenceParameterSets = 1
        out.write((sps.size ushr 8) and 0xFF)
        out.write(sps.size and 0xFF)
        out.write(sps)
        out.write(0x01) // numOfPictureParameterSets = 1
        out.write((pps.size ushr 8) and 0xFF)
        out.write(pps.size and 0xFF)
        out.write(pps)
        return out.toByteArray()
    }

    /**
     * Converts Annex-B frame bytes into 4-byte big-endian length-prefixed AVCC NAL sequence,
     * patching any inline SPS NALs to at least Level 4.0.
     */
    fun packAvccNals(annexB: ByteArray): ByteArray {
        val nals = splitAnnexB(annexB)
        if (nals.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(annexB.size + nals.size * 4)
        for (nal in nals) {
            if (nal.isEmpty()) continue
            val patched = if ((nal[0].toInt() and 0x1F) == 7) patchSpsLevelTo40(nal) else nal
            val len = patched.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(patched)
        }
        return out.toByteArray()
    }

    internal fun writeU32Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    internal fun writeU64Le(dst: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            dst[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
        }
    }

    internal fun writeF32Le(dst: ByteArray, offset: Int, value: Float) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value)
        bb.array().copyInto(dst, offset)
    }
}
