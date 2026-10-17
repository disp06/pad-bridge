package com.padbridge.wear.net

import android.net.Network
import com.padbridge.wear.protocol.Protocol
import com.padbridge.wear.protocol.TcpFraming
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Link to the companion server. [send] is called from the single network thread only.
 * [startReader] spawns a daemon thread that delivers incoming frames (ACK, RUMBLE).
 */
interface Transport {
    fun open()
    fun send(frame: ByteArray)
    fun startReader(onFrame: (Protocol.Frame) -> Unit, onError: (Throwable) -> Unit)
    fun close()
}

class UdpTransport(private val host: String, private val port: Int, private val network: Network?) : Transport {
    private var socket: DatagramSocket? = null
    private lateinit var target: InetAddress

    override fun open() {
        target = network?.getByName(host) ?: InetAddress.getByName(host)
        val s = DatagramSocket()
        network?.bindSocket(s)
        // Low-delay DSCP hint; many APs ignore it, it costs nothing.
        runCatching { s.trafficClass = 0x10 }
        s.connect(target, port)
        socket = s
    }

    override fun send(frame: ByteArray) {
        val s = socket ?: throw IOException("closed")
        s.send(DatagramPacket(frame, frame.size))
    }

    override fun startReader(onFrame: (Protocol.Frame) -> Unit, onError: (Throwable) -> Unit) {
        val s = socket ?: return
        Thread({
            val buf = ByteArray(Protocol.MAX_FRAME)
            val p = DatagramPacket(buf, buf.size)
            while (!s.isClosed) {
                try {
                    p.length = buf.size
                    s.receive(p)
                    Protocol.parse(buf, p.length)?.let(onFrame)
                } catch (t: Throwable) {
                    // ICMP port unreachable surfaces here as PortUnreachableException while the server is down.
                    if (s.isClosed) break
                    if (t is java.net.PortUnreachableException) { Thread.sleep(200); continue }
                    onError(t)
                    break
                }
            }
        }, "pb-udp-rx").apply { isDaemon = true }.start()
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}

class TcpTransport(private val host: String, private val port: Int, private val network: Network?) : Transport {
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null

    override fun open() {
        val addr = network?.getByName(host) ?: InetAddress.getByName(host)
        val s = network?.socketFactory?.createSocket() ?: Socket()
        s.tcpNoDelay = true
        runCatching { s.trafficClass = 0x10 }
        s.connect(InetSocketAddress(addr, port), 3000)
        socket = s
        out = BufferedOutputStream(s.getOutputStream(), 256)
    }

    override fun send(frame: ByteArray) {
        val o = out ?: throw IOException("closed")
        o.write(TcpFraming.wrap(frame))
        o.flush()
    }

    override fun startReader(onFrame: (Protocol.Frame) -> Unit, onError: (Throwable) -> Unit) {
        val s = socket ?: return
        Thread({
            try {
                val inp = DataInputStream(s.getInputStream())
                val buf = ByteArray(Protocol.MAX_FRAME)
                while (!s.isClosed) {
                    val lo = inp.readUnsignedByte()
                    val hi = inp.readUnsignedByte()
                    val len = lo or (hi shl 8)
                    if (len > buf.size) throw IOException("frame too large: $len")
                    inp.readFully(buf, 0, len)
                    Protocol.parse(buf, len)?.let(onFrame)
                }
            } catch (t: Throwable) {
                if (!s.isClosed) onError(t)
            }
        }, "pb-tcp-rx").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        out = null
    }
}
