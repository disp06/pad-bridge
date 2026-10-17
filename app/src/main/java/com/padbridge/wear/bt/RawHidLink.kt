package com.padbridge.wear.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.protocol.SwitchReportParser
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan B for watches whose Bluetooth stack has no HID host profile (so the pad never shows up
 * as an Android InputDevice). We open the two classic HID L2CAP channels ourselves
 * (PSM 0x11 control, 0x13 interrupt) through hidden-but-greylisted socket APIs and read
 * input reports directly.
 *
 * Reports are parsed as Nintendo Switch Pro Controller reports: put the 8BitDo pad in
 * Switch mode (power on with START + Y) for this path. Every report is also forwarded raw
 * to the server (RAW frames) and shown on the Input Test screen, so other modes can be
 * decoded later.
 *
 * EXPERIMENTAL: whether the stack allows outgoing L2CAP connections on HID PSMs from an app
 * is device-specific. The UI reports exactly where it fails.
 */
object RawHidLink {
    private const val TAG = "PadBridge"
    private const val PSM_CONTROL = 0x11
    private const val PSM_INTERRUPT = 0x13
    /** InputHub key for the raw pad (negative = not an Android InputDevice). */
    const val HUB_KEY = -100

    enum class State { IDLE, CONNECTING, CONNECTED, RETRYING }

    @Volatile var state = State.IDLE
        private set
    @Volatile var detail = ""
        private set
    @Volatile var deviceName = ""
        private set

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Called on the link thread for every changed report (BridgeService forwards it as RAW). */
    @Volatile var rawSink: ((ByteArray, Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wanted: BluetoothDevice? = null
    private var thread: Thread? = null
    @Volatile private var ctrl: BluetoothSocket? = null
    @Volatile private var intr: BluetoothSocket? = null

    val isActive get() = wanted != null

    fun connect(device: BluetoothDevice) {
        if (wanted?.address == device.address && thread?.isAlive == true) return
        disconnect()
        wanted = device
        deviceName = BtManager.safeName(device)
        val t = Thread({ runLoop(device) }, "pb-l2cap")
        thread = t
        t.start()
    }

    fun disconnect() {
        wanted = null
        closeSockets()
        thread?.interrupt()
        thread = null
        set(State.IDLE, "")
        main.post { InputHub.externalRemoved(HUB_KEY) }
    }

    private fun set(s: State, d: String) {
        state = s
        detail = d
        main.post { listeners.forEach { it() } }
    }

    private fun runLoop(device: BluetoothDevice) {
        var attempt = 0
        while (wanted?.address == device.address && !Thread.currentThread().isInterrupted) {
            attempt++
            set(if (attempt == 1) State.CONNECTING else State.RETRYING, "Opening L2CAP to ${deviceName}…")
            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                val c = openL2cap(device, PSM_CONTROL)
                ctrl = c
                c.connect()
                val i = openL2cap(device, PSM_INTERRUPT)
                intr = i
                i.connect()
                attempt = 0
                set(State.CONNECTED, "Raw link up")
                readReports(i)
            } catch (e: Throwable) {
                Log.w(TAG, "raw L2CAP link failed", e)
                set(State.RETRYING, "L2CAP: ${BtManager.rootMessage(e)}")
            } finally {
                closeSockets()
                main.post { InputHub.externalRemoved(HUB_KEY) }
            }
            if (wanted == null) break
            try {
                Thread.sleep(if (attempt < 3) 2000L else 5000L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun readReports(sock: BluetoothSocket) {
        val inp = sock.inputStream
        val buf = ByteArray(128)
        val last = ByteArray(128)
        var lastLen = 0
        var lastLog = 0L
        while (true) {
            val n = inp.read(buf)
            if (n < 0) throw IOException("pad closed the link")
            if (n < 2) continue
            // HIDP header 0xA1 = DATA | INPUT. Strip it; report[0] is the report id.
            if (buf[0] != 0xA1.toByte()) continue
            val len = n - 1
            val report = buf.copyOfRange(1, n)
            if (!changed(report, len, last, lastLen)) continue
            System.arraycopy(report, 0, last, 0, len)
            lastLen = len

            rawSink?.invoke(report, len)
            val parsed = SwitchReportParser.parse(0, report, len)
            val now = SystemClock.uptimeMillis()
            val logIt = now - lastLog > 100
            if (logIt) lastLog = now
            main.post {
                if (parsed != null) InputHub.externalState(HUB_KEY, deviceName, parsed)
                if (logIt) InputHub.rawReport(hex(report, len))
            }
        }
    }

    /** Ignore the free-running timer byte of 0x30 reports so we only forward real changes. */
    private fun changed(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int): Boolean {
        if (aLen != bLen) return true
        val skipTimer = aLen > 1 && a[0] == 0x30.toByte()
        for (i in 0 until aLen) {
            if (skipTimer && i == 1) continue
            if (a[i] != b[i]) return true
        }
        return false
    }

    /**
     * Hidden API ladder (all greylisted on API 28, reachable with targetSdk 28):
     *  1. BluetoothDevice.createL2capSocket(int)            (authenticated + encrypted)
     *  2. BluetoothDevice.createInsecureL2capSocket(int)
     *  3. new BluetoothSocket(TYPE_L2CAP, -1, auth, encrypt, device, psm, null)
     */
    private fun openL2cap(device: BluetoothDevice, psm: Int): BluetoothSocket {
        val errors = ArrayList<String>()
        for (name in listOf("createL2capSocket", "createInsecureL2capSocket")) {
            try {
                val m = BluetoothDevice::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
                m.isAccessible = true
                return m.invoke(device, psm) as BluetoothSocket
            } catch (t: Throwable) {
                errors += "$name: ${BtManager.rootMessage(t)}"
            }
        }
        try {
            val ctor = BluetoothSocket::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                BluetoothDevice::class.java, Int::class.javaPrimitiveType, ParcelUuid::class.java,
            )
            ctor.isAccessible = true
            return ctor.newInstance(3 /* TYPE_L2CAP */, -1, true, true, device, psm, null)
        } catch (t: Throwable) {
            errors += "ctor: ${BtManager.rootMessage(t)}"
        }
        throw IOException("no L2CAP socket API: " + errors.joinToString("; "))
    }

    private fun closeSockets() {
        runCatching { intr?.close() }
        runCatching { ctrl?.close() }
        intr = null
        ctrl = null
    }

    fun hex(b: ByteArray, len: Int): String {
        val sb = StringBuilder(len * 3)
        for (i in 0 until len) {
            if (i > 0) sb.append(' ')
            sb.append("%02x".format(b[i].toInt() and 0xFF))
        }
        return sb.toString()
    }
}
