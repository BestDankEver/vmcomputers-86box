#!/usr/bin/env python3
"""Minimal RCON client: rcon.py <command...>"""
import socket, struct, sys
def pkt(i, t, body):
    b = body.encode() + b'\x00\x00'
    return struct.pack('<iii', len(b) + 8, i, t) + b
s = socket.create_connection(('127.0.0.1', 25575), timeout=10)
s.sendall(pkt(1, 3, 'smoke'))
s.recv(4096)
s.sendall(pkt(2, 2, ' '.join(sys.argv[1:])))
data = s.recv(65536)
print(data[12:-2].decode(errors='replace'))
