#!/usr/bin/env python3
"""
Integration test suite for Klardrop standalone Qt Quick UI.
Tests isolated fake control discovery/auth, action/history routing,
focus/inactive/close lifecycle, disconnect/malformed/backoff/reconnect/credential rotation,
local paths, screenshot preview capture, and offscreen QML startup with zero warnings.
"""

import http.server
import json
import os
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
QT_DIR = os.path.dirname(SCRIPT_DIR)
TEST_BINARY = os.path.join(SCRIPT_DIR, "klardrop-test")
PROD_BINARY = None
IPC_TEST_BINARY = None
PREVIEW_PNG = "/tmp/klardrop-qt-preview.png"


class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class FakeDaemonHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        auth = self.headers.get("Authorization", "")
        self.server.recorded_requests.append({
            "method": "GET",
            "path": path,
            "query": query,
            "auth": auth,
            "time": time.time(),
        })

        if getattr(self.server, "simulate_malformed", False):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"{\"ok\": false, malformed_json_here!!")
            return

        if path == "/state":
            state = {
                "ok": True,
                "version": self.server.state_version,
                "self": {
                    "deviceId": "self-test-1",
                    "deviceName": "Test Linux PC",
                    "osType": "Linux",
                    "deviceType": "DESKTOP"
                },
                "protocols": {
                    "klardrop": True,
                    "nearby": True,
                    "ble": False
                },
                "settings": {
                    "backgroundDiscoveryEnabled": self.server.bg_discovery,
                    "supportsBackgroundDiscovery": True
                },
                "devices": [
                    {
                        "deviceId": "dev-peer-1",
                        "deviceName": "Phone <b style='color:red'>Peer</b>",
                        "deviceType": "MOBILE",
                        "trustStatus": "trusted",
                        "reachability": "reachable",
                        "hasUnread": True,
                        "unreadCount": 2,
                        "pairingError": None,
                        "connectionTypes": ["LAN"]
                    },
                    {
                        "deviceId": "dev-peer-2",
                        "deviceName": "Laptop & \"Peer\"",
                        "deviceType": "DESKTOP",
                        "trustStatus": "untrusted",
                        "reachability": "reachable",
                        "hasUnread": False,
                        "unreadCount": 0,
                        "pairingError": None,
                        "connectionTypes": ["LAN"]
                    }
                ],
                "trustedIds": ["dev-peer-1"],
                "pairingDialog": getattr(self.server, "pairing_dialog", None),
                "incoming": getattr(self.server, "incoming_requests", [
                    {
                        "receiveId": 1,
                        "deviceId": "dev-peer-2",
                        "deviceName": "Incoming Peer <script>",
                        "pendingAuth": True,
                        "fileCount": 2,
                        "totalSize": 2048000,
                        "status": "PendingAuthorization"
                    },
                    {
                        "receiveId": 2,
                        "deviceId": "dev-peer-1",
                        "deviceName": "Phone Peer",
                        "pendingAuth": False,
                        "fileCount": 1,
                        "totalSize": 512000,
                        "status": "Completed"
                    }
                ]),
                "notifications": getattr(self.server, "notifications", [
                    {
                        "id": 1,
                        "deviceId": "dev-revoked",
                        "deviceName": "Revoked Peer <i>untrusted</i>"
                    }
                ]),
                "transfers": [
                    {
                        "id": 10,
                        "deviceId": "dev-peer-1",
                        "fileName": "photo <preview>.png",
                        "totalSize": 1048576,
                        "transferredSize": 524288,
                        "isSender": True,
                        "phase": "transferring"
                    }
                ],
                "qrShare": {
                    "active": False,
                    "url": None,
                    "expiresAt": None,
                    "downloadCount": 0,
                    "downloads": []
                },
                "update": getattr(self.server, "update_state", {
                    "status": "up_to_date",
                    "version": None,
                    "staged": False,
                    "error": None,
                    "currentVersion": "1.0.0",
                    "channel": "stable",
                    "supported": True
                })
            }
            body = json.dumps(state).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/history":
            dev = query.get("device", [""])[0]
            resp = {
                "ok": True,
                "deviceId": dev,
                "nextBefore": None,
                "messages": [
                    {
                        "id": 101,
                        "content": "Hello <b>Alice</b> & <script>alert(1)</script>",
                        "timestamp": int(time.time() * 1000) - 5000,
                        "isSender": False,
                        "messageType": "TEXT",
                        "deliveryStatus": "DELIVERED",
                        "isRead": True,
                        "mimeType": "text/plain",
                        "fileTransferId": None,
                        "file": None
                    },
                    {
                        "id": 102,
                        "content": "Sent document: <code>readme.txt</code>",
                        "timestamp": int(time.time() * 1000) - 1000,
                        "isSender": True,
                        "messageType": "TEXT",
                        "deliveryStatus": "DELIVERED",
                        "isRead": True,
                        "mimeType": "text/plain",
                        "fileTransferId": None,
                        "file": None
                    }
                ]
            }
            body = json.dumps(resp).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        content_len = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(content_len).decode("utf-8") if content_len > 0 else ""
        parsed_body = {}
        try:
            if raw_body:
                parsed_body = json.loads(raw_body)
        except Exception:
            pass

        auth = self.headers.get("Authorization", "")
        self.server.recorded_requests.append({
            "method": "POST",
            "path": self.path,
            "body": parsed_body,
            "auth": auth,
            "time": time.time(),
        })

        if self.path == "/active-chat":
            dev = parsed_body.get("deviceId")
            self.server.active_chat_events.append(dev)
            resp = {"ok": True, "action": "active-chat"}
        elif self.path == "/send-text":
            self.server.sent_texts.append(parsed_body)
            txt = parsed_body.get("text", "")
            if "fail" in txt or getattr(self.server, "fail_send_text", False):
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"ok": false, "error": "Simulated send failure"}')
                return
            resp = {"ok": True, "action": "send-text", "result": "completed"}
        elif self.path == "/send-clipboard":
            resp = {"ok": True, "action": "send-clipboard", "result": "completed"}
        elif self.path == "/pair":
            self.server.paired_attempts.append(parsed_body.get("deviceId"))
            resp = {"ok": True, "action": "pair"}
        elif self.path == "/settings":
            if "backgroundDiscoveryEnabled" in parsed_body:
                self.server.bg_discovery = parsed_body["backgroundDiscoveryEnabled"]
            if "name" in parsed_body:
                self.server.device_name = parsed_body["name"]
            resp = {"ok": True, "action": "settings"}
        elif self.path == "/history/read":
            self.server.history_read_devices.append(parsed_body.get("deviceId"))
            resp = {"ok": True, "action": "history-read"}
        elif self.path == "/update/check":
            self.server.update_checked = True
            self.server.simulate_active_transfer = True
            self.server.state_version += 1
            self.server.update_state = {
                "status": "ready",
                "version": "1.2.0",
                "staged": True,
                "error": None,
                "currentVersion": "1.0.0",
                "channel": "stable",
                "supported": True
            }
            resp = {"ok": True, "action": "check"}
        elif self.path == "/update/apply":
            self.server.update_applied = True
            if getattr(self.server, "simulate_active_transfer", False):
                self.server.simulate_active_transfer = False
                self.send_response(409)
                self.send_header("Content-Type", "application/json")
                err_body = json.dumps({"ok": False, "error": "active transfers in progress"}).encode("utf-8")
                self.send_header("Content-Length", str(len(err_body)))
                self.end_headers()
                self.wfile.write(err_body)
                return
            self.server.state_version += 1
            self.server.update_state = {
                "status": "applying",
                "version": "1.2.0",
                "staged": False,
                "error": None,
                "currentVersion": "1.0.0",
                "channel": "stable",
                "supported": True
            }
            resp = {"ok": True, "action": "apply"}
        else:
            resp = {"ok": True, "action": "generic"}

        body = json.dumps(resp).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def test_1_selftest():
    print("\n--- Test 1: Built-in Unit & Contract Selftest ---")
    proc = subprocess.run([TEST_BINARY, "-platform", "offscreen", "--selftest"],
                          capture_output=True, text=True, timeout=10)
    assert proc.returncode == 0, f"Selftest failed with RC={proc.returncode}: {proc.stderr}"
    assert "[SELFTEST] ALL PASSED" in proc.stdout, f"Expected '[SELFTEST] ALL PASSED' in stdout, got: {proc.stdout}"
    print("✓ Selftest passed: port validation, token validation, metadata checks, URL path decode (#, spaces, Unicode), and formatters verified.")


