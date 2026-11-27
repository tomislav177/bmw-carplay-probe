package io.bimmergestalt.bmwcarplay.bt

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex byte stream wrapper with STRICTLY SEPARATE RX and TX locks.
 *
 * Blocking InputStream.read() must NEVER hold a lock shared with OutputStream.write(),
 * otherwise TX deadlocks permanently while waiting for incoming bytes.
 */
class DuplexByteStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
) : Closeable {
    private val rxLock = Any()
    private val txLock = Any()
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int {
        synchronized(rxLock) {
            if (closed.get()) return -1
            return input.read(buffer)
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(txLock) {
            if (closed.get()) throw IOException("DuplexByteStream je zatvoren")
            output.write(data)
            output.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { onClose() }
        }
    }
}
