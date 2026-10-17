package com.padbridge.wear.input

import android.view.InputDevice
import android.view.MotionEvent
import com.padbridge.wear.util.Prefs

/**
 * Which Android axes carry the right stick and triggers on a given pad. Pads disagree:
 *  - Google's recommended HID layout and Android's Xbox key layouts: right stick = Z/RZ,
 *    triggers = LTRIGGER/RTRIGGER or BRAKE/GAS. 8BitDo in Android mode (START+B) follows this.
 *  - Linux-xpad style pads with no Android key layout file: right stick = RX/RY, triggers = Z/RZ.
 * "Auto" picks one; the Input Test screen shows the raw axes if it guesses wrong, and
 * Settings > Right stick overrides it.
 */
class AxisLayout(
    val rightX: Int?,
    val rightY: Int?,
    val left: Trigger,
    val right: Trigger,
    val hasHat: Boolean,
) {
    /** Trigger source: candidate axes and whether each is "centred" (rests at -1, Android maps -1..1). */
    class Trigger(val axes: IntArray, val centred: BooleanArray)

    fun trigger(ev: MotionEvent, t: Trigger): Float {
        var best = 0f
        for (i in t.axes.indices) {
            val raw = ev.getAxisValue(t.axes[i])
            val v = if (t.centred[i]) (raw + 1f) / 2f else raw
            if (v > best) best = v
        }
        return best
    }

    override fun toString(): String {
        fun n(a: Int?) = a?.let { MotionEvent.axisToString(it).removePrefix("AXIS_") } ?: "-"
        fun tn(t: Trigger) = t.axes.joinToString("|") { n(it) }.ifEmpty { "-" }
        return "R=${n(rightX)}/${n(rightY)} LT=${tn(left)} RT=${tn(right)} hat=$hasHat"
    }

    companion object {
        fun of(dev: InputDevice, override: Prefs.RightStick): AxisLayout {
            fun has(a: Int) = dev.getMotionRange(a, InputDevice.SOURCE_JOYSTICK) != null ||
                dev.getMotionRange(a) != null
            fun centred(a: Int): Boolean {
                val r = dev.getMotionRange(a, InputDevice.SOURCE_JOYSTICK) ?: dev.getMotionRange(a)
                return r != null && r.min < -0.5f
            }

            val hasZ = has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)
            val hasR = has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)
            val dedicatedL = listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE).filter(::has)
            val dedicatedR = listOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS).filter(::has)
            val hasDedicated = dedicatedL.isNotEmpty() || dedicatedR.isNotEmpty()

            val useZ = when (override) {
                Prefs.RightStick.Z_RZ -> true
                Prefs.RightStick.RX_RY -> false
                Prefs.RightStick.AUTO -> hasZ && (hasDedicated || !hasR)
            }

            val l = ArrayList(dedicatedL)
            val r = ArrayList(dedicatedR)
            val rightX: Int?
            val rightY: Int?
            if (useZ) {
                rightX = MotionEvent.AXIS_Z.takeIf { has(it) }
                rightY = MotionEvent.AXIS_RZ.takeIf { has(it) }
                if (!hasDedicated && hasR) { l += MotionEvent.AXIS_RX; r += MotionEvent.AXIS_RY }
            } else {
                rightX = MotionEvent.AXIS_RX.takeIf { has(it) }
                rightY = MotionEvent.AXIS_RY.takeIf { has(it) }
                if (has(MotionEvent.AXIS_Z)) l += MotionEvent.AXIS_Z
                if (has(MotionEvent.AXIS_RZ)) r += MotionEvent.AXIS_RZ
            }

            fun trig(list: List<Int>) = Trigger(
                list.toIntArray(),
                // Dedicated trigger axes are always 0..1 on Android; re-used stick axes are -1..1.
                BooleanArray(list.size) { i -> list[i] !in dedicatedL && list[i] !in dedicatedR && centred(list[i]) },
            )

            return AxisLayout(
                rightX, rightY, trig(l), trig(r),
                has(MotionEvent.AXIS_HAT_X) || has(MotionEvent.AXIS_HAT_Y),
            )
        }
    }
}
