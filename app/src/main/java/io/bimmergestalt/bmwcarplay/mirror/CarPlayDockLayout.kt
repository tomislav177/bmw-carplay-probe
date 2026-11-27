package io.bimmergestalt.bmwcarplay.mirror

import io.bimmergestalt.bmwcarplay.mirror.MirrorLayoutMath.RectInt
import kotlin.math.roundToInt

/**
 * Pure-Kotlin OEM CarPlay Dock geometry and touch hit-testing for both:
 * - Thin OEM Dock (~122×480 px): clock + 1 vertical column of 4 large (~10.5 mm) app icons + Back/Home
 * - Wide OEM Dock (~240×480 px): clock + 2×2 app tiles with labels + media controls (Prev/PlayPause/Next) + Back/Home
 *
 * Coordinates are expressed in BMW display pixels (`0..bmwWidth`, `0..bmwHeight`), so hit-testing
 * and Canvas rendering share one single source of truth.
 */
object CarPlayDockLayout {

    sealed class DockAction {
        data class LaunchApp(val slotIndex: Int) : DockAction()
        data object NavBack : DockAction()
        data object NavHome : DockAction()
        data object MediaPrev : DockAction()
        data object MediaPlayPause : DockAction()
        data object MediaNext : DockAction()
    }

    data class DockButton(
        val action: DockAction,
        val visualRect: RectInt,
        val hitRect: RectInt,
    )

    data class DockGeometry(
        val dockRect: RectInt,
        val isWide: Boolean,
        val clockRect: RectInt,
        val mediaCardRect: RectInt?,
        val buttons: List<DockButton>,
    ) {
        fun hitTest(bmwX: Int, bmwY: Int): DockAction? {
            if (!dockRect.contains(bmwX, bmwY)) return null
            return buttons.firstOrNull { it.hitRect.contains(bmwX, bmwY) }?.action
        }
    }

