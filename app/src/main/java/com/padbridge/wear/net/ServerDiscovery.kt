package com.padbridge.wear.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.padbridge.wear.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds PadBridge servers on the LAN: brings Wi-Fi up, broadcasts a DISCOVER frame on the
 * default port and collects replies for 1.5 s. Saves typing an IP on a watch keyboard.
 */
class ServerDiscovery(context: Context) {
    data class Found(val host: String, val port: Int, val name: String)

    private val wifi = WifiNetwork(context)
    private val main = Handler(Looper.getMainLooper())

    fun find(port: Int, onDone: (List<Found>, String?) -> Unit) {
        wifi.acquire(12_000, { network ->
            if (network == null) {
                wifi.release()
                onDone(emptyList(), "Wi-Fi didn't come up. Is it set up on the watch?")
                return@acquire
            }
            val bcast = wifi.broadcastAddress(network)
            Thread({
                val found = LinkedHashMap<String, Found>()
                var error: String? = null
                try {
                    DatagramSocket().use { s ->
                        network.bindSocket(s)
                        s.broadcast = true
                        s.soTimeout = 250
                        val frame = Protocol.encodeDiscover(0)
                        val targets = listOfNotNull(bcast, InetAddress.getByName("255.255.255.255")).distinct()
                        val buf = ByteArray(Protocol.MAX_FRAME)
                        val end = System.currentTimeMillis() + 1500
                        var nextSend = 0L
                        while (System.currentTimeMillis() < end) {
                            if (System.currentTimeMillis() >= nextSend) {
                                for (t in targets) runCatching { s.send(DatagramPacket(frame, frame.size, t, port)) }
                                nextSend = System.currentTimeMillis() + 500
                            }
                            val p = DatagramPacket(buf, buf.size)
                            try {
                                s.receive(p)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            val f = Protocol.parse(buf, p.length) ?: continue
                            val r = Protocol.decodeDiscoverReply(f) ?: continue
                            val host = p.address.hostAddress ?: continue
                            found[host] = Found(host, r.port, r.serverName)
                        }
                    }
                } catch (t: Throwable) {
                    error = t.message ?: t.javaClass.simpleName
                }
                main.post {
                    wifi.release()
                    onDone(found.values.toList(), error ?: if (found.isEmpty()) "No server answered. Is it running?" else null)
                }
            }, "pb-discover").start()
        })
    }
}
