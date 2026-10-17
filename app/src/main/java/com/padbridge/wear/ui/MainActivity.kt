package com.padbridge.wear.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.padbridge.wear.R
import com.padbridge.wear.bt.RawHidLink
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.service.BridgeService
import com.padbridge.wear.service.BridgeStatus

/** View 1: status (gamepad + server), live pad, start/stop, entry to the other screens. */
class MainActivity : GamepadActivity() {

    private lateinit var tvPad: TextView
    private lateinit var tvServer: TextView
    private lateinit var btnBridge: Button

    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvPad = findViewById(R.id.tvPad)
        tvServer = findViewById(R.id.tvServer)
        btnBridge = findViewById(R.id.btnBridge)

        btnBridge.setOnClickListener {
            if (BridgeService.running) BridgeService.stop(this)
            else if (prefs.host.isBlank()) startActivity(Intent(this, SettingsActivity::class.java))
            else BridgeService.start(this)
            // Service state flips asynchronously; status listener re-renders.
            btnBridge.postDelayed({ render() }, 300)
        }
        findViewById<Button>(R.id.btnPair).setOnClickListener { startActivity(Intent(this, PairActivity::class.java)) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnInputTest).setOnClickListener { startActivity(Intent(this, InputTestActivity::class.java)) }
        findViewById<android.view.View>(R.id.scroll).requestFocus() // rotary crown scrolling
    }

    override fun onResume() {
        super.onResume()
        InputHub.padsListeners.add(refresh)
        BridgeStatus.listeners.add(refresh)
        RawHidLink.listeners.add(refresh)
        render()
    }

    override fun onPause() {
        InputHub.padsListeners.remove(refresh)
        BridgeStatus.listeners.remove(refresh)
        RawHidLink.listeners.remove(refresh)
        super.onPause()
    }

    private fun render() {
        val pads = InputHub.pads()
        tvPad.text = when {
            pads.isNotEmpty() -> pads.joinToString("\n") { "🎮 ${it.name}" + if (it.isRaw) " (raw)" else "" }
            RawHidLink.isActive -> "Raw link: ${RawHidLink.state.name.lowercase()} ${RawHidLink.detail}".trim()
            else -> getString(R.string.no_pad)
        }
        tvPad.setTextColor(getColor(if (pads.isNotEmpty()) R.color.ok else R.color.text_secondary))

        val s = BridgeStatus
        tvServer.text = when {
            !BridgeService.running && prefs.host.isBlank() -> getString(R.string.server_not_set)
            !BridgeService.running -> "Server: ${prefs.host}:${prefs.port} ${prefs.transport.label}"
            s.serverOnline -> "${s.serverName.ifEmpty { prefs.host }} · ${s.rttMs} ms · ${s.packetsPerSec} pkt/s"
            else -> listOf(s.phase.label, s.detail).filter { it.isNotEmpty() }.joinToString(": ")
        }
        tvServer.setTextColor(
            getColor(
                when {
                    !BridgeService.running -> R.color.text_secondary
                    s.serverOnline -> R.color.ok
                    else -> R.color.warn
                }
            )
        )

        btnBridge.setText(if (BridgeService.running) R.string.btn_stop else R.string.btn_start)
        btnBridge.setBackgroundResource(if (BridgeService.running) R.drawable.btn_pill_stop else R.drawable.btn_pill_primary)
        applyScreenMode()
    }
}
