package com.padbridge.wear.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.padbridge.wear.protocol.AxisScale
import com.padbridge.wear.protocol.PadState
import com.padbridge.wear.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single in-process hub for controller input. Main thread only.
 *
 * Sources:
 *  - Android input framework: every activity forwards KeyEvent/MotionEvent here (see GamepadActivity).
 *    This is the normal path when the watch's Bluetooth stack has a HID host profile.
 *  - Raw L2CAP link (Plan B): [externalState] with a negative key.
 *
 * Sinks: [stateListeners] (BridgeService, the live pad view) and [logListeners] (Input Test screen).
 */
object InputHub : InputManager.InputDeviceListener {
    private const val TAG = "PadBridge"
    const val MAX_SLOTS = 4

    class Pad(val tracker: PadTracker, val layout: AxisLayout?, val vendorId: Int, val productId: Int) {
        val name get() = tracker.name
        val slot get() = tracker.slot
        val isRaw get() = layout == null
    }

    private val pads = LinkedHashMap<Int, Pad>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var inputManager: InputManager? = null

    val stateListeners = CopyOnWriteArrayList<(PadState) -> Unit>()
    val padsListeners = CopyOnWriteArrayList<() -> Unit>()
    val logListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(context: Context) {
        prefs = Prefs(context)
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager = im
        im.registerInputDeviceListener(this, main)
        // Pads that were already connected before the app started.
        for (id in im.inputDeviceIds) onInputDeviceAdded(id)
    }

    fun pads(): List<Pad> = pads.values.toList()

    fun hasPad(): Boolean = pads.isNotEmpty()

    /** Re-read settings that affect mapping (called after the Settings screen changes them). */
    fun reloadSettings() {
        val rebuild = pads.entries.filter { !it.value.isRaw }.map { it.key }
        for (p in pads.values) {
            p.tracker.swapFace = prefs.swapFace
            p.tracker.deadZone = prefs.deadZonePct / 100f
        }
        // Axis layout depends on the right-stick override.
        for (id in rebuild) {
            val old = pads[id] ?: continue
            val dev = InputDevice.getDevice(id) ?: continue
            pads[id] = Pad(old.tracker, AxisLayout.of(dev, prefs.rightStick), old.vendorId, old.productId)
        }
    }

    // ---------------------------------------------------------------- Android input framework

