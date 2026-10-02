#!/usr/bin/env python3
"""A recording loopback reverse proxy in front of a real Klardrop engine.

The point is to observe what the *real* `klardrop-qt` binary actually asks for,
rather than re-implementing its request sequence in a test and asserting on that
imitation. The proxy owns a second control file with its own port and the real
engine's token; Qt connects here, and every request is forwarded verbatim to the
engine and recorded.

Verbatim means: same method, same path and query, same `Authorization` header, same
body. Responses (status, headers, body) go back untouched, so Qt parses real engine
payloads, not proxy-invented ones.

Usage:
    scripts/klardrop-qt-proxy.py --control-file <engine control.json> \\
        --proxy-control-file <path to write> --log <path to append JSONL>
"""

import argparse
import http.server
import json
import os
import socket
import sys
import threading
import urllib.error
import urllib.request

UPSTREAM_TIMEOUT = 70  # the Qt frontend long-polls /state for 35s
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
}


class Recorder:
    def __init__(self, path, token):
        self.path = path
        self.token = token
        self.lock = threading.Lock()

    def record(self, entry):
        with self.lock:
            with open(self.path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(entry, sort_keys=True) + "\n")
                handle.flush()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "klardrop-qt-proxy/1"

    def log_message(self, fmt, *args):  # keep the proxy quiet
        pass

    def _forward(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None

        auth = self.headers.get("Authorization") or ""
        presented = auth[7:] if auth.startswith("Bearer ") else None

        url = "http://127.0.0.1:%d%s" % (self.server.upstream_port, self.path)
        req = urllib.request.Request(url, data=body, method=method)
        for key, value in self.headers.items():
            if key.lower() in HOP_BY_HOP or key.lower() == "host":
                continue
            req.add_header(key, value)

        try:
            with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT) as resp:
                status = resp.status
                payload = resp.read()
                content_type = resp.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as exc:
            status = exc.code
            payload = exc.read()
            content_type = exc.headers.get("Content-Type", "application/json")
        except Exception as exc:  # connection refused, timeout, reset
            self.server.recorder.record(
                {
                    "method": method,
                    "path": self.path,
                    "auth_present": auth != "",
                    "auth_valid": presented == self.server.recorder.token,
                    "status": 0,
                    "error": type(exc).__name__,
                }
            )
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")
            return

        self.server.recorder.record(
            {
                "method": method,
                "path": self.path,
                "auth_present": auth != "",
                "auth_valid": presented == self.server.recorder.token,
                "status": status,
                "bytes": len(payload),
            }
        )

        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._forward("GET")

    def do_POST(self):
        self._forward("POST")

    def do_PUT(self):
        self._forward("PUT")

    def do_DELETE(self):
        self._forward("DELETE")


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--control-file", required=True)
    parser.add_argument("--proxy-control-file", required=True)
    parser.add_argument("--log", required=True)
    args = parser.parse_args()

    with open(args.control_file, "rb") as handle:
        control = json.loads(handle.read())
    token = control["token"]

    recorder = Recorder(args.log, token)
    server = Server(("127.0.0.1", 0), Handler)
    server.upstream_port = control["port"]
    server.recorder = recorder

    # Bind first, then publish: a control file that names a port nobody is
    # listening on would make the Qt frontend's first request fail for a reason
    # that has nothing to do with the engine.
    port = server.socket.getsockname()[1]
    proxy_control = dict(control)
    proxy_control["port"] = port

    # Write atomically with 0600: the frontend rejects a non-regular or oversized
    # file, and a half-written one would be read as malformed metadata.
    tmp = args.proxy_control_file + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(proxy_control, handle)
    os.replace(tmp, args.proxy_control_file)
    os.chmod(args.proxy_control_file, 0o600)

    print("proxy listening on 127.0.0.1:%d -> 127.0.0.1:%d" % (port, server.upstream_port), flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
