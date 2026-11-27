package io.bimmergestalt.bmwcarplay.input

/**
 * Pure-Kotlin real-time streaming touch gesture state machine for Android
 * `AccessibilityService.dispatchGesture` using chained `StrokeDescription.continueStroke(...)`.
 *
 * Supports:
 * - Instant crisp taps (~50 ms total duration)
 * - Live smooth dragging/panning (Google Maps / Waze map panning tracks the finger in real time)
 * - Natural long-presses (holding the finger stationary chains continuing hold segments)
 * - Rapid double-taps (queues a new DOWN arriving during the 12 ms finishing segment)
 * - Watchdog recovery if Android ever drops a `GestureResultCallback`
 */
class TouchGestureEngine(
    private val nowMs: () -> Long,
    private val onStartStroke: (x: Float, y: Float, durationMs: Long, willContinue: Boolean) -> Boolean,
    private val onContinueStroke: (
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        willContinue: Boolean,
    ) -> Boolean,
) {
    companion object {
        const val CHUNK_DURATION_MS = 40L
        const val FINISH_DURATION_MS = 12L
        const val STUCK_WATCHDOG_MS = 700L
    }

    private enum class State {
        IDLE,
        ACTIVE_CONTINUING,
        FINISHING,
    }

    private data class QueuedTouch(
        val downX: Float,
        val downY: Float,
        var latestX: Float,
        var latestY: Float,
        var stillDown: Boolean,
    )

    private val lock = Any()
    private var state: State = State.IDLE
    private var lastDispatchAtMs: Long = 0L
    private var fingerDown: Boolean = false
    private var strokeEndX: Float = 0f
    private var strokeEndY: Float = 0f
    private var targetX: Float = 0f
    private var targetY: Float = 0f
    private var queued: QueuedTouch? = null

    fun onTouch(down: Boolean, x: Float, y: Float) {
        val action: (() -> Unit)? = synchronized(lock) {
            val now = nowMs()
            if (state != State.IDLE && (now - lastDispatchAtMs) > STUCK_WATCHDOG_MS) {
                resetLocked()
            }

            if (down) {
                when (state) {
                    State.IDLE -> {
                        fingerDown = true
                        strokeEndX = x
                        strokeEndY = y
                        targetX = x
                        targetY = y
                        state = State.ACTIVE_CONTINUING
                        lastDispatchAtMs = now
                        {
                            val ok = onStartStroke(x, y, CHUNK_DURATION_MS, true)
                            if (!ok) reset()
                        }
                    }

                    State.ACTIVE_CONTINUING -> {
                        // Finger moved (or stayed down) while current stroke chunk is in flight
                        fingerDown = true
                        targetX = x
                        targetY = y
                        null
                    }

                    State.FINISHING -> {
                        // A rapid second tap/touch started during the 12 ms finishing stroke!
                        val q = queued
                        if (q == null) {
                            queued = QueuedTouch(x, y, x, y, stillDown = true)
                        } else {
                            q.latestX = x
                            q.latestY = y
                            q.stillDown = true
                        }
                        null
                    }
                }
            } else {
                // Finger lifted
                when (state) {
                    State.IDLE -> null
                    State.ACTIVE_CONTINUING -> {
                        fingerDown = false
                        targetX = x
                        targetY = y
                        null
                    }

                    State.FINISHING -> {
                        queued?.let { q ->
                            q.latestX = x
                            q.latestY = y
                            q.stillDown = false
                        }
                        null
                    }
                }
            }
        }
        action?.invoke()
    }

    /** Called by `GestureResultCallback.onCompleted` when the currently executing stroke ends. */
    fun onStrokeCompleted() {
        val action: (() -> Unit)? = synchronized(lock) {
            val now = nowMs()
            when (state) {
                State.IDLE -> null

                State.ACTIVE_CONTINUING -> {
                    val fromX = strokeEndX
                    val fromY = strokeEndY
                    val toX = targetX
                    val toY = targetY
                    strokeEndX = toX
                    strokeEndY = toY
                    lastDispatchAtMs = now
                    if (fingerDown) {
                        {
                            val ok = onContinueStroke(fromX, fromY, toX, toY, CHUNK_DURATION_MS, true)
                            if (!ok) reset()
                        }
                    } else {
                        state = State.FINISHING
                        {
                            val ok = onContinueStroke(fromX, fromY, toX, toY, FINISH_DURATION_MS, false)
                            if (!ok) reset()
                        }
                    }
                }

                State.FINISHING -> {
                    val q = queued
                    queued = null
                    if (q != null) {
                        fingerDown = q.stillDown
                        strokeEndX = q.downX
                        strokeEndY = q.downY
                        targetX = q.latestX
                        targetY = q.latestY
                        state = State.ACTIVE_CONTINUING
                        lastDispatchAtMs = now
                        {
                            val ok = onStartStroke(q.downX, q.downY, CHUNK_DURATION_MS, true)
                            if (!ok) reset()
                        }
                    } else {
                        state = State.IDLE
                        fingerDown = false
                        null
                    }
                }
            }
        }
        action?.invoke()
    }

    /** Called by `GestureResultCallback.onCancelled` when Android interrupts a gesture. */
    fun onStrokeCancelled() {
        reset()
    }

    fun reset() {
        synchronized(lock) {
            resetLocked()
        }
    }

    private fun resetLocked() {
        state = State.IDLE
        fingerDown = false
        queued = null
        lastDispatchAtMs = 0L
    }
}
