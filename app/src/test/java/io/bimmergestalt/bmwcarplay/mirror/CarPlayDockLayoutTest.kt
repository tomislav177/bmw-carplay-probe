package io.bimmergestalt.bmwcarplay.mirror

import io.bimmergestalt.bmwcarplay.mirror.CarPlayDockLayout.DockAction
import io.bimmergestalt.bmwcarplay.mirror.MirrorLayoutMath.RectInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDockLayoutTest {

    @Test
    fun `Thin 122px Left dock exposes 4 app slots plus Back and Home with exact hit targets`() {
        val geom = CarPlayDockLayout.build(RectInt(0, 0, 122, 480))
        assertFalse(geom.isWide)
        assertNull(geom.mediaCardRect)
        assertEquals(6, geom.buttons.size)

        // Every button center hits its exact action
        for (btn in geom.buttons) {
            val cx = (btn.visualRect.left + btn.visualRect.right) / 2
            val cy = (btn.visualRect.top + btn.visualRect.bottom) / 2
            assertEquals(btn.action, geom.hitTest(cx, cy))
        }

        // Clock area does not trigger any action
        assertNull(geom.hitTest(60, 20))
        // Touch outside dock returns null
        assertNull(geom.hitTest(200, 200))
    }

    @Test
    fun `Wide 240px Right dock exposes 2x2 apps, 3 media controls, Back and Home`() {
        val geom = CarPlayDockLayout.build(RectInt(1040, 0, 1280, 480))
        assertTrue(geom.isWide)
        assertNotNull(geom.mediaCardRect)
        // 4 apps + 3 media buttons + Back + Home = 9 buttons
        assertEquals(9, geom.buttons.size)

        val expectedActions = setOf(
            DockAction.LaunchApp(0),
            DockAction.LaunchApp(1),
            DockAction.LaunchApp(2),
            DockAction.LaunchApp(3),
            DockAction.MediaPrev,
            DockAction.MediaPlayPause,
            DockAction.MediaNext,
            DockAction.NavBack,
            DockAction.NavHome,
        )
        assertEquals(expectedActions, geom.buttons.map { it.action }.toSet())

        // Every visual button stays strictly inside dockRect and hits itself
        for (btn in geom.buttons) {
            assertTrue(btn.visualRect.left >= 1040 && btn.visualRect.right <= 1280)
            assertTrue(btn.visualRect.top >= 0 && btn.visualRect.bottom <= 480)
            val cx = (btn.visualRect.left + btn.visualRect.right) / 2
            val cy = (btn.visualRect.top + btn.visualRect.bottom) / 2
            assertEquals(btn.action, geom.hitTest(cx, cy))
        }

        // Touch in map zone (left of x=1040) returns null
        assertNull(geom.hitTest(500, 240))
    }
}
