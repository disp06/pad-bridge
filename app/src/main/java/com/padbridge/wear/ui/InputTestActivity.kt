package com.padbridge.wear.ui

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView
import com.padbridge.wear.R
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.protocol.Buttons
import com.padbridge.wear.protocol.PadState

/**
 * Brief, Step 1: input prototyping. Logs every KeyEvent / MotionEvent the pad produces, the
 * device's axis ranges, and raw L2CAP reports, so you can see exactly how Android 9 on the watch
 * maps an 8BitDo in each mode. Everything is mirrored to logcat (tag PadBridge).
 */
class InputTestActivity : GamepadActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvState: TextView
    private lateinit var scroll: ScrollView
    private val lines = ArrayDeque<String>()

    private val logListener: (String) -> Unit = { append(it) }
    private val stateListener: (PadState) -> Unit = { s ->
        tvState.text = "s${s.slot} ${Buttons.describe(s.buttons)}\n" +
            "L %6d %6d  R %6d %6d\nLT %5d  RT %5d".format(s.lx, s.ly, s.rx, s.ry, s.lt, s.rt)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_test)
        tvLog = findViewById(R.id.tvLog)
        tvState = findViewById(R.id.tvState)
        scroll = findViewById(R.id.logScroll)
        tvLog.setOnClickListener { lines.clear(); tvLog.text = "" }
    }

    override fun onResume() {
        super.onResume()
        InputHub.logListeners.add(logListener)
        InputHub.stateListeners.add(stateListener)
        // List what's already attached.
        val pads = InputHub.pads()
        if (pads.isEmpty()) append("No gamepad InputDevice yet. Pair one, or use Raw L2CAP mode.")
        for (p in pads) {
            append("PAD ${p.name} slot ${p.slot}" + if (p.isRaw) " (raw L2CAP)" else " vid:pid %04x:%04x layout ${p.layout}".format(p.vendorId, p.productId))
        }
    }

    override fun onPause() {
        InputHub.logListeners.remove(logListener)
        InputHub.stateListeners.remove(stateListener)
        super.onPause()
    }

    /** Also log events that DON'T come from a recognised gamepad: shows misclassified pads. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!InputHub.isGamepad(event.device) && event.action == KeyEvent.ACTION_DOWN) {
            append("non-pad key ${KeyEvent.keyCodeToString(event.keyCode)} from '${event.device?.name}' " +
                "src=0x%08x".format(event.source))
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!InputHub.isGamepad(InputDevice.getDevice(ev.deviceId)) &&
            ev.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0
        ) {
            append("non-pad joystick motion from '${InputDevice.getDevice(ev.deviceId)?.name}'")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun append(line: String) {
        Log.d("PadBridge", line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        tvLog.text = lines.joinToString("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_LINES = 60
    }
}
