package io.bimmergestalt.bmwcarplay.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Network
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayCrypto
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.diag.HexDump
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 (`MediaCodec`) encoder and CarPlay Screen Stream (`type = 110`) TCP sender.
 *
 * Key BMW `HU_ENTRYNAV2` (`AirTunes/280.33.8`) guarantees:
 * - Connects strictly via `carNetwork.socketFactory` (keeps 5G cellular data alive on Galaxy S25!).
 * - Supports opening TCP `dataPort` immediately after screen `SETUP` (before `RECORD`) so the
 *   head unit's ~2s data-port watchdog never triggers.
 * - Encodes at exact `displays[0]` resolution (even-aligned) with `6.5 Mbps`, `B-frames = 0`,
 *   `BT.709 Limited`, `AVCLevel4` (`level_idc = 40`), and `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100ms`.
 * - Sends plaintext `VideoConfig` (`opcode = 1`, `flags = 0x04`, `width/height` f32 LE, `avcC`) and
 *   logs its first 32 header bytes in HEX.
 * - Seals `VideoFrame` (`opcode = 0`) with ChaCha20-Poly1305 using the exact 128-byte header as AAD
 *   (or continuous AES-128-CTR if `et = NONE`).
 * - Sends plaintext Video Keep-Alive every 1.0 s (`opcode = 5` + `bplist00` stats when
 *   `keepAliveSendStatsAsBody = true`, else `opcode = 2`), never consuming the ChaCha frame counter!
 */
