#!/usr/bin/env python3
"""
PadBridge companion server.

Receives gamepad state from the Pad Bridge watch app (Wear OS) over UDP or TCP and turns
it into a virtual Xbox 360 controller (via `vgamepad`), or just prints it.

    pip install vgamepad          # optional; on Windows this also installs the ViGEmBus driver
    python padbridge_server.py    # listens on UDP+TCP 7777

Options: --port N, --no-vgamepad, --raw (print raw L2CAP HID reports), --quiet, --name NAME.

Wire protocol v1 (little-endian). One UDP datagram = one frame; over TCP each frame is
prefixed with a u16 length.

  header    'P' 'B' | version u8 (=1) | type u8 | seq u16
  STATE  1  slot u8 | flags u8 (bit0 connected) | buttons u32 |
            lx ly rx ry lt rt : i16 each | watch time ms u32          (28 bytes total)
            sticks -32767..32767 with Y NEGATIVE = UP (Android convention); triggers 0..32767
  HELLO  2  time ms u32 | nameLen u8 | name           watch -> server, every 2 s
  ACK    3  echoed time ms u32 | nameLen u8 | name     server -> watch (watch measures RTT)
  BYE    4  (empty)                                   watch stopped the bridge
  RAW    5  slot u8 | len u8 | report bytes           raw HID report (experimental L2CAP path)
  RUMBLE 6  slot u8 | large u8 | small u8              server -> watch, watch vibrates
  DISCOVER        0x10 (empty)                        broadcast by the watch
  DISCOVER_REPLY  0x11 port u16 | nameLen u8 | name    server -> watch

Button bits: see BUTTONS below (Xbox positional naming: A = bottom face button).
"""

from __future__ import annotations

import argparse
import socket
import socketserver
import struct
import sys
import threading
import time

VERSION = 1
DEFAULT_PORT = 7777
MAGIC = b"PB"

T_STATE, T_HELLO, T_ACK, T_BYE, T_RAW, T_RUMBLE = 1, 2, 3, 4, 5, 6
T_DISCOVER, T_DISCOVER_REPLY = 0x10, 0x11

HEADER = struct.Struct("<2sBBH")
STATE_BODY = struct.Struct("<BBI6hI")
STATE_SIZE = HEADER.size + STATE_BODY.size  # 28

BUTTONS = [
    "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "SELECT", "START",
    "L3", "R3", "HOME", "UP", "DOWN", "LEFT", "RIGHT", "C", "Z", "CAPTURE",
]
BIT = {name: 1 << i for i, name in enumerate(BUTTONS)}

PAD_TIMEOUT_S = 1.0      # release a pad that went silent (lost Wi-Fi, app killed)
RUMBLE_REPEAT_S = 0.4    # watch vibrations are one-shots; repeat while the game holds rumble on


# --------------------------------------------------------------------------- protocol

class State:
    __slots__ = ("slot", "connected", "buttons", "lx", "ly", "rx", "ry", "lt", "rt", "t")

    def __init__(self, slot=0, connected=False, buttons=0, lx=0, ly=0, rx=0, ry=0, lt=0, rt=0, t=0):
        self.slot, self.connected, self.buttons = slot, connected, buttons
        self.lx, self.ly, self.rx, self.ry, self.lt, self.rt, self.t = lx, ly, rx, ry, lt, rt, t

    def key(self):
        return (self.connected, self.buttons, self.lx, self.ly, self.rx, self.ry, self.lt, self.rt)

    def pressed(self):
        return [n for n in BUTTONS if self.buttons & BIT[n]]


def parse_header(data: bytes):
    if len(data) < HEADER.size:
        return None
    magic, ver, ftype, seq = HEADER.unpack_from(data)
    if magic != MAGIC or ver != VERSION:
        return None
    return ftype, seq, data[HEADER.size:]


def decode_state(body: bytes) -> State | None:
    if len(body) < STATE_BODY.size:
        return None
    slot, flags, buttons, lx, ly, rx, ry, lt, rt, t = STATE_BODY.unpack_from(body)
    return State(slot, bool(flags & 1), buttons, lx, ly, rx, ry, lt, rt, t)


def encode_state(s: State, seq: int) -> bytes:
    return HEADER.pack(MAGIC, VERSION, T_STATE, seq & 0xFFFF) + STATE_BODY.pack(
        s.slot, 1 if s.connected else 0, s.buttons & 0xFFFFFFFF,
        s.lx, s.ly, s.rx, s.ry, s.lt, s.rt, s.t & 0xFFFFFFFF)


