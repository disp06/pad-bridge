package com.padbridge.wear.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PadBridge wire protocol v1. Little-endian. One UDP datagram = one frame.
 * Over TCP every frame is prefixed with a u16 length (see [TcpFraming]).
 *
 * Header (6 bytes):  'P' 'B' | version u8 | type u8 | seq u16
 *
 * STATE  (type 1, 28 bytes total)
 *   slot u8 | flags u8 (bit0 = pad connected) | buttons u32 |
 *   lx i16 | ly i16 | rx i16 | ry i16 | lt i16 | rt i16 | watch time ms u32
 * HELLO  (type 2)  watch -> server, every 2 s:  time ms u32 | nameLen u8 | name utf8
 * ACK    (type 3)  server -> watch:             echoed time ms u32 | nameLen u8 | name utf8
 * BYE    (type 4)  watch -> server: release all pads from this watch. No body.
 * RAW    (type 5)  watch -> server: raw HID report from the L2CAP path: slot u8 | len u8 | bytes
 * RUMBLE (type 6)  server -> watch: slot u8 | large motor u8 | small motor u8
 * DISCOVER (0x10)  broadcast watch -> any server. No body.
 * DISCOVER_REPLY (0x11) server -> watch: port u16 | nameLen u8 | name utf8
 *
 * The full spec (and the reference decoder) lives in server/padbridge_server.py.
 */
object Protocol {
    const val MAGIC0: Byte = 'P'.code.toByte()
    const val MAGIC1: Byte = 'B'.code.toByte()
    const val VERSION: Int = 1
    const val DEFAULT_PORT = 7777

    const val T_STATE = 1
    const val T_HELLO = 2
    const val T_ACK = 3
    const val T_BYE = 4
    const val T_RAW = 5
    const val T_RUMBLE = 6
    const val T_DISCOVER = 0x10
    const val T_DISCOVER_REPLY = 0x11

    const val HEADER_SIZE = 6
    const val STATE_SIZE = 28
    const val MAX_FRAME = 512

    const val FLAG_CONNECTED = 0x01

    private fun header(buf: ByteBuffer, type: Int, seq: Int) {
        buf.put(MAGIC0).put(MAGIC1).put(VERSION.toByte()).put(type.toByte()).putShort(seq.toShort())
    }

    private fun alloc(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun clampShort(v: Int): Short = v.coerceIn(-32767, 32767).toShort()

    fun encodeState(s: PadState, seq: Int, timeMs: Long): ByteArray {
        val b = alloc(STATE_SIZE)
        header(b, T_STATE, seq)
        b.put(s.slot.toByte())
        b.put((if (s.connected) FLAG_CONNECTED else 0).toByte())
        b.putInt(s.buttons)
        b.putShort(clampShort(s.lx)).putShort(clampShort(s.ly))
        b.putShort(clampShort(s.rx)).putShort(clampShort(s.ry))
        b.putShort(clampShort(s.lt)).putShort(clampShort(s.rt))
        b.putInt(timeMs.toInt())
        return b.array()
    }

    private fun nameBytes(name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        return if (raw.size > 64) raw.copyOf(64) else raw
    }

    fun encodeHello(seq: Int, timeMs: Long, name: String): ByteArray {
        val n = nameBytes(name)
        val b = alloc(HEADER_SIZE + 4 + 1 + n.size)
        header(b, T_HELLO, seq)
        b.putInt(timeMs.toInt()).put(n.size.toByte()).put(n)
        return b.array()
    }

    fun encodeBye(seq: Int): ByteArray = alloc(HEADER_SIZE).also { header(it, T_BYE, seq) }.array()

    fun encodeDiscover(seq: Int): ByteArray = alloc(HEADER_SIZE).also { header(it, T_DISCOVER, seq) }.array()

    fun encodeRaw(seq: Int, slot: Int, report: ByteArray, len: Int = report.size): ByteArray {
        val n = len.coerceAtMost(255)
        val b = alloc(HEADER_SIZE + 2 + n)
        header(b, T_RAW, seq)
        b.put(slot.toByte()).put(n.toByte()).put(report, 0, n)
        return b.array()
    }

    // --- decoding (only what the watch receives, plus STATE for tests) ---

    class Frame(val type: Int, val seq: Int, val body: ByteBuffer)

    fun parse(data: ByteArray, len: Int = data.size): Frame? {
        if (len < HEADER_SIZE) return null
        if (data[0] != MAGIC0 || data[1] != MAGIC1) return null
        if (data[2].toInt() and 0xFF != VERSION) return null
        val b = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        b.position(3)
        val type = b.get().toInt() and 0xFF
        val seq = b.short.toInt() and 0xFFFF
        return Frame(type, seq, b.slice().order(ByteOrder.LITTLE_ENDIAN))
    }

    private fun readName(b: ByteBuffer): String {
        if (b.remaining() < 1) return ""
        val n = (b.get().toInt() and 0xFF).coerceAtMost(b.remaining())
        val arr = ByteArray(n)
        b.get(arr)
        return String(arr, Charsets.UTF_8)
    }

    data class Ack(val echoedTimeMs: Long, val serverName: String)
    data class DiscoverReply(val port: Int, val serverName: String)
    data class Rumble(val slot: Int, val large: Int, val small: Int)

    fun decodeAck(f: Frame): Ack? {
        if (f.type != T_ACK || f.body.remaining() < 4) return null
        val t = f.body.int.toLong() and 0xFFFFFFFFL
        return Ack(t, readName(f.body))
    }

    fun decodeDiscoverReply(f: Frame): DiscoverReply? {
        if (f.type != T_DISCOVER_REPLY || f.body.remaining() < 2) return null
        val port = f.body.short.toInt() and 0xFFFF
        return DiscoverReply(port, readName(f.body))
    }

    fun decodeRumble(f: Frame): Rumble? {
        if (f.type != T_RUMBLE || f.body.remaining() < 3) return null
        return Rumble(f.body.get().toInt() and 0xFF, f.body.get().toInt() and 0xFF, f.body.get().toInt() and 0xFF)
    }

    fun decodeState(f: Frame): PadState? {
        if (f.type != T_STATE || f.body.remaining() < STATE_SIZE - HEADER_SIZE) return null
        val b = f.body
        val slot = b.get().toInt() and 0xFF
        val flags = b.get().toInt() and 0xFF
        val buttons = b.int
        return PadState(
            slot, flags and FLAG_CONNECTED != 0, buttons,
            b.short.toInt(), b.short.toInt(), b.short.toInt(), b.short.toInt(), b.short.toInt(), b.short.toInt(),
        )
    }

    /** True if [seq] is newer than [last] in 16-bit wrap-around arithmetic. */
    fun isNewer(seq: Int, last: Int): Boolean {
        val d = (seq - last) and 0xFFFF
        return d in 1..0x7FFF
    }
}

/** TCP framing: u16 little-endian length + frame. */
object TcpFraming {
    fun wrap(frame: ByteArray): ByteArray {
        val out = ByteArray(frame.size + 2)
        out[0] = (frame.size and 0xFF).toByte()
        out[1] = ((frame.size shr 8) and 0xFF).toByte()
        System.arraycopy(frame, 0, out, 2, frame.size)
        return out
    }
}
