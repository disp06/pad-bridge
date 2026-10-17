#!/usr/bin/env python3
"""
Pretend to be the watch: find the server, say HELLO, and play a short scripted input sequence.
Use it to check the server, the virtual pad and your firewall before involving the watch.

    python fake_watch.py                    # broadcast discovery, then UDP
    python fake_watch.py --host 127.0.0.1   # skip discovery
    python fake_watch.py --tcp
"""

from __future__ import annotations

import argparse
import math
import socket
import struct
import time

import padbridge_server as pb


def discover(port: int, timeout=1.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.3)
    probe = pb.HEADER.pack(pb.MAGIC, pb.VERSION, pb.T_DISCOVER, 0)
    end = time.time() + timeout
    while time.time() < end:
        for target in ("255.255.255.255", "127.0.0.1"):
            try:
                s.sendto(probe, (target, port))
            except OSError:
                pass
        try:
            data, addr = s.recvfrom(512)
        except socket.timeout:
            continue
        h = pb.parse_header(data)
        if h and h[0] == pb.T_DISCOVER_REPLY:
            (p,) = struct.unpack_from("<H", h[2])
            return addr[0], p, pb.read_name(h[2][2:])
    return None


class Link:
    def __init__(self, host, port, tcp):
        self.tcp = tcp
        if tcp:
            self.s = socket.create_connection((host, port), timeout=3)
            self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        else:
            self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.s.connect((host, port))
        self.s.settimeout(1.0)
        self.seq = 0

    def next_seq(self):
        self.seq = (self.seq + 1) & 0xFFFF
        return self.seq

    def send(self, frame: bytes):
        self.s.sendall(struct.pack("<H", len(frame)) + frame if self.tcp else frame)

    def recv(self):
        if not self.tcp:
            return self.s.recv(512)
        hdr = self._exact(2)
        (n,) = struct.unpack("<H", hdr)
        return self._exact(n)

    def _exact(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise ConnectionError("closed")
            b += c
        return b


def now_ms():
    return int(time.monotonic() * 1000) & 0xFFFFFFFF


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host")
    ap.add_argument("--port", type=int, default=pb.DEFAULT_PORT)
    ap.add_argument("--tcp", action="store_true")
    a = ap.parse_args()

    host, port = a.host, a.port
    if not host:
        found = discover(port)
        if not found:
            raise SystemExit("No server answered discovery. Is padbridge_server.py running?")
        host, port, name = found
        print(f"discovered '{name}' at {host}:{port}")

    link = Link(host, port, a.tcp)
    t0 = now_ms()
    link.send(pb.HEADER.pack(pb.MAGIC, pb.VERSION, pb.T_HELLO, link.next_seq())
              + struct.pack("<I", t0) + bytes([9]) + b"fakewatch")
    h = pb.parse_header(link.recv())
    assert h and h[0] == pb.T_ACK, "expected ACK"
    (echo,) = struct.unpack_from("<I", h[2])
    print(f"ACK from '{pb.read_name(h[2][4:])}', rtt {(now_ms() - echo) & 0xFFFFFFFF} ms")

    def state(**kw):
        s = pb.State(slot=0, connected=True, t=now_ms(), **kw)
        link.send(pb.encode_state(s, link.next_seq()))

    script = [("A", pb.BIT["A"]), ("B", pb.BIT["B"]), ("START", pb.BIT["START"]), ("UP+X", pb.BIT["UP"] | pb.BIT["X"])]
    for label, mask in script:
        print("press", label)
        state(buttons=mask)
        time.sleep(0.25)
        state()
        time.sleep(0.15)
    print("left stick circle, triggers ramp")
    for i in range(60):
        ang = i / 60 * 2 * math.pi
        state(lx=int(math.cos(ang) * 32767), ly=int(math.sin(ang) * 32767),
              lt=int(i / 59 * 32767), rt=int((59 - i) / 59 * 32767))
        time.sleep(1 / 60)
    state()
    link.send(pb.encode_state(pb.State(slot=0, connected=False), link.next_seq()))
    link.send(pb.HEADER.pack(pb.MAGIC, pb.VERSION, pb.T_BYE, link.next_seq()))
    print("done")


if __name__ == "__main__":
    main()
