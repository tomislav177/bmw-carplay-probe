package io.bimmergestalt.bmwcarplay.aap

/**
 * Stateful mapper converting BMW CarPlay HID reports (Touchscreen, iDrive Knob/Rotary,
 * Media buttons, and Phone/Steering-wheel buttons) into edge-triggered AAP input actions.
 *
 * Touch coordinates are shifted by `[profile.cropLeft, profile.cropTop]` into the full AAP
 * video coordinate space (e.g., BMW `1280×480` → AAP `1280×720` active band `Y = 120..600`).
 */
class BmwToAapInputMapper(
    private val profile: AapProto.AapDisplayProfile,
) {

    sealed class AapInputAction {
        data class Touch(
            val x: Int,
            val y: Int,
            val action: Int,
            val pointerId: Int = 0,
        ) : AapInputAction()

        data class Key(
            val keycode: Int,
            val isPressed: Boolean,
            val longPress: Boolean = false,
        ) : AapInputAction()

        data class Relative(
            val scanCode: Int,
            val delta: Int,
        ) : AapInputAction()
    }

    private val lock = Any()

    // Touch state
    private var wasTouchDown = false

    // iDrive knob button & directional nudge state
    private var prevSelect = false
    private var prevHome = false
    private var prevBack = false
    private var prevLeft = false
    private var prevRight = false
    private var prevUp = false
    private var prevDown = false

    // Media button state
    private var prevMediaNext = false
    private var prevMediaPrev = false
    private var prevMediaPlayPause = false
    private var prevMediaPlay = false
    private var prevMediaPause = false

    // Phone / steering wheel button state
    private var prevVoice = false
    private var prevHook = false
    private var prevDrop = false

    /**
     * Maps a BMW touchscreen report to AAP `PRESS` / `DRAG` / `RELEASE` touch events.
     * Ignores spurious `down == false` reports when touch was already up.
     */
    fun onTouch(down: Boolean, bmwX: Int, bmwY: Int): List<AapInputAction> = synchronized(lock) {
        val x = (bmwX + profile.cropLeft).coerceIn(0, profile.videoWidth)
        val y = (bmwY + profile.cropTop).coerceIn(0, profile.videoHeight)
        when {
            !wasTouchDown && down -> {
                wasTouchDown = true
                listOf(AapInputAction.Touch(x = x, y = y, action = AapProto.TouchAction.PRESS))
            }

            wasTouchDown && down -> {
                listOf(AapInputAction.Touch(x = x, y = y, action = AapProto.TouchAction.DRAG))
            }

            wasTouchDown && !down -> {
                wasTouchDown = false
                listOf(AapInputAction.Touch(x = x, y = y, action = AapProto.TouchAction.RELEASE))
            }

            else -> emptyList()
        }
    }

    /**
     * Maps a BMW iDrive knob report into edge-triggered DPAD / HOME / BACK key events and
     * relative rotary scroll wheel deltas.
     */
    fun onKnob(
        select: Boolean,
        home: Boolean,
        back: Boolean,
        nudgeX: Int,
        nudgeY: Int,
        wheel: Int,
    ): List<AapInputAction> = synchronized(lock) {
        val out = ArrayList<AapInputAction>(4)

        val curLeft = nudgeX < 0
        val curRight = nudgeX > 0
        val curUp = nudgeY < 0
        val curDown = nudgeY > 0

        if (select != prevSelect) {
            prevSelect = select
            out.add(AapInputAction.Key(AapProto.AapKeyCode.DPAD_CENTER, select))
        }
        if (home != prevHome) {
            prevHome = home
            out.add(AapInputAction.Key(AapProto.AapKeyCode.HOME, home))
        }
        if (back != prevBack) {
            prevBack = back
            out.add(AapInputAction.Key(AapProto.AapKeyCode.BACK, back))
        }
        if (curLeft != prevLeft) {
            prevLeft = curLeft
            out.add(AapInputAction.Key(AapProto.AapKeyCode.DPAD_LEFT, curLeft))
        }
        if (curRight != prevRight) {
            prevRight = curRight
            out.add(AapInputAction.Key(AapProto.AapKeyCode.DPAD_RIGHT, curRight))
        }
        if (curUp != prevUp) {
            prevUp = curUp
            out.add(AapInputAction.Key(AapProto.AapKeyCode.DPAD_UP, curUp))
        }
        if (curDown != prevDown) {
            prevDown = curDown
            out.add(AapInputAction.Key(AapProto.AapKeyCode.DPAD_DOWN, curDown))
        }
        if (wheel != 0) {
            out.add(AapInputAction.Relative(AapProto.AapKeyCode.SCROLL_WHEEL, wheel))
        }

        out
    }

    /**
     * Maps BMW media button states into edge-triggered AAP media key events.
     */
    fun onMedia(
        next: Boolean,
        prev: Boolean,
        playPause: Boolean,
        play: Boolean,
        pause: Boolean,
    ): List<AapInputAction> = synchronized(lock) {
        val out = ArrayList<AapInputAction>(3)
        if (next != prevMediaNext) {
            prevMediaNext = next
            out.add(AapInputAction.Key(AapProto.AapKeyCode.MEDIA_NEXT, next))
        }
        if (prev != prevMediaPrev) {
            prevMediaPrev = prev
            out.add(AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PREVIOUS, prev))
        }
        if (playPause != prevMediaPlayPause) {
            prevMediaPlayPause = playPause
            out.add(AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PLAY_PAUSE, playPause))
        }
        if (play != prevMediaPlay) {
            prevMediaPlay = play
            out.add(AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PLAY, play))
        }
        if (pause != prevMediaPause) {
            prevMediaPause = pause
            out.add(AapInputAction.Key(AapProto.AapKeyCode.MEDIA_PAUSE, pause))
        }
        out
    }

    /**
     * Maps BMW phone / voice steering-wheel button states into edge-triggered AAP key events.
     */
    fun onPhone(
        voice: Boolean,
        hook: Boolean,
        drop: Boolean,
    ): List<AapInputAction> = synchronized(lock) {
        val out = ArrayList<AapInputAction>(3)
        if (voice != prevVoice) {
            prevVoice = voice
            out.add(AapInputAction.Key(AapProto.AapKeyCode.VOICE_ASSIST, voice))
        }
        if (hook != prevHook) {
            prevHook = hook
            out.add(AapInputAction.Key(AapProto.AapKeyCode.CALL, hook))
        }
        if (drop != prevDrop) {
            prevDrop = drop
            out.add(AapInputAction.Key(AapProto.AapKeyCode.ENDCALL, drop))
        }
        out
    }

    /**
     * Clears all tracked touch and button states (e.g. on session start/stop).
     */
    fun reset() = synchronized(lock) {
        wasTouchDown = false
        prevSelect = false
        prevHome = false
        prevBack = false
        prevLeft = false
        prevRight = false
        prevUp = false
        prevDown = false
        prevMediaNext = false
        prevMediaPrev = false
        prevMediaPlayPause = false
        prevMediaPlay = false
        prevMediaPause = false
        prevVoice = false
        prevHook = false
        prevDrop = false
    }
}
