package io.bimmergestalt.bmwcarplay.iap2.link

import io.bimmergestalt.bmwcarplay.bt.DuplexByteStream
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Threaded I/O driver binding [DuplexByteStream] (separate RX & TX locks) with [Iap2LinkEngine].
 *
 * - Reader thread continuously reads raw RFCOMM chunks without blocking writes.
 * - Tick thread periodically drives retransmit/heartbeat timers respecting peer ackTimeout.
 * - Control session payloads are queued for [Iap2SessionCoordinator].
 */
class Iap2LinkChannel(
    private val stream: DuplexByteStream,
    private val logger: DiagnosticLogger,
    private val onLinkReady: (controlSessionId: Int, lsp: Iap2LinkPacket.LinkSyncPayload?) -> Unit = { _, _ -> },
    private val onLinkClosed: (reasonHr: String) -> Unit = {},
) : Closeable {

    private val engineLock = Any()
    private val engine = Iap2LinkEngine()

    private val alive = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val readyLatch = CountDownLatch(1)
    private val readyNotified = AtomicBoolean(false)

    private val controlRxQueue = LinkedBlockingQueue<ByteArray>(512)

    val isAlive: Boolean get() = alive.get() && !closed.get() && !stream.isClosed

    fun controlSessionId(): Int = synchronized(engineLock) { engine.controlSessionId }

    fun maxControlPayloadLength(): Int = synchronized(engineLock) { engine.maxControlPayloadLength() }

    /**
     * Starts link negotiation, RX reader thread, and heartbeat/retransmit timer thread.
     */
    fun start() {
        if (!alive.compareAndSet(false, true)) return

        val initialTx: ByteArray
        val initialLogs: List<Pair<String, ByteArray>>
        val initialEvents: List<Iap2LinkEngine.Event>
        synchronized(engineLock) {
            engine.start(nowMs())
            initialTx = engine.drainOutgoingBytes()
            initialLogs = engine.drainOutgoingFrameLogs()
            initialEvents = engine.drainEvents()
        }
        dispatchEvents(initialEvents)
        flushTx(initialTx, initialLogs)

        Thread(::readerLoop, "iap2-link-rx").apply {
            isDaemon = true
            start()
        }
        Thread(::timerLoop, "iap2-link-timer").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Waits until the link transitions to NORMAL (or fails/times out).
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        if (readyLatch.count == 0L) return isAlive
        return try {
            val ok = readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            ok && isAlive && synchronized(engineLock) { engine.state == Iap2LinkEngine.State.NORMAL }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Sends a Control Session payload on the negotiated Control Session ID.
     */
    fun sendControl(payload: ByteArray, awaitAck: Boolean = true): Boolean {
        if (!isAlive) return false
        val txBytes: ByteArray
        val txLogs: List<Pair<String, ByteArray>>
        val events: List<Iap2LinkEngine.Event>
        val accepted: Boolean
        synchronized(engineLock) {
            accepted = engine.sendControlPayload(payload, nowMs(), awaitAck = awaitAck)
            txBytes = engine.drainOutgoingBytes()
            txLogs = engine.drainOutgoingFrameLogs()
            events = engine.drainEvents()
        }
        dispatchEvents(events)
        if (accepted) {
            flushTx(txBytes, txLogs)
        }
        return accepted
    }

    /**
     * Polls the next incoming Control Session byte chunk (or null on timeout / close).
     */
    fun pollControlData(timeoutMs: Long): ByteArray? = try {
        controlRxQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    override fun close() {
        closeInternal("Zatvoreno na zahtjev korisnika/sesije")
    }

    private fun readerLoop() {
        val buf = ByteArray(4096)
        try {
            while (isAlive) {
                val readCount = stream.read(buf)
                if (readCount < 0) {
                    closeInternal("RFCOMM ulazni tok zatvoren (EOF)")
                    return
                }
                if (readCount == 0) continue

                val chunk = buf.copyOf(readCount)
                logger.logPacket("IAP2-LINK", "RX RFCOMM sirovi paket", chunk)

                val txBytes: ByteArray
                val txLogs: List<Pair<String, ByteArray>>
                val events: List<Iap2LinkEngine.Event>
                synchronized(engineLock) {
                    engine.onBytesReceived(chunk, nowMs())
                    txBytes = engine.drainOutgoingBytes()
                    txLogs = engine.drainOutgoingFrameLogs()
                    events = engine.drainEvents()
                }
                dispatchEvents(events)
                flushTx(txBytes, txLogs)
            }
        } catch (e: IOException) {
            if (!closed.get()) {
                closeInternal("RFCOMM čitanje prekinuto: ${e.message}")
            }
        } catch (t: Throwable) {
            if (!closed.get()) {
                closeInternal("Neočekivana greška na iAP2 linku: ${t.message}")
            }
        }
    }

    private fun timerLoop() {
        while (isAlive) {
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
                return
            }
            if (!isAlive) return
            val txBytes: ByteArray
            val txLogs: List<Pair<String, ByteArray>>
            val events: List<Iap2LinkEngine.Event>
            synchronized(engineLock) {
                engine.tick(nowMs())
                txBytes = engine.drainOutgoingBytes()
                txLogs = engine.drainOutgoingFrameLogs()
                events = engine.drainEvents()
            }
            dispatchEvents(events)
            flushTx(txBytes, txLogs)
        }
    }

    private fun flushTx(bytes: ByteArray, logs: List<Pair<String, ByteArray>>) {
        if (bytes.isEmpty()) return
        for ((label, frameBytes) in logs) {
            logger.logPacket("IAP2-LINK", label, frameBytes)
        }
        try {
            stream.write(bytes)
        } catch (e: IOException) {
            closeInternal("Greška pri pisanju na RFCOMM: ${e.message}")
        }
    }

    private fun dispatchEvents(events: List<Iap2LinkEngine.Event>) {
        for (event in events) {
            when (event) {
                is Iap2LinkEngine.Event.StateChanged -> {
                    logger.log("IAP2-LINK", "Stanje [${event.state}]: ${event.detailHr}")
                    if (event.state == Iap2LinkEngine.State.NORMAL && readyNotified.compareAndSet(false, true)) {
                        readyLatch.countDown()
                        val sid: Int
                        val lsp: Iap2LinkPacket.LinkSyncPayload?
                        synchronized(engineLock) {
                            sid = engine.controlSessionId
                            lsp = engine.peerLsp
                        }
                        onLinkReady(sid, lsp)
                    }
                }

                is Iap2LinkEngine.Event.SyncNegotiated -> {
                    logger.log(
                        "IAP2-LINK",
                        "Dogovoreni LSP parametri auta: ${event.peerLsp.summary()} → " +
                            "Control SID=${event.controlSessionId}, EA SID=${event.eaSessionIds}",
                    )
                }

                is Iap2LinkEngine.Event.ControlDataReceived -> {
                    if (!controlRxQueue.offer(event.payload)) {
                        logger.log("IAP2-LINK", "UPOZORENJE: Control RX red je pun — odbacujem najstariji paket")
                        controlRxQueue.poll()
                        controlRxQueue.offer(event.payload)
                    }
                }

                is Iap2LinkEngine.Event.EaDataReceived -> {
                    logger.logPacket(
                        "IAP2-EA",
                        "Primljen EA paket (SID=${event.sessionId})",
                        event.payload,
                    )
                }

                is Iap2LinkEngine.Event.UnknownSessionData -> {
                    logger.logPacket(
                        "IAP2-LINK",
                        "Primljen paket na nepoznatoj sesiji SID=${event.sessionId}",
                        event.payload,
                    )
                }

                is Iap2LinkEngine.Event.DiagnosticNote -> {
                    logger.log("IAP2-LINK", event.messageHr)
                }

                is Iap2LinkEngine.Event.LinkClosed -> {
                    closeInternal(event.reasonHr)
                }
            }
        }
    }

    private fun closeInternal(reasonHr: String) {
        if (!closed.compareAndSet(false, true)) return
        alive.set(false)
        readyLatch.countDown()
        stream.close()
        logger.log("IAP2-LINK", "iAP2 Link zatvoren: $reasonHr")
        onLinkClosed(reasonHr)
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
