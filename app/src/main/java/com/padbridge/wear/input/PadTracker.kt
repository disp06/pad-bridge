package com.padbridge.wear.input

import android.view.KeyEvent
import com.padbridge.wear.protocol.AxisScale
import com.padbridge.wear.protocol.Buttons
import com.padbridge.wear.protocol.PadState

/** Android key codes -> PadBridge button bits. 0 = not a mapped gamepad button. */
object ButtonMap {
    fun bitFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Buttons.A
        KeyEvent.KEYCODE_BUTTON_B -> Buttons.B
        KeyEvent.KEYCODE_BUTTON_X -> Buttons.X
        KeyEvent.KEYCODE_BUTTON_Y -> Buttons.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> Buttons.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> Buttons.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> Buttons.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Buttons.R2
        KeyEvent.KEYCODE_BUTTON_SELECT -> Buttons.SELECT
        // Some pads (and Android's fallback mapping) report Select/Back as KEYCODE_BACK.
        KeyEvent.KEYCODE_BACK -> Buttons.SELECT
        KeyEvent.KEYCODE_BUTTON_START -> Buttons.START
        KeyEvent.KEYCODE_MENU -> Buttons.START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Buttons.THUMB_L
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Buttons.THUMB_R
        KeyEvent.KEYCODE_BUTTON_MODE -> Buttons.HOME
        KeyEvent.KEYCODE_HOME -> Buttons.HOME
        KeyEvent.KEYCODE_DPAD_UP -> Buttons.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Buttons.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Buttons.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Buttons.DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> Buttons.A
        KeyEvent.KEYCODE_BUTTON_C -> Buttons.C
        KeyEvent.KEYCODE_BUTTON_Z -> Buttons.Z
        else -> 0
    }
}

/**
 * Mutable state of one physical pad. Not thread-safe: touched from the main thread only
 * (input events arrive there; the raw-L2CAP path posts to the main thread first).
 */
class PadTracker(val key: Int, val slot: Int, val name: String) {
    var keyMask = 0
        private set
    var hatMask = 0
        private set
    private var lxF = 0f
    private var lyF = 0f
    private var rxF = 0f
    private var ryF = 0f
    private var ltF = 0f
    private var rtF = 0f

    var swapFace = false
    var deadZone = 0.10f

    private var last: PadState? = null

    /** @return true if this changed the mask. */
    fun setKey(bit: Int, down: Boolean): Boolean {
        if (bit == 0) return false
        val before = keyMask
        keyMask = if (down) keyMask or bit else keyMask and bit.inv()
        return before != keyMask
    }

    fun setHat(x: Int, y: Int) {
        var m = 0
        if (x < 0) m = m or Buttons.DPAD_LEFT
        if (x > 0) m = m or Buttons.DPAD_RIGHT
        if (y < 0) m = m or Buttons.DPAD_UP
        if (y > 0) m = m or Buttons.DPAD_DOWN
        hatMask = m
    }

    fun setSticks(lx: Float, ly: Float, rx: Float, ry: Float) {
        val (a, b) = AxisScale.radialDeadZone(lx, ly, deadZone)
        val (c, d) = AxisScale.radialDeadZone(rx, ry, deadZone)
        lxF = a; lyF = b; rxF = c; ryF = d
    }

    fun setTriggers(lt: Float, rt: Float) {
        ltF = lt; rtF = rt
    }

    /** Replace everything at once (raw-L2CAP path: the report already is a full state). */
    fun setFromState(s: PadState) {
        keyMask = s.buttons
        hatMask = 0
        lxF = s.lx / 32767f; lyF = s.ly / 32767f
        rxF = s.rx / 32767f; ryF = s.ry / 32767f
        ltF = s.lt / 32767f; rtF = s.rt / 32767f
    }

    fun snapshot(connected: Boolean = true): PadState {
        var m = keyMask or hatMask
        // Keep digital and analog triggers consistent in both directions:
        // a digital-only L2/R2 key reads as a fully pulled trigger, an analog pull past half sets the bit.
        val lt = if (keyMask and Buttons.L2 != 0) 1f else ltF
        val rt = if (keyMask and Buttons.R2 != 0) 1f else rtF
        if (lt > 0.5f) m = m or Buttons.L2
        if (rt > 0.5f) m = m or Buttons.R2
        if (swapFace) m = swapFaceBits(m)
        return PadState(
            slot, connected, m,
            AxisScale.stick(lxF), AxisScale.stick(lyF),
            AxisScale.stick(rxF), AxisScale.stick(ryF),
            AxisScale.trigger(lt), AxisScale.trigger(rt),
        )
    }

    /** Snapshot only if it differs from the previously returned one. */
    fun changedSnapshot(): PadState? {
        val s = snapshot()
        if (s == last) return null
        last = s
        return s
    }

    companion object {
        /** Nintendo-label layout: swap A<->B and X<->Y. */
        fun swapFaceBits(m: Int): Int {
            var out = m and (Buttons.A or Buttons.B or Buttons.X or Buttons.Y).inv()
            if (m and Buttons.A != 0) out = out or Buttons.B
            if (m and Buttons.B != 0) out = out or Buttons.A
            if (m and Buttons.X != 0) out = out or Buttons.Y
            if (m and Buttons.Y != 0) out = out or Buttons.X
            return out
        }
    }
}
