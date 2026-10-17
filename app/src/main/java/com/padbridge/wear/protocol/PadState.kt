package com.padbridge.wear.protocol

/**
 * One controller's full state at one instant. Immutable snapshot, safe to hand across threads.
 *
 * Axis conventions (same as Android's input framework, NOT XInput):
 *  - sticks: -32767..32767, X: negative = left, Y: negative = UP.
 *  - triggers: 0..32767.
 * The PC server flips Y for XInput.
 */
data class PadState(
    val slot: Int,
    val connected: Boolean,
    val buttons: Int,
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
) {
    fun isPressed(bit: Int): Boolean = buttons and bit != 0

    companion object {
        fun released(slot: Int, connected: Boolean = false) = PadState(slot, connected, 0)
    }
}

/** Button bitmask. Positional naming (Xbox layout): A = bottom face button, B = right, X = left, Y = top. */
object Buttons {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val L1 = 1 shl 4
    const val R1 = 1 shl 5
    const val L2 = 1 shl 6
    const val R2 = 1 shl 7
    const val SELECT = 1 shl 8
    const val START = 1 shl 9
    const val THUMB_L = 1 shl 10
    const val THUMB_R = 1 shl 11
    const val HOME = 1 shl 12
    const val DPAD_UP = 1 shl 13
    const val DPAD_DOWN = 1 shl 14
    const val DPAD_LEFT = 1 shl 15
    const val DPAD_RIGHT = 1 shl 16
    const val C = 1 shl 17
    const val Z = 1 shl 18
    const val CAPTURE = 1 shl 19

    const val DPAD_MASK = DPAD_UP or DPAD_DOWN or DPAD_LEFT or DPAD_RIGHT

    /** Short labels, index = bit number. Used by the UI and logs. */
    val LABELS = listOf(
        "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "SEL", "START",
        "L3", "R3", "HOME", "UP", "DOWN", "LEFT", "RIGHT", "C", "Z", "CAP",
    )

    fun describe(mask: Int): String {
        if (mask == 0) return "-"
        val sb = StringBuilder()
        for (i in LABELS.indices) if (mask and (1 shl i) != 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(LABELS[i])
        }
        return sb.toString()
    }
}

/** Float [-1,1] / [0,1] to the wire range, with clamping. */
object AxisScale {
    const val MAX = 32767

    fun stick(v: Float): Int {
        val c = if (v > 1f) 1f else if (v < -1f) -1f else v
        return Math.round(c * MAX)
    }

    fun trigger(v: Float): Int {
        val c = if (v > 1f) 1f else if (v < 0f) 0f else v
        return Math.round(c * MAX)
    }

    /** Hat axis value (-1..1, possibly fractional on cheap pads) to a -1/0/1 direction. */
    fun hat(v: Float): Int = if (v > 0.5f) 1 else if (v < -0.5f) -1 else 0

    /** Radial dead zone: zeroes (x,y) when the stick magnitude is inside [dz]. */
    fun radialDeadZone(x: Float, y: Float, dz: Float): Pair<Float, Float> {
        if (dz <= 0f) return x to y
        val mag = Math.sqrt((x * x + y * y).toDouble()).toFloat()
        if (mag <= dz) return 0f to 0f
        // Rescale so output still reaches 1.0 at the edge and starts at 0 just outside the dead zone.
        val scaled = ((mag - dz) / (1f - dz)).coerceAtMost(1f) / mag
        return x * scaled to y * scaled
    }
}
