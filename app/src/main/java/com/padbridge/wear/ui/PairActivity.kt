package com.padbridge.wear.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.padbridge.wear.R
import com.padbridge.wear.bt.BtManager
import com.padbridge.wear.bt.RawHidLink
import com.padbridge.wear.util.Prefs

/**
 * View 2: in-app Bluetooth discovery and pairing (Wear OS 2 has no pairing UI for HID devices).
 * Shows whether the watch has a HID host profile, which decides Android-HID vs raw-L2CAP.
 */
class PairActivity : GamepadActivity(), BtManager.Listener {

    private lateinit var bt: BtManager
    private lateinit var adapter: Adapter
    private var scanning = false
    private var message = ""
    /** Device we're bonding with in order to open a raw link once bonded. */
    private var pendingRaw: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)
        bt = BtManager(this, this)
        adapter = Adapter()
        val list = findViewById<WearableRecyclerView>(R.id.list)
        list.isEdgeItemsCenteringEnabled = false
        list.layoutManager = WearableLinearLayoutManager(this)
        list.adapter = adapter
        list.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        RawHidLink.listeners.add(rawListener)
        bt.start()
        if (hasLocationPermission()) maybeScan()
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
    }

    override fun onPause() {
        RawHidLink.listeners.remove(rawListener)
        bt.stop()
        scanning = false
        super.onPause()
    }

    private val rawListener: () -> Unit = { adapter.notifyItemChanged(0) }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) maybeScan()
        else onMessage(getString(R.string.perm_location))
    }

    private fun maybeScan() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) onMessage(getString(R.string.location_off))
        if (bt.adapter?.isEnabled == true) bt.startDiscovery()
    }

    private fun effectiveMode(): Prefs.LinkMode = when (prefs.linkMode) {
        Prefs.LinkMode.AUTO -> if (bt.hidHost == BtManager.HidHostState.UNAVAILABLE) Prefs.LinkMode.RAW else Prefs.LinkMode.HID
        else -> prefs.linkMode
    }

    private fun onDeviceTapped(e: BtManager.Entry) {
        val mode = effectiveMode()
        if (!e.bonded) {
            if (mode == Prefs.LinkMode.RAW) pendingRaw = e.address
            bt.pair(e)
            return
        }
        if (mode == Prefs.LinkMode.RAW) startRaw(e) else bt.connect(e)
    }

    private fun startRaw(e: BtManager.Entry) {
        bt.cancelDiscovery()
        prefs.rawPadAddress = e.address
        RawHidLink.connect(e.device)
        onMessage("Opening raw link to ${e.name}…")
    }

    private fun onDeviceLongPressed(e: BtManager.Entry) {
        if (prefs.rawPadAddress == e.address) {
            RawHidLink.disconnect()
            prefs.rawPadAddress = ""
        }
        if (e.bonded) bt.unpair(e)
    }

    // ------------------------------------------------------------------ BtManager.Listener

    override fun onDevicesChanged() {
        pendingRaw?.let { addr ->
            bt.devices().firstOrNull { it.address == addr && it.bonded }?.let {
                pendingRaw = null
                startRaw(it)
            }
        }
        adapter.submit(bt.devices())
    }

    override fun onDiscoveryState(running: Boolean) {
        scanning = running
        adapter.notifyItemChanged(0)
    }

    override fun onHidHostState(state: BtManager.HidHostState) = adapter.notifyItemChanged(0)

    override fun onMessage(text: String) {
        message = text
        adapter.notifyItemChanged(0)
    }

    // ------------------------------------------------------------------ list

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val hid: TextView = v.findViewById(R.id.tvHid)
        val raw: TextView = v.findViewById(R.id.tvRaw)
        val msg: TextView = v.findViewById(R.id.tvMessage)
        val scan: Button = v.findViewById(R.id.btnScan)
    }

    private class DeviceHolder(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.row)
        val name: TextView = v.findViewById(R.id.tvName)
        val sub: TextView = v.findViewById(R.id.tvSub)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<BtManager.Entry> = emptyList()

        fun submit(list: List<BtManager.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(inf.inflate(R.layout.item_pair_header, parent, false))
            else DeviceHolder(inf.inflate(R.layout.item_device, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderHolder) bindHeader(holder) else bindDevice(holder as DeviceHolder, items[position - 1])
        }

        private fun bindHeader(h: HeaderHolder) {
            h.hid.setText(
                when (bt.hidHost) {
                    BtManager.HidHostState.CHECKING -> R.string.hid_checking
                    BtManager.HidHostState.AVAILABLE -> R.string.hid_available
                    BtManager.HidHostState.UNAVAILABLE -> R.string.hid_unavailable
                }
            )
            h.hid.setTextColor(
                getColor(
                    when (bt.hidHost) {
                        BtManager.HidHostState.AVAILABLE -> R.color.ok
                        BtManager.HidHostState.UNAVAILABLE -> R.color.warn
                        else -> R.color.text_secondary
                    }
                )
            )
            val showRaw = RawHidLink.isActive || effectiveMode() == Prefs.LinkMode.RAW
            h.raw.visibility = if (showRaw) View.VISIBLE else View.GONE
            if (showRaw) {
                val st = if (RawHidLink.isActive) "${RawHidLink.state.name.lowercase()} ${RawHidLink.detail}" else "idle"
                h.raw.text = getString(R.string.raw_link, st.trim())
            }
            h.msg.text = message
            h.msg.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            h.scan.setText(if (scanning) R.string.btn_scanning else R.string.btn_scan)
            h.scan.setOnClickListener {
                if (!hasLocationPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
                } else if (scanning) bt.cancelDiscovery() else bt.startDiscovery()
            }
        }

        private fun bindDevice(h: DeviceHolder, e: BtManager.Entry) {
            h.name.text = e.name
            val tags = ArrayList<String>()
            when {
                e.hidConnected -> tags += getString(R.string.tag_connected)
                prefs.rawPadAddress == e.address && RawHidLink.state == RawHidLink.State.CONNECTED -> tags += "raw link"
                e.bonding -> tags += getString(R.string.tag_pairing)
                e.bonded -> tags += getString(R.string.tag_paired)
            }
            if (e.looksLikeGamepad) tags += getString(R.string.tag_gamepad)
            if (e.rssi != Short.MIN_VALUE) tags += "${e.rssi} dBm"
            h.sub.text = (tags + e.address).joinToString(" · ")
            h.name.setTextColor(getColor(if (e.hidConnected) R.color.ok else if (e.looksLikeGamepad) R.color.accent else R.color.text_primary))
            h.row.setOnClickListener { onDeviceTapped(e) }
            h.row.setOnLongClickListener { onDeviceLongPressed(e); true }
        }
    }

    companion object {
        private const val REQ_LOCATION = 1
    }
}
