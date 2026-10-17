package com.padbridge.wear.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Wear OS 2 ships no pairing UI for HID devices, so the app does it itself:
 * discovery -> createBond() -> HID host connect.
 *
 * The HID host profile is the big unknown on a watch. [hidHost] reports whether the Bluetooth
 * stack exposes it (profile proxy id 4 = BluetoothProfile.HID_HOST, hidden constant). If it's
 * missing, the pad can still be bonded and then read over raw L2CAP ([RawHidLink]).
 *
 * Main thread only. Call [start] / [stop] from the owning screen's lifecycle.
 */
class BtManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onDevicesChanged()
        fun onDiscoveryState(running: Boolean)
        fun onHidHostState(state: HidHostState)
        fun onMessage(text: String)
    }

    enum class HidHostState { CHECKING, AVAILABLE, UNAVAILABLE }

    class Entry(val device: BluetoothDevice) {
        var name: String = safeName(device)
        var rssi: Short = Short.MIN_VALUE
        var hidConnected = false
        val address: String get() = device.address
        val bonded get() = device.bondState == BluetoothDevice.BOND_BONDED
        val bonding get() = device.bondState == BluetoothDevice.BOND_BONDING
        val looksLikeGamepad get() = isGamepadClass(device.bluetoothClass) || isGamepadName(name)
    }

    val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val main = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<String, Entry>()
    private var hidProxy: BluetoothProfile? = null
    var hidHost = HidHostState.CHECKING
        private set
    private var registered = false

    fun devices(): List<Entry> = entries.values.sortedWith(
        compareByDescending<Entry> { it.hidConnected }
            .thenByDescending { it.bonded }
            .thenByDescending { it.looksLikeGamepad }
            .thenByDescending { it.rssi }
    )

    fun start() {
        val a = adapter ?: return listener.onMessage("No Bluetooth adapter")
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(ACTION_HID_CONNECTION_STATE_CHANGED)
                // Run before any system pairing handler so we can answer PIN requests.
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
            }
            context.registerReceiver(receiver, f)
            registered = true
        }
        for (d in a.bondedDevices.orEmpty()) entries.getOrPut(d.address) { Entry(d) }
        listener.onDevicesChanged()
        if (!a.isEnabled) {
            listener.onMessage("Turning Bluetooth on…")
            @Suppress("DEPRECATION") a.enable()
        } else {
            connectHidProxy()
        }
    }

    fun stop() {
        adapter?.let { if (it.isDiscovering) it.cancelDiscovery() }
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        hidProxy?.let { adapter?.closeProfileProxy(PROFILE_HID_HOST, it) }
        hidProxy = null
        main.removeCallbacksAndMessages(null)
    }

    fun startDiscovery() {
        val a = adapter ?: return
        if (!a.isEnabled) return listener.onMessage("Bluetooth is off")
        if (a.isDiscovering) a.cancelDiscovery()
        // Drop stale unbonded results; bonded ones stay.
        entries.values.removeAll { !it.bonded }
        listener.onDevicesChanged()
        if (!a.startDiscovery()) listener.onMessage("Scan failed. Location permission/services on?")
    }

    fun cancelDiscovery() {
        adapter?.let { if (it.isDiscovering) it.cancelDiscovery() }
    }

    /** Bond (pair) with a device. Discovery must be stopped first or bonding is flaky. */
    fun pair(e: Entry) {
        cancelDiscovery()
        when (e.device.bondState) {
            BluetoothDevice.BOND_BONDED -> connect(e)
            BluetoothDevice.BOND_BONDING -> listener.onMessage("Already pairing…")
            else -> {
                listener.onMessage("Pairing with ${e.name}…")
                if (!e.device.createBond()) listener.onMessage("createBond() refused")
            }
        }
    }

    /** Ask the HID host profile to connect a bonded pad. Uses the hidden BluetoothHidHost.connect(). */
    fun connect(e: Entry): Boolean {
        val proxy = hidProxy
        if (proxy == null) {
            listener.onMessage(
                if (hidHost == HidHostState.UNAVAILABLE) "No HID host on this watch. Use Raw L2CAP."
                else "HID host not ready yet"
            )
            return false
        }
        return try {
            val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val ok = m.invoke(proxy, e.device) as? Boolean ?: false
            listener.onMessage(if (ok) "Connecting ${e.name}…" else "HID connect refused")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "HID connect failed", t)
            listener.onMessage("HID connect error: ${rootMessage(t)}")
            false
        }
    }

    /** Forget a pairing. Hidden BluetoothDevice.removeBond(), greylisted, works with targetSdk 28. */
    fun unpair(e: Entry) {
        try {
            e.device.javaClass.getMethod("removeBond").invoke(e.device)
            listener.onMessage("Forgot ${e.name}")
        } catch (t: Throwable) {
            listener.onMessage("Forget failed: ${rootMessage(t)}")
        }
    }

    private fun connectHidProxy() {
        val a = adapter ?: return
        if (hidProxy != null) return
        hidHost = HidHostState.CHECKING
        listener.onHidHostState(hidHost)
        val requested = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hidProxy = proxy
                hidHost = HidHostState.AVAILABLE
                listener.onHidHostState(hidHost)
                refreshHidConnections()
            }

            override fun onServiceDisconnected(profile: Int) {
                hidProxy = null
            }
        }, PROFILE_HID_HOST)
        if (!requested) {
            hidHost = HidHostState.UNAVAILABLE
            listener.onHidHostState(hidHost)
            return
        }
        // If the profile service isn't running in the stack, the bind silently never completes.
        main.postDelayed({
            if (hidProxy == null && hidHost == HidHostState.CHECKING) {
                hidHost = HidHostState.UNAVAILABLE
                listener.onHidHostState(hidHost)
            }
        }, 4000)
    }

    private fun refreshHidConnections() {
        val p = hidProxy ?: return
        val connected = runCatching { p.connectedDevices.map { it.address }.toSet() }.getOrDefault(emptySet())
        for (e in entries.values) e.hidConnected = e.address in connected
        for (d in runCatching { p.connectedDevices }.getOrDefault(emptyList())) {
            entries.getOrPut(d.address) { Entry(d) }.hidConnected = true
        }
        listener.onDevicesChanged()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> dev?.let {
                    val e = entries.getOrPut(it.address) { Entry(it) }
                    e.rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    intent.getStringExtra(BluetoothDevice.EXTRA_NAME)?.let { n -> e.name = n }
                    listener.onDevicesChanged()
                }

                BluetoothDevice.ACTION_NAME_CHANGED -> dev?.let {
                    entries[it.address]?.name = safeName(it)
                    listener.onDevicesChanged()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> dev?.let {
                    val e = entries.getOrPut(it.address) { Entry(it) }
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            listener.onMessage("Paired: ${e.name}")
                            // The stack normally auto-connects HID after bonding; nudge it anyway.
                            main.postDelayed({ if (!e.hidConnected && hidProxy != null) connect(e) }, 1500)
                        }
                        BluetoothDevice.BOND_NONE -> if (prev == BluetoothDevice.BOND_BONDING) {
                            listener.onMessage("Pairing failed. Put the pad back in pairing mode and retry.")
                        }
                    }
                    listener.onDevicesChanged()
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> dev?.let { handlePairingRequest(it, intent) }

                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> refreshHidConnections()

                ACTION_HID_CONNECTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    dev?.let {
                        val e = entries.getOrPut(it.address) { Entry(it) }
                        e.hidConnected = st == BluetoothProfile.STATE_CONNECTED
                        if (st == BluetoothProfile.STATE_CONNECTED) listener.onMessage("Connected: ${e.name}")
                    }
                    listener.onDevicesChanged()
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> listener.onDiscoveryState(true)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> listener.onDiscoveryState(false)

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_ON) {
                        listener.onMessage("Bluetooth on")
                        connectHidProxy()
                        startDiscovery()
                    }
                }
            }
        }
    }

    /**
     * 8BitDo pads use Just Works (auto-accepted by the stack when we initiated the bond) or,
     * in some modes, a legacy PIN. Answer "0000" for PIN requests. Confirmation variants
     * need BLUETOOTH_PRIVILEGED, which a sideloaded app can't hold, so we only try.
     */
    private fun handlePairingRequest(dev: BluetoothDevice, intent: Intent) {
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        Log.i(TAG, "pairing request variant=$variant for ${dev.address}")
        try {
            when (variant) {
                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                    dev.setPin("0000".toByteArray())
                    abortIfOrdered()
                }
                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION, PAIRING_VARIANT_CONSENT -> {
                    dev.setPairingConfirmation(true)
                    abortIfOrdered()
                }
            }
        } catch (se: SecurityException) {
            // Expected for confirmation variants; the system dialog (if Wear has one) takes over.
            Log.i(TAG, "pairing confirmation needs privileged permission; leaving it to the system")
        } catch (t: Throwable) {
            Log.w(TAG, "pairing request handling failed", t)
        }
    }

    /** Only valid while inside onReceive (which is where it's called from). */
    private fun abortIfOrdered() {
        if (receiver.isOrderedBroadcast) receiver.abortBroadcast()
    }

    companion object {
        private const val TAG = "PadBridge"

        /** BluetoothProfile.HID_HOST (hidden in the API-28 SDK). */
        const val PROFILE_HID_HOST = 4

        /** BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED (hidden). */
        const val ACTION_HID_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"

        /** BluetoothDevice.PAIRING_VARIANT_CONSENT (hidden). */
        private const val PAIRING_VARIANT_CONSENT = 3

        fun safeName(d: BluetoothDevice): String = d.name?.takeIf { it.isNotBlank() } ?: d.address

        /** Major class PERIPHERAL (0x0500) with the joystick (0x04) or gamepad (0x08) minor bits. */
        fun isGamepadClass(c: BluetoothClass?): Boolean {
            if (c == null) return false
            if (c.majorDeviceClass != BluetoothClass.Device.Major.PERIPHERAL) return false
            val minor = c.deviceClass and 0x3C
            return minor == 0x04 || minor == 0x08
        }

        fun isGamepadName(n: String): Boolean {
            val s = n.lowercase()
            return listOf("8bitdo", "pro controller", "wireless controller", "xbox", "gamepad", "joy-con", "sn30")
                .any { it in s }
        }

        fun rootMessage(t: Throwable): String {
            var c: Throwable = t
            while (c.cause != null && c.cause !== c) c = c.cause!!
            return c.message ?: c.javaClass.simpleName
        }
    }
}
