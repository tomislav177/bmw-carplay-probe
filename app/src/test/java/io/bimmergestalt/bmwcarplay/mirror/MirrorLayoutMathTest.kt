package io.bimmergestalt.bmwcarplay.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MirrorLayoutMathTest {

    @Test
    fun `Galaxy S25 landscape Smart Crop 55-55 fills exact 1158x480 with 122px Left dock and zero distortion`() {
        val cfg = MirrorDisplayConfig(
            layoutMode = MirrorLayoutMode.SMART_CROP_DOCK,
            dockSide = DockSide.LEFT,
            cropTopPx = 55,
            cropBottomPx = 55,
        )
        val layout = MirrorLayoutMath.computeLayout(
            bmwWidth = 1280,
            bmwHeight = 480,
            phoneWidth = 2340,
            phoneHeight = 1080,
            config = cfg,
        )

        assertTrue(layout.isPhoneLandscape)
        assertEquals(MirrorLayoutMath.RectInt(0, 0, 122, 480), layout.dockRect)
        assertEquals(MirrorLayoutMath.RectInt(122, 0, 1280, 480), layout.contentViewportRect)
        assertEquals(MirrorLayoutMath.RectInt(122, 0, 1280, 480), layout.videoDrawRect)
        assertEquals(55, layout.srcCropTop)
        assertEquals(1025, layout.srcCropBottom)
        assertEquals(90, layout.videoScreenPercent) // 1158 / 1280 = 90.47% -> 90 or 91

        // Check OpenGL bottom-up UVs
        assertEquals(0f, layout.uvLeft, 1e-5f)
        assertEquals(1f, layout.uvRight, 1e-5f)
        assertEquals(55f / 1080f, layout.uvBottom, 1e-5f)
        assertEquals(1f - 55f / 1080f, layout.uvTop, 1e-5f)

        // Touch inside dock returns null from bmwToPhone
        assertNull(MirrorLayoutMath.bmwToPhone(layout, 60, 240))

        // Top-left corner of active map maps to (0, 55) on Galaxy S25
        val topLeft = MirrorLayoutMath.bmwToPhone(layout, 122, 0)
        assertNotNull(topLeft)
        assertEquals(0f, topLeft!!.x, 1f)
        assertEquals(55f, topLeft.y, 1f)

        // Center of active map maps to exact center of cropped S25 (1170, 540)
        val center = MirrorLayoutMath.bmwToPhone(layout, 122 + 1158 / 2, 240)
        assertNotNull(center)
        assertEquals(1170f, center!!.x, 2f)
        assertEquals(540f, center.y, 2f)
    }

    @Test
    fun `Galaxy S25 landscape 1-to-1 Fit 0-0 fills exact 1040x480 with 240px Right dock`() {
        val cfg = MirrorDisplayConfig(
            layoutMode = MirrorLayoutMode.FIT_WIDE_DOCK,
            dockSide = DockSide.RIGHT,
            cropTopPx = 0,
            cropBottomPx = 0,
        )
        val layout = MirrorLayoutMath.computeLayout(
            bmwWidth = 1280,
            bmwHeight = 480,
            phoneWidth = 2340,
            phoneHeight = 1080,
            config = cfg,
        )

        assertEquals(MirrorLayoutMath.RectInt(1040, 0, 1280, 480), layout.dockRect)
        assertEquals(MirrorLayoutMath.RectInt(0, 0, 1040, 480), layout.contentViewportRect)
        assertEquals(MirrorLayoutMath.RectInt(0, 0, 1040, 480), layout.videoDrawRect)
        assertEquals(0, layout.srcCropTop)
        assertEquals(1080, layout.srcCropBottom)
        assertEquals(0f, layout.uvBottom, 1e-5f)
        assertEquals(1f, layout.uvTop, 1e-5f)

        // Touch in Right dock is outside videoDrawRect
        assertNull(MirrorLayoutMath.bmwToPhone(layout, 1150, 200))

        // Touch at map center (520, 240) maps to S25 center (1170, 540)
        val center = MirrorLayoutMath.bmwToPhone(layout, 520, 240)
        assertNotNull(center)
        assertEquals(1170f, center!!.x, 2f)
        assertEquals(540f, center.y, 2f)
    }

    @Test
    fun `Asymmetric top-bottom crop maps UV and touch coordinates accurately`() {
        val cfg = MirrorDisplayConfig(
            layoutMode = MirrorLayoutMode.SMART_CROP_DOCK,
            dockSide = DockSide.LEFT,
            cropTopPx = 100,
            cropBottomPx = 10,
        )
        val layout = MirrorLayoutMath.computeLayout(
            bmwWidth = 1280,
            bmwHeight = 480,
            phoneWidth = 2340,
            phoneHeight = 1080,
            config = cfg,
        )

        // Total crop is still 110px -> srcH=970 -> fills full 1158x480 with 0% distortion
        assertEquals(100, layout.srcCropTop)
        assertEquals(1070, layout.srcCropBottom)
        assertEquals(MirrorLayoutMath.RectInt(122, 0, 1280, 480), layout.videoDrawRect)

        // Check asymmetric OpenGL UVs: vBottom = 10/1080, vTop = 1 - 100/1080
        assertEquals(10f / 1080f, layout.uvBottom, 1e-5f)
        assertEquals(1f - 100f / 1080f, layout.uvTop, 1e-5f)

        // Top edge of BMW map maps to phoneY=100; bottom edge maps to phoneY~1068
        val topPt = MirrorLayoutMath.bmwToPhone(layout, 500, 0)!!
        val botPt = MirrorLayoutMath.bmwToPhone(layout, 500, 479)!!
        assertEquals(100f, topPt.y, 1f)
        assertEquals(1068f, botPt.y, 3f)
    }

    @Test
    fun `Zero crop in Smart Crop mode preserves zero distortion via horizontal centering`() {
        val cfg = MirrorDisplayConfig(
            layoutMode = MirrorLayoutMode.SMART_CROP_DOCK,
            dockSide = DockSide.LEFT,
            cropTopPx = 0,
            cropBottomPx = 0,
        )
        val layout = MirrorLayoutMath.computeLayout(
            bmwWidth = 1280,
            bmwHeight = 480,
            phoneWidth = 2340,
            phoneHeight = 1080,
            config = cfg,
        )

        // 480 * (2340/1080) = 1040 px centered inside [122..1280] (width 1158) -> margins 59px each side
        assertEquals(1040, layout.videoDrawRect.width)
        assertEquals(480, layout.videoDrawRect.height)
        assertEquals(122 + (1158 - 1040) / 2, layout.videoDrawRect.left)

        val srcAspect = 2340f / 1080f
        val dstAspect = layout.videoDrawRect.width.toFloat() / layout.videoDrawRect.height.toFloat()
        assertTrue(abs(srcAspect - dstAspect) < 0.01f)

        // Touch in the dark pillarbox margin is safely ignored
        assertNull(MirrorLayoutMath.bmwToPhone(layout, 130, 240))
    }

    @Test
    fun `Portrait phone orientation displays centered 1-to-1 without stretching or harsh crop`() {
        val cfg = MirrorDisplayConfig(
            layoutMode = MirrorLayoutMode.SMART_CROP_DOCK,
            dockSide = DockSide.LEFT,
            cropTopPx = 80,
            cropBottomPx = 80,
        )
        val layout = MirrorLayoutMath.computeLayout(
            bmwWidth = 1280,
            bmwHeight = 480,
            phoneWidth = 1080,
            phoneHeight = 2340,
            config = cfg,
        )

        assertFalse(layout.isPhoneLandscape)
        assertEquals(0, layout.srcCropTop)
        assertEquals(2340, layout.srcCropBottom)
        // 480 * (1080 / 2340) = 222 px wide, 480 px tall, centered in [122..1280]
        assertEquals(222, layout.videoDrawRect.width)
        assertEquals(480, layout.videoDrawRect.height)

        val midX = (layout.videoDrawRect.left + layout.videoDrawRect.right) / 2
        val pt = MirrorLayoutMath.bmwToPhone(layout, midX, 240)
        assertNotNull(pt)
        assertEquals(540f, pt!!.x, 5f)
        assertEquals(1170f, pt.y, 5f)
    }

    @Test
    fun `Extreme crop values are safely clamped to prevent invalid geometry`() {
        val cfg = MirrorDisplayConfig(
            layoutMode = MirrorLayoutMode.SMART_CROP_DOCK,
            dockSide = DockSide.LEFT,
            cropTopPx = 9999,
            cropBottomPx = 9999,
        )
        val layout = MirrorLayoutMath.computeLayout(1280, 480, 2340, 1080, cfg)
        assertTrue(layout.srcCropBottom > layout.srcCropTop + 100)
        assertTrue(layout.uvTop > layout.uvBottom)
        assertTrue(layout.videoDrawRect.width > 0 && layout.videoDrawRect.height > 0)
    }
}
