package com.padbridge.wear.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.padbridge.wear.R
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.net.ServerDiscovery
import com.padbridge.wear.protocol.Protocol
import com.padbridge.wear.service.BridgeService
import com.padbridge.wear.util.Prefs

/**
 * View 3: server address and tuning. Every control cycles through its values on tap, which is
 * kinder to a 1.4" screen than spinners. "Find server" saves typing an IP altogether.
 */
class SettingsActivity : GamepadActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var tvFind: TextView
    private lateinit var btnFind: Button
    private var bridgeWasRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        tvFind = findViewById(R.id.tvFind)
        btnFind = findViewById(R.id.btnFind)
        bridgeWasRunning = BridgeService.running

        etHost.setText(prefs.host)
        etPort.setText(prefs.port.toString())
        etHost.addTextChangedListener(watcher { prefs.host = it })
        etPort.addTextChangedListener(watcher { s -> s.toIntOrNull()?.takeIf { it in 1..65535 }?.let { prefs.port = it } })

        btnFind.setOnClickListener { findServer() }

        cycle(R.id.btnTransport, { prefs.transport.label }) { prefs.transport = prefs.transport.next() }
        cycle(R.id.btnKeepalive, { "${prefs.keepaliveMs} ms" }) {
            prefs.keepaliveMs = KEEPALIVES[(KEEPALIVES.indexOf(prefs.keepaliveMs) + 1) % KEEPALIVES.size]
        }
        cycle(R.id.btnLink, { prefs.linkMode.label }) { prefs.linkMode = prefs.linkMode.next() }
        cycle(R.id.btnRightStick, { prefs.rightStick.label }) { prefs.rightStick = prefs.rightStick.next() }
        cycle(R.id.btnDeadZone, { "${prefs.deadZonePct} %" }) {
            prefs.deadZonePct = DEAD_ZONES[(DEAD_ZONES.indexOf(prefs.deadZonePct) + 1) % DEAD_ZONES.size]
        }
        cycle(R.id.btnSwap, { getString(R.string.opt_swap_face, onOff(prefs.swapFace)) }) { prefs.swapFace = !prefs.swapFace }
        cycle(R.id.btnKeepOn, { getString(R.string.opt_keep_on, onOff(prefs.keepScreenOn)) }) { prefs.keepScreenOn = !prefs.keepScreenOn }
        cycle(R.id.btnDim, { getString(R.string.opt_dim, onOff(prefs.dimScreen)) }) { prefs.dimScreen = !prefs.dimScreen }
        cycle(R.id.btnRumble, { getString(R.string.opt_rumble, onOff(prefs.rumble)) }) { prefs.rumble = !prefs.rumble }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        findViewById<TextView>(R.id.tvVersion).text = "v$version · protocol v${Protocol.VERSION}"
        findViewById<View>(R.id.scroll).requestFocus()
    }

    override fun onPause() {
        InputHub.reloadSettings()
        super.onPause()
    }

    private fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)

    private fun cycle(id: Int, label: () -> String, action: () -> Unit) {
        val b = findViewById<Button>(id)
        b.text = label()
        b.setOnClickListener {
            action()
            b.text = label()
            noteRestart()
        }
    }

    private fun noteRestart() {
        if (bridgeWasRunning) {
            tvFind.visibility = View.VISIBLE
            tvFind.setText(R.string.saved_restart)
        }
    }

    private fun findServer() {
        btnFind.isEnabled = false
        btnFind.setText(R.string.btn_finding)
        tvFind.visibility = View.GONE
        val port = etPort.text.toString().toIntOrNull() ?: Protocol.DEFAULT_PORT
        ServerDiscovery(this).find(port) { found, error ->
            btnFind.isEnabled = true
            btnFind.setText(R.string.btn_find_server)
            tvFind.visibility = View.VISIBLE
            val first = found.firstOrNull()
            if (first != null) {
                etHost.setText(first.host)
                etPort.setText(first.port.toString())
                prefs.host = first.host
                prefs.port = first.port
                tvFind.text = "Found ${first.name} (${first.host})" +
                    if (found.size > 1) " +${found.size - 1} more" else ""
                tvFind.setTextColor(getColor(R.color.ok))
                noteRestart()
            } else {
                tvFind.text = error ?: "Nothing found"
                tvFind.setTextColor(getColor(R.color.warn))
            }
        }
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
    }

    companion object {
        private val KEEPALIVES = listOf(50, 100, 250, 500)
        private val DEAD_ZONES = listOf(0, 5, 10, 15, 20)
    }
}
