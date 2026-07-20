package com.paleblue

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Gesture state machine over BOTH temple touchpads.
 * On the X3 Pro the right-arm pad reports as an input device whose name
 * contains "cyttsp5" and the left-arm "cyttsp6"; either arm triggers the
 * same actions. Tap = up within ~250 ms and < touch-slop movement.
 * Double tap = two taps within ~350 ms. Long press = held ≥ 2000 ms.
 * The "liftoff jump" of capacitive pads is guarded by ignoring the final
 * micro-move sample before ACTION_UP.
 */
class TouchpadGestures(
    private val onSingleTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onSwipeForward: () -> Unit = {},
    private val onSwipeBack: () -> Unit = {},
    private val onTripleTap: () -> Unit = {}
) {
    companion object {
        private const val TAP_MS = 250L
        private const val DOUBLE_MS = 350L
        private const val LONG_MS = 2000L
        private const val SLOP_PX = 24f
        private const val SWIPE_PX = 70f
    }

    private class PadState {
        var downTime = 0L
        var downX = 0f; var downY = 0f
        var lastX = 0f; var lastY = 0f
        var moved = false
        var longFired = false
        var lastTapTime = 0L
        var tapCount = 0
        var pendingSingle: Runnable? = null
    }

    private val pads = HashMap<String, PadState>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun isTouchpad(event: MotionEvent): Boolean {
        val name = event.device?.name ?: return false
        return name.contains("cyttsp5") || name.contains("cyttsp6")
    }

    /** Feed every MotionEvent here; returns true when consumed. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val name = event.device?.name ?: "unknown"
        val pad = pads.getOrPut(name) { PadState() }
        val now = android.os.SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pad.downTime = now
                pad.downX = event.x; pad.downY = event.y
                pad.lastX = event.x; pad.lastY = event.y
                pad.moved = false
                pad.longFired = false
                handler.postDelayed({
                    if (!pad.moved && !pad.longFired && pad.downTime != 0L) {
                        pad.longFired = true
                        onLongPress()
                    }
                }, LONG_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                // ignore the liftoff-jump micro-move right before UP: only mark as moved
                // when displacement exceeds slop measured from the DOWN point
                if (abs(event.x - pad.downX) > SLOP_PX || abs(event.y - pad.downY) > SLOP_PX) {
                    pad.moved = true
                }
                pad.lastX = event.x; pad.lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacksAndMessages(null)
                val held = now - pad.downTime
                val dx = event.x - pad.downX
                pad.downTime = 0L
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) return true
                if (pad.longFired) return true
                if (pad.moved) {
                    if (held <= 900L && abs(dx) >= SWIPE_PX && abs(dx) > abs(event.y - pad.downY) * 1.25f) {
                        if (dx > 0f) onSwipeForward() else onSwipeBack()
                    }
                    return true
                }
                if (held > TAP_MS) return true
                // it's a tap: count the burst, decide when the window closes.
                // A third tap fires TRIPLE instantly; otherwise the count is
                // resolved DOUBLE_MS after the last tap (so double-tap waits one
                // window to be sure a triple isn't coming).
                pad.tapCount = if (now - pad.lastTapTime <= DOUBLE_MS) pad.tapCount + 1 else 1
                pad.lastTapTime = now
                pad.pendingSingle = null   // superseded (already removed above)
                if (pad.tapCount >= 3) {
                    pad.tapCount = 0
                    pad.lastTapTime = 0L
                    onTripleTap()
                } else {
                    val n = pad.tapCount
                    val r = Runnable {
                        pad.pendingSingle = null
                        pad.tapCount = 0
                        pad.lastTapTime = 0L
                        if (n == 1) onSingleTap() else onDoubleTap()
                    }
                    pad.pendingSingle = r
                    handler.postDelayed(r, DOUBLE_MS)
                }
            }
        }
        return true
    }
}
