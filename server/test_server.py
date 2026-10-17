"""
Server tests. No watch, no driver needed:  python -m unittest test_server -v

Golden vectors were produced by the watch's Kotlin encoder (app/.../protocol/Protocol.kt) and
are also asserted in the app's unit tests, so both sides are pinned to the same bytes.
"""

import struct
import sys
import types
import unittest

import padbridge_server as pb

GOLDEN_STATE = bytes.fromhex("50420101feff020101820000018039300100ffffff7f000078563412")
GOLDEN_HELLO = bytes.fromhex("504201020700e80300000e54696357617463682050726f2033")


class ProtocolTest(unittest.TestCase):
    def test_state_golden(self):
        ftype, seq, body = pb.parse_header(GOLDEN_STATE)
        self.assertEqual((ftype, seq), (pb.T_STATE, 0xFFFE))
        s = pb.decode_state(body)
        self.assertEqual(s.slot, 2)
        self.assertTrue(s.connected)
        self.assertEqual(s.pressed(), ["A", "START", "LEFT"])
        self.assertEqual((s.lx, s.ly, s.rx, s.ry, s.lt, s.rt), (-32767, 12345, 1, -1, 32767, 0))
        self.assertEqual(s.t, 0x12345678)
        self.assertEqual(len(GOLDEN_STATE), pb.STATE_SIZE)

    def test_state_encode_matches_kotlin(self):
        s = pb.State(2, True, pb.BIT["A"] | pb.BIT["START"] | pb.BIT["LEFT"], -32767, 12345, 1, -1, 32767, 0, 0x12345678)
        self.assertEqual(pb.encode_state(s, 0xFFFE), GOLDEN_STATE)

    def test_hello_golden(self):
        ftype, seq, body = pb.parse_header(GOLDEN_HELLO)
        self.assertEqual((ftype, seq), (pb.T_HELLO, 7))
        (t,) = struct.unpack_from("<I", body)
        self.assertEqual(t, 1000)
        self.assertEqual(pb.read_name(body[4:]), "TicWatch Pro 3")

    def test_rejects_garbage(self):
        self.assertIsNone(pb.parse_header(b"XX\x01\x01\x00\x00"))
        self.assertIsNone(pb.parse_header(b"PB\x02\x01\x00\x00"))  # future version
        self.assertIsNone(pb.parse_header(b"PB"))

    def test_seq_wraparound(self):
        self.assertTrue(pb.is_newer(1, 0xFFFF))
        self.assertFalse(pb.is_newer(0xFFFF, 1))
        self.assertFalse(pb.is_newer(5, 5))


class FakeVg:
    """Minimal stand-in for the vgamepad module that records calls."""

    class XUSB_BUTTON:
        XUSB_GAMEPAD_DPAD_UP, XUSB_GAMEPAD_DPAD_DOWN, XUSB_GAMEPAD_DPAD_LEFT, XUSB_GAMEPAD_DPAD_RIGHT = 1, 2, 4, 8
        XUSB_GAMEPAD_START, XUSB_GAMEPAD_BACK, XUSB_GAMEPAD_LEFT_THUMB, XUSB_GAMEPAD_RIGHT_THUMB = 0x10, 0x20, 0x40, 0x80
        XUSB_GAMEPAD_LEFT_SHOULDER, XUSB_GAMEPAD_RIGHT_SHOULDER, XUSB_GAMEPAD_GUIDE = 0x100, 0x200, 0x400
        XUSB_GAMEPAD_A, XUSB_GAMEPAD_B, XUSB_GAMEPAD_X, XUSB_GAMEPAD_Y = 0x1000, 0x2000, 0x4000, 0x8000

    instances = []

    class VX360Gamepad:
        def __init__(self):
            self.buttons = 0
            self.left = self.right = (0, 0)
            self.lt = self.rt = 0
            self.updates = 0
            self.callback = None
            FakeVg.instances.append(self)

        def press_button(self, button): self.buttons |= button
        def release_button(self, button): self.buttons &= ~button
        def left_joystick(self, x_value, y_value): self.left = (x_value, y_value)
        def right_joystick(self, x_value, y_value): self.right = (x_value, y_value)
        def left_trigger(self, value): self.lt = value
        def right_trigger(self, value): self.rt = value
        def update(self): self.updates += 1
        def register_notification(self, callback_function): self.callback = callback_function


