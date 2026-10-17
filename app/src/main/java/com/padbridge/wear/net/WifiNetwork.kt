package com.padbridge.wear.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Wear OS 2 keeps Wi-Fi off while the phone is reachable over Bluetooth, and routes app traffic
 * through the phone proxy. That path can't reach a PC on your LAN and adds latency. The
 * documented fix is to request a Wi-Fi network explicitly and bind our sockets to it. Holding
 * the request also keeps Wi-Fi up for as long as the bridge runs.
 *
 * [onResult] is called once on the main thread: with the Wi-Fi network, or null on timeout.
 */
class WifiNetwork(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile var network: Network? = null
        private set

    fun acquire(timeoutMs: Long, onResult: (Network?) -> Unit, onLost: () -> Unit = {}) {
        release()
        var delivered = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                main.post {
                    if (!delivered) { delivered = true; onResult(n) }
                }
            }

            override fun onLost(n: Network) {
                if (n == network) {
                    network = null
                    main.post(onLost)
                }
            }

            override fun onUnavailable() {
                main.post {
                    if (!delivered) { delivered = true; onResult(null) }
                }
            }
        }
        callback = cb
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // A home LAN without internet access is fine for us.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(req, cb, timeoutMs.toInt())
        } catch (t: Throwable) {
            callback = null
            main.post { if (!delivered) { delivered = true; onResult(null) } }
        }
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    /** Subnet broadcast address of the network (e.g. 192.168.1.255), if it has IPv4. */
    fun broadcastAddress(n: Network?): InetAddress? {
        val lp: LinkProperties = (n ?: return null).let { cm.getLinkProperties(it) } ?: return null
        for (la in lp.linkAddresses) {
            val a = la.address as? Inet4Address ?: continue
            val prefix = la.prefixLength
            val ip = a.address
            val v = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            val b = v or mask.inv()
            return InetAddress.getByAddress(
                byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte())
            )
        }
        return null
    }

    /** The watch's own IPv4 address on that network, for display. */
    fun localAddress(n: Network?): String? {
        val lp = (n ?: return null).let { cm.getLinkProperties(it) } ?: return null
        return lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    }
}