def _name(name: str) -> bytes:
    raw = name.encode("utf-8")[:64]
    return bytes([len(raw)]) + raw


def read_name(b: bytes) -> str:
    if not b:
        return ""
    n = min(b[0], len(b) - 1)
    return b[1:1 + n].decode("utf-8", "replace")


def encode_ack(seq: int, echoed_ms: int, name: str) -> bytes:
    return HEADER.pack(MAGIC, VERSION, T_ACK, seq & 0xFFFF) + struct.pack("<I", echoed_ms & 0xFFFFFFFF) + _name(name)


def encode_discover_reply(seq: int, port: int, name: str) -> bytes:
    return HEADER.pack(MAGIC, VERSION, T_DISCOVER_REPLY, seq & 0xFFFF) + struct.pack("<H", port) + _name(name)


def encode_rumble(seq: int, slot: int, large: int, small: int) -> bytes:
    return HEADER.pack(MAGIC, VERSION, T_RUMBLE, seq & 0xFFFF) + bytes([slot & 0xFF, large & 0xFF, small & 0xFF])


def is_newer(seq: int, last: int) -> bool:
    d = (seq - last) & 0xFFFF
    return 0 < d < 0x8000


# --------------------------------------------------------------------------- sinks

class VirtualX360:
    """One virtual Xbox 360 pad per (watch, slot), via vgamepad (ViGEmBus on Windows, uinput on Linux)."""

    def __init__(self, vg, on_rumble):
        self.vg = vg
        self.pad = vg.VX360Gamepad()
        self.mask = 0
        self.on_rumble = on_rumble
        X = vg.XUSB_BUTTON
        self.map = {
            "A": X.XUSB_GAMEPAD_A, "B": X.XUSB_GAMEPAD_B, "X": X.XUSB_GAMEPAD_X, "Y": X.XUSB_GAMEPAD_Y,
            "L1": X.XUSB_GAMEPAD_LEFT_SHOULDER, "R1": X.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "SELECT": X.XUSB_GAMEPAD_BACK, "START": X.XUSB_GAMEPAD_START,
            "L3": X.XUSB_GAMEPAD_LEFT_THUMB, "R3": X.XUSB_GAMEPAD_RIGHT_THUMB, "HOME": X.XUSB_GAMEPAD_GUIDE,
            "UP": X.XUSB_GAMEPAD_DPAD_UP, "DOWN": X.XUSB_GAMEPAD_DPAD_DOWN,
            "LEFT": X.XUSB_GAMEPAD_DPAD_LEFT, "RIGHT": X.XUSB_GAMEPAD_DPAD_RIGHT,
        }
        try:
            self.pad.register_notification(callback_function=self._notify)
        except Exception:
            pass  # rumble is optional

    def _notify(self, client, target, large_motor, small_motor, led_number, user_data):
        self.on_rumble(large_motor, small_motor)

    def apply(self, s: State):
        changed = s.buttons ^ self.mask
        for name, xb in self.map.items():
            bit = BIT[name]
            if changed & bit:
                (self.pad.press_button if s.buttons & bit else self.pad.release_button)(button=xb)
        self.mask = s.buttons
        # XInput: Y positive = up. Watch sends Android convention (negative = up).
        self.pad.left_joystick(x_value=_c16(s.lx), y_value=_c16(-s.ly))
        self.pad.right_joystick(x_value=_c16(s.rx), y_value=_c16(-s.ry))
        self.pad.left_trigger(value=min(255, max(0, s.lt * 255 // 32767)))
        self.pad.right_trigger(value=min(255, max(0, s.rt * 255 // 32767)))
        self.pad.update()

    def release(self):
        self.apply(State())

    def close(self):
        try:
            self.release()
            del self.pad
        except Exception:
            pass


def _c16(v: int) -> int:
    return max(-32768, min(32767, int(v)))


# --------------------------------------------------------------------------- server

class Pad:
    def __init__(self, client: "Client", slot: int):
        self.client, self.slot = client, slot
        self.last_seq = None
        self.last_rx = time.monotonic()
        self.state = State(slot)
        self.virtual = None
        self.rumble = (0, 0)
        self.rumble_sent = 0.0
        self.rumble_pending = False


class Client:
    """A watch. Identified by its UDP address, or by its TCP connection."""

    def __init__(self, cid: str, send):
        self.cid, self.send = cid, send
        self.name = cid
        self.seq = 0
        self.last_hello = 0.0

    def next_seq(self):
        self.seq = (self.seq + 1) & 0xFFFF
        return self.seq


class PadBridgeServer:
    def __init__(self, port: int, name: str, use_vgamepad: bool, show_raw: bool, quiet: bool):
        self.port, self.name, self.show_raw, self.quiet = port, name, show_raw, quiet
        self.lock = threading.RLock()
        self.clients: dict[str, Client] = {}
        self.pads: dict[tuple[str, int], Pad] = {}
        self.vg = None
        self.stats_rx = 0
        if use_vgamepad:
            try:
                import vgamepad  # noqa: F401
                self.vg = vgamepad
            except Exception as e:  # ImportError, or the driver missing on Windows
                print(f"[!] vgamepad unavailable ({e}). Running in print-only mode. "
                      f"Install with: pip install vgamepad", file=sys.stderr)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp.bind(("0.0.0.0", port))

    # ---- frame handling (any thread)

    def client_for(self, cid: str, send) -> Client:
        with self.lock:
            c = self.clients.get(cid)
            if c is None:
                c = self.clients[cid] = Client(cid, send)
            return c

    def handle(self, c: Client, data: bytes, reply_raw_udp=None):
        h = parse_header(data)
        if h is None:
            return
        ftype, seq, body = h
        self.stats_rx += 1
        if ftype == T_DISCOVER:
            if reply_raw_udp:
                reply_raw_udp(encode_discover_reply(0, self.port, self.name))
            return
        if ftype == T_HELLO:
            if len(body) >= 4:
                (t,) = struct.unpack_from("<I", body)
                new_name = read_name(body[4:]) or c.cid
                if new_name != c.name or not c.last_hello:
                    self.log(f"[+] watch '{new_name}' at {c.cid}")
                c.name = new_name
                c.last_hello = time.monotonic()
                c.send(encode_ack(c.next_seq(), t, self.name))
            return
        if ftype == T_STATE:
            s = decode_state(body)
            if s is not None:
                self.on_state(c, seq, s)
            return
        if ftype == T_BYE:
            self.log(f"[-] watch '{c.name}' stopped the bridge")
            self.drop_client(c)
            return
        if ftype == T_RAW:
            if self.show_raw and len(body) >= 2:
                slot, n = body[0], body[1]
                rep = body[2:2 + n]
                self.log(f"RAW s{slot} [{n}] " + rep.hex(" "))
            return

    def on_state(self, c: Client, seq: int, s: State):
        if s.slot > 15:
            return
        key = (c.cid, s.slot)
        with self.lock:
            pad = self.pads.get(key)
            if pad is None:
                pad = self.pads[key] = Pad(c, s.slot)
            if pad.last_seq is not None and not is_newer(seq, pad.last_seq):
                return  # stale/reordered UDP datagram
            pad.last_seq = seq
            pad.last_rx = time.monotonic()
            changed = s.key() != pad.state.key()
            pad.state = s
            if not s.connected:
                if pad.virtual:
                    pad.virtual.close()
                    pad.virtual = None
                    self.log(f"[-] pad {c.name}/slot{s.slot} disconnected, virtual pad removed")
                return
            if pad.virtual is None and self.vg is not None:
                pad.virtual = VirtualX360(self.vg, lambda big, small, p=pad: self.set_rumble(p, big, small))
                self.log(f"[+] virtual Xbox 360 pad created for {c.name}/slot{s.slot}")
            if changed:
                if pad.virtual:
                    pad.virtual.apply(s)
                self.show(pad)

    def set_rumble(self, pad: Pad, large: int, small: int):
        with self.lock:
            pad.rumble = (large, small)
            pad.rumble_pending = True
        self.tick_rumble()

    def tick_rumble(self):
        now = time.monotonic()
        with self.lock:
            for pad in self.pads.values():
                large, small = pad.rumble
                active = large or small
                if pad.rumble_pending or (active and now - pad.rumble_sent > RUMBLE_REPEAT_S):
                    pad.client.send(encode_rumble(pad.client.next_seq(), pad.slot, large, small))
                    pad.rumble_sent = now
                    pad.rumble_pending = False

    def drop_client(self, c: Client):
        with self.lock:
            for key in [k for k in self.pads if k[0] == c.cid]:
                pad = self.pads.pop(key)
                if pad.virtual:
                    pad.virtual.close()
            self.clients.pop(c.cid, None)

    def watchdog(self):
        while True:
            time.sleep(0.1)
            now = time.monotonic()
            with self.lock:
                for pad in list(self.pads.values()):
                    if pad.state.connected and now - pad.last_rx > PAD_TIMEOUT_S:
                        self.log(f"[!] no data from {pad.client.name}/slot{pad.slot} for {PAD_TIMEOUT_S}s, releasing")
                        pad.state = State(pad.slot, connected=True)
                        if pad.virtual:
                            pad.virtual.release()
                        pad.last_rx = now + 3600  # don't spam; next packet resets it
            self.tick_rumble()

    # ---- output

    def log(self, msg: str):
        sys.stdout.write("\r\033[K" + msg + "\n")
        sys.stdout.flush()

    def show(self, pad: Pad):
        if self.quiet:
            return
        s = pad.state
        btn = " ".join(s.pressed()) or "-"
        line = (f"s{s.slot} {btn:<24} L({s.lx / 32767:+.2f},{s.ly / 32767:+.2f}) "
                f"R({s.rx / 32767:+.2f},{s.ry / 32767:+.2f}) LT{s.lt * 100 // 32767:3d}% RT{s.rt * 100 // 32767:3d}%")
        sys.stdout.write("\r\033[K" + line)
        sys.stdout.flush()

    # ---- network loops

    def serve_udp(self):
        while True:
            data, addr = self.udp.recvfrom(2048)
            cid = f"udp:{addr[0]}:{addr[1]}"
            send = (lambda payload, a=addr: self.udp.sendto(payload, a))
            h = parse_header(data)
            if h and h[0] == T_DISCOVER:
                # Don't create a client record for discovery probes.
                self.handle(Client(cid, send), data, reply_raw_udp=send)
                continue
            self.handle(self.client_for(cid, send), data)

    def serve_tcp(self):
        server = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                sock = self.request
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                wlock = threading.Lock()

                def send(payload: bytes):
                    with wlock:
                        try:
                            sock.sendall(struct.pack("<H", len(payload)) + payload)
                        except OSError:
                            pass

                cid = f"tcp:{self.client_address[0]}:{self.client_address[1]}"
                c = server.client_for(cid, send)
                buf = b""
                try:
                    while True:
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        buf += chunk
                        while len(buf) >= 2:
                            (n,) = struct.unpack_from("<H", buf)
                            if len(buf) < 2 + n:
                                break
                            frame, buf = buf[2:2 + n], buf[2 + n:]
                            server.handle(c, frame)
                except OSError:
                    pass
                finally:
                    server.log(f"[-] TCP {cid} closed")
                    server.drop_client(c)

        class TCPServer(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        with TCPServer(("0.0.0.0", self.port), Handler) as srv:
            srv.serve_forever()

    def run(self):
        for target in (self.serve_udp, self.serve_tcp, self.watchdog):
            threading.Thread(target=target, daemon=True).start()
        mode = "virtual Xbox 360 pads" if self.vg else "print-only"
        print(f"PadBridge server '{self.name}' listening on UDP+TCP :{self.port} ({mode}).")
        for ip in local_ips():
            print(f"  watch Settings -> Server IP: {ip}   (or tap 'Find server on Wi-Fi')")
        print("Ctrl+C to quit.")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nbye")
            with self.lock:
                for pad in self.pads.values():
                    if pad.virtual:
                        pad.virtual.close()


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main(argv=None):
    ap = argparse.ArgumentParser(description="PadBridge companion server")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--name", default=socket.gethostname())
    ap.add_argument("--no-vgamepad", action="store_true", help="only print input, don't create virtual pads")
    ap.add_argument("--raw", action="store_true", help="print raw HID reports from the L2CAP path")
    ap.add_argument("--quiet", action="store_true", help="don't print the live input line")
    a = ap.parse_args(argv)
    if sys.platform == "win32":
        import os
        os.system("")  # enable ANSI escapes in the Windows console
    PadBridgeServer(a.port, a.name, not a.no_vgamepad, a.raw, a.quiet).run()


if __name__ == "__main__":
    main()