class ServerLogicTest(unittest.TestCase):
    def setUp(self):
        FakeVg.instances.clear()
        sys.modules["vgamepad"] = types.SimpleNamespace(VX360Gamepad=FakeVg.VX360Gamepad, XUSB_BUTTON=FakeVg.XUSB_BUTTON)
        self.srv = pb.PadBridgeServer(0, "test", use_vgamepad=True, show_raw=False, quiet=True)
        self.srv.log = lambda msg: None
        self.sent = []
        self.client = self.srv.client_for("udp:1.2.3.4:5", self.sent.append)

    def tearDown(self):
        self.srv.udp.close()
        sys.modules.pop("vgamepad", None)

    def feed(self, seq, **kw):
        s = pb.State(slot=0, connected=True, **kw)
        self.srv.handle(self.client, pb.encode_state(s, seq))

    def test_state_drives_virtual_pad_with_y_flipped(self):
        self.feed(1, buttons=pb.BIT["A"] | pb.BIT["L1"], lx=1000, ly=-32767, lt=32767)
        pad = FakeVg.instances[0]
        self.assertEqual(pad.buttons, 0x1000 | 0x100)
        self.assertEqual(pad.left, (1000, 32767))  # Android up (-) -> XInput up (+)
        self.assertEqual(pad.lt, 255)
        self.feed(2, buttons=0)
        self.assertEqual(pad.buttons, 0)

    def test_stale_datagram_ignored(self):
        self.feed(10, buttons=pb.BIT["B"])
        self.feed(9, buttons=0)  # arrived late
        self.assertEqual(FakeVg.instances[0].buttons, 0x2000)

    def test_hello_gets_ack_with_echo(self):
        hello = pb.HEADER.pack(pb.MAGIC, 1, pb.T_HELLO, 1) + struct.pack("<I", 4242) + b"\x01w"
        self.srv.handle(self.client, hello)
        ftype, _, body = pb.parse_header(self.sent[-1])
        self.assertEqual(ftype, pb.T_ACK)
        self.assertEqual(struct.unpack_from("<I", body)[0], 4242)
        self.assertEqual(pb.read_name(body[4:]), "test")

    def test_disconnect_removes_virtual_pad_and_bye_drops_client(self):
        self.feed(1, buttons=pb.BIT["A"])
        self.srv.handle(self.client, pb.encode_state(pb.State(slot=0, connected=False), 2))
        self.assertIsNone(self.srv.pads[("udp:1.2.3.4:5", 0)].virtual)
        self.srv.handle(self.client, pb.HEADER.pack(pb.MAGIC, 1, pb.T_BYE, 3))
        self.assertNotIn("udp:1.2.3.4:5", self.srv.clients)

    def test_rumble_forwarded_to_watch(self):
        self.feed(1)
        cb = FakeVg.instances[0].callback
        self.assertIsNotNone(cb)
        cb(None, None, 200, 50, 0, None)
        ftype, _, body = pb.parse_header(self.sent[-1])
        self.assertEqual(ftype, pb.T_RUMBLE)
        self.assertEqual(tuple(body[:3]), (0, 200, 50))

    def test_discover_reply(self):
        replies = []
        probe = pb.HEADER.pack(pb.MAGIC, 1, pb.T_DISCOVER, 0)
        self.srv.handle(self.client, probe, reply_raw_udp=replies.append)
        ftype, _, body = pb.parse_header(replies[0])
        self.assertEqual(ftype, pb.T_DISCOVER_REPLY)
        self.assertEqual(pb.read_name(body[2:]), "test")


if __name__ == "__main__":
    unittest.main()
