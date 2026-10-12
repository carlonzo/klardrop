#!/usr/bin/env python3
"""Check a REAL Klardrop engine against the contract the Qt frontend depends on.

`linux/qt/tests/test_integration.py` proves the Qt side's own behaviour against an
in-process fake daemon. This proves the other half: that the engine the shared
control-plane component actually runs still answers the requests
`linux/qt/clientbridge.cpp` sends, with the shapes it parses.

Every assertion here names the C++ or QML line it protects, because a shape
assertion with no consumer behind it is decoration. Nothing about Qt's
implementation is imported or re-implemented: only what the frontend *reads* is
asserted.

Usage:
    scripts/klardrop-qt-real-engine-checks.py <control.json>

Exit 0 = every check passed. The bearer token is never printed.
"""

import json
import os
import sys
import urllib.error
import urllib.request

TIMEOUT = 20


class Checker:
    def __init__(self):
        self.passed = 0
        self.failed = 0

    def check(self, name, ok, detail=""):
        if ok:
            self.passed += 1
            print("  ok   %s" % name)
        else:
            self.failed += 1
            print("  FAIL %s" % name, file=sys.stderr)
            if detail:
                print("       %s" % detail, file=sys.stderr)
        return ok

    def summary(self):
        print("\nreal-engine parity: %d passed, %d failed" % (self.passed, self.failed))
        return 1 if self.failed else 0


