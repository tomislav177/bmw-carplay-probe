package io.bimmergestalt.bmwcarplay.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureEngineTest {

    private data class RecordedStroke(
        val kind: String,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val durationMs: Long,
        val willContinue: Boolean,
    )

    @Test
    fun `Fast tap starts continuing stroke then finishes cleanly on completion`() {
        var now = 1000L
        val strokes = ArrayList<RecordedStroke>()
        val engine = TouchGestureEngine(
            nowMs = { now },
            onStartStroke = { x, y, dur, cont ->
                strokes.add(RecordedStroke("START", x, y, x, y, dur, cont))
                true
            },
            onContinueStroke = { fx, fy, tx, ty, dur, cont ->
                strokes.add(RecordedStroke("CONT", fx, fy, tx, ty, dur, cont))
                true
            },
        )

        // Quick down + up before first 40ms chunk completes
        engine.onTouch(down = true, x = 400f, y = 300f)
        now += 15L
        engine.onTouch(down = false, x = 402f, y = 301f)
        assertEquals(1, strokes.size)
        assertTrue(strokes[0].willContinue)

        // First chunk finishes -> emits final finishing stroke with willContinue=false
        now += 25L
        engine.onStrokeCompleted()
        assertEquals(2, strokes.size)
        assertEquals("CONT", strokes[1].kind)
        assertFalse(strokes[1].willContinue)
        assertEquals(400f, strokes[1].fromX, 0.01f)
        assertEquals(402f, strokes[1].toX, 0.01f)

        // Finishing stroke completes -> engine returns to IDLE
        now += 12L
        engine.onStrokeCompleted()
        assertEquals(2, strokes.size)
    }

    @Test
    fun `Live drag pans smoothly across multiple continuous chunks and releases cleanly`() {
        var now = 5000L
        val strokes = ArrayList<RecordedStroke>()
        val engine = TouchGestureEngine(
            nowMs = { now },
            onStartStroke = { x, y, dur, cont ->
                strokes.add(RecordedStroke("START", x, y, x, y, dur, cont))
                true
            },
            onContinueStroke = { fx, fy, tx, ty, dur, cont ->
                strokes.add(RecordedStroke("CONT", fx, fy, tx, ty, dur, cont))
                true
            },
        )

        engine.onTouch(true, 100f, 200f)
        now += 20L
        engine.onTouch(true, 180f, 220f)
        now += 20L
        engine.onStrokeCompleted() // chains to (180, 220) with willContinue=true

        assertEquals(2, strokes.size)
        assertTrue(strokes[1].willContinue)
        assertEquals(100f, strokes[1].fromX, 0.01f)
        assertEquals(180f, strokes[1].toX, 0.01f)

        // Continue dragging further then lift
        now += 20L
        engine.onTouch(true, 300f, 260f)
        now += 20L
        engine.onStrokeCompleted() // chains (180,220) -> (300,260), still continuing
        assertEquals(3, strokes.size)
        assertTrue(strokes[2].willContinue)
        assertEquals(180f, strokes[2].fromX, 0.01f)
        assertEquals(300f, strokes[2].toX, 0.01f)

        now += 10L
        engine.onTouch(false, 310f, 265f)
        now += 30L
        engine.onStrokeCompleted() // finishing stroke (300,260) -> (310,265), willContinue=false
        assertEquals(4, strokes.size)
        assertFalse(strokes[3].willContinue)
        assertEquals(300f, strokes[3].fromX, 0.01f)
        assertEquals(310f, strokes[3].toX, 0.01f)
    }

    @Test
    fun `Rapid double-tap queues second touch during finishing stroke`() {
        var now = 2000L
        val strokes = ArrayList<RecordedStroke>()
        val engine = TouchGestureEngine(
            nowMs = { now },
            onStartStroke = { x, y, dur, cont ->
                strokes.add(RecordedStroke("START", x, y, x, y, dur, cont))
                true
            },
            onContinueStroke = { fx, fy, tx, ty, dur, cont ->
                strokes.add(RecordedStroke("CONT", fx, fy, tx, ty, dur, cont))
                true
            },
        )

        // Tap 1
        engine.onTouch(true, 500f, 500f)
        engine.onTouch(false, 500f, 500f)
        now += 40L
        engine.onStrokeCompleted() // now in FINISHING state

        // Tap 2 arrives right inside the 12 ms finishing window!
        now += 5L
        engine.onTouch(true, 505f, 505f)
        engine.onTouch(false, 505f, 505f)

        // Finishing stroke completes -> immediately launches queued Tap 2 START!
        now += 7L
        engine.onStrokeCompleted()
        assertEquals(3, strokes.size)
        assertEquals("START", strokes[2].kind)
        assertEquals(505f, strokes[2].fromX, 0.01f)

        now += 40L
        engine.onStrokeCompleted() // Tap 2 finishes
        assertEquals(4, strokes.size)
        assertFalse(strokes[3].willContinue)
    }

    @Test
    fun `Watchdog recovers automatically if Android drops GestureResultCallback`() {
        var now = 10_000L
        val strokes = ArrayList<RecordedStroke>()
        val engine = TouchGestureEngine(
            nowMs = { now },
            onStartStroke = { x, y, dur, cont ->
                strokes.add(RecordedStroke("START", x, y, x, y, dur, cont))
                true
            },
            onContinueStroke = { fx, fy, tx, ty, dur, cont ->
                strokes.add(RecordedStroke("CONT", fx, fy, tx, ty, dur, cont))
                true
            },
        )

        engine.onTouch(true, 100f, 100f)
        assertEquals(1, strokes.size)

        // Simulate missing callback for > STUCK_WATCHDOG_MS
        now += TouchGestureEngine.STUCK_WATCHDOG_MS + 100L
        engine.onTouch(true, 250f, 250f)

        assertEquals(2, strokes.size)
        assertEquals("START", strokes[1].kind)
        assertEquals(250f, strokes[1].fromX, 0.01f)
    }
}
