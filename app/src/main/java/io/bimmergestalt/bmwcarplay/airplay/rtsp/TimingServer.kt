package io.bimmergestalt.bmwcarplay.airplay.rtsp

import android.net.Network
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AirPlay NTP-over-RTCP Timing Server bound strictly to `carNetwork`.
 *
 * Advertised in initial RTSP `SETUP /setup` as `timingPort`. The BMW head unit
 * sends 32-byte RTCP timesync requests (`pt = 0xD2`) and expects 32-byte responses
 * (`pt = 0xD3`) echoing `t1 = request.t3` with local monotonic `t2` / `t3` timestamps.
 */
class TimingServer(
    private val logger: DiagnosticLogger,
) {
    private val running = AtomicBoolean(false)
    private val replyCount = AtomicLong(0L)

    @Volatile
    private var socket: DatagramSocket? = null

    val localPort: Int
        get() = socket?.localPort ?: 0

    @Synchronized
    fun start(carNetwork: Network): Int {
        stop()
        return try {
            val s = DatagramSocket().apply {
                reuseAddress = true
            }
            carNetwork.bindSocket(s)
            socket = s
            running.set(true)
            replyCount.set(0L)
            val port = s.localPort
            logger.log("TIMING", "NTP TimingServer pokrenut na carNetwork UDP portu $port")
            Thread({ receiveLoop(s) }, "airplay-timing").apply {
                isDaemon = true
                start()
            }
            port
        } catch (e: Exception) {
            logger.log("TIMING", "GREŠKA pri pokretanju TimingServer-a: ${e.message}")
            0
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(256)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (_: Exception) {
                break
            }
            val req = buf.copyOfRange(0, pkt.length)
            val arrival = System.nanoTime()
            val resp = buildResponse(req, arrival, System.nanoTime())
            if (resp == null) {
                logger.log("TIMING", "Primljen ne-NTP UDP paket (${req.size} B) od ${pkt.address.hostAddress}:${pkt.port}")
                continue
            }
            try {
                s.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                val n = replyCount.incrementAndGet()
                if (n <= 3L || n % 30L == 0L) {
                    logger.log("TIMING", "✓ NTP timesync odgovor #$n -> ${pkt.address.hostAddress}:${pkt.port}")
                }
            } catch (e: Exception) {
                if (running.get()) {
                    logger.log("TIMING", "Greška pri slanju NTP odgovora: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val PT_TIMESYNC_REQUEST: Byte = 0xD2.toByte()
        private const val PT_TIMESYNC_RESPONSE: Byte = 0xD3.toByte()
        const val PACKET_BYTES = 32

        /**
         * Pure function building a 32-byte RTCP NTP `0xD3` response from a 32-byte `0xD2` request.
         * Returns null if the incoming packet is not a valid AirPlay RTCP timesync request.
         */
        fun buildResponse(
            request: ByteArray,
            receiveNanos: Long,
            transmitNanos: Long = receiveNanos,
        ): ByteArray? {
            if (request.size < PACKET_BYTES) return null
            val version = (request[0].toInt() and 0xFF) ushr 6
            if (version != 2) return null
            if (request[1] != PT_TIMESYNC_REQUEST) return null
            val wordsMinusOne = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
            if (wordsMinusOne != PACKET_BYTES / 4 - 1) return null

            val response = ByteArray(PACKET_BYTES)
            response[0] = (2 shl 6).toByte()
            response[1] = PT_TIMESYNC_RESPONSE
            response[2] = 0
            response[3] = (PACKET_BYTES / 4 - 1).toByte()
            // Bytes 4..7 = rtp_ts (0)
            // Bytes 8..15 = t1 (echo request's t3 at offset 24..31)
            System.arraycopy(request, 24, response, 8, 8)
            // Bytes 16..23 = t2 (local receive monotonic NTP)
            System.arraycopy(ntp64BigEndianFromNanos(receiveNanos), 0, response, 16, 8)
            // Bytes 24..31 = t3 (local transmit monotonic NTP)
            System.arraycopy(ntp64BigEndianFromNanos(transmitNanos), 0, response, 24, 8)
            return response
        }

        fun ntp64BigEndianFromNanos(nanos: Long): ByteArray {
            val secs = Math.floorDiv(nanos, 1_000_000_000L)
            val fracNanos = Math.floorMod(nanos, 1_000_000_000L)
            val frac = (fracNanos shl 32) / 1_000_000_000L
            val packed = (secs shl 32) or (frac and 0xFFFF_FFFFL)
            return ByteArray(8) { idx -> ((packed ushr (8 * (7 - idx))) and 0xFFL).toByte() }
        }
    }
}
