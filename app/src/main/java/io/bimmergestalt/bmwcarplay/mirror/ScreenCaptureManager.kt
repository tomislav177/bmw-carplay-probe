package io.bimmergestalt.bmwcarplay.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the Android 12–17 [MediaProjection] + [VirtualDisplay] screen capture pipeline.
 *
 * Strictly complies with Android 14–17 (API 34+) requirements:
 * 1. Registers [MediaProjection.Callback] BEFORE creating any [VirtualDisplay].
 * 2. Creates at most ONE [VirtualDisplay] per projection token and swaps/resizes it via
 *    `virtualDisplay.setSurface(...)` and `virtualDisplay.resize(...)` on live portrait↔landscape
 *    rotation — never recreating a second VirtualDisplay on the same token.
 * 3. Cleans up all listeners, VirtualDisplay, and MediaProjection cleanly on stop.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val logger: DiagnosticLogger,
) {
    data class PhoneDisplayMetrics(
        val realWidth: Int,
        val realHeight: Int,
        val captureWidth: Int,
        val captureHeight: Int,
        val densityDpi: Int,
    )

    companion object {
        private const val MAX_CAPTURE_LONG_EDGE = 2560
        private const val VIRTUAL_DISPLAY_NAME = "BMWCarPlayMirror"

        fun readPhoneDisplayMetrics(context: Context): PhoneDisplayMetrics {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            val realW = (bounds?.width() ?: context.resources.displayMetrics.widthPixels).coerceAtLeast(320)
            val realH = (bounds?.height() ?: context.resources.displayMetrics.heightPixels).coerceAtLeast(320)
            val dpi = context.resources.configuration.densityDpi.coerceIn(120, 640)

            val longEdge = maxOf(realW, realH)
            val scale = if (longEdge > MAX_CAPTURE_LONG_EDGE) {
                MAX_CAPTURE_LONG_EDGE.toFloat() / longEdge.toFloat()
            } else {
                1f
            }
            val capW = ((realW * scale).toInt() / 2) * 2
            val capH = ((realH * scale).toInt() / 2) * 2
            return PhoneDisplayMetrics(
                realWidth = realW,
                realHeight = realH,
                captureWidth = capW.coerceAtLeast(320),
                captureHeight = capH.coerceAtLeast(320),
                densityDpi = dpi,
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val lock = Any()
    private val displayListenerRegistered = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentSurface: Surface? = null
    private var currentMetrics: PhoneDisplayMetrics = readPhoneDisplayMetrics(context)

    @Volatile
    private var onMetricsChangedCallback: ((PhoneDisplayMetrics) -> Unit)? = null

    @Volatile
    private var onProjectionStoppedCallback: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            logger.log("MIRROR", "Android je zaustavio MediaProjection prijenos zaslona")
            synchronized(lock) {
                releaseVirtualDisplayLocked()
                mediaProjection = null
            }
            unregisterDisplayListener()
            onProjectionStoppedCallback?.invoke()
        }
    }

    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            logger.log("MIRROR", "VirtualDisplay privremeno pauziran")
        }

        override fun onResumed() {
            logger.log("MIRROR", "VirtualDisplay nastavljen")
        }

        override fun onStopped() {
            logger.log("MIRROR", "VirtualDisplay zaustavljen")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            checkAndApplyRotationResize()
        }
    }

    fun hasActiveProjection(): Boolean = synchronized(lock) { mediaProjection != null }

    fun getCurrentMetrics(): PhoneDisplayMetrics = synchronized(lock) { currentMetrics }

    /**
     * Attaches a freshly granted [MediaProjection] (obtained inside `ProbeForegroundService`
     * after promoting to `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`).
     */
    fun attachProjection(projection: MediaProjection) {
        synchronized(lock) {
            stopAndReleaseLocked(stopProjection = true)
            mediaProjection = projection
            currentMetrics = readPhoneDisplayMetrics(context)
            runCatching {
                projection.registerCallback(projectionCallback, mainHandler)
            }.onFailure { e ->
                logger.log("MIRROR", "Upozorenje pri registraciji MediaProjection.Callback: ${e.message}")
            }
        }
        logger.log(
            "MIRROR",
            "✓ MediaProjection aktivan (zaslon mobitela ${currentMetrics.realWidth}×${currentMetrics.realHeight}, dpi=${currentMetrics.densityDpi})",
        )
    }

    /**
     * Connects the active [MediaProjection] to [MirrorDockGlRenderer]'s `SurfaceTexture` output [Surface].
     * Returns `true` if screen capture is live, or `false` if no MediaProjection permission was granted.
     */
    fun attachOutputSurface(
        surface: Surface,
        onMetricsChanged: (PhoneDisplayMetrics) -> Unit,
        onProjectionStopped: () -> Unit,
    ): Boolean {
        onMetricsChangedCallback = onMetricsChanged
        onProjectionStoppedCallback = onProjectionStopped

        val metrics: PhoneDisplayMetrics
        val ok = synchronized(lock) {
            val proj = mediaProjection ?: return false
            metrics = readPhoneDisplayMetrics(context)
            currentMetrics = metrics
            currentSurface = surface

            val existingVd = virtualDisplay
            if (existingVd != null) {
                runCatching {
                    existingVd.resize(metrics.captureWidth, metrics.captureHeight, metrics.densityDpi)
                    existingVd.surface = surface
                    true
                }.onFailure { e ->
                    logger.log("MIRROR", "✗ Ponovno povezivanje VirtualDisplay-a nije uspjelo: ${e.message}")
                }.getOrDefault(false)
            } else {
                runCatching {
                    virtualDisplay = proj.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        metrics.captureWidth,
                        metrics.captureHeight,
                        metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        virtualDisplayCallback,
                        mainHandler,
                    )
                    virtualDisplay != null
                }.onFailure { e ->
                    logger.log("MIRROR", "✗ createVirtualDisplay nije uspio: ${e.message}")
                }.getOrDefault(false)
            }
        }

        if (ok) {
            registerDisplayListener()
            onMetricsChanged(metrics)
            logger.log(
                "MIRROR",
                "✓ VirtualDisplay zrcaljenja pokrenut (${metrics.captureWidth}×${metrics.captureHeight} @ ${metrics.densityDpi} dpi)",
            )
        }
        return ok
    }

    /** Detaches the GL output surface without tearing down the FGS MediaProjection token. */
    fun detachOutputSurface() {
        onMetricsChangedCallback = null
        onProjectionStoppedCallback = null
        synchronized(lock) {
            currentSurface = null
            runCatching { virtualDisplay?.surface = null }
        }
    }

    /** Completely stops VirtualDisplay, display listener, and MediaProjection (called on session stop). */
    fun stopAndReleaseAll() {
        onMetricsChangedCallback = null
        onProjectionStoppedCallback = null
        unregisterDisplayListener()
        synchronized(lock) {
            stopAndReleaseLocked(stopProjection = true)
        }
    }

    private fun checkAndApplyRotationResize() {
        val updated = readPhoneDisplayMetrics(context)
        val changed = synchronized(lock) {
            if (updated.realWidth == currentMetrics.realWidth && updated.realHeight == currentMetrics.realHeight) {
                false
            } else {
                currentMetrics = updated
                val vd = virtualDisplay
                if (vd != null) {
                    runCatching {
                        vd.resize(updated.captureWidth, updated.captureHeight, updated.densityDpi)
                    }.onFailure { e ->
                        logger.log("MIRROR", "Upozorenje pri promjeni orijentacije VirtualDisplay-a: ${e.message}")
                    }
                }
                true
            }
        }
        if (changed) {
            logger.log(
                "MIRROR",
                "Promjena orijentacije zaslona → ${updated.realWidth}×${updated.realHeight} " +
                    "(${if (updated.realWidth >= updated.realHeight) "Vodoravno / Landscape" else "Uspravno / Portrait"})",
            )
            onMetricsChangedCallback?.invoke(updated)
        }
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered.compareAndSet(false, true)) {
            runCatching {
                displayManager?.registerDisplayListener(displayListener, mainHandler)
            }
        }
    }

    private fun unregisterDisplayListener() {
        if (displayListenerRegistered.compareAndSet(true, false)) {
            runCatching {
                displayManager?.unregisterDisplayListener(displayListener)
            }
        }
    }

    private fun releaseVirtualDisplayLocked() {
        currentSurface = null
        runCatching { virtualDisplay?.surface = null }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }

    private fun stopAndReleaseLocked(stopProjection: Boolean) {
        releaseVirtualDisplayLocked()
        val proj = mediaProjection
        if (proj != null && stopProjection) {
            mediaProjection = null
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
    }
}
