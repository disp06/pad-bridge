package com.padbridge.wear.protocol

/**
 * Parses Nintendo Switch Pro Controller HID input reports. Used only by the experimental
 * raw-L2CAP path (Plan B), when the watch has no usable HID host profile.
 *
 * 8BitDo SN30 / SN30 Pro in Switch mode (hold START + Y to power on) emulate a Pro Controller,
 * which starts out sending simple 0x3F reports with no handshake. The 0x30 "full" format is
 * also handled in case the pad is in that mode.
 *
 * Input: the report WITHOUT the 0xA1 HIDP transaction byte (report[0] = report id).
 * Face buttons are mapped by POSITION (Nintendo B = bottom = our A), like Android does.
 */
object SwitchReportParser {

    fun parse(slot: Int, report: ByteArray, len: Int = report.size): PadState? {
        if (len < 1) return null
        return when (report[0].toInt() and 0xFF) {
            0x3F -> if (len >= 12) parseSimple(slot, report) else null
            0x30, 0x21 -> if (len >= 12) parseFull(slot, report) else null
            else -> null
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** 0x3F: btn1 | btn2 | hat | LX u16 | LY u16 | RX u16 | RY u16 (center 0x8000, Y down = larger). */
    private fun parseSimple(slot: Int, r: ByteArray): PadState {
        val b1 = u8(r, 1)
        val b2 = u8(r, 2)
        var m = 0
        if (b1 and 0x01 != 0) m = m or Buttons.A      // Nintendo B (bottom)
        if (b1 and 0x02 != 0) m = m or Buttons.B      // Nintendo A (right)
        if (b1 and 0x04 != 0) m = m or Buttons.X      // Nintendo Y (left)
        if (b1 and 0x08 != 0) m = m or Buttons.Y      // Nintendo X (top)
        if (b1 and 0x10 != 0) m = m or Buttons.L1
        if (b1 and 0x20 != 0) m = m or Buttons.R1
        if (b1 and 0x40 != 0) m = m or Buttons.L2
        if (b1 and 0x80 != 0) m = m or Buttons.R2
        if (b2 and 0x01 != 0) m = m or Buttons.SELECT
        if (b2 and 0x02 != 0) m = m or Buttons.START
        if (b2 and 0x04 != 0) m = m or Buttons.THUMB_L
        if (b2 and 0x08 != 0) m = m or Buttons.THUMB_R
        if (b2 and 0x10 != 0) m = m or Buttons.HOME
        if (b2 and 0x20 != 0) m = m or Buttons.CAPTURE
        m = m or hatToDpad(u8(r, 3))
        fun axis(i: Int) = (u16(r, i) - 0x8000).coerceIn(-32767, 32767)
        return withTriggers(PadState(slot, true, m, axis(4), axis(6), axis(8), axis(10)))
    }

    /** 0x30/0x21: timer | batt | right btns | shared btns | left btns | L stick 3B | R stick 3B. */
    private fun parseFull(slot: Int, r: ByteArray): PadState {
        val right = u8(r, 3)
        val shared = u8(r, 4)
        val left = u8(r, 5)
        var m = 0
        if (right and 0x04 != 0) m = m or Buttons.A      // Nintendo B
        if (right and 0x08 != 0) m = m or Buttons.B      // Nintendo A
        if (right and 0x01 != 0) m = m or Buttons.X      // Nintendo Y
        if (right and 0x02 != 0) m = m or Buttons.Y      // Nintendo X
        if (right and 0x40 != 0) m = m or Buttons.R1
        if (right and 0x80 != 0) m = m or Buttons.R2
        if (left and 0x40 != 0) m = m or Buttons.L1
        if (left and 0x80 != 0) m = m or Buttons.L2
        if (left and 0x01 != 0) m = m or Buttons.DPAD_DOWN
        if (left and 0x02 != 0) m = m or Buttons.DPAD_UP
        if (left and 0x04 != 0) m = m or Buttons.DPAD_RIGHT
        if (left and 0x08 != 0) m = m or Buttons.DPAD_LEFT
        if (shared and 0x01 != 0) m = m or Buttons.SELECT
        if (shared and 0x02 != 0) m = m or Buttons.START
        if (shared and 0x08 != 0) m = m or Buttons.THUMB_L
        if (shared and 0x04 != 0) m = m or Buttons.THUMB_R
        if (shared and 0x10 != 0) m = m or Buttons.HOME
        if (shared and 0x20 != 0) m = m or Buttons.CAPTURE
        // 12-bit packed sticks, 0..4095, Nintendo Y axis: up = larger -> invert to Android convention.
        fun x12(i: Int) = u8(r, i) or ((u8(r, i + 1) and 0x0F) shl 8)
        fun y12(i: Int) = (u8(r, i + 1) shr 4) or (u8(r, i + 2) shl 4)
        fun norm(v: Int) = ((v - 2048) * 16).coerceIn(-32767, 32767)
        return withTriggers(
            PadState(slot, true, m, norm(x12(6)), -norm(y12(6)), norm(x12(9)), -norm(y12(9)))
        )
    }

    /** Hat: 0 = N, clockwise to 7 = NW, 8+ = centred. */
    fun hatToDpad(h: Int): Int = when (h) {
        0 -> Buttons.DPAD_UP
        1 -> Buttons.DPAD_UP or Buttons.DPAD_RIGHT
        2 -> Buttons.DPAD_RIGHT
        3 -> Buttons.DPAD_DOWN or Buttons.DPAD_RIGHT
        4 -> Buttons.DPAD_DOWN
        5 -> Buttons.DPAD_DOWN or Buttons.DPAD_LEFT
        6 -> Buttons.DPAD_LEFT
        7 -> Buttons.DPAD_UP or Buttons.DPAD_LEFT
        else -> 0
    }

    /** Switch triggers are digital: report them as full-scale analog too. */
    private fun withTriggers(s: PadState) = s.copy(
        lt = if (s.isPressed(Buttons.L2)) AxisScale.MAX else 0,
        rt = if (s.isPressed(Buttons.R2)) AxisScale.MAX else 0,
    )
}