    fun isGamepad(dev: InputDevice?): Boolean {
        if (dev == null || dev.isVirtual) return false
        val s = dev.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun padFor(deviceId: Int): Pad? {
        pads[deviceId]?.let { return it }
        val dev = InputDevice.getDevice(deviceId)
        if (!isGamepad(dev)) return null
        return addDevice(dev!!)
    }

    private fun freeSlot(): Int {
        val used = pads.values.map { it.slot }.toSet()
        return (0 until MAX_SLOTS).firstOrNull { it !in used } ?: -1
    }

    private fun addDevice(dev: InputDevice): Pad? {
        val slot = freeSlot()
        if (slot < 0) return null
        val t = PadTracker(dev.id, slot, dev.name).apply {
            swapFace = prefs.swapFace
            deadZone = prefs.deadZonePct / 100f
        }
        val pad = Pad(t, AxisLayout.of(dev, prefs.rightStick), dev.vendorId, dev.productId)
        pads[dev.id] = pad
        log(describeDevice(dev, pad))
        Log.i(TAG, "pad added: ${dev.name} slot=$slot layout=${pad.layout}")
        emit(t.snapshot())
        padsListeners.forEach { it() }
        return pad
    }

    /** @return true if the event came from a gamepad and was consumed. */
    fun onKeyEvent(ev: KeyEvent): Boolean {
        val pad = padFor(ev.deviceId) ?: return false
        if (ev.action == KeyEvent.ACTION_MULTIPLE) return true
        val down = ev.action == KeyEvent.ACTION_DOWN
        if (down && ev.repeatCount > 0) return true
        val bit = ButtonMap.bitFor(ev.keyCode)
        if (logListeners.isNotEmpty()) {
            log("${if (down) "DOWN" else "UP  "} ${KeyEvent.keyCodeToString(ev.keyCode).removePrefix("KEYCODE_")}" +
                " (${ev.keyCode}) sc=${ev.scanCode}" + if (bit == 0) " [unmapped]" else "")
        }
        if (pad.tracker.setKey(bit, down)) pad.tracker.changedSnapshot()?.let(::emit)
        // Consume even unmapped gamepad keys, so e.g. B -> KEYCODE_BACK never closes the screen.
        return true
    }

    /** @return true if the event came from a gamepad/joystick and was consumed. */
    fun onMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        val pad = padFor(ev.deviceId) ?: return false
        val l = pad.layout ?: return true
        val t = pad.tracker
        t.setSticks(
            ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y),
            l.rightX?.let { ev.getAxisValue(it) } ?: 0f, l.rightY?.let { ev.getAxisValue(it) } ?: 0f,
        )
        t.setTriggers(l.trigger(ev, l.left), l.trigger(ev, l.right))
        if (l.hasHat) {
            t.setHat(
                AxisScale.hat(ev.getAxisValue(MotionEvent.AXIS_HAT_X)),
                AxisScale.hat(ev.getAxisValue(MotionEvent.AXIS_HAT_Y)),
            )
        }
        val s = t.changedSnapshot()
        if (s != null) {
            if (logListeners.isNotEmpty()) log(describeMotion(ev, pad))
            emit(s)
        }
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (pads.containsKey(deviceId)) return
        val dev = InputDevice.getDevice(deviceId)
        if (isGamepad(dev)) addDevice(dev!!)
    }

    override fun onInputDeviceRemoved(deviceId: Int) = removeKey(deviceId)

    override fun onInputDeviceChanged(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        val old = pads[deviceId] ?: return onInputDeviceAdded(deviceId)
        pads[deviceId] = Pad(old.tracker, AxisLayout.of(dev, prefs.rightStick), dev.vendorId, dev.productId)
    }

    // ---------------------------------------------------------------- raw L2CAP path

    /** Slot of the raw-L2CAP pad, -1 if none. Readable from any thread. */
    @Volatile var rawSlot = -1
        private set

    /** A pad driven by the raw-L2CAP link. [key] must be negative. Main thread. */
    fun externalState(key: Int, name: String, s: PadState) {
        var pad = pads[key]
        if (pad == null) {
            val slot = freeSlot()
            if (slot < 0) return
            pad = Pad(PadTracker(key, slot, name).apply { swapFace = prefs.swapFace }, null, 0x057E, 0x2009)
            pads[key] = pad
            rawSlot = slot
            log("RAW pad connected: $name -> slot $slot")
            padsListeners.forEach { it() }
        }
        pad.tracker.setFromState(s)
        pad.tracker.changedSnapshot()?.let(::emit)
    }

    fun externalRemoved(key: Int) = removeKey(key)

    fun rawReport(hex: String) {
        if (logListeners.isNotEmpty()) log("RAW $hex")
    }

    // ----------------------------------------------------------------

    private fun removeKey(key: Int) {
        val pad = pads.remove(key) ?: return
        if (pad.isRaw) rawSlot = -1
        log("pad removed: ${pad.name}")
        emit(PadState.released(pad.slot, connected = false))
        padsListeners.forEach { it() }
    }

    private fun emit(s: PadState) = stateListeners.forEach { it(s) }

    fun log(line: String) {
        if (logListeners.isEmpty()) return
        logListeners.forEach { it(line) }
    }

    private fun describeDevice(dev: InputDevice, pad: Pad): String {
        val sb = StringBuilder()
        sb.append("PAD ${dev.name} [slot ${pad.slot}]\n")
        sb.append("  vid:pid %04x:%04x  id=%d\n".format(dev.vendorId, dev.productId, dev.id))
        sb.append("  sources=0x%08x\n".format(dev.sources))
        for (r in dev.motionRanges) {
            sb.append("  axis ${MotionEvent.axisToString(r.axis).removePrefix("AXIS_")} " +
                "%.2f..%.2f flat=%.2f\n".format(r.min, r.max, r.flat))
        }
        sb.append("  layout: ${pad.layout}")
        return sb.toString()
    }

    private fun describeMotion(ev: MotionEvent, pad: Pad): String {
        val sb = StringBuilder("AXES")
        val dev = InputDevice.getDevice(ev.deviceId) ?: return sb.toString()
        for (r in dev.motionRanges) {
            if (r.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) continue
            val v = ev.getAxisValue(r.axis)
            if (v > 0.02f || v < -0.02f) {
                sb.append(' ').append(MotionEvent.axisToString(r.axis).removePrefix("AXIS_"))
                    .append('=').append("%.2f".format(v))
            }
        }
        return sb.toString()
    }
}