    fun build(dockRect: RectInt): DockGeometry {
        val w = dockRect.width.coerceAtLeast(64)
        val h = dockRect.height.coerceAtLeast(240)
        val isWide = w >= 180
        val sx = dockRect.left
        val sy = dockRect.top
        val scaleY = h / 480f

        fun ry(refY: Int): Int = sy + (refY * scaleY).roundToInt()

        val clockRect = RectInt(sx, sy, sx + w, ry(72))
        val buttons = ArrayList<DockButton>()

        if (!isWide) {
            // Thin OEM Dock (122×480 reference):
            // 4 vertical app icons (64×64 visual, full-width generous hit target for bumpy roads)
            val iconSize = minOf((w * 0.56f).roundToInt(), (64 * scaleY).roundToInt()).coerceAtLeast(36)
            val topAreaStart = ry(80)
            val bottomAreaTop = ry(402)
            val slotBandHeight = ((bottomAreaTop - topAreaStart) / 4f)
            val iconLeft = sx + (w - iconSize) / 2

            for (slot in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) {
                val bandTop = topAreaStart + (slot * slotBandHeight).roundToInt()
                val bandBottom = topAreaStart + ((slot + 1) * slotBandHeight).roundToInt()
                val cy = (bandTop + bandBottom) / 2
                val vTop = cy - iconSize / 2
                val visual = RectInt(iconLeft, vTop, iconLeft + iconSize, vTop + iconSize)
                val hit = RectInt(sx, bandTop, sx + w, bandBottom)
                buttons.add(DockButton(DockAction.LaunchApp(slot), visual, hit))
            }

            // Bottom Back + Home pills side-by-side
            val padX = (w * 0.08f).roundToInt().coerceAtLeast(6)
            val gapX = (w * 0.07f).roundToInt().coerceAtLeast(6)
            val btnW = ((w - 2 * padX - gapX) / 2).coerceAtLeast(24)
            val btnTop = ry(408)
            val btnBottom = ry(464).coerceAtMost(sy + h - 8)
            val backVisual = RectInt(sx + padX, btnTop, sx + padX + btnW, btnBottom)
            val homeVisual = RectInt(sx + w - padX - btnW, btnTop, sx + w - padX, btnBottom)
            val midX = sx + w / 2

            buttons.add(DockButton(DockAction.NavBack, backVisual, RectInt(sx, bottomAreaTop, midX, sy + h)))
            buttons.add(DockButton(DockAction.NavHome, homeVisual, RectInt(midX, bottomAreaTop, sx + w, sy + h)))

            return DockGeometry(
                dockRect = dockRect,
                isWide = false,
                clockRect = clockRect,
                mediaCardRect = null,
                buttons = buttons,
            )
        } else {
            // Wide OEM Dock (240×480 reference):
            // 2×2 app grid (y=78..274) + Media card (y=286..390) + Back/Home (y=404..466)
            val padX = (w * 0.07f).roundToInt().coerceAtLeast(10)
            val gapX = (w * 0.055f).roundToInt().coerceAtLeast(8)
            val tileW = ((w - 2 * padX - gapX) / 2).coerceAtLeast(48)
            val gridTop = ry(78)
            val gridBottom = ry(274)
            val gapY = (12 * scaleY).roundToInt().coerceAtLeast(6)
            val tileH = (((gridBottom - gridTop) - gapY) / 2).coerceAtLeast(48)

            for (slot in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) {
                val col = slot % 2
                val row = slot / 2
                val left = sx + padX + col * (tileW + gapX)
                val top = gridTop + row * (tileH + gapY)
                val visual = RectInt(left, top, left + tileW, top + tileH)
                val hit = RectInt(
                    left = (left - gapX / 2).coerceAtLeast(sx),
                    top = (top - gapY / 2).coerceAtLeast(gridTop - 4),
                    right = (left + tileW + gapX / 2).coerceAtMost(sx + w),
                    bottom = (top + tileH + gapY / 2).coerceAtMost(gridBottom + 4),
                )
                buttons.add(DockButton(DockAction.LaunchApp(slot), visual, hit))
            }

            // Media controls card (Prev / PlayPause / Next)
            val mediaTop = ry(286)
            val mediaBottom = ry(390)
            val mediaCardRect = RectInt(sx + padX, mediaTop, sx + w - padX, mediaBottom)
            val mW = mediaCardRect.width / 3
            val mBtnTop = mediaTop + ((mediaBottom - mediaTop) * 0.36f).roundToInt()
            val mBtnBottom = mediaBottom - 6
            val prevRect = RectInt(mediaCardRect.left, mBtnTop, mediaCardRect.left + mW, mBtnBottom)
            val playRect = RectInt(mediaCardRect.left + mW, mBtnTop, mediaCardRect.left + 2 * mW, mBtnBottom)
            val nextRect = RectInt(mediaCardRect.left + 2 * mW, mBtnTop, mediaCardRect.right, mBtnBottom)

            buttons.add(DockButton(DockAction.MediaPrev, prevRect, RectInt(sx, mediaTop, mediaCardRect.left + mW, mediaBottom)))
            buttons.add(DockButton(DockAction.MediaPlayPause, playRect, RectInt(mediaCardRect.left + mW, mediaTop, mediaCardRect.left + 2 * mW, mediaBottom)))
            buttons.add(DockButton(DockAction.MediaNext, nextRect, RectInt(mediaCardRect.left + 2 * mW, mediaTop, sx + w, mediaBottom)))

            // Bottom Back + Home pills
            val bottomTop = ry(404)
            val bottomBottom = ry(464).coerceAtMost(sy + h - 8)
            val btnW = ((w - 2 * padX - gapX) / 2).coerceAtLeast(48)
            val backVisual = RectInt(sx + padX, bottomTop, sx + padX + btnW, bottomBottom)
            val homeVisual = RectInt(sx + w - padX - btnW, bottomTop, sx + w - padX, bottomBottom)
            val midX = sx + w / 2

            buttons.add(DockButton(DockAction.NavBack, backVisual, RectInt(sx, bottomTop - 6, midX, sy + h)))
            buttons.add(DockButton(DockAction.NavHome, homeVisual, RectInt(midX, bottomTop - 6, sx + w, sy + h)))

            return DockGeometry(
                dockRect = dockRect,
                isWide = true,
                clockRect = clockRect,
                mediaCardRect = mediaCardRect,
                buttons = buttons,
            )
        }
    }
}
