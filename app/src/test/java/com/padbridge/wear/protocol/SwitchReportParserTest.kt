package com.padbridge.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitchReportParserTest {
    private fun r(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun simpleReportPositionalMapping() {
        // Nintendo B (bottom) + Plus, hat = right, LX full left, others centred, RY full down.
        val s = SwitchReportParser.parse(0, r(0x3f, 0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x80, 0x00, 0x80, 0xff, 0xff))!!
        assertEquals(Buttons.A or Buttons.START or Buttons.DPAD_RIGHT, s.buttons)
        assertEquals(-32767, s.lx)
        assertEquals(0, s.ly)
        assertEquals(0, s.rx)
        assertEquals(32767, s.ry)
    }

    @Test fun digitalTriggersBecomeFullScale() {
        val s = SwitchReportParser.parse(0, r(0x3f, 0x40, 0x00, 0x08, 0, 0x80, 0, 0x80, 0, 0x80, 0, 0x80))!!
        assertEquals(Buttons.L2, s.buttons)
        assertEquals(AxisScale.MAX, s.lt)
        assertEquals(0, s.rt)
    }

    @Test fun hatDiagonals() {
        assertEquals(Buttons.DPAD_UP or Buttons.DPAD_LEFT, SwitchReportParser.hatToDpad(7))
        assertEquals(0, SwitchReportParser.hatToDpad(8))
    }

    @Test fun fullReport() {
        // 0x30: timer, batt, right=A(0x08), shared=Home(0x10), left=Up(0x02), sticks centred (0x800).
        val s = SwitchReportParser.parse(0, r(0x30, 0x00, 0x90, 0x08, 0x10, 0x02, 0x00, 0x08, 0x80, 0x00, 0x08, 0x80))!!
        assertEquals(Buttons.B or Buttons.HOME or Buttons.DPAD_UP, s.buttons)
        assertEquals(0, s.lx)
        assertEquals(0, s.ly)
    }

    @Test fun unknownOrShortReportsIgnored() {
        assertNull(SwitchReportParser.parse(0, r(0x01, 0, 0)))
        assertNull(SwitchReportParser.parse(0, r(0x3f, 0, 0)))
    }
}