class CarPlayVideoSender(
    private val carNetwork: Network,
    private val host: InetAddress,
    private val dataPort: Int,
    private val frameCipher: FrameCipher,
    rawWidth: Int,
    rawHeight: Int,
    private val targetFps: Int,
    private val keepAliveWithStats: Boolean,
    private val logger: DiagnosticLogger,
    private val onStatsUpdate: (framesSent: Long, fps: Int) -> Unit,
    private val onEnded: (reasonHr: String) -> Unit,
) {
    sealed class FrameCipher {
        class ChaCha(val streamKey: ByteArray) : FrameCipher()
        class AesCtr(val key: ByteArray, val iv: ByteArray) : FrameCipher()
    }

    companion object {
        private const val VIDEO_BITRATE_BPS = 6_500_000
        private const val KEEPALIVE_INTERVAL_MS = 1_000L
        private const val REPEAT_FRAME_AFTER_US = 100_000L // 100 ms -> min 10 fps when static
    }

    val width: Int = (rawWidth / 2) * 2
    val height: Int = (rawHeight / 2) * 2

    private val running = AtomicBoolean(false)
    private val framesSent = AtomicLong(0L)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var inputSurface: Surface? = null

    private var frameCounter = 0L
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? FrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    private var lastKeepAliveAtMs = 0L
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L

    val totalFramesSent: Long get() = framesSent.get()
    val isRunning: Boolean get() = running.get()

    /**
     * Opens the screen stream TCP `dataPort` on `carNetwork` right after `SETUP #2` (before `RECORD`).
     */
    @Synchronized
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && existing.isConnected && !existing.isClosed) return true
        return try {
            val s = carNetwork.socketFactory.createSocket()
            s.tcpNoDelay = true
            s.soTimeout = 10_000
            s.connect(InetSocketAddress(host, dataPort), 5_000)
            socket = s
            logger.log(
                "VIDEO",
                "✓ TCP dataPort spojen na ${host.hostAddress}:$dataPort (carNetwork, lokalni port=${s.localPort})",
            )
            true
        } catch (e: Exception) {
            logger.log("VIDEO", "✗ Ne mogu spojiti video TCP dataPort ${host.hostAddress}:$dataPort: ${e.message}")
            false
        }
    }

    /**
     * Configures and starts the hardware H.264 MediaCodec encoder and begins draining frames to BMW.
     */
    @Synchronized
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (running.get()) return true
        if (!openDataPort()) {
            stop()
            return false
        }
        return try {
            val codec = configureEncoder()
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface
            running.set(true)
            lastKeepAliveAtMs = SystemClock.uptimeMillis()

            logger.log(
                "VIDEO",
                "✓ H.264 MediaCodec aktivan: ${width}×${height} @ ${targetFps}fps, " +
                    "${VIDEO_BITRATE_BPS / 1_000_000.0} Mbps, BT.709 Limited, B-frames=0, " +
                    "keepAlive=${if (keepAliveWithStats) "opcode=5 (bplist stats)" else "opcode=2 (prazno)"}",
            )
            onSurfaceReady(surface)

            Thread({ drainLoop() }, "carplay-video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            logger.log("VIDEO", "✗ Pokretanje H.264 MediaCodec enkodera nije uspjelo: ${e.message}")
            stop()
            false
        }
    }

    /** Immediately requests an H.264 IDR keyframe (triggered on BMW Event Channel `forceKeyFrame`). */
    fun requestKeyFrame() {
        val codec = encoder ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            logger.log("VIDEO", "Zatražen hitni H.264 ključni okvir (IDR / forceKeyFrame)")
        }.onFailure { e ->
            logger.log("VIDEO", "requestKeyFrame upozorenje: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun configureEncoder(): MediaCodec {
        val fullFormat = buildBaseFormat().apply {
            runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
            runCatching { setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709) }
            runCatching { setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) }
            runCatching { setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US) }
            runCatching { setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4) }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            codec.configure(fullFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            logger.log("VIDEO", "Upozorenje: puni MediaFormat odbijen (${e.message}) — pokušavam osnovni AVC profil bez KEY_LEVEL")
            runCatching { codec.release() }
            val fallbackCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val fallbackFormat = buildBaseFormat().apply {
                runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
                runCatching { setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US) }
            }
            fallbackCodec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            fallbackCodec
        }
    }

    private fun buildBaseFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    private fun drainLoop() {
        val codec = encoder ?: return
        val out = socket?.getOutputStream() ?: return
        val info = MediaCodec.BufferInfo()
        var endReason = "Video stream zaustavljen"

        try {
            while (running.get()) {
                val idx = codec.dequeueOutputBuffer(info, 15_000L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(out)
                    }

                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buf.get(chunk)
                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            if (isConfig) {
                                sendVideoConfig(chunk, out)
                            } else {
                                sendVideoFrame(chunk, isKey, out)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        maybeSendKeepAlive(out)
                    }
                }
            }
        } catch (e: IOException) {
            endReason = "BMW zatvorio video dataPort: ${e.message}"
            logger.log("VIDEO", "✗ $endReason")
        } catch (e: Exception) {
            endReason = "Greška u video petlji: ${e.message}"
            logger.log("VIDEO", "✗ $endReason")
        } finally {
            stop()
            onEnded(endReason)
        }
    }

    private fun sendVideoConfig(annexB: ByteArray, out: OutputStream) {
        val nals = CarPlayVideoFraming.splitAnnexB(annexB)
        val rawSps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (rawSps == null || pps == null) {
            logger.log("VIDEO", "Upozorenje: CSD bez SPS/PPS (${nals.size} NAL-a) — preskačem")
            return
        }
        val origLevel = if (rawSps.size >= 4) rawSps[3].toInt() and 0xFF else -1
        val avcc = CarPlayVideoFraming.buildAvcc(rawSps, pps)
        val patchedLevel = avcc[3].toInt() and 0xFF
        val header = CarPlayVideoFraming.buildVideoConfigHeader(
            avccBodySize = avcc.size,
            width = width,
            height = height,
            encryptedFrames = true,
        )
        out.write(header)
        out.write(avcc)
        out.flush()
        logger.log(
            "VIDEO",
            "✓ Poslan VideoConfig (opcode=1, nekriptirano): ${width}×${height} | avcC=${avcc.size} B | " +
                "SPS level $origLevel -> $patchedLevel | prvih 32 B zaglavlja:\n  " +
                HexDump.toCompactHex(header.copyOfRange(0, 32)),
        )
    }

    private fun sendVideoFrame(annexB: ByteArray, isKeyFrame: Boolean, out: OutputStream) {
        val avccPlain = CarPlayVideoFraming.packAvccNals(annexB)
        if (avccPlain.isEmpty()) return

        val wireBodyLen = when (frameCipher) {
            is FrameCipher.ChaCha -> avccPlain.size + CarPlayVideoFraming.CHACHA_TAG_SIZE
            is FrameCipher.AesCtr -> avccPlain.size
        }
        val header = CarPlayVideoFraming.buildVideoFrameHeader(
            wireBodySize = wireBodyLen,
            monotonicNanos = System.nanoTime(),
        )
        val sealedBody = when (frameCipher) {
            is FrameCipher.ChaCha -> AirPlayCrypto.chachaSeal(
                key = frameCipher.streamKey,
                nonce = AirPlayCrypto.nonce64(frameCounter),
                plaintext = avccPlain,
                aad = header,
            )

            is FrameCipher.AesCtr -> aesStream!!.process(avccPlain)
        }
        frameCounter++
        out.write(header)
        out.write(sealedBody)
        out.flush()

        val total = framesSent.incrementAndGet()
        framesSinceKeepAlive++
        bytesSinceKeepAlive += avccPlain.size.toLong()
        if (total <= 5L || total % 150L == 0L) {
            logger.log(
                "VIDEO",
                "TX VideoFrame #$total: ${avccPlain.size} B${if (isKeyFrame) " [IDR]" else ""} (wire=${sealedBody.size} B)",
            )
        }
    }

    private fun maybeSendKeepAlive(out: OutputStream) {
        if (!running.get()) return
        val now = SystemClock.uptimeMillis()
        val elapsedMs = now - lastKeepAliveAtMs
        if (elapsedMs < KEEPALIVE_INTERVAL_MS) return

        val framesWindow = framesSinceKeepAlive
        val bytesWindow = bytesSinceKeepAlive
        val fps = ((framesWindow * 1000L) / elapsedMs.coerceAtLeast(1L)).toInt()
        val bps = (bytesWindow * 1000.0) / elapsedMs.coerceAtLeast(1L).toDouble()

        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0L
        lastKeepAliveAtMs = now
        keepAliveCount++

        val keepAlivePacket = CarPlayVideoFraming.buildKeepAliveFrame(
            withStats = keepAliveWithStats,
            framesSentWindow = framesWindow,
            bytesPerSecond = bps,
            framesPerSecond = fps,
        )
        out.write(keepAlivePacket)
        out.flush()
        onStatsUpdate(framesSent.get(), fps)

        if (keepAliveCount <= 3L || keepAliveCount % 15L == 0L) {
            logger.log(
                "VIDEO",
                "TX VideoKeepAlive #$keepAliveCount (opcode=${if (keepAliveWithStats) 5 else 2}, body=${keepAlivePacket.size - 128} B, fps=$fps, ukupno=${framesSent.get()} sličica)",
            )
        }
    }
}
