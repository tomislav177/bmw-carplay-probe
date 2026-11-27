package io.bimmergestalt.bmwcarplay.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dual-role Bluetooth RFCOMM transport for Wireless CarPlay iAP2:
 *
 * 1. **Server (Listener)**: Always listens on iPhone Wireless iAP2 UUID
 *    (00000000-deca-fade-deca-deafdecacafe) with SDP name "Wireless iAP"
 *    using either Insecure (default, confirmed working on BMW 04990) or Secure RFCOMM.
 * 2. **Client (Optional Outbound)**: Gently attempts an outbound connection to the
 *    selected paired BMW head unit on Accessory UUID (...decacaff) ONLY when the server
 *    has not already accepted an incoming connection, with polite backoff so HFP/A2DP
 *    audio is never disrupted.
 *
 * Zombie-thread protection:
 * - Every start()/stop() increments an atomic [generationId].
 * - Every server/client socket is registered in [activeSockets] BEFORE calling blocking
 *   accept() or connect(), so stop() immediately unblocks and terminates all threads.
 */
class RfcommTransport(
    private val context: Context,
    private val logger: DiagnosticLogger,
    private val onConnected: (connection: ConnectedPeer) -> Unit,
    private val onError: (messageHr: String) -> Unit,
) {
    data class ConnectedPeer(
        val role: Role,
        val deviceName: String,
        val deviceAddress: String,
        val stream: DuplexByteStream,
        val generation: Long,
    )

    enum class Role(val labelHr: String) {
        SERVER_INBOUND("RFCOMM Server (dolazna veza auta)"),
        CLIENT_OUTBOUND("RFCOMM Klijent (odlazna veza prema autu)"),
    }

    companion object {
        /** Give BMW head unit time to connect inbound first before trying outbound. */
        private const val INITIAL_OUTBOUND_DELAY_MS = 4_000L

        /** Polite backoff between outbound attempts so HFP/A2DP is never disrupted. */
        private const val OUTBOUND_RETRY_DELAY_MS = 15_000L
        private const val MAX_OUTBOUND_ATTEMPTS = 4
    }

    private val generationId = AtomicLong(0)
    private val activeSockets = CopyOnWriteArrayList<Closeable>()
    private val activeSessionConnected = AtomicBoolean(false)

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun currentGeneration(): Long = generationId.get()

    fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts the RFCOMM server listener (and optionally the gentle outbound client).
     */
    @SuppressLint("MissingPermission")
    fun start(
        useSecureRfcomm: Boolean,
        enableOutboundClient: Boolean,
        selectedTargetMac: String?,
    ): Boolean {
        stop()
        if (!hasBluetoothPermission()) {
            val msg = "Nedostaje dozvola BLUETOOTH_CONNECT"
            logger.log("BT-RFCOMM", "GREŠKA: $msg")
            onError(msg)
            return false
        }

        val adapter: BluetoothAdapter? =
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        if (adapter == null || !adapter.isEnabled) {
            val msg = "Bluetooth je isključen ili nedostupan na mobitelu"
            logger.log("BT-RFCOMM", "GREŠKA: $msg")
            onError(msg)
            return false
        }

        val gen = generationId.incrementAndGet()
        activeSessionConnected.set(false)
        running = true

        val modeLabel = if (useSecureRfcomm) "Secure" else "Insecure"
        val serverSocket: BluetoothServerSocket = try {
            if (useSecureRfcomm) {
                adapter.listenUsingRfcommWithServiceRecord(
                    BluetoothConstants.SDP_SERVICE_NAME,
                    BluetoothConstants.IPHONE_IAP2_UUID,
                )
            } else {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    BluetoothConstants.SDP_SERVICE_NAME,
                    BluetoothConstants.IPHONE_IAP2_UUID,
                )
            }
        } catch (e: IOException) {
            running = false
            val msg = "Neuspješno pokretanje $modeLabel RFCOMM servera: ${e.message}"
            logger.log("BT-RFCOMM", "GREŠKA: $msg")
            onError(msg)
            return false
        }

        registerSocket(serverSocket)
        logger.log(
            "BT-RFCOMM",
            "Server sluša (${modeLabel}) SDP=\"${BluetoothConstants.SDP_SERVICE_NAME}\" " +
                "UUID=${BluetoothConstants.IPHONE_IAP2_UUID} [gen=$gen]",
        )

        Thread(
            { runServerAcceptLoop(gen, serverSocket, selectedTargetMac) },
            "rfcomm-server-gen$gen",
        ).apply {
            isDaemon = true
            start()
        }

        if (enableOutboundClient) {
            Thread(
                { runOutboundClientLoop(gen, adapter, useSecureRfcomm, selectedTargetMac) },
                "rfcomm-client-gen$gen",
            ).apply {
                isDaemon = true
                start()
            }
        } else {
            logger.log("BT-RFCOMM", "Odlazni BT klijent isključen u postavkama — čekam isključivo dolaznu vezu auta.")
        }

        return true
    }

    /**
     * Immediately stops listening, increments generationId, and closes all registered sockets
     * so blocking accept()/connect()/read() calls return immediately.
     */
    fun stop() {
        val nextGen = generationId.incrementAndGet()
        running = false
        activeSessionConnected.set(false)
        closeAllSockets()
        logger.log("BT-RFCOMM", "RFCOMM transport zaustavljen i svi socketi zatvoreni [gen=$nextGen]")
    }

    /**
     * Marks the active iAP2 peer socket closed so the server may accept a fresh connection
     * without killing the listener.
     */
    fun notifyPeerDisconnected(gen: Long) {
        if (generationId.get() == gen) {
            activeSessionConnected.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun runServerAcceptLoop(
        gen: Long,
        serverSocket: BluetoothServerSocket,
        selectedTargetMac: String?,
    ) {
        while (running && generationId.get() == gen) {
            val clientSocket: BluetoothSocket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (running && generationId.get() == gen) {
                    logger.log("BT-RFCOMM", "Server accept() prekinut: ${e.message}")
                }
                break
            }

            if (!running || generationId.get() != gen) {
                runCatching { clientSocket.close() }
                break
            }

            val remote = clientSocket.remoteDevice
            val remoteName = runCatching { remote?.name }.getOrNull().orEmpty().ifBlank { "Nepoznat uređaj" }
            val remoteMac = remote?.address?.uppercase() ?: "??:??:??:??:??:??"
            val candidate = toCandidate(remote)

            if (candidate != null && DeviceFilter.isExplicitlyExcluded(candidate)) {
                logger.log("BT-RFCOMM", "Odbijam dolaznu vezu isključenog uređaja (sat/slušalice): $remoteName ($remoteMac)")
                runCatching { clientSocket.close() }
                continue
            }

            if (!selectedTargetMac.isNullOrBlank() &&
                !remoteMac.equals(selectedTargetMac, ignoreCase = true)
            ) {
                logger.log(
                    "BT-RFCOMM",
                    "Dolazna veza od $remoteName ($remoteMac) nije odabrani cilj ($selectedTargetMac) — prihvaćam jer je auto inicirao CarPlay UUID!",
                )
            }

            if (!activeSessionConnected.compareAndSet(false, true)) {
                logger.log(
                    "BT-RFCOMM",
                    "Već postoji aktivna RFCOMM sesija; odbijam duplu dolaznu vezu od $remoteName ($remoteMac)",
                )
                runCatching { clientSocket.close() }
                continue
            }

            registerSocket(clientSocket)
            logger.log(
                "BT-RFCOMM",
                "✓ Prihvaćena dolazna veza auta: $remoteName ($remoteMac) [gen=$gen]",
            )

            val stream = createDuplexStream(clientSocket, gen)
            if (stream != null) {
                onConnected(
                    ConnectedPeer(
                        role = Role.SERVER_INBOUND,
                        deviceName = remoteName,
                        deviceAddress = remoteMac,
                        stream = stream,
                        generation = gen,
                    ),
                )
            } else {
                activeSessionConnected.set(false)
                unregisterAndClose(clientSocket)
            }
        }
        unregisterAndClose(serverSocket)
    }

    @SuppressLint("MissingPermission")
    private fun runOutboundClientLoop(
        gen: Long,
        adapter: BluetoothAdapter,
        useSecureRfcomm: Boolean,
        selectedTargetMac: String?,
    ) {
        if (!sleepRespectingStop(gen, INITIAL_OUTBOUND_DELAY_MS)) return

        var attempt = 0
        while (running && generationId.get() == gen && attempt < MAX_OUTBOUND_ATTEMPTS) {
            if (activeSessionConnected.get()) {
                logger.log("BT-RFCOMM", "Odlazni klijent se povlači — server je već prihvatio dolaznu vezu.")
                return
            }

            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrElse { emptySet() }
            val eligibleTargets = bonded.mapNotNull { device ->
                val candidate = toCandidate(device) ?: return@mapNotNull null
                if (DeviceFilter.isEligibleOutboundTarget(candidate, selectedTargetMac)) {
                    device to candidate
                } else {
                    null
                }
            }

            if (eligibleTargets.isEmpty()) {
                if (attempt == 0) {
                    logger.log(
                        "BT-RFCOMM",
                        "Nema uparenog BMW/CarPlay cilja za odlazni klijent (satovi/slušalice su filtrirani). " +
                            "Server i dalje sluša dolazne veze.",
                    )
                }
                return
            }

            attempt++
            for ((device, candidate) in eligibleTargets) {
                if (!running || generationId.get() != gen || activeSessionConnected.get()) return

                val name = candidate.name ?: device.address
                val mac = device.address.uppercase()
                logger.log(
                    "BT-RFCOMM",
                    "Odlazni pokušaj $attempt/$MAX_OUTBOUND_ATTEMPTS prema $name ($mac) na Accessory UUID " +
                        "${BluetoothConstants.ACCESSORY_IAP2_UUID}...",
                )

                val socket: BluetoothSocket = try {
                    if (useSecureRfcomm) {
                        device.createRfcommSocketToServiceRecord(BluetoothConstants.ACCESSORY_IAP2_UUID)
                    } else {
                        device.createInsecureRfcommSocketToServiceRecord(BluetoothConstants.ACCESSORY_IAP2_UUID)
                    }
                } catch (e: IOException) {
                    logger.log("BT-RFCOMM", "Ne mogu kreirati odlazni RFCOMM socket za $name: ${e.message}")
                    continue
                }

                // Register BEFORE blocking connect() so stop() can interrupt immediately.
                registerSocket(socket)
                try {
                    socket.connect()
                } catch (e: IOException) {
                    unregisterAndClose(socket)
                    if (!running || generationId.get() != gen) return
                    logger.log(
                        "BT-RFCOMM",
                        "Odlazni RFCOMM prema $name ($mac) nije uspio (${e.message}) — nastavljam slušati kao server.",
                    )
                    continue
                }

                if (!running || generationId.get() != gen) {
                    unregisterAndClose(socket)
                    return
                }

                if (!activeSessionConnected.compareAndSet(false, true)) {
                    logger.log("BT-RFCOMM", "Server je u međuvremenu preuzeo vezu; zatvaram odlazni socket.")
                    unregisterAndClose(socket)
                    return
                }

                logger.log("BT-RFCOMM", "✓ Odlazna RFCOMM veza uspostavljena prema $name ($mac) [gen=$gen]")
                val stream = createDuplexStream(socket, gen)
                if (stream != null) {
                    onConnected(
                        ConnectedPeer(
                            role = Role.CLIENT_OUTBOUND,
                            deviceName = name,
                            deviceAddress = mac,
                            stream = stream,
                            generation = gen,
                        ),
                    )
                    return
                } else {
                    activeSessionConnected.set(false)
                    unregisterAndClose(socket)
                }
            }

            if (attempt < MAX_OUTBOUND_ATTEMPTS) {
                logger.log(
                    "BT-RFCOMM",
                    "Čekam ${OUTBOUND_RETRY_DELAY_MS / 1000}s prije sljedećeg blagog odlaznog pokušaja (ne diram HFP/A2DP).",
                )
                if (!sleepRespectingStop(gen, OUTBOUND_RETRY_DELAY_MS)) return
            }
        }
    }

    private fun createDuplexStream(socket: BluetoothSocket, gen: Long): DuplexByteStream? = try {
        DuplexByteStream(socket.inputStream, socket.outputStream) {
            unregisterAndClose(socket)
            notifyPeerDisconnected(gen)
        }
    } catch (e: IOException) {
        logger.log("BT-RFCOMM", "Greška pri otvaranju RFCOMM streamova: ${e.message}")
        null
    }

    private fun sleepRespectingStop(gen: Long, durationMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + durationMs
        while (running && generationId.get() == gen && !activeSessionConnected.get()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return true
            try {
                Thread.sleep(minOf(remaining, 250L))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun toCandidate(device: BluetoothDevice?): DeviceFilter.DeviceCandidate? {
        if (device == null) return null
        val name = runCatching { device.name }.getOrNull()
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        val uuids = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull().orEmpty()
        return DeviceFilter.DeviceCandidate(
            name = name,
            address = device.address,
            majorDeviceClass = btClass?.majorDeviceClass ?: 0,
            deviceClass = btClass?.deviceClass ?: 0,
            uuids = uuids,
        )
    }

    private fun registerSocket(closeable: Closeable) {
        activeSockets.add(closeable)
    }

    private fun unregisterAndClose(closeable: Closeable) {
        activeSockets.remove(closeable)
        runCatching { closeable.close() }
    }

    private fun closeAllSockets() {
        val snapshot = activeSockets.toList()
        activeSockets.clear()
        snapshot.forEach { socket -> runCatching { socket.close() } }
    }
}