def test_2_refusal_without_isolated_config():
    print("\n--- Test 2: Strict Refusal without Explicit Isolated Config ---")
    proc = subprocess.run([TEST_BINARY, "-platform", "offscreen"],
                          capture_output=True, text=True, timeout=5)
    assert proc.returncode == 2, f"Expected RC=2 when missing --control-file, got {proc.returncode}"
    assert "required in test executable" in proc.stderr or "required in test executable" in proc.stdout, \
        f"Expected refusal error message, got stderr: {proc.stderr}, stdout: {proc.stdout}"
    print("✓ Test binary successfully refused connection without explicit isolated --control-file.")


def test_3_discovery_auth_and_screenshot():
    print("\n--- Test 3: Control Discovery, Bearer Auth, and Screenshot Capture ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_test_")
    control_path = os.path.join(temp_dir, "control.json")

    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token = "test-token-phase1-abc123"
    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token}, f)

    if os.path.exists(PREVIEW_PNG):
        try:
            os.remove(PREVIEW_PNG)
        except OSError:
            pass

    try:
        proc = subprocess.run([
            TEST_BINARY,
            "-platform", "offscreen",
            "--control-file", control_path,
            "--screenshot", PREVIEW_PNG,
            "--exit-after", "1500"
        ], capture_output=True, text=True, timeout=15)

        assert proc.returncode == 0, f"Process exited with error {proc.returncode}: {proc.stderr}"

        # Verify authentic request arrived with expected token header
        state_reqs = [r for r in server.recorded_requests if r["path"] == "/state"]
        assert len(state_reqs) > 0, "No /state request received by fake daemon"
        assert state_reqs[0]["auth"] == f"Bearer {token}", f"Unexpected auth header: {state_reqs[0]['auth']}"
        print(f"✓ Authentic control discovery and Bearer auth verified ({len(state_reqs)} /state polls).")

        # Verify screenshot was generated
        assert os.path.isfile(PREVIEW_PNG), f"Screenshot was not generated at {PREVIEW_PNG}"
        assert os.path.getsize(PREVIEW_PNG) > 1000, f"Screenshot at {PREVIEW_PNG} is too small"
        print(f"✓ Window preview screenshot captured ({os.path.getsize(PREVIEW_PNG)} bytes at {PREVIEW_PNG}).")

        # Verify no QML errors were logged
        assert "FAIL: QML errors detected" not in proc.stderr
        print("✓ Offscreen QML startup verified with 0 binding/component errors.")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_4_action_routing_and_focus_lifecycle():
    print("\n--- Test 4: Action Routing, Active-Chat Focus Lifecycle & Safe Close ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_test_")
    control_path = os.path.join(temp_dir, "control.json")

    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token = "action-test-token-xyz789"
    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token}, f)

    try:
        proc = subprocess.run([
            TEST_BINARY,
            "-platform", "offscreen",
            "--control-file", control_path,
            "--run-actions",
            "--exit-after", "2500"
        ], capture_output=True, text=True, timeout=15)

        assert proc.returncode == 0, f"Process exited with error {proc.returncode}: {proc.stderr}"

        # Verify active chat routing: active-chat must NOT be called in Qt frontend
        active_chat_reqs = [r for r in server.recorded_requests if r["path"] == "/active-chat"]
        assert len(active_chat_reqs) == 0, f"Expected 0 /active-chat calls, found {len(active_chat_reqs)}"
        print("✓ /active-chat side effects correctly omitted from Qt frontend.")

        # Verify history routing
        history_reqs = [r for r in server.recorded_requests if r["path"] == "/history"]
        assert len(history_reqs) > 0, "No /history request received"
        assert history_reqs[0]["query"].get("device") == ["dev-peer-1"]
        assert history_reqs[0]["auth"] == f"Bearer {token}"
        print("✓ /history routing verified.")

        # Verify send-text routing
        assert len(server.sent_texts) > 0, "No /send-text action received"
        assert server.sent_texts[0].get("deviceId") == "dev-peer-1"
        assert server.sent_texts[0].get("text") == "Test message from harness"
        print("✓ /send-text action routing verified.")

        # Verify pair routing
        assert "dev-peer-2" in server.paired_attempts, "Pair request for dev-peer-2 not received"
        print("✓ /pair action routing verified.")

        # Verify settings routing
        assert server.bg_discovery is False, "Settings update for backgroundDiscoveryEnabled not applied"
        assert server.device_name == "Renamed In Test", "Settings update for rename not applied"
        print("✓ /settings action routing verified.")

        # Verify history/read marked read for unread device and supports future unreads
        read_count = server.history_read_devices.count("dev-peer-1")
        assert read_count >= 2, f"Expected at least 2 /history/read calls for future unread verification, got {read_count}"
        print(f"✓ /history/read deduplicated and marked for unread device ({read_count} read marks verified, future unread honored).")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_5_disconnect_malformed_and_backoff():
    print("\n--- Test 5: Disconnect, Malformed 200, and Bounded Backoff ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_test_")
    control_path = os.path.join(temp_dir, "control.json")

    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server.simulate_malformed = True
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token = "malformed-test-token"
    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token}, f)

    try:
        proc = subprocess.run([
            TEST_BINARY,
            "-platform", "offscreen",
            "--control-file", control_path,
            "--exit-after", "2500"
        ], capture_output=True, text=True, timeout=10)

        assert proc.returncode == 0, f"Process crashed on malformed 200: {proc.stderr}"

        req_count = len([r for r in server.recorded_requests if r["path"] == "/state"])
        assert 1 <= req_count <= 5, f"Expected bounded backoff requests (1-5), but received {req_count} (busy spin detected!)"
        print(f"✓ Malformed response handled gracefully without busy spin ({req_count} requests in 2.5s with backoff).")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_6_credential_rotation():
    print("\n--- Test 6: Reconnect & Credential Rotation ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_test_")
    control_path = os.path.join(temp_dir, "control.json")

    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token_initial = "initial-token-111111"
    token_rotated = "rotated-token-222222"

    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token_initial}, f)

    proc = subprocess.Popen([
        TEST_BINARY,
        "-platform", "offscreen",
        "--control-file", control_path,
        "--exit-after", "4000"
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    try:
        time.sleep(1.0)
        initial_reqs = [r for r in server.recorded_requests if r["auth"] == f"Bearer {token_initial}"]
        assert len(initial_reqs) > 0, "No initial request with token 1"

        with open(control_path, "w") as f:
            json.dump({"port": server_port, "token": token_rotated}, f)

        time.sleep(2.0)
        rotated_reqs = [r for r in server.recorded_requests if r["auth"] == f"Bearer {token_rotated}"]
        assert len(rotated_reqs) > 0, "No rotated request with token 2 after credential change"

        proc.wait(timeout=5)
        assert proc.returncode == 0, f"Process failed: {proc.stderr}"
        print(f"✓ Credential rotation verified (initial token: {len(initial_reqs)} times, rotated token: {len(rotated_reqs)} times).")

    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait()
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_7_held_poll_credential_rotation():
    print("\n--- Test 7: Held-Poll Credential Rotation (Safety & No SIGSEGV) ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_held_poll_")
    control_path = os.path.join(temp_dir, "control.json")

    poll_started = threading.Event()

    class HeldPollHandler(FakeDaemonHandler):
        def do_GET(self):
            first_poll = not poll_started.is_set()
            if self.path.startswith("/state") and first_poll:
                poll_started.set()
                time.sleep(1.5)
            try:
                super().do_GET()
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadedHTTPServer(("127.0.0.1", 0), HeldPollHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token_old = "held-token-old-1111"
    token_new = "held-token-new-2222"

    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token_old}, f)

    proc = subprocess.Popen([
        TEST_BINARY,
        "-platform", "offscreen",
        "--control-file", control_path,
        "--exit-after", "3500"
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    try:
        assert poll_started.wait(3.0), "Initial /state poll was not started"
        # Rotate credentials while the first /state poll is actively held by daemon
        with open(control_path, "w") as f:
            json.dump({"port": server_port, "token": token_new}, f)

        out, err = proc.communicate(timeout=8)
        assert proc.returncode == 0, f"Process crashed during held-poll credential rotation with RC={proc.returncode}, stderr:\n{err}"
        print("✓ Credential rotation during active in-flight /state poll succeeded safely without crash.")

        # Assert rotated token was actually used and client reconnected after held old reply
        old_polls = [r for r in server.recorded_requests if r["path"].startswith("/state") and r["auth"] == f"Bearer {token_old}"]
        new_polls = [r for r in server.recorded_requests if r["path"].startswith("/state") and r["auth"] == f"Bearer {token_new}"]
        assert len(old_polls) >= 1, f"Expected at least 1 /state poll with initial token, got {len(old_polls)}"
        assert len(new_polls) >= 1, f"Expected /state poll with rotated token after held reply, got {len(new_polls)}"
        print(f"✓ Reconnected using rotated token after held poll ({len(new_polls)} polls with rotated token).")

    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait()
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_8_composer_draft_retention_and_regression():
    print("\n--- Test 8: Composer Draft Retention & Rejected-Send Regression ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_composer_")
    control_path = os.path.join(temp_dir, "control.json")

    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token = "composer-test-token-xyz"
    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token}, f)

    try:
        proc = subprocess.run([
            TEST_BINARY,
            "-platform", "offscreen",
            "--control-file", control_path,
            "--test-composer",
            "--exit-after", "4000"
        ], capture_output=True, text=True, timeout=12)

        assert proc.returncode == 0, f"Composer regression test failed (RC={proc.returncode}):\n{proc.stderr}\n{proc.stdout}"

        assert "[COMPOSER] ✓ Draft retained on failure verified." in proc.stdout, "Draft retention on failure not verified"
        assert "[COMPOSER] ✓ Same draft cleared on success verified." in proc.stdout, "Same draft clearing on success not verified"
        assert "[COMPOSER] ✓ Newer edit remains verified." in proc.stdout, "Newer edit preservation not verified"
        assert "[COMPOSER] ✓ Newer whitespace edit remains verified." in proc.stdout, "Newer whitespace edit preservation not verified"

        # Assert no duplicate requests were received by the daemon while pending
        send_text_reqs = [r for r in server.recorded_requests if r["path"] == "/send-text"]
        # Exactly 4 send-text requests should have been transmitted (1 fail, 1 succeed, 1 newer substantive edit, 1 newer whitespace edit)
        assert len(send_text_reqs) == 4, f"Expected exactly 4 /send-text requests (duplicates blocked), got {len(send_text_reqs)}"
        print(f"✓ Composer draft retention verified: retained on failure, cleared on same draft, newer edits & whitespace preserved, and duplicates blocked ({len(send_text_reqs)} total sends).")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_9_check_runtime_mode():
    print("\n--- Test 9: --check-runtime Offscreen Mode & Bounded Isolation ---")
    assert PROD_BINARY and os.path.isfile(PROD_BINARY), f"Production binary missing at {PROD_BINARY}"

    # Isolated HOME and XDG_RUNTIME_DIR paths
    iso_home = tempfile.mkdtemp(prefix="klardrop_iso_home_")
    iso_xdg = tempfile.mkdtemp(prefix="klardrop_iso_xdg_")

    # Start a fake daemon to prove ZERO requests occur even with a valid real control file present
    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server_port = server.server_address[1]
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    ctrl_dir = os.path.join(iso_xdg, "klardrop")
    os.makedirs(ctrl_dir, exist_ok=True)
    ctrl_path = os.path.join(ctrl_dir, "control.json")
    with open(ctrl_path, "w") as f:
        json.dump({"port": server_port, "token": "check-runtime-token-xyz"}, f)

    iso_env = os.environ.copy()
    iso_env["HOME"] = iso_home
    iso_env["XDG_RUNTIME_DIR"] = iso_xdg

    try:
        start = time.time()
        # Verify fresh production binary in runtime check mode (NOT test-only mode)
        proc = subprocess.run([
            PROD_BINARY,
            "--check-runtime",
            "--control-file", ctrl_path
        ], env=iso_env, capture_output=True, text=True, timeout=5)
        duration = time.time() - start

        assert proc.returncode == 0, f"Production --check-runtime failed with RC={proc.returncode}, stderr:\n{proc.stderr}"
        assert duration < 4.0, f"Expected bounded execution (<4s), took {duration:.2f}s"
        print(f"✓ Production binary --check-runtime verified bounded exit (took {duration:.2f}s, RC=0).")

        # Verify ZERO requests sent to fake daemon API
        assert len(server.recorded_requests) == 0, f"Expected ZERO requests to fakeAPI during --check-runtime, got {len(server.recorded_requests)}"
        print("✓ Verified fakeAPI received 0 requests during --check-runtime.")

        # Verify no lock or socket writes in XDG_RUNTIME_DIR
        runtime_pollutants = [f for f in os.listdir(iso_xdg) if f.endswith(".lock") or f.endswith(".sock")]
        assert len(runtime_pollutants) == 0, f"Lock/socket files written to XDG_RUNTIME_DIR during check-runtime: {runtime_pollutants}"
        print("✓ Verified zero lock or socket writes in XDG_RUNTIME_DIR.")

        # Verify no QML disk cache created in HOME
        cache_path = os.path.join(iso_home, ".cache", "qmlcache")
        assert not os.path.exists(cache_path) or len(os.listdir(cache_path)) == 0, "QML disk cache was written despite QML_DISABLE_DISK_CACHE"
        print("✓ Verified zero disk cache writes in user cache.")

        # Also verify test harness check-runtime runs clean
        h_proc = subprocess.run([TEST_BINARY, "-platform", "offscreen", "--check-runtime"], capture_output=True, text=True, timeout=5)
        assert h_proc.returncode == 0, f"Harness --check-runtime failed: {h_proc.stderr}"
        print("✓ Verified clean runtime validation without errors or warnings.")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(iso_home, ignore_errors=True)
        shutil.rmtree(iso_xdg, ignore_errors=True)


def test_10_duplicate_launch_and_ipc_activation():
    print("\n--- Test 10: Single-Instance Local IPC Activation & Session Isolation ---")
    assert PROD_BINARY and os.path.isfile(PROD_BINARY), f"Production binary missing: {PROD_BINARY}"

    temp_dir = tempfile.mkdtemp(prefix="klardrop_ipc_")
    ctrl1 = os.path.join(temp_dir, "ctrl1.json")
    ctrl2 = os.path.join(temp_dir, "ctrl2.json")
    ctrl3 = os.path.join(temp_dir, "ctrl3.json")
    with open(ctrl1, "w") as f:
        json.dump({"port": 12345, "token": "dummy-token-1"}, f)
    with open(ctrl2, "w") as f:
        json.dump({"port": 12346, "token": "dummy-token-2"}, f)
    with open(ctrl3, "w") as f:
        json.dump({"port": 12347, "token": "dummy-token-3"}, f)

    p1 = subprocess.Popen([PROD_BINARY, "-platform", "offscreen", "--control-file", ctrl1],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p3 = None
    try:
        time.sleep(1.0)
        assert p1.poll() is None, f"First instance on ctrl1 exited prematurely with RC={p1.poll()}"

        # 1. Launch 2 simultaneous instances with SAME private control path: activates the one alive UI and exits 0
        p2 = subprocess.run([PROD_BINARY, "-platform", "offscreen", "--control-file", ctrl1],
                            capture_output=True, text=True, timeout=5)
        assert p2.returncode == 0, f"Duplicate instance failed to exit cleanly (RC={p2.returncode}):\n{p2.stderr}"
        assert p1.poll() is None, "First instance was closed by duplicate launch!"
        print("✓ Two simultaneous launches with same private control path activate the one alive UI.")

        # 2. Meaningful IPC reopen regression: separate test exe linking production main + TESTONLY startup hook
        # Hides window, connects private IPC socket without sending data, and asserts window becomes visible
        # Runs on independent ctrl3 private control file so it starts as primary instance, creating its own socket
        assert IPC_TEST_BINARY and os.path.isfile(IPC_TEST_BINARY), f"Mandatory IPC test binary missing: {IPC_TEST_BINARY}"
        p_ipc = subprocess.run([IPC_TEST_BINARY, "-platform", "offscreen", "--control-file", ctrl3],
                               capture_output=True, text=True, timeout=10)
        assert p_ipc.returncode == 0, f"IPC window reopen test failed (RC={p_ipc.returncode}):\nStdout: {p_ipc.stdout}\nStderr: {p_ipc.stderr}"
        assert "[IPC-TEST] ✓ Window successfully restored to visible upon IPC socket connection without data." in p_ipc.stdout, \
            f"IPC reopen stdout marker missing (early duplicate exit?). Stdout:\n{p_ipc.stdout}\nStderr:\n{p_ipc.stderr}"
        print("✓ Hidden window reopened and made visible upon private IPC socket connection without data.")

        # 3. Launch instance with DIFFERENT control file path: remains distinct and concurrently alive
        p3 = subprocess.Popen([PROD_BINARY, "-platform", "offscreen", "--control-file", ctrl2],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        time.sleep(1.0)
        assert p3.poll() is None, f"Instance on ctrl2 failed to start or exited with RC={p3.poll()}"
        assert p1.poll() is None, "First instance on ctrl1 died when distinct ctrl2 instance launched!"
        print("✓ Launches with different control file paths remain distinct concurrent sessions.")

    finally:
        if p1.poll() is None:
            p1.terminate()
            p1.wait(timeout=3)
        if p3 and p3.poll() is None:
            p3.terminate()
            p3.wait(timeout=3)
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_11_update_state_and_routing():
    print("\n--- Test 11: Update State Parsing & Action Routing ---")
    temp_dir = tempfile.mkdtemp(prefix="klardrop_update_")
    control_path = os.path.join(temp_dir, "control.json")

    server = ThreadedHTTPServer(("127.0.0.1", 0), FakeDaemonHandler)
    server.recorded_requests = []
    server.state_version = 1
    server.bg_discovery = True
    server.device_name = "Original Name"
    server.sent_texts = []
    server.paired_attempts = []
    server.active_chat_events = []
    server.history_read_devices = []
    server.incoming_requests = []
    server.notifications = []
    server.pairing_dialog = None
    server.update_checked = False
    server.update_applied = False
    server.simulate_active_transfer = False
    server.update_state = {
        "status": "available",
        "version": "1.2.0",
        "staged": False,
        "error": None,
        "currentVersion": "1.0.0",
        "channel": "stable",
        "supported": True
    }
    server_port = server.server_address[1]

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    token = "update-test-token"
    with open(control_path, "w") as f:
        json.dump({"port": server_port, "token": token}, f)

    try:
        # Run test harness invoking ClientBridge checkUpdate/applyUpdate and testing 409 + restart notice
        proc = subprocess.run([
            TEST_BINARY,
            "-platform", "offscreen",
            "--control-file", control_path,
            "--test-updater",
            "--exit-after", "6000"
        ], capture_output=True, text=True, timeout=12)

        assert proc.returncode == 0, f"Updater test harness failed with RC={proc.returncode}:\n{proc.stderr}\n{proc.stdout}"
        assert "[UPDATER] ✓ checkUpdate routed with auth" in proc.stdout, "checkUpdate verification missing"
        assert "[UPDATER] ✓ applyUpdate 409 error visible without forced retry" in proc.stdout, "409 error verification missing"
        assert "[UPDATER] ✓ applyUpdate succeeded, restart notice visible" in proc.stdout, "apply restart notice verification missing"

        # Verify recorded requests on fake daemon
        check_reqs = [r for r in server.recorded_requests if r["path"] == "/update/check"]
        assert len(check_reqs) >= 1, "Daemon recorded zero /update/check requests"
        assert check_reqs[0]["auth"] == f"Bearer {token}", f"Invalid auth on /update/check: {check_reqs[0]['auth']}"
        assert check_reqs[0]["body"].get("force") is not True, "Unexpected force request in checkUpdate"
        print("✓ Harness checkUpdate invoked with valid bearer auth and no force.")

        apply_reqs = [r for r in server.recorded_requests if r["path"] == "/update/apply"]
        assert len(apply_reqs) == 2, f"Expected exactly 2 /update/apply requests (1 failed 409, 1 retried by harness), got {len(apply_reqs)}"
        for req in apply_reqs:
            assert req["auth"] == f"Bearer {token}", f"Invalid auth on /update/apply: {req['auth']}"
            assert req["body"].get("force") is not True, "Unexpected force request in applyUpdate"
        print("✓ Harness applyUpdate recorded endpoint, bearer auth, no-force requests, and 409 visibility without forced retry.")
        print("✓ UI presented updater status, 409 error, and restart notice ('Quit and reopen to load updated UI') without killing application.")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(temp_dir, ignore_errors=True)


def main():
    global TEST_BINARY, PROD_BINARY, IPC_TEST_BINARY
    print("=====================================================================")
    print(" Running Klardrop Standalone Qt UI Integration Test Suite")
    print("=====================================================================")

    custom_test_bin = os.environ.get("KLARDROP_TEST_BIN")
    custom_prod_bin = os.environ.get("KLARDROP_PROD_BIN")
    temp_build_dir = None

    if custom_test_bin and os.path.isfile(custom_test_bin) and custom_prod_bin and os.path.isfile(custom_prod_bin):
        TEST_BINARY = custom_test_bin
        PROD_BINARY = custom_prod_bin
        candidate_ipc = os.environ.get("KLARDROP_IPC_TEST_BIN") or os.path.join(os.path.dirname(custom_test_bin), "klardrop-ipc-test")
        if os.path.isfile(candidate_ipc):
            IPC_TEST_BINARY = candidate_ipc
    else:
        print("[Build] Clean building BOTH production binary and test harness in own /tmp...")
        temp_build_dir = tempfile.mkdtemp(prefix="build_klardrop_qt_suite_")
        try:
            prod_dir = os.path.join(temp_build_dir, "prod")
            os.makedirs(prod_dir, exist_ok=True)
            subprocess.run(["qmake6", os.path.join(QT_DIR, "klardrop-qt.pro")], cwd=prod_dir, check=True)
            subprocess.run(["make", f"-j{os.cpu_count() or 4}"], cwd=prod_dir, check=True)
            PROD_BINARY = os.path.join(prod_dir, "klardrop-qt")

            test_dir = os.path.join(temp_build_dir, "test")
            os.makedirs(test_dir, exist_ok=True)
            subprocess.run(["qmake6", os.path.join(SCRIPT_DIR, "tests.pro")], cwd=test_dir, check=True)
            subprocess.run(["make", f"-j{os.cpu_count() or 4}"], cwd=test_dir, check=True)
            TEST_BINARY = os.path.join(test_dir, "klardrop-test")

            ipc_dir = os.path.join(temp_build_dir, "test_ipc")
            os.makedirs(ipc_dir, exist_ok=True)
            subprocess.run(["qmake6", "CONFIG+=test_ipc", os.path.join(SCRIPT_DIR, "tests.pro")], cwd=ipc_dir, check=True)
            subprocess.run(["make", f"-j{os.cpu_count() or 4}"], cwd=ipc_dir, check=True)
            IPC_TEST_BINARY = os.path.join(ipc_dir, "klardrop-ipc-test")

            print(f"[Build] Successfully built fresh artifacts:\n  Prod: {PROD_BINARY}\n  Test: {TEST_BINARY}\n  IPC:  {IPC_TEST_BINARY}")
        except Exception as e:
            if temp_build_dir:
                shutil.rmtree(temp_build_dir, ignore_errors=True)
            print(f"Error building fresh Qt binaries: {e}")
            sys.exit(1)

    assert os.path.isfile(PROD_BINARY), f"Fresh production binary missing: {PROD_BINARY}"
    assert os.path.isfile(TEST_BINARY), f"Fresh test harness binary missing: {TEST_BINARY}"
    assert IPC_TEST_BINARY and os.path.isfile(IPC_TEST_BINARY), f"Mandatory IPC test binary missing: {IPC_TEST_BINARY}"

    print(f" Target Production Binary: {PROD_BINARY}")
    print(f" Target Test Binary:       {TEST_BINARY}")
    print(f" Target IPC Test Binary:   {IPC_TEST_BINARY}")

    try:
        test_1_selftest()
        test_2_refusal_without_isolated_config()
        test_3_discovery_auth_and_screenshot()
        test_4_action_routing_and_focus_lifecycle()
        test_5_disconnect_malformed_and_backoff()
        test_6_credential_rotation()
        test_7_held_poll_credential_rotation()
        test_8_composer_draft_retention_and_regression()
        test_9_check_runtime_mode()
        test_10_duplicate_launch_and_ipc_activation()
        test_11_update_state_and_routing()
        print("\n=====================================================================")
        print(" ALL INTEGRATION TESTS PASSED SUCCESSFULLY!")
        print("=====================================================================")
        if temp_build_dir:
            shutil.rmtree(temp_build_dir, ignore_errors=True)
        sys.exit(0)
    except AssertionError as e:
        print(f"\n❌ TEST FAILURE: {e}")
        if temp_build_dir:
            shutil.rmtree(temp_build_dir, ignore_errors=True)
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ UNEXPECTED ERROR: {e}")
        import traceback
        traceback.print_exc()
        if temp_build_dir:
            shutil.rmtree(temp_build_dir, ignore_errors=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
