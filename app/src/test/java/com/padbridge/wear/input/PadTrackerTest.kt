package com.padbridge.wear.input

import android.view.KeyEvent
import com.padbridge.wear.protocol.AxisScale
import com.padbridge.wear.protocol.Buttons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PadTrackerTest {

    @Test fun keyCodesMapToBits() {
        assertEquals(Buttons.A, ButtonMap.bitFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(Buttons.SELECT, ButtonMap.bitFor(KeyEvent.KEYCODE_BACK))
        assertEquals(Buttons.DPAD_DOWN, ButtonMap.bitFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(0, ButtonMap.bitFor(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun pressAndRelease() {
        val t = PadTracker(1, 0, "pad")
        t.setKey(Buttons.A, true)
        t.setKey(Buttons.X, true)
        assertEquals(Buttons.A or Buttons.X, t.snapshot().buttons)
        t.setKey(Buttons.A, false)
        assertEquals(Buttons.X, t.snapshot().buttons)
    }

    @Test fun hatAndKeysCombine() {
        val t = PadTracker(1, 0, "pad")
        t.setHat(-1, 1)
        t.setKey(Buttons.DPAD_UP, true)
        assertEquals(Buttons.DPAD_LEFT or Buttons.DPAD_DOWN or Buttons.DPAD_UP, t.snapshot().buttons)
    }

    @Test fun triggersAndDigitalBitsStayConsistent() {
        val t = PadTracker(1, 0, "pad")
        t.setKey(Buttons.R2, true)              // digital-only trigger
        assertEquals(AxisScale.MAX, t.snapshot().rt)
        t.setKey(Buttons.R2, false)
        t.setTriggers(0.8f, 0f)                 // analog-only trigger
        val s = t.snapshot()
        assertEquals(Buttons.L2, s.buttons and Buttons.L2)
        assertEquals(AxisScale.trigger(0.8f), s.lt)
    }

    @Test fun deadZoneAndScaling() {
        val t = PadTracker(1, 0, "pad").apply { deadZone = 0.1f }
        t.setSticks(0.05f, -0.05f, 1f, 0f)
        val s = t.snapshot()
        assertEquals(0, s.lx)
        assertEquals(0, s.ly)
        assertEquals(32767, s.rx)
    }

    @Test fun swapFaceForNintendoLayout() {
        val t = PadTracker(1, 0, "pad").apply { swapFace = true }
        t.setKey(Buttons.A, true)
        t.setKey(Buttons.Y, true)
        assertEquals(Buttons.B or Buttons.X, t.snapshot().buttons)
    }

    @Test fun changedSnapshotOnlyOnChange() {
        val t = PadTracker(1, 0, "pad")
        assertNotNull(t.changedSnapshot())
        assertNull(t.changedSnapshot())
        t.setKey(Buttons.B, true)
        assertNotNull(t.changedSnapshot())
    }
}
