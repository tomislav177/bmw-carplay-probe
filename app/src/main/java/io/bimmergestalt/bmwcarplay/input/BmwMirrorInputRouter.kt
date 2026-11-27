package io.bimmergestalt.bmwcarplay.input

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import io.bimmergestalt.bmwcarplay.airplay.rtsp.CarPlayHidDecoder
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.mirror.CarPlayDockLayout
import io.bimmergestalt.bmwcarplay.mirror.CarPlayDockLayout.DockAction
import io.bimmergestalt.bmwcarplay.mirror.DockAppCatalog
import io.bimmergestalt.bmwcarplay.mirror.MirrorDisplayConfig
import io.bimmergestalt.bmwcarplay.mirror.MirrorLayoutMath

/**
 * Routes decoded BMW HID input reports (1:1 Touch, 4-way iDrive Knob + rotary wheel,
 * and steering-wheel Media buttons) between the CarPlay Dock and the mirrored Android display.
 */
class BmwMirrorInputRouter(
    private val context: Context,
    private val logger: DiagnosticLogger,
    private val layoutProvider: () -> MirrorLayoutMath.ComputedLayout,
    private val configProvider: () -> MirrorDisplayConfig,
    private val onDockPressedChanged: (DockAction?) -> Unit,
    private val onDockFocusSlotChanged: (Int?) -> Unit,
) {
    private enum class TouchOwner {
        NONE,
        DOCK,
        CONTENT,
    }

    companion object {
        private const val FOCUS_TIMEOUT_MS = 6_000L
        private const val A11Y_HINT_THROTTLE_MS = 10_000L
    }

    private val lock = Any()
    private var touchOwner: TouchOwner = TouchOwner.NONE
    private var dockDownAction: DockAction? = null
    private var lastContentPhoneX: Float = 0f
    private var lastContentPhoneY: Float = 0f
    private var lastA11yHintMs: Long = 0L

    private var prevKnob: CarPlayHidDecoder.KnobState? = null
    private var prevMedia: CarPlayHidDecoder.MediaButtonsState? = null
    private var focusedSlotIndex: Int? = null
    private var lastFocusInteractionMs: Long = 0L

    fun onHidReport(decoded: CarPlayHidDecoder.DecodedReport) {
        when (decoded.kind) {
            CarPlayHidDecoder.DeviceKind.TOUCH -> decoded.touch?.let(::handleTouch)
            CarPlayHidDecoder.DeviceKind.KNOB -> decoded.knob?.let(::handleKnob)
            CarPlayHidDecoder.DeviceKind.MEDIA -> decoded.media?.let(::handleMediaButtons)
            CarPlayHidDecoder.DeviceKind.PHONE,
            CarPlayHidDecoder.DeviceKind.UNKNOWN,
            -> Unit
        }
    }

    fun reset() {
        synchronized(lock) {
            touchOwner = TouchOwner.NONE
            dockDownAction = null
            prevKnob = null
            prevMedia = null
            focusedSlotIndex = null
        }
        onDockPressedChanged(null)
        onDockFocusSlotChanged(null)
        BmwTouchAccessibilityService.instance?.resetTouch()
    }

    private fun handleTouch(tp: CarPlayHidDecoder.TouchPoint) {
        val layout = layoutProvider()
        val dockGeom = CarPlayDockLayout.build(layout.dockRect)
        var actionToExecute: DockAction? = null
        var pressedVisualUpdate: DockAction? = null
        var shouldUpdatePressedVisual = false

        synchronized(lock) {
            if (tp.down) {
                if (touchOwner == TouchOwner.NONE) {
                    if (layout.dockRect.contains(tp.x, tp.y)) {
                        touchOwner = TouchOwner.DOCK
                        dockDownAction = dockGeom.hitTest(tp.x, tp.y)
                        pressedVisualUpdate = dockDownAction
                        shouldUpdatePressedVisual = true
                    } else {
                        val pt = MirrorLayoutMath.bmwToPhone(layout, tp.x, tp.y)
                        if (pt != null) {
                            touchOwner = TouchOwner.CONTENT
                            lastContentPhoneX = pt.x
                            lastContentPhoneY = pt.y
                            forwardContentTouch(down = true, phoneX = pt.x, phoneY = pt.y)
                        }
                    }
                } else if (touchOwner == TouchOwner.DOCK) {
                    val hovered = dockGeom.hitTest(tp.x, tp.y)
                    val newVisual = if (hovered == dockDownAction) hovered else null
                    pressedVisualUpdate = newVisual
                    shouldUpdatePressedVisual = true
                } else if (touchOwner == TouchOwner.CONTENT) {
                    // Clamp to videoDrawRect while dragging so panning doesn't break at edge
                    val clampedX = tp.x.coerceIn(layout.videoDrawRect.left, (layout.videoDrawRect.right - 1).coerceAtLeast(layout.videoDrawRect.left))
                    val clampedY = tp.y.coerceIn(layout.videoDrawRect.top, (layout.videoDrawRect.bottom - 1).coerceAtLeast(layout.videoDrawRect.top))
                    val pt = MirrorLayoutMath.bmwToPhone(layout, clampedX, clampedY)
                    if (pt != null) {
                        lastContentPhoneX = pt.x
                        lastContentPhoneY = pt.y
                        forwardContentTouch(down = true, phoneX = pt.x, phoneY = pt.y)
                    }
                }
            } else {
                // Finger lifted
                when (touchOwner) {
                    TouchOwner.DOCK -> {
                        val upAction = dockGeom.hitTest(tp.x, tp.y)
                        if (dockDownAction != null && upAction == dockDownAction) {
                            actionToExecute = dockDownAction
                        }
                        dockDownAction = null
                        touchOwner = TouchOwner.NONE
                        pressedVisualUpdate = null
                        shouldUpdatePressedVisual = true
                    }

                    TouchOwner.CONTENT -> {
                        val clampedX = tp.x.coerceIn(layout.videoDrawRect.left, (layout.videoDrawRect.right - 1).coerceAtLeast(layout.videoDrawRect.left))
                        val clampedY = tp.y.coerceIn(layout.videoDrawRect.top, (layout.videoDrawRect.bottom - 1).coerceAtLeast(layout.videoDrawRect.top))
                        val pt = MirrorLayoutMath.bmwToPhone(layout, clampedX, clampedY)
                        val upX = pt?.x ?: lastContentPhoneX
                        val upY = pt?.y ?: lastContentPhoneY
                        forwardContentTouch(down = false, phoneX = upX, phoneY = upY)
                        touchOwner = TouchOwner.NONE
                    }

                    TouchOwner.NONE -> Unit
                }
            }
        }

        if (shouldUpdatePressedVisual) {
            onDockPressedChanged(pressedVisualUpdate)
        }
        actionToExecute?.let(::executeDockAction)
    }

    private fun handleKnob(k: CarPlayHidDecoder.KnobState) {
        val now = SystemClock.uptimeMillis()
        var triggerBack = false
        var triggerHome = false
        var launchFocusedSlot: Int? = null
        var focusChangedTo: Int? = null
        var shouldUpdateFocus = false

        synchronized(lock) {
            val prev = prevKnob
            prevKnob = k

            if (focusedSlotIndex != null && (now - lastFocusInteractionMs) > FOCUS_TIMEOUT_MS) {
                focusedSlotIndex = null
                shouldUpdateFocus = true
                focusChangedTo = null
            }

            if (k.back && prev?.back != true) {
                triggerBack = true
            }
            if (k.home && prev?.home != true) {
                triggerHome = true
            }
            if (k.wheel != 0) {
                val step = if (k.wheel > 0) 1 else -1
                val cur = focusedSlotIndex
                val next = if (cur == null) {
                    if (step > 0) 0 else MirrorDisplayConfig.DOCK_SLOT_COUNT - 1
                } else {
                    (cur + step + MirrorDisplayConfig.DOCK_SLOT_COUNT) % MirrorDisplayConfig.DOCK_SLOT_COUNT
                }
                focusedSlotIndex = next
                lastFocusInteractionMs = now
                shouldUpdateFocus = true
                focusChangedTo = next
            }
            if (k.select && prev?.select != true) {
                val cur = focusedSlotIndex
                if (cur != null) {
                    launchFocusedSlot = cur
                    focusedSlotIndex = null
                    shouldUpdateFocus = true
                    focusChangedTo = null
                }
            }
        }

        if (shouldUpdateFocus) {
            onDockFocusSlotChanged(focusChangedTo)
        }
        if (triggerBack) {
            executeDockAction(DockAction.NavBack)
        }
        if (triggerHome) {
            executeDockAction(DockAction.NavHome)
        }
        launchFocusedSlot?.let { slot ->
            executeDockAction(DockAction.LaunchApp(slot))
        }
    }

    private fun handleMediaButtons(m: CarPlayHidDecoder.MediaButtonsState) {
        val prev: CarPlayHidDecoder.MediaButtonsState?
        synchronized(lock) {
            prev = prevMedia
            prevMedia = m
        }
        if (m.next && prev?.next != true) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Volan: Sljedeća")
        if (m.prev && prev?.prev != true) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Volan: Prethodna")
        if (m.playPause && prev?.playPause != true) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Volan: Play/Pauza")
        if (m.play && prev?.play != true) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Volan: Play")
        if (m.pause && prev?.pause != true) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Volan: Pauza")
    }

    private fun forwardContentTouch(down: Boolean, phoneX: Float, phoneY: Float) {
        val svc = BmwTouchAccessibilityService.instance
        if (svc != null) {
            svc.injectTouch(down, phoneX, phoneY)
        } else if (down) {
            val now = SystemClock.uptimeMillis()
            if (now - lastA11yHintMs >= A11Y_HINT_THROTTLE_MS) {
                lastA11yHintMs = now
                logger.log(
                    "DODIR",
                    "BMW dodir (${phoneX.toInt()},${phoneY.toInt()}) primljen, ali usluga Pristupačnosti nije uključena — uključi BMW CarPlay Probe u Pristupačnosti!",
                )
            }
        }
    }

    private fun executeDockAction(action: DockAction) {
        when (action) {
            is DockAction.LaunchApp -> {
                val pkg = configProvider().dockAppPackages.getOrNull(action.slotIndex)
                if (pkg.isNullOrBlank()) {
                    logger.log("DOCK", "Ikona #${action.slotIndex + 1} u docku nije dodijeljena — odaberi aplikaciju u postavkama")
                } else {
                    DockAppCatalog.launchApp(context, pkg, logger)
                }
            }

            DockAction.NavBack -> {
                val ok = BmwTouchAccessibilityService.instance?.performBack() ?: false
                logger.log("DOCK", if (ok) "◀ Natrag (GLOBAL_ACTION_BACK)" else "◀ Natrag zahtijeva uključenu Pristupačnost")
            }

            DockAction.NavHome -> {
                val ok = BmwTouchAccessibilityService.instance?.performHome() ?: false
                logger.log("DOCK", if (ok) "⌂ Početna (GLOBAL_ACTION_HOME)" else "⌂ Početna zahtijeva uključenu Pristupačnost")
            }

            DockAction.MediaPrev -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Dock: ⏮ Prethodna")
            DockAction.MediaPlayPause -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Dock: ⏯ Play/Pauza")
            DockAction.MediaNext -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Dock: ⏭ Sljedeća")
        }
    }

    private fun dispatchMediaKey(keyCode: Int, labelHr: String) {
        runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            logger.log("MEDIJ", "✓ $labelHr")
        }.onFailure { e ->
            logger.log("MEDIJ", "✗ Greška media tipke ($labelHr): ${e.message}")
        }
    }
}
