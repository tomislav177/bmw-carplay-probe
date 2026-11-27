package io.bimmergestalt.bmwcarplay.mirror

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin, zero-distortion geometry and inverse touch mapping between the phone display
 * (e.g. Samsung Galaxy S25 2340×1080 landscape or 1080×2340 portrait) and the BMW CarPlay screen
 * (e.g. BMW X1 F48 1280×480 @ 60 fps).
 *
 * Shared by both [MirrorDockGlRenderer] (OpenGL ES quad & UV coordinates) and
 * [io.bimmergestalt.bmwcarplay.input.BmwMirrorInputRouter] (pixel-accurate inverse touch mapping),
 * guaranteeing that visual crop and touch coordinates never drift regardless of top/bottom crop
 * slider values, layout mode, dock side, or device orientation.
 */
object MirrorLayoutMath {

    data class RectInt(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)

        fun contains(x: Int, y: Int): Boolean =
            x in left until right && y in top until bottom
    }

    data class PhonePoint(
        val x: Float,
        val y: Float,
    )

    data class ComputedLayout(
        val bmwWidth: Int,
        val bmwHeight: Int,
        val phoneWidth: Int,
        val phoneHeight: Int,
        val isPhoneLandscape: Boolean,
        val dockSide: DockSide,
        val dockRect: RectInt,
        val contentViewportRect: RectInt,
        val videoDrawRect: RectInt,
        val srcCropLeft: Int,
        val srcCropTop: Int,
        val srcCropRight: Int,
        val srcCropBottom: Int,
        // OpenGL ES bottom-up UV coordinates for SurfaceTexture (stMatrix flips Y internally)
        val uvLeft: Float,
        val uvRight: Float,
        val uvBottom: Float,
        val uvTop: Float,
        // OpenGL ES NDC coordinates (-1..+1) for video quad
        val ndcLeft: Float,
        val ndcRight: Float,
        val ndcBottom: Float,
        val ndcTop: Float,
        // OpenGL ES NDC coordinates (-1..+1) for dock quad
        val dockNdcLeft: Float,
        val dockNdcRight: Float,
        val dockNdcBottom: Float,
        val dockNdcTop: Float,
    ) {
        val videoScreenPercent: Int
            get() {
                val totalArea = (bmwWidth.toLong() * bmwHeight.toLong()).coerceAtLeast(1L)
                val videoArea = videoDrawRect.width.toLong() * videoDrawRect.height.toLong()
                return ((videoArea * 100.0) / totalArea).roundToInt().coerceIn(0, 100)
            }
    }

    /**
     * Computes exact zero-distortion layout rectangles, OpenGL NDC/UV coordinates, and source crop
     * bounds for the given BMW screen size, phone screen size, and user display configuration.
     */
    fun computeLayout(
        bmwWidth: Int,
        bmwHeight: Int,
        phoneWidth: Int,
        phoneHeight: Int,
        config: MirrorDisplayConfig,
    ): ComputedLayout {
        val safeBmwW = bmwWidth.coerceAtLeast(320)
        val safeBmwH = bmwHeight.coerceAtLeast(240)
        val safePhoneW = phoneWidth.coerceAtLeast(240)
        val safePhoneH = phoneHeight.coerceAtLeast(240)
        val isLandscape = safePhoneW >= safePhoneH

        // Scale dock width proportionally to BMW width (exact 122 px or 240 px at 1280×480)
        val dockWidth = ((config.layoutMode.baseDockWidth1280 * safeBmwW.toFloat()) / 1280f)
            .roundToInt()
            .coerceIn(80, safeBmwW / 3)
        val contentWidth = (safeBmwW - dockWidth).coerceAtLeast(160)

        val dockRect = when (config.dockSide) {
            DockSide.LEFT -> RectInt(0, 0, dockWidth, safeBmwH)
            DockSide.RIGHT -> RectInt(contentWidth, 0, safeBmwW, safeBmwH)
        }
        val contentRect = when (config.dockSide) {
            DockSide.LEFT -> RectInt(dockWidth, 0, safeBmwW, safeBmwH)
            DockSide.RIGHT -> RectInt(0, 0, contentWidth, safeBmwH)
        }

        // Apply user's top/bottom crop sliders in landscape; keep full portrait height uncropped
        val srcLeft = 0
        val srcRight = safePhoneW
        val srcTop: Int
        val srcBottom: Int
        if (isLandscape) {
            val maxSingleEdgeCrop = (safePhoneH * 0.35f).toInt()
            var cropTop = config.cropTopPx.coerceIn(0, maxSingleEdgeCrop)
            var cropBottom = config.cropBottomPx.coerceIn(0, maxSingleEdgeCrop)
            val minActiveHeight = (safePhoneH * 0.30f).toInt().coerceAtLeast(120)
            if (safePhoneH - cropTop - cropBottom < minActiveHeight) {
                val excess = minActiveHeight - (safePhoneH - cropTop - cropBottom)
                val reduceTop = excess / 2
                val reduceBottom = excess - reduceTop
                cropTop = (cropTop - reduceTop).coerceAtLeast(0)
                cropBottom = (cropBottom - reduceBottom).coerceAtLeast(0)
            }
            srcTop = cropTop
            srcBottom = (safePhoneH - cropBottom).coerceAtLeast(srcTop + 1)
        } else {
            srcTop = 0
            srcBottom = safePhoneH
        }

        val srcW = (srcRight - srcLeft).coerceAtLeast(1)
        val srcH = (srcBottom - srcTop).coerceAtLeast(1)
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val dstAspect = contentRect.width.toFloat() / contentRect.height.toFloat()

        // Zero-distortion aspect-fit inside contentViewportRect.
        // Snap within 1.5% tolerance so exact Galaxy S25 presets (e.g. 2.4124 vs 2.4125) leave zero hairline border.
        val drawW: Int
        val drawH: Int
        if (abs(srcAspect - dstAspect) / dstAspect <= 0.015f) {
            drawW = contentRect.width
            drawH = contentRect.height
        } else if (srcAspect < dstAspect) {
            drawH = contentRect.height
            drawW = (contentRect.height * srcAspect).roundToInt().coerceIn(1, contentRect.width)
        } else {
            drawW = contentRect.width
            drawH = (contentRect.width / srcAspect).roundToInt().coerceIn(1, contentRect.height)
        }

        val drawLeft = contentRect.left + (contentRect.width - drawW) / 2
        val drawTop = contentRect.top + (contentRect.height - drawH) / 2
        val videoDrawRect = RectInt(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)

        // OpenGL bottom-up UV coordinates for SurfaceTexture (v=0 is phone bottom, v=1 is phone top)
        val uvLeft = srcLeft.toFloat() / safePhoneW.toFloat()
        val uvRight = srcRight.toFloat() / safePhoneW.toFloat()
        val uvBottom = (safePhoneH - srcBottom).toFloat() / safePhoneH.toFloat()
        val uvTop = 1f - (srcTop.toFloat() / safePhoneH.toFloat())

        return ComputedLayout(
            bmwWidth = safeBmwW,
            bmwHeight = safeBmwH,
            phoneWidth = safePhoneW,
            phoneHeight = safePhoneH,
            isPhoneLandscape = isLandscape,
            dockSide = config.dockSide,
            dockRect = dockRect,
            contentViewportRect = contentRect,
            videoDrawRect = videoDrawRect,
            srcCropLeft = srcLeft,
            srcCropTop = srcTop,
            srcCropRight = srcRight,
            srcCropBottom = srcBottom,
            uvLeft = uvLeft.coerceIn(0f, 1f),
            uvRight = uvRight.coerceIn(0f, 1f),
            uvBottom = uvBottom.coerceIn(0f, 1f),
            uvTop = uvTop.coerceIn(0f, 1f),
            ndcLeft = toNdcX(videoDrawRect.left, safeBmwW),
            ndcRight = toNdcX(videoDrawRect.right, safeBmwW),
            ndcBottom = toNdcY(videoDrawRect.bottom, safeBmwH),
            ndcTop = toNdcY(videoDrawRect.top, safeBmwH),
            dockNdcLeft = toNdcX(dockRect.left, safeBmwW),
            dockNdcRight = toNdcX(dockRect.right, safeBmwW),
            dockNdcBottom = toNdcY(dockRect.bottom, safeBmwH),
            dockNdcTop = toNdcY(dockRect.top, safeBmwH),
        )
    }

    /**
     * Maps a BMW touchscreen coordinate `(bmwX, bmwY)` inside [ComputedLayout.videoDrawRect]
     * to the exact corresponding physical pixel coordinate on the phone display.
     *
     * Returns `null` if the touch is outside the active mirrored video area (e.g. in the dock or letterbox).
     */
    fun bmwToPhone(layout: ComputedLayout, bmwX: Int, bmwY: Int): PhonePoint? {
        val draw = layout.videoDrawRect
        if (!draw.contains(bmwX, bmwY)) return null
        val nx = ((bmwX - draw.left).toFloat() / draw.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val ny = ((bmwY - draw.top).toFloat() / draw.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val srcW = (layout.srcCropRight - layout.srcCropLeft).coerceAtLeast(1)
        val srcH = (layout.srcCropBottom - layout.srcCropTop).coerceAtLeast(1)
        val px = (layout.srcCropLeft + nx * srcW).coerceIn(0f, (layout.phoneWidth - 1).toFloat())
        val py = (layout.srcCropTop + ny * srcH).coerceIn(0f, (layout.phoneHeight - 1).toFloat())
        return PhonePoint(px, py)
    }

    private fun toNdcX(px: Int, totalW: Int): Float =
        (px.toFloat() / totalW.toFloat()) * 2f - 1f

    private fun toNdcY(py: Int, totalH: Int): Float =
        1f - (py.toFloat() / totalH.toFloat()) * 2f
}
