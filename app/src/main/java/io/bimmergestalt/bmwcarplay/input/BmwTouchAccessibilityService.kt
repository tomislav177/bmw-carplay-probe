package io.bimmergestalt.bmwcarplay.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight Android AccessibilityService enabling live BMW touchscreen & iDrive back-channel
 * control over mirrored Android apps (Google Maps, Waze, Spotify, etc.) without root.
 *
 * Capabilities:
 * - Streaming touch injection (`tap`, live `drag/pan`, `long-press`) via [TouchGestureEngine]
 *   and `StrokeDescription.continueStroke(...)`.
 * - Global system navigation (`GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`).
 * - Background Activity Launch (BAL)-exempt app launching when tapping icons in the BMW CarPlay Dock.
 * - Passive foreground package tracking (`TYPE_WINDOW_STATE_CHANGED`) so the CarPlay Dock highlights
 *   the currently active app. Does NOT inspect window text/content (`canRetrieveWindowContent=false`).
 */
class BmwTouchAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BmwTouchAccessibilityService? = null
            private set

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentStroke: GestureDescription.StrokeDescription? = null

    private val gestureEngine = TouchGestureEngine(
        nowMs = SystemClock::uptimeMillis,
        onStartStroke = { x, y, durationMs, willContinue ->
            dispatchInitialStrokeOnMain(x, y, durationMs, willContinue)
        },
        onContinueStroke = { fromX, fromY, toX, toY, durationMs, willContinue ->
            dispatchContinuedStrokeOnMain(fromX, fromY, toX, toY, durationMs, willContinue)
        },
    )

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            gestureEngine.onStrokeCompleted()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            currentStroke = null
            gestureEngine.onStrokeCancelled()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isActive.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
            // Ignore transient SystemUI / IME windows so the dock keeps highlighting the active user app
            if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("honeyboard")) {
                return
            }
            _foregroundPackage.value = pkg
        }
    }

    override fun onInterrupt() {
        resetTouch()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupState()
        super.onDestroy()
    }

    fun injectTouch(down: Boolean, phoneX: Float, phoneY: Float) {
        mainHandler.post {
            gestureEngine.onTouch(down, phoneX, phoneY)
        }
    }

    fun resetTouch() {
        mainHandler.post {
            currentStroke = null
            gestureEngine.reset()
        }
    }

    fun performBack(): Boolean = runCatching {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }.getOrDefault(false)

    fun performHome(): Boolean = runCatching {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }.getOrDefault(false)

    fun launchAppFromService(intent: Intent): Boolean = runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)

    private fun dispatchInitialStrokeOnMain(
        x: Float,
        y: Float,
        durationMs: Long,
        willContinue: Boolean,
    ): Boolean {
        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(safeX, safeY)
            lineTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceAtLeast(1L),
            willContinue,
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        currentStroke = if (willContinue) stroke else null
        val ok = runCatching { dispatchGesture(gesture, gestureCallback, mainHandler) }.getOrDefault(false)
        if (!ok) {
            currentStroke = null
        }
        return ok
    }

    private fun dispatchContinuedStrokeOnMain(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        willContinue: Boolean,
    ): Boolean {
        val prev = currentStroke ?: return false
        val path = Path().apply {
            moveTo(fromX.coerceAtLeast(0f), fromY.coerceAtLeast(0f))
            lineTo(toX.coerceAtLeast(0f), toY.coerceAtLeast(0f))
        }
        val nextStroke = runCatching {
            prev.continueStroke(path, 0L, durationMs.coerceAtLeast(1L), willContinue)
        }.getOrNull() ?: run {
            currentStroke = null
            return false
        }
        val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
        currentStroke = if (willContinue) nextStroke else null
        val ok = runCatching { dispatchGesture(gesture, gestureCallback, mainHandler) }.getOrDefault(false)
        if (!ok) {
            currentStroke = null
        }
        return ok
    }

    private fun cleanupState() {
        currentStroke = null
        gestureEngine.reset()
        if (instance === this) {
            instance = null
        }
        _isActive.value = false
    }
}