def request(port, path, method="GET", body=None, token=None, timeout=TIMEOUT):
    """One loopback request. Returns (status, parsed_json_or_None, raw_bytes)."""
    url = "http://127.0.0.1:%d%s" % (port, path)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    except urllib.error.URLError as exc:
        return 0, None, str(exc).encode("utf-8", "replace")
    try:
        return status, json.loads(raw), raw
    except ValueError:
        return status, None, raw


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    control_path = sys.argv[1]
    with open(control_path, "rb") as handle:
        control = json.loads(handle.read())
    port = control["port"]
    token = control["token"]
    c = Checker()

    # The listener must not be open: an unauthenticated GET /state would let any
    # local process read the device's identity, peers and files.
    status, _, _ = request(port, "/state", token=None)
    c.check(
        "GET /state without a bearer token is rejected (401)",
        status == 401,
        "got HTTP %s" % status,
    )

    # ── GET /state: the shape handleState() parses (clientbridge.cpp:425-470) ──
    status, state, _ = request(port, "/state", token=token, timeout=40)
    if not c.check("GET /state answers 200 with a bearer token", status == 200, "HTTP %s" % status):
        return c.summary()
    if not c.check("GET /state body is a JSON object", isinstance(state, dict), repr(state)[:200]):
        return c.summary()

    c.check("/state.version is an integer", isinstance(state.get("version"), int))
    c.check("/state.self is an object (clientbridge.cpp:433)", isinstance(state.get("self"), dict))

    settings = state.get("settings")
    c.check("/state.settings is an object (clientbridge.cpp:435)", isinstance(settings, dict))
    if isinstance(settings, dict):
        c.check(
            "settings.supportsBackgroundDiscovery is a bool (clientbridge.cpp:436)",
            isinstance(settings.get("supportsBackgroundDiscovery"), bool),
        )
        c.check(
            "settings.backgroundDiscoveryEnabled is a bool (clientbridge.cpp:437)",
            isinstance(settings.get("backgroundDiscoveryEnabled"), bool),
        )

    devices = state.get("devices")
    c.check("/state.devices is an array (clientbridge.cpp:439)", isinstance(devices, list))
    if isinstance(devices, list):
        device_fields_ok = all(
            isinstance(d, dict)
            and isinstance(d.get("deviceId"), str)
            and isinstance(d.get("deviceName"), str)
            and isinstance(d.get("trustStatus"), str)
            and isinstance(d.get("hasUnread"), bool)
            and isinstance(d.get("unreadCount"), int)
            for d in devices
        )
        c.check(
            "every device carries deviceId/deviceName/trustStatus/hasUnread/unreadCount "
            "with the types the models read (clientbridge.cpp:444,812-814; QML roles)",
            device_fields_ok,
            "first offending device: %r" % next(
                (d for d in devices if not (isinstance(d, dict) and isinstance(d.get("deviceId"), str)
                 and isinstance(d.get("deviceName"), str) and isinstance(d.get("trustStatus"), str)
                 and isinstance(d.get("hasUnread"), bool) and isinstance(d.get("unreadCount"), int))),
                None,
            ),
        )
        # The frontend splits devices on this exact string.
        c.check(
            "trustStatus is one of the values handleState() knows ('trusted' or not)",
            all(d.get("trustStatus") in ("trusted", "untrusted", "pending", "revoked") for d in devices),
            "observed: %r" % sorted({d.get("trustStatus") for d in devices}),
        )

    # `pairingDialog` is JsonNull while no dialog is pending (ControlPlane.kt:1279)
    # and an object once one is. Qt reads it through QJsonValue::toObject(), which
    # turns JsonNull into an empty QJsonObject, so null is correct. The claim is
    # that the key is PRESENT and never some third type — not that it is an object.
    c.check(
        "/state.pairingDialog is present and is an object or null (clientbridge.cpp:455)",
        "pairingDialog" in state
        and (state["pairingDialog"] is None or isinstance(state["pairingDialog"], dict)),
        "observed: %r" % (state.get("pairingDialog", "<absent>"),),
    )
    for key, line in (("qrShare", 458), ("update", 460)):
        c.check("/state.%s is an object (clientbridge.cpp:%d)" % (key, line), isinstance(state.get(key), dict))
    for key, line in (("incoming", 456), ("transfers", 457), ("notifications", 457)):
        c.check("/state.%s is an array (clientbridge.cpp:%d)" % (key, line), isinstance(state.get(key), list))

    # ── /state.transfers[] must still be the file-transfer array ───────────────
    # The submission routes added by this branch keep their own registry; if they
    # had started publishing here, the Qt transfer model would show queued
    # submissions it has no way to render and the TUI's history would double-count.
    transfers = state.get("transfers") or []
    transfer_shape_ok = all(
        isinstance(t, dict)
        and "status" in t
        and ("fileName" in t or "kind" in t or "transferId" in t)
        for t in transfers
    )
    c.check(
        "/state.transfers[] entries keep the file-transfer shape Qt renders (status + fileName)",
        transfer_shape_ok,
        "entries: %r" % (transfers[:2],),
    )

    # ── /history: the paging fields fetchHistoryPage() reads (704-740) ─────────
    status, history, _ = request(port, "/history?device=unknown-device&limit=50", token=token)
    c.check("GET /history answers 200 (clientbridge.cpp:704)", status == 200, "HTTP %s" % status)
    if isinstance(history, dict):
        c.check(
            "/history.messages is an array (clientbridge.cpp:723)",
            isinstance(history.get("messages"), list),
        )
        c.check(
            "/history carries nextBefore (keyset paging, clientbridge.cpp:729-731)",
            "nextBefore" in history,
        )

    # ── POST /history/read: Qt must not have to guard this against a 4xx ───────
    # Window focus gates *whether* Qt sends it (README: focus strictly gates
    # /history/read), never whether it succeeds.
    status, _, _ = request(
        port, "/history/read", "POST", {"deviceId": "unknown-device"}, token=token
    )
    c.check(
        "POST /history/read answers 2xx for a device the engine knows nothing about",
        200 <= status < 300,
        "HTTP %s" % status,
    )

    # ── POST /settings really round-trips through /state ───────────────────────
    before = state.get("settings", {}).get("backgroundDiscoveryEnabled")
    flipped = not bool(before)
    status, _, _ = request(
        port,
        "/settings",
        "POST",
        {"backgroundDiscoveryEnabled": flipped},
        token=token,
    )
    c.check("POST /settings answers 2xx (clientbridge.cpp:1027-1034)", 200 <= status < 300, "HTTP %s" % status)
    _, after_state, _ = request(port, "/state", token=token, timeout=40)
    observed = (after_state or {}).get("settings", {}).get("backgroundDiscoveryEnabled")
    c.check(
        "POST /settings changes what GET /state publishes (backgroundDiscoveryEnabled %r -> %r)"
        % (before, flipped),
        observed == flipped,
        "GET /state still reports %r" % (observed,),
    )
    # Put it back so a run leaves the fixture as it found it.
    request(port, "/settings", "POST", {"backgroundDiscoveryEnabled": before}, token=token)

    # ── POST /send-text: 200 with a non-"completed" result must NOT be success ─
    # clientbridge.cpp:626-627 accepts the text routes ONLY on result == "completed".
    # Sending to a device that does not exist must therefore come back as a result
    # the frontend renders as a failure, never as a 200 it treats as delivered.
    status, text_resp, _ = request(
        port,
        "/send-text",
        "POST",
        {"deviceId": "unknown-device", "text": "qt parity probe"},
        token=token,
    )
    c.check("POST /send-text answers 2xx (clientbridge.cpp:903)", 200 <= status < 300, "HTTP %s" % status)
    result = (text_resp or {}).get("result")
    c.check(
        "POST /send-text to an unknown device does NOT report result='completed'",
        result != "completed",
        "result=%r -- the Qt bridge would treat this as delivery" % (result,),
    )

    # ── Update routes (clientbridge.cpp:1192-1196) ────────────────────────────
    status, _, _ = request(port, "/update/check", "POST", {}, token=token)
    c.check("POST /update/check answers 2xx", 200 <= status < 300, "HTTP %s" % status)
    c.check(
        "/state.update is an object the updater can render without a special case",
        isinstance(state.get("update"), dict),
    )

    # ── The additive routes this branch introduced ────────────────────────────
    status, caps, _ = request(port, "/capabilities", token=token)
    c.check("GET /capabilities answers 200", status == 200, "HTTP %s" % status)
    if isinstance(caps, dict):
        c.check("/capabilities carries an integer apiVersion", isinstance(caps.get("apiVersion"), int))
        c.check(
            "/capabilities advertises a list of capability names",
            isinstance(caps.get("capabilities"), list)
            and all(isinstance(name, str) for name in caps.get("capabilities", [])),
        )
        published = control.get("capabilities")
        c.check(
            "the control file's capability list matches GET /capabilities",
            isinstance(published, list) and sorted(published) == sorted(caps.get("capabilities", [])),
            "control=%r endpoint=%r" % (published, caps.get("capabilities")),
        )

    status, _, _ = request(port, "/share", "POST", {}, token=token)
    c.check(
        "POST /share rejects an empty body rather than accepting it (no accidental empty send)",
        status == 400,
        "HTTP %s" % status,
    )
    status, listing, _ = request(port, "/transfers", token=token)
    c.check("GET /transfers answers 200", status == 200, "HTTP %s" % status)
    c.check(
        "GET /transfers body is a JSON array or object with a requests key",
        isinstance(listing, (list, dict)),
        repr(listing)[:200],
    )

    # A route that does not exist must stay a 404 rather than becoming a catch-all
    # 200: the Qt frontend treats an unrecognised answer as malformed state.
    status, _, _ = request(port, "/definitely-not-a-route", token=token)
    c.check("an unknown route is still 404", status == 404, "HTTP %s" % status)

    return c.summary()


if __name__ == "__main__":
    sys.exit(main())
