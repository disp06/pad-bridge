package com.padbridge.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same golden bytes are asserted by server/test_server.py, pinning both ends of the wire. */
class ProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val sample = PadState(2, true, Buttons.A or Buttons.START or Buttons.DPAD_LEFT, -32767, 12345, 1, -1, 32767, 0)

    @Test fun stateGolden() {
        assertEquals(
            "50420101feff020101820000018039300100ffffff7f000078563412",
            hex(Protocol.encodeState(sample, 0xFFFE, 0x12345678L)),
        )
    }

    @Test fun stateSizeIs28() = assertEquals(Protocol.STATE_SIZE, Protocol.encodeState(sample, 0, 0).size)

    @Test fun helloGolden() {
        assertEquals("504201020700e80300000e54696357617463682050726f2033", hex(Protocol.encodeHello(7, 1000, "TicWatch Pro 3")))
    }

    @Test fun stateRoundTrip() {
        val f = Protocol.parse(Protocol.encodeState(sample, 5, 0))!!
        assertEquals(Protocol.T_STATE, f.type)
        assertEquals(5, f.seq)
        assertEquals(sample, Protocol.decodeState(f))
    }

    @Test fun clampsOutOfRangeAxes() {
        val s = Protocol.decodeState(Protocol.parse(Protocol.encodeState(PadState(0, true, 0, lx = 99999, ly = -99999), 1, 0))!!)!!
        assertEquals(32767, s.lx)
        assertEquals(-32767, s.ly)
    }

    @Test fun decodesServerFrames() {
        // ACK: echoed 4242, name "pc"
        val ack = Protocol.decodeAck(Protocol.parse(bytes("50420103010092100000027063"))!!)!!
        assertEquals(4242L, ack.echoedTimeMs)
        assertEquals("pc", ack.serverName)
        // DISCOVER_REPLY: port 7777, name "pc"
        val dr = Protocol.decodeDiscoverReply(Protocol.parse(bytes("504201110000611e027063"))!!)!!
        assertEquals(7777, dr.port)
        // RUMBLE slot 0, 200, 50
        val r = Protocol.decodeRumble(Protocol.parse(bytes("50420106020000c832"))!!)!!
        assertEquals(Protocol.Rumble(0, 200, 50), r)
    }

    @Test fun rejectsGarbage() {
        assertNull(Protocol.parse(bytes("585801010000")))
        assertNull(Protocol.parse(bytes("504202010000"))) // future version
        assertNull(Protocol.parse(bytes("5042")))
    }

    @Test fun seqWraparound() {
        assertTrue(Protocol.isNewer(1, 0xFFFF))
        assertFalse(Protocol.isNewer(0xFFFF, 1))
        assertFalse(Protocol.isNewer(5, 5))
    }

    @Test fun tcpFramingPrefixesLength() {
        val w = TcpFraming.wrap(byteArrayOf(1, 2, 3))
        assertEquals("0300010203", hex(w))
    }

    @Test fun rawFrame() {
        assertEquals("50420105030001033f0102", hex(Protocol.encodeRaw(3, 1, byteArrayOf(0x3f, 1, 2))))
        assertNotNull(Protocol.parse(Protocol.encodeBye(1)))
    }
}
