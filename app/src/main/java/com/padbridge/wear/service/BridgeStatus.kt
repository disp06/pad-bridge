package com.padbridge.wear.service

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Live bridge status for the UI. Written from any thread, listeners called on the main thread. */
object BridgeStatus {
    enum class Phase(val label: String) {
        STOPPED("Stopped"),
        WAITING_WIFI("Waiting for Wi-Fi"),
        CONNECTING("Connecting"),
        RUNNING("Running"),
        RETRYING("Retrying"),
    }

    @Volatile var phase = Phase.STOPPED
    @Volatile var target = ""
    @Volatile var detail = ""
    @Volatile var serverName = ""
    @Volatile var lastAckAt = 0L
    @Volatile var rttMs = -1
    @Volatile var packetsPerSec = 0
    @Volatile var watchIp = ""

    val serverOnline get() = lastAckAt != 0L && android.os.SystemClock.uptimeMillis() - lastAckAt < 5000

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val notify = Runnable { listeners.forEach { it() } }

    fun changed() {
        main.removeCallbacks(notify)
        main.post(notify)
    }

    fun reset() {
        phase = Phase.STOPPED
        detail = ""
        serverName = ""
        lastAckAt = 0L
        rttMs = -1
        packetsPerSec = 0
        changed()
    }
}
