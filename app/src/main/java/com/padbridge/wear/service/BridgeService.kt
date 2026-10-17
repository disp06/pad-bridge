package com.padbridge.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.padbridge.wear.R
import com.padbridge.wear.bt.RawHidLink
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.net.TcpTransport
import com.padbridge.wear.net.Transport
import com.padbridge.wear.net.UdpTransport
import com.padbridge.wear.net.WifiNetwork
import com.padbridge.wear.protocol.PadState
import com.padbridge.wear.protocol.Protocol
import com.padbridge.wear.ui.MainActivity
import com.padbridge.wear.util.Prefs
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Foreground service that owns the link to the companion server.
 *
 * Hot path: InputHub (main thread) -> [onPadState] -> post to a dedicated high-priority
 * network thread -> encode 28-byte STATE frame -> send. No coroutine dispatch, no batching,
 * no allocation beyond the frame itself: every change goes out immediately. The last state
 * of each pad is re-sent every keepalive interval so a lost UDP datagram can't leave a
 * button stuck (the server also releases a pad after 1 s of silence).
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "PadBridge"
        const val ACTION_START = "com.padbridge.wear.action.START"
        const val ACTION_STOP = "com.padbridge.wear.action.STOP"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val HELLO_INTERVAL_MS = 2000L

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var wifi: WifiNetwork
    private lateinit var netThread: HandlerThread
    private lateinit var net: Handler

    // Network-thread state.
    private var transport: Transport? = null
    private var network: Network? = null
    private var seq = 0
    private var sentThisSecond = 0
    private var host = ""
    private var port = Protocol.DEFAULT_PORT
    private var useTcp = false
    private var keepaliveMs = 100L

    private val lastStates = AtomicReferenceArray<PadState?>(InputHub.MAX_SLOTS)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationText = ""

    private val onPadState: (PadState) -> Unit = { s ->
        if (s.slot in 0 until InputHub.MAX_SLOTS) lastStates.set(s.slot, s)
        net.post { sendState(s) }
    }

    private val onStatusChanged: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wifi = WifiNetwork(this)
        netThread = HandlerThread("pb-net", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        net = Handler(netThread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                // startForegroundService() demands startForeground() every time, even if already running.
                if (running) startForeground(NOTIFICATION_ID, buildNotification(statusText()))
                else startBridge()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        netThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startBridge() {
        host = prefs.host
        port = prefs.port
        useTcp = prefs.transport == Prefs.Transport.TCP
        keepaliveMs = prefs.keepaliveMs.toLong().coerceIn(20, 1000)
        running = true

        BridgeStatus.target = "$host:$port ${prefs.transport.label}"
        BridgeStatus.phase = BridgeStatus.Phase.WAITING_WIFI
        BridgeStatus.detail = ""
        BridgeStatus.changed()
        startForeground(NOTIFICATION_ID, buildNotification(statusText()))
        BridgeStatus.listeners.add(onStatusChanged)

        if (host.isBlank()) {
            BridgeStatus.phase = BridgeStatus.Phase.RETRYING
            BridgeStatus.detail = "Set the server IP in Settings"
            BridgeStatus.changed()
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PadBridge:bridge").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PadBridge:wifi").apply {
            setReferenceCounted(false); acquire()
        }

        InputHub.stateListeners.add(onPadState)
        // Seed with pads that are already connected so the server sees them immediately.
        for (p in InputHub.pads()) lastStates.set(p.slot, p.tracker.snapshot())

        RawHidLink.rawSink = { report, len -> net.post { sendRaw(report, len) } }
        if (prefs.linkMode == Prefs.LinkMode.RAW && prefs.rawPadAddress.isNotEmpty() && !RawHidLink.isActive) {
            runCatching {
                BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(prefs.rawPadAddress)?.let(RawHidLink::connect)
            }
        }

        if (host.isBlank()) return
        wifi.acquire(15_000, { n ->
            if (!running) return@acquire
            if (n == null) {
                BridgeStatus.detail = "No Wi-Fi, trying default route"
                BridgeStatus.changed()
            } else {
                BridgeStatus.watchIp = wifi.localAddress(n) ?: ""
            }
            net.post { network = n; connect() }
        }, onLost = {
            if (running) {
                BridgeStatus.detail = "Wi-Fi lost"
                BridgeStatus.changed()
                net.post { network = null; scheduleReconnect() }
            }
        })
        net.postDelayed(keepaliveTick, keepaliveMs)
        net.postDelayed(helloTick, HELLO_INTERVAL_MS)
        net.postDelayed(statsTick, 1000)
    }

    private fun shutdown() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        InputHub.stateListeners.remove(onPadState)
        BridgeStatus.listeners.remove(onStatusChanged)
        RawHidLink.rawSink = null
        net.removeCallbacksAndMessages(null)
        net.post {
            transport?.let { t -> runCatching { t.send(Protocol.encodeBye(nextSeq())) }; t.close() }
            transport = null
        }
        wifi.release()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        BridgeStatus.reset()
        stopForeground(true)
        stopSelf()
    }

    // ------------------------------------------------------------------ network thread

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFFFF
        return seq
    }

    private fun now32(): Long = SystemClock.uptimeMillis() and 0xFFFFFFFFL

    private fun connect() {
        if (!running) return
        transport?.close()
        transport = null
        BridgeStatus.phase = BridgeStatus.Phase.CONNECTING
        BridgeStatus.changed()
        val t: Transport = if (useTcp) TcpTransport(host, port, network) else UdpTransport(host, port, network)
        try {
            t.open()
            transport = t
            t.startReader(::onFrame) { err ->
                Log.w(TAG, "reader error", err)
                net.post { if (transport === t) failAndRetry(err) }
            }
            BridgeStatus.phase = BridgeStatus.Phase.RUNNING
            BridgeStatus.detail = if (network == null) "via default route" else ""
            BridgeStatus.changed()
            sendHello()
            for (i in 0 until lastStates.length()) lastStates.get(i)?.let { sendState(it) }
        } catch (e: Throwable) {
            t.close()
            failAndRetry(e)
        }
    }

    private fun failAndRetry(e: Throwable) {
        transport?.close()
        transport = null
        BridgeStatus.phase = BridgeStatus.Phase.RETRYING
        BridgeStatus.detail = e.message ?: e.javaClass.simpleName
        BridgeStatus.lastAckAt = 0
        BridgeStatus.changed()
        scheduleReconnect()
    }

    private val reconnectRunnable = Runnable { connect() }

    private fun scheduleReconnect() {
        net.removeCallbacks(reconnectRunnable)
        if (running) net.postDelayed(reconnectRunnable, 2000)
    }

    private fun send(frame: ByteArray) {
        val t = transport ?: return
        try {
            t.send(frame)
            sentThisSecond++
        } catch (e: Throwable) {
            // UDP: transient (e.g. ECONNREFUSED after ICMP). TCP: the link is gone.
            if (useTcp) failAndRetry(e)
        }
    }

    private fun sendState(s: PadState) = send(Protocol.encodeState(s, nextSeq(), now32()))

    private fun sendRaw(report: ByteArray, len: Int) {
        send(Protocol.encodeRaw(nextSeq(), InputHub.rawSlot.coerceAtLeast(0), report, len))
    }

    private fun sendHello() = send(Protocol.encodeHello(nextSeq(), now32(), Build.MODEL ?: "watch"))

    private val keepaliveTick = object : Runnable {
        override fun run() {
            if (!running) return
            for (i in 0 until lastStates.length()) {
                val s = lastStates.get(i) ?: continue
                sendState(s)
                // A disconnected pad is announced once, then forgotten.
                if (!s.connected) lastStates.compareAndSet(i, s, null)
            }
            net.postDelayed(this, keepaliveMs)
        }
    }

    private val helloTick = object : Runnable {
        override fun run() {
            if (!running) return
            sendHello()
            if (!BridgeStatus.serverOnline) BridgeStatus.changed()
            net.postDelayed(this, HELLO_INTERVAL_MS)
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running) return
            BridgeStatus.packetsPerSec = sentThisSecond
            sentThisSecond = 0
            BridgeStatus.changed()
            net.postDelayed(this, 1000)
        }
    }

    /** Reader thread. */
    private fun onFrame(f: Protocol.Frame) {
        when (f.type) {
            Protocol.T_ACK -> Protocol.decodeAck(f)?.let { a ->
                BridgeStatus.rttMs = ((now32() - a.echoedTimeMs) and 0xFFFFFFFFL).toInt().coerceAtMost(9999)
                BridgeStatus.lastAckAt = SystemClock.uptimeMillis()
                BridgeStatus.serverName = a.serverName
                BridgeStatus.changed()
            }
            Protocol.T_RUMBLE -> Protocol.decodeRumble(f)?.let(::rumble)
        }
    }

    private fun rumble(r: Protocol.Rumble) {
        if (!prefs.rumble) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val strength = maxOf(r.large, r.small)
        if (strength == 0) {
            v.cancel()
            return
        }
        val amp = if (v.hasAmplitudeControl()) strength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(600, amp))
    }

    // ------------------------------------------------------------------ notification

    private fun statusText(): String {
        val s = BridgeStatus
        val server = if (s.serverOnline) "online ${s.rttMs} ms" else s.phase.label.lowercase()
        val pads = InputHub.pads().size
        return "${s.target} · $server · $pads pad${if (pads == 1) "" else "s"}"
    }

    private fun updateNotification() {
        if (!running) return
        val text = statusText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_bridge), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }
}
