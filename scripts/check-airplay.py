#!/usr/bin/env python3
"""Bounded RTSP smoke check; does not validate video/audio decoding or DRM."""
import argparse
import socket

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('host', help='TV IP address')
parser.add_argument('--port', type=int, default=7000)
args = parser.parse_args()
with socket.create_connection((args.host, args.port), timeout=5) as sock:
    sock.sendall(b'OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n')
    response = b''
    while b'\r\n\r\n' not in response and len(response) < 16384:
        chunk = sock.recv(4096)
        if not chunk:
            break
        response += chunk
text = response.decode('utf-8', errors='replace')
if not text.startswith('RTSP/1.0 200 OK\r\n') or 'CSeq: 1\r\n' not in text:
    raise SystemExit('Unexpected receiver response: ' + text[:500])
print('PASS: AirPlay receiver answered RTSP OPTIONS (200 OK).')
print('Next: verify Bonjour discovery and a real Apple sender session.')
