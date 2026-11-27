package io.bimmergestalt.bmwcarplay.airplay

import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.diag.HexDump
import io.bimmergestalt.bmwcarplay.net.CarWifiConnector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Probes the BMW head unit's AirPlay RTSP endpoint with `GET /info RTSP/1.0`
 * strictly over `carNetwork` (`carNetwork.socketFactory`).
 *
 * - Supports both IPv4 and IPv6 link-local (`fe80::...%wlan0`) addresses.
 * - Retries every 2 seconds for up to 30 seconds; after 5 seconds without mDNS results,
 *   automatically adds DHCP gateway fallback (`gateway:7000`).
 * - Can be woken immediately whenever `/ctrl-int/1/connect` or `0x4301` arrives!
 * - Decodes the returned `bplist00` dictionary and logs complete display, HID, audio,
 *   and keep-alive capability details for Phase 2 planning.
 */
class AirPlayInfoClient(
    private val logger: DiagnosticLogger,
) {
    data class EndpointCandidate(
        val host: String,
        val port: Int,
        val source: String,
    )

    data class InfoResult(
        val host: String,
        val port: Int,
        val statusLine: String,
        val parsedRoot: Map<String, Any?>,
        val rawBody: ByteArray,
    )

    companion object {
        const val DEFAULT_AIRPLAY_PORT = 7000
        private const val USER_AGENT = "AirPlay/566.25.21"
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val READ_TIMEOUT_MS = 4_000
        private const val MDNS_GRACE_BEFORE_GATEWAY_FALLBACK_MS = 5_000L
        private const val RETRY_INTERVAL_MS = 2_000L
        private const val TOTAL_PROBE_WINDOW_MS = 30_000L
    }

    private val candidates = CopyOnWriteArrayList<EndpointCandidate>()
    private val wakeupLock = Object()
    private val running = AtomicBoolean(false)
    private val activeSocket = java.util.concurrent.atomic.AtomicReference<Socket?>(null)

    fun addCandidate(host: String, port: Int, source: String) {
        val cleanHost = host.trim()
        val effectivePort = if (port > 0) port else DEFAULT_AIRPLAY_PORT
        if (cleanHost.isEmpty()) return
        val entry = EndpointCandidate(cleanHost, effectivePort, source)
        if (candidates.none { it.host.equals(cleanHost, ignoreCase = true) && it.port == effectivePort }) {
            candidates.add(0, entry) // Prioritize newly arrived mDNS / 0x4301 / ctrl endpoints
            logger.log("AIRPLAY-INFO", "Dodan kandidat za AirPlay /info: $cleanHost:$effectivePort ($source)")
        }
        triggerImmediateRetry()
    }

    fun triggerImmediateRetry() {
        synchronized(wakeupLock) {
            wakeupLock.notifyAll()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { activeSocket.getAndSet(null)?.close() }
        triggerImmediateRetry()
    }

    /**
     * Runs the bounded retry loop (up to 30s, every 2s, immediate wakeup on ctrl invite / 0x4301)
     * until `GET /info` returns 200 OK with a parsed bplist dictionary, or window expires.
     */
    fun probeWithRetries(
        carNetInfo: CarWifiConnector.CarNetworkInfo,
        onSuccess: (InfoResult) -> Unit,
        onFailure: (reasonHr: String) -> Unit,
    ) {
        running.set(true)
        val startedAt = nowMs()
        val deadline = startedAt + TOTAL_PROBE_WINDOW_MS
        var gatewayFallbackAdded = false
        var attempt = 0

        logger.log(
            "AIRPLAY-INFO",
            "Pokrećem AirPlay GET /info provjeru na carNetwork (prozor ${TOTAL_PROBE_WINDOW_MS / 1000}s, " +
                "fallback na gateway:7000 nakon ${MDNS_GRACE_BEFORE_GATEWAY_FALLBACK_MS / 1000}s ako mDNS ne vrati rezultat)...",
        )

        while (running.get() && nowMs() < deadline) {
            // After 5s, add DHCP gateway fallback on port 7000 if not already added
            if (!gatewayFallbackAdded &&
                nowMs() - startedAt >= MDNS_GRACE_BEFORE_GATEWAY_FALLBACK_MS
            ) {
                gatewayFallbackAdded = true
                carNetInfo.gatewayIpv4?.let { gw ->
                    addCandidate(gw, DEFAULT_AIRPLAY_PORT, "DHCP gateway IPv4 fallback")
                }
                carNetInfo.gatewayIpv6?.let { gw6 ->
                    addCandidate(gw6, DEFAULT_AIRPLAY_PORT, "Gateway IPv6 fallback")
                }
            }

            val snapshot = candidates.toList()
            if (snapshot.isEmpty()) {
                waitWithWakeup(500L)
                continue
            }

            attempt++
            for (target in snapshot) {
                if (!running.get()) return
                logger.log(
                    "AIRPLAY-INFO",
                    "Pokušaj #$attempt: GET /info RTSP/1.0 -> ${target.host}:${target.port} [${target.source}]",
                )
                val result = tryFetchInfo(carNetInfo, target.host, target.port)
                if (result != null) {
                    running.set(false)
                    logger.log("AIRPLAY-INFO", formatInfoSummaryHr(result))
                    onSuccess(result)
                    return
                }
            }

            waitWithWakeup(RETRY_INTERVAL_MS)
        }

        if (running.compareAndSet(true, false)) {
            val msg = "AirPlay GET /info nije uspio unutar ${TOTAL_PROBE_WINDOW_MS / 1000}s (kandidati: ${candidates.map { "${it.host}:${it.port}" }})"
            logger.log("AIRPLAY-INFO", "✗ $msg")
            onFailure(msg)
        }
    }

    private fun tryFetchInfo(
        carNetInfo: CarWifiConnector.CarNetworkInfo,
        host: String,
        port: Int,
    ): InfoResult? {
        val inetAddr = try {
            resolveAddressWithScope(host, carNetInfo.interfaceName)
        } catch (e: Exception) {
            logger.log("AIRPLAY-INFO", "Ne mogu razriješiti adresu '$host': ${e.message}")
            return null
        }

        val socket = try {
            carNetInfo.network.socketFactory.createSocket()
        } catch (e: IOException) {
            logger.log("AIRPLAY-INFO", "Ne mogu kreirati socket na carNetwork: ${e.message}")
            return null
        }

        activeSocket.set(socket)
        return try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(inetAddr, port), CONNECT_TIMEOUT_MS)

            // Empty bplist dictionary body as required by AirPlay GET /info
            val reqBody = BinaryPlist.encode(linkedMapOf<String, Any?>())
            val reqHead = buildString {
                append("GET /info RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Content-Type: application/x-apple-binary-plist\r\n")
                append("Content-Length: ${reqBody.size}\r\n")
                append("\r\n")
            }
            val wireRequest = reqHead.toByteArray(Charsets.US_ASCII) + reqBody
            logger.logPacket("AIRPLAY-INFO", "TX RTSP GET /info (${host}:${port})", wireRequest)

            socket.getOutputStream().apply {
                write(wireRequest)
                flush()
            }

            val (statusLine, headersMap, responseBody) = readRtspResponse(socket)
            logger.log(
                "AIRPLAY-INFO",
                "RX RTSP [$statusLine] headers=$headersMap body=${responseBody.size} B",
            )
            if (responseBody.isNotEmpty()) {
                logger.logPacket("AIRPLAY-INFO", "RX /info bplist tijelo", responseBody)
            }

            if (!statusLine.contains("200")) {
                logger.log("AIRPLAY-INFO", "Auto vratio status bez 200 OK: $statusLine")
                return null
            }

            val parsed = if (responseBody.isNotEmpty()) {
                val root = BinaryPlist.parse(responseBody)
                @Suppress("UNCHECKED_CAST")
                (root as? Map<String, Any?>) ?: linkedMapOf("root" to root)
            } else {
                linkedMapOf()
            }

            InfoResult(
                host = host,
                port = port,
                statusLine = statusLine,
                parsedRoot = parsed,
                rawBody = responseBody,
            )
        } catch (e: IOException) {
            logger.log("AIRPLAY-INFO", "Veza prema $host:$port nije uspjela: ${e.message}")
            null
        } catch (e: Exception) {
            logger.log("AIRPLAY-INFO", "Greška pri parsiranju /info od $host:$port: ${e.message}")
            null
        } finally {
            activeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    /**
     * Reads RTSP headers and exact Content-Length body from a single unified byte buffer
     * so body bytes arriving in the same TCP segment as the headers are NEVER lost.
     */
    @Throws(IOException::class)
    private fun readRtspResponse(socket: Socket): Triple<String, Map<String, String>, ByteArray> {
        val input = socket.getInputStream()
        val acc = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var headerEnd = -1
        var contentLength = -1

        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (n == 0) continue
            acc.write(chunk, 0, n)

            val current = acc.toByteArray()
            if (headerEnd < 0) {
                headerEnd = findCrLfCrLf(current)
                if (headerEnd >= 0) {
                    val headerText = String(current, 0, headerEnd, Charsets.ISO_8859_1)
                    contentLength = extractContentLength(headerText)
                }
            }
            if (headerEnd >= 0) {
                val bodyStart = headerEnd + 4
                val receivedBodyLen = current.size - bodyStart
                if (contentLength >= 0 && receivedBodyLen >= contentLength) {
                    break
                }
            }
        }

        val allBytes = acc.toByteArray()
        if (headerEnd < 0) {
            val preview = String(allBytes, Charsets.ISO_8859_1)
            throw IOException("Nepotpun RTSP odgovor (nema \\r\\n\\r\\n): ${preview.take(200)}")
        }

        val headerText = String(allBytes, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty().trim()
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }

        val bodyStart = headerEnd + 4
        val bodyEnd = if (contentLength >= 0) {
            minOf(allBytes.size, bodyStart + contentLength)
        } else {
            allBytes.size
        }
        val body = if (bodyEnd > bodyStart) {
            allBytes.copyOfRange(bodyStart, bodyEnd)
        } else {
            ByteArray(0)
        }
        return Triple(statusLine, headers, body)
    }

    private fun resolveAddressWithScope(host: String, defaultIfaceName: String?): InetAddress {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        val percentIdx = clean.indexOf('%')
        val addrPart = if (percentIdx >= 0) clean.substring(0, percentIdx) else clean
        val scopePart = if (percentIdx >= 0) clean.substring(percentIdx + 1) else defaultIfaceName

        val baseAddr = InetAddress.getByName(addrPart)
        if (baseAddr is Inet6Address && baseAddr.isLinkLocalAddress && !scopePart.isNullOrBlank()) {
            val nif = runCatching { NetworkInterface.getByName(scopePart) }.getOrNull()
            if (nif != null) {
                return Inet6Address.getByAddress(null, baseAddr.address, nif)
            }
        }
        return baseAddr
    }

    private fun formatInfoSummaryHr(result: InfoResult): String {
        val root = result.parsedRoot
        val sb = StringBuilder()
        sb.append("✓✓ AIRPLAY GET /info USPJEŠAN (${result.host}:${result.port} — ${result.statusLine}):")
        sb.append("\n  Model: ${root["model"] ?: "?"} | Naziv: ${root["name"] ?: "?"}")
        sb.append("\n  sourceVersion: ${root["sourceVersion"] ?: "?"} | deviceID: ${root["deviceID"] ?: "?"} | macAddress: ${root["macAddress"] ?: "?"}")
        sb.append("\n  statusFlags: ${root["statusFlags"] ?: "?"} | keepAliveLowPower: ${root["keepAliveLowPower"] ?: "?"} | keepAliveSendStatsAsBody: ${root["keepAliveSendStatsAsBody"] ?: "?"}")

        val featuresVal = root["features"]
        if (featuresVal is Number) {
            val analysis = AirPlayFeatures.analyze("0x${featuresVal.toLong().toULong().toString(16)}")
            if (analysis != null) {
                sb.append("\n  ").append(analysis.formatReportHr().replace("\n", "\n  "))
            }
        }

        // Displays breakdown
        val displays = root["displays"] as? List<*>
        if (!displays.isNullOrEmpty()) {
            sb.append("\n  Zasloni (displays = ${displays.size}):")
            displays.forEachIndexed { idx, item ->
                val d = item as? Map<*, *> ?: return@forEachIndexed
                sb.append(
                    "\n    [$idx] Rezolucija=${d["widthPixels"]}×${d["heightPixels"]} px | " +
                        "Fizički=${d["widthPhysical"]}×${d["heightPhysical"]} mm | " +
                        "maxFPS=${d["maxFPS"]} | features=${d["features"]} | uuid=${d["uuid"]}",
                )
            }
        }

        // HID devices (touchscreen + iDrive rotary knob)
        val hidDevices = root["hidDevices"] as? List<*>
        if (!hidDevices.isNullOrEmpty()) {
            sb.append("\n  HID ulazni uređaji (hidDevices = ${hidDevices.size}):")
            hidDevices.forEachIndexed { idx, item ->
                val h = item as? Map<*, *> ?: return@forEachIndexed
                val desc = h["hidDescriptor"] as? ByteArray
                val descInfo = desc?.let { "${it.size} B [${HexDump.toCompactHex(it, 32)}]" } ?: "<nema>"
                sb.append(
                    "\n    [$idx] naziv=${h["name"]} | uuid=${h["uuid"]} | " +
                        "vendorID=${h["hidVendorID"]} | productID=${h["hidProductID"]} | " +
                        "descriptor=$descInfo",
                )
            }
        }

        // Audio formats
        val audioFormats = root["audioFormats"] as? List<*>
        if (!audioFormats.isNullOrEmpty()) {
            sb.append("\n  Audio formati (audioFormats = ${audioFormats.size}):")
            audioFormats.forEachIndexed { idx, item ->
                val a = item as? Map<*, *> ?: return@forEachIndexed
                sb.append(
                    "\n    [$idx] type=${a["type"]} | audioInputFormats=${a["audioInputFormats"]} | " +
                        "audioOutputFormats=${a["audioOutputFormats"]}",
                )
            }
        }

        sb.append("\n  --- Cijeli /info bplist rječnik ---\n")
        sb.append(BinaryPlist.formatReadable(root, "  "))
        return sb.toString()
    }

    private fun findCrLfCrLf(bytes: ByteArray): Int {
        for (i in 0..(bytes.size - 4)) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun extractContentLength(headerText: String): Int =
        headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: -1

    private fun waitWithWakeup(ms: Long) {
        synchronized(wakeupLock) {
            if (!running.get()) return
            try {
                wakeupLock.wait(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
