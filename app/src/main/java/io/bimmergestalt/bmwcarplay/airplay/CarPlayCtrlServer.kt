package io.bimmergestalt.bmwcarplay.airplay

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local TCP control server & Bonjour/mDNS advertiser on `carNetwork`.
 *
 * Advertises:
 * - `_carplay-ctrl._tcp` (name "iPhone", TXT: `id=<realPhoneBtMac>`, `srcvers=566.25.21`)
 * - `_device-info._tcp` (name "iPhone", TXT: `model=iPhone14,5`, `deviceid=<realPhoneBtMac>`)
 *
 * When BMW resolves `_carplay-ctrl._tcp` and connects (e.g. `GET /ctrl-int/1/connect`),
 * logs the complete HTTP/RTSP request headers + body, replies immediately with `200 OK`,
 * and triggers [onCarInvite] so AirPlay `/info` probing runs immediately.
 */
class CarPlayCtrlServer(
    context: Context,
    private val logger: DiagnosticLogger,
    private val onCarInvite: (peerIp: String) -> Unit,
) {
    companion object {
        const val CTRL_SERVICE_TYPE = "_carplay-ctrl._tcp"
        const val DEVICE_INFO_SERVICE_TYPE = "_device-info._tcp"
        const val SERVICE_NAME = "iPhone"
        const val SRCVERS = "566.25.21"
        const val DEVICE_MODEL = "iPhone14,5"
        const val HARDWARE_BOARD = "D84AP"
        private const val MAX_REQUEST_BYTES = 32 * 1024
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nsd-ctrl-advertiser").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var ctrlRegListener: NsdManager.RegistrationListener? = null
    private var devInfoRegListener: NsdManager.RegistrationListener? = null

    @Volatile
    var listeningPort: Int = 0
        private set

    /**
     * Starts the local TCP control server on an ephemeral port and advertises both
     * `_carplay-ctrl._tcp` and `_device-info._tcp` strictly on [carNetwork].
     */
    @Synchronized
    fun start(carNetwork: Network, phoneBluetoothMac: String) {
        stop()

        val sock = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        } catch (e: IOException) {
            logger.log("MDNS-CTRL", "GREŠKA: Ne mogu otvoriti lokalni TCP server za _carplay-ctrl: ${e.message}")
            return
        }

        serverSocket = sock
        listeningPort = sock.localPort
        running.set(true)

        logger.log(
            "MDNS-CTRL",
            "Lokalni TCP kontrolni server pokrenut na portu $listeningPort (BT MAC id=$phoneBluetoothMac)",
        )

        Thread({ acceptLoop(sock) }, "carplay-ctrl-accept").apply {
            isDaemon = true
            start()
        }

        registerCtrlService(carNetwork, listeningPort, phoneBluetoothMac)
        registerDeviceInfoService(carNetwork, listeningPort, phoneBluetoothMac)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        ctrlRegListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        ctrlRegListener = null

        devInfoRegListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        devInfoRegListener = null

        runCatching { serverSocket?.close() }
        serverSocket = null
        listeningPort = 0
    }

    private fun registerCtrlService(carNetwork: Network, port: Int, phoneBtMac: String) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = CTRL_SERVICE_TYPE
            this.port = port
            setAttribute("id", phoneBtMac)
            setAttribute("srcvers", SRCVERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                network = carNetwork
            }
        }
        val listener = createListener(CTRL_SERVICE_TYPE)
        ctrlRegListener = listener
        logger.log(
            "MDNS-CTRL",
            "Oglašavam $CTRL_SERVICE_TYPE (ime=\"$SERVICE_NAME\", port=$port, TXT: id=$phoneBtMac, srcvers=$SRCVERS)",
        )
        registerServiceCompat(info, listener)
    }

    private fun registerDeviceInfoService(carNetwork: Network, port: Int, phoneBtMac: String) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = DEVICE_INFO_SERVICE_TYPE
            this.port = port
            setAttribute("model", DEVICE_MODEL)
            setAttribute("board", HARDWARE_BOARD)
            setAttribute("deviceid", phoneBtMac)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                network = carNetwork
            }
        }
        val listener = createListener(DEVICE_INFO_SERVICE_TYPE)
        devInfoRegListener = listener
        logger.log(
            "MDNS-CTRL",
            "Oglašavam $DEVICE_INFO_SERVICE_TYPE (ime=\"$SERVICE_NAME\", port=$port, TXT: model=$DEVICE_MODEL / $HARDWARE_BOARD)",
        )
        registerServiceCompat(info, listener)
    }

    private fun registerServiceCompat(info: NsdServiceInfo, listener: NsdManager.RegistrationListener) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, executor, listener)
            } else {
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            logger.log("MDNS-CTRL", "GREŠKA pri mDNS registraciji ${info.serviceType}: ${e.message}")
        }
    }

    private fun createListener(typeLabel: String): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                logger.log(
                    "MDNS-CTRL",
                    "✓ mDNS oglašavanje aktivno: ${serviceInfo.serviceName}.$typeLabel (port=$listeningPort)",
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logger.log("MDNS-CTRL", "✗ mDNS oglašavanje $typeLabel neuspješno (code=$errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                logger.log("MDNS-CTRL", "mDNS oglašavanje odjavljeno: $typeLabel")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logger.log("MDNS-CTRL", "mDNS odjava $typeLabel greška (code=$errorCode)")
            }
        }

    private fun acceptLoop(sock: ServerSocket) {
        while (running.get()) {
            val client: Socket = try {
                sock.accept()
            } catch (e: IOException) {
                if (running.get()) {
                    logger.log("MDNS-CTRL", "TCP accept prekinut: ${e.message}")
                }
                break
            }
            Thread({ handleClient(client) }, "carplay-ctrl-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(client: Socket) {
        val peerIp = client.inetAddress?.hostAddress ?: "nepoznat"
        logger.log("MDNS-CTRL", "✓ Dolazna TCP veza od BMW-a na _carplay-ctrl port: $peerIp:${client.port}")
        try {
            client.soTimeout = 5_000
            val input = BufferedInputStream(client.getInputStream())
            val rawRequest = readHttpOrRtspRequest(input)
            val requestText = rawRequest.toString(Charsets.ISO_8859_1)

            logger.logPacket(
                "MDNS-CTRL",
                "Primljen poziv BMW-a na kontrolnom serveru ($peerIp):\n$requestText",
                rawRequest,
            )

            // Echo AirPlay-Receiver-Device-ID if BMW included it, and reply immediately with 200 OK
            val isRtsp = requestText.lineSequence().firstOrNull()?.contains("RTSP/", ignoreCase = true) == true
            val protocol = if (isRtsp) "RTSP/1.0" else "HTTP/1.1"
            val cseqLine = requestText.lineSequence()
                .firstOrNull { it.startsWith("CSeq:", ignoreCase = true) }
                ?.trim()
            val rxDeviceId = requestText.lineSequence()
                .firstOrNull { it.startsWith("AirPlay-Receiver-Device-ID:", ignoreCase = true) }
                ?.trim()

            val responseBuilder = StringBuilder()
            responseBuilder.append("$protocol 200 OK\r\n")
            if (cseqLine != null) responseBuilder.append("$cseqLine\r\n")
            if (rxDeviceId != null) responseBuilder.append("$rxDeviceId\r\n")
            responseBuilder.append("Content-Length: 0\r\n")
            responseBuilder.append("\r\n")

            val responseBytes = responseBuilder.toString().toByteArray(Charsets.US_ASCII)
            client.getOutputStream().apply {
                write(responseBytes)
                flush()
            }
            logger.log("MDNS-CTRL", "✓ Poslan odgovor '$protocol 200 OK' prema BMW ($peerIp)")
            onCarInvite(peerIp)
        } catch (e: Exception) {
            logger.log("MDNS-CTRL", "Greška u obradi poziva od $peerIp: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHttpOrRtspRequest(input: BufferedInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        var headerEndIdx = -1
        var expectedContentLength = 0

        while (baos.size() < MAX_REQUEST_BYTES) {
            val n = try {
                input.read(buf)
            } catch (_: IOException) {
                break
            }
            if (n < 0) break
            baos.write(buf, 0, n)
            val current = baos.toByteArray()
            if (headerEndIdx < 0) {
                headerEndIdx = findHeaderEnd(current)
                if (headerEndIdx >= 0) {
                    val headerText = String(current, 0, headerEndIdx, Charsets.ISO_8859_1)
                    expectedContentLength = parseContentLength(headerText)
                }
            }
            if (headerEndIdx >= 0) {
                val bodyReceived = current.size - (headerEndIdx + 4)
                if (bodyReceived >= expectedContentLength) break
            }
        }
        return baos.toByteArray()
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
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

    private fun parseContentLength(headerText: String): Int =
        headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
}
