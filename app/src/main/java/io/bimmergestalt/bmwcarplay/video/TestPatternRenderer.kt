package io.bimmergestalt.bmwcarplay.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.Surface
import io.bimmergestalt.bmwcarplay.airplay.rtsp.CarPlayHidDecoder
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 2A live diagnostic test pattern renderer drawing directly onto the hardware
 * MediaCodec H.264 input [Surface] (no MediaProjection permission required!).
 *
 * Displays:
 * - Exact 2 px bright cyan border at `(0,0)..(W-1,H-1)` + corner ticks + diagonals + center crosshair
 *   to visually verify pixel-for-pixel fit (`1280×480` Fullscreen vs `800×480` Split-Screen).
 * - Smooth Red, Green, and Dark-Gray (Google Maps Dark) gradient bars to verify BT.709 color rendering
 *   on the matte BMW HU_ENTRYNAV2 LCD without banding.
 * - Live elapsed timer (`MM:SS.d`), frame counter, virtual iAP2 handoff state, negotiated resolution,
 *   latest BMW touch/iDrive HID event, and an interactive touch target ring at the exact `(X, Y)` finger point.
 */
class TestPatternRenderer(
    private val width: Int,
    private val height: Int,
    private val targetFps: Int,
    private val displaySourceLabel: String,
    private val logger: DiagnosticLogger,
) {
    private val running = AtomicBoolean(false)
    private val lastHidEventText = AtomicReference("Dodirni ekran ili okreni iDrive kotačić za HID test")
    private val virtualIap2Status = AtomicReference("Wi-Fi iAP2: čeka DETECT...")
    private val lastTouchPoint = AtomicReference<CarPlayHidDecoder.TouchPoint?>(null)
    private var renderThread: Thread? = null

    fun updateLastHidEvent(text: String) {
        lastHidEventText.set(text)
    }

    fun updateVirtualIap2Status(text: String) {
        virtualIap2Status.set(text)
    }

    fun updateTouchPoint(point: CarPlayHidDecoder.TouchPoint?) {
        lastTouchPoint.set(point)
    }

    @Synchronized
    fun start(surface: Surface) {
        stop()
        running.set(true)
        renderThread = Thread({ renderLoop(surface) }, "carplay-test-pattern").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        renderThread?.interrupt()
        renderThread = null
    }

    private fun renderLoop(surface: Surface) {
        val startedMs = SystemClock.uptimeMillis()
        val frameIntervalMs = (1000L / targetFps.coerceIn(15, 60)).coerceAtLeast(16L)
        var frameIdx = 0L

        val bgPaint = Paint().apply { color = Color.rgb(16, 20, 28) }
        val borderPaint = Paint().apply {
            color = Color.rgb(0, 230, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = false
        }
        val diagPaint = Paint().apply {
            color = Color.argb(90, 120, 175, 255)
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        val crossPaint = Paint().apply {
            color = Color.rgb(255, 210, 60)
            strokeWidth = 2f
            isAntiAlias = false
        }
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = (height * 0.082f).coerceIn(26f, 44f)
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint().apply {
            color = Color.rgb(160, 220, 255)
            textSize = (height * 0.050f).coerceIn(18f, 28f)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val clockPaint = Paint().apply {
            color = Color.rgb(120, 255, 140)
            textSize = (height * 0.105f).coerceIn(34f, 54f)
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val hidPaint = Paint().apply {
            color = Color.rgb(255, 225, 130)
            textSize = (height * 0.044f).coerceIn(16f, 23f)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val labelPaint = Paint().apply {
            color = Color.WHITE
            textSize = 16f
            isAntiAlias = true
        }
        val touchRingPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val touchFillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val gradMarginX = width * 0.06f
        val gradRightX = width * 0.94f
        val barH = (height * 0.072f).coerceIn(24f, 40f)
        val barGap = 8f
        val barStartY = height * 0.64f

        val redGradPaint = Paint().apply {
            shader = LinearGradient(
                gradMarginX,
                0f,
                gradRightX,
                0f,
                Color.rgb(20, 0, 0),
                Color.rgb(255, 40, 40),
                Shader.TileMode.CLAMP,
            )
        }
        val greenGradPaint = Paint().apply {
            shader = LinearGradient(
                gradMarginX,
                0f,
                gradRightX,
                0f,
                Color.rgb(0, 20, 0),
                Color.rgb(40, 255, 90),
                Shader.TileMode.CLAMP,
            )
        }
        val mapsDarkGradPaint = Paint().apply {
            shader = LinearGradient(
                gradMarginX,
                0f,
                gradRightX,
                0f,
                Color.rgb(18, 22, 28),
                Color.rgb(72, 82, 98),
                Shader.TileMode.CLAMP,
            )
        }
        val pulsePaint = Paint().apply {
            color = Color.rgb(0, 175, 255)
            isAntiAlias = true
        }

        logger.log("VIDEO", "TestPatternRenderer pokrenut (${width}×${height} @ ${targetFps}fps, izvor=$displaySourceLabel)")

        while (running.get() && surface.isValid) {
            val frameStart = SystemClock.uptimeMillis()
            val elapsedMs = frameStart - startedMs
            frameIdx++

            val canvas: Canvas? = try {
                surface.lockHardwareCanvas()
            } catch (_: Exception) {
                runCatching { surface.lockCanvas(null) }.getOrNull()
            }
            if (canvas == null) {
                try {
                    Thread.sleep(50L)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            try {
                val w = width.toFloat()
                val h = height.toFloat()

                // 1. Dark background
                canvas.drawRect(0f, 0f, w, h, bgPaint)

                // 2. Diagonals & center crosshair
                canvas.drawLine(0f, 0f, w, h, diagPaint)
                canvas.drawLine(w, 0f, 0f, h, diagPaint)
                val cx = w / 2f
                val cy = h / 2f
                canvas.drawLine(cx - 28f, cy, cx + 28f, cy, crossPaint)
                canvas.drawLine(cx, cy - 28f, cx, cy + 28f, crossPaint)

                // 3. Exact 2 px outer edge border (stroke centered at 1.0f covers pixels 0..1 and W-2..W-1)
                canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)

                // 4. Moving dot along top bar to visually verify smooth real-time FPS
                val phase = ((elapsedMs % 3000L).toFloat() / 3000f)
                val dotX = gradMarginX + phase * (gradRightX - gradMarginX)
                canvas.drawCircle(dotX, 24f, 9f, pulsePaint)

                // 5. Title, resolution, virtual iAP2 status & live elapsed timer
                canvas.drawText("BMW CarPlay Probe · Faza 2A (v0.2.1)", cx, h * 0.18f, titlePaint)
                canvas.drawText(
                    "${width}×${height} @ ${targetFps}fps  |  $displaySourceLabel  |  ${virtualIap2Status.get()}",
                    cx,
                    h * 0.28f,
                    subPaint,
                )

                val totalSec = elapsedMs / 1000L
                val mins = totalSec / 60L
                val secs = totalSec % 60L
                val tenths = (elapsedMs % 1000L) / 100L
                val clockText = String.format(Locale.US, "%02d:%02d.%d   ·   Sličica #%d", mins, secs, tenths, frameIdx)
                canvas.drawText(clockText, cx, h * 0.44f, clockPaint)

                // 6. Live HID feedback line
                canvas.drawText(lastHidEventText.get(), cx, h * 0.56f, hidPaint)

                // 7. Three smooth BT.709 color gradient bars (Red, Green, Maps Dark Gray)
                val y0 = barStartY
                val y1 = y0 + barH + barGap
                val y2 = y1 + barH + barGap
                canvas.drawRect(gradMarginX, y0, gradRightX, y0 + barH, redGradPaint)
                canvas.drawText("Crveni gradijent (BT.709)", gradMarginX + 10f, y0 + barH * 0.68f, labelPaint)

                canvas.drawRect(gradMarginX, y1, gradRightX, y1 + barH, greenGradPaint)
                canvas.drawText("Zeleni gradijent (BT.709)", gradMarginX + 10f, y1 + barH * 0.68f, labelPaint)

                canvas.drawRect(gradMarginX, y2, gradRightX, y2 + barH, mapsDarkGradPaint)
                canvas.drawText("Google Maps Dark gradijent", gradMarginX + 10f, y2 + barH * 0.68f, labelPaint)

                // 8. Interactive touch target marker (visual proof of 1:1 BMW touchscreen calibration!)
                lastTouchPoint.get()?.let { tp ->
                    val tx = tp.x.toFloat().coerceIn(0f, w)
                    val ty = tp.y.toFloat().coerceIn(0f, h)
                    touchRingPaint.color = if (tp.down) Color.rgb(255, 60, 220) else Color.argb(140, 255, 220, 80)
                    touchFillPaint.color = if (tp.down) Color.argb(90, 255, 60, 220) else Color.argb(40, 255, 220, 80)
                    val radius = if (tp.down) 34f else 22f
                    canvas.drawCircle(tx, ty, radius, touchFillPaint)
                    canvas.drawCircle(tx, ty, radius, touchRingPaint)
                    canvas.drawLine(tx - 16f, ty, tx + 16f, ty, touchRingPaint)
                    canvas.drawLine(tx, ty - 16f, tx, ty + 16f, touchRingPaint)
                }
            } finally {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }

            val spent = SystemClock.uptimeMillis() - frameStart
            val sleepMs = frameIntervalMs - spent
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        logger.log("VIDEO", "TestPatternRenderer zaustavljen (ukupno iscrtano $frameIdx sličica)")
    }
}
