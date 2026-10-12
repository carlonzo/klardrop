#!/usr/bin/env python3
"""Report on what the real Qt frontend asked the real engine for.

Reads the JSONL the proxy wrote and asserts the properties that make the recorded
traffic evidence rather than decoration:

  * every request carried the bearer token (no unauthenticated probe succeeded);
  * every request the engine answered came back 2xx — a 401/404/5xx would be a
    protocol regression the Qt app could paper over by looking merely "connected";
  * at least one /state poll actually happened, because that is the request whose
    shape this whole exercise is about;
  * nothing was redirected (a 3xx would mean the token left loopback).

Usage:
    scripts/klardrop-qt-proxy-report.py --log <path>
"""

import argparse
import json
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    args = parser.parse_args()

    with open(args.log, "r", encoding="utf-8") as handle:
        entries = [json.loads(line) for line in handle if line.strip()]

    failures = []

    if not entries:
        print("no requests were recorded", file=sys.stderr)
        return 1

    unauthenticated = [e for e in entries if not e.get("auth_valid")]
    if unauthenticated:
        failures.append(
            "%d request(s) were not authenticated with the engine's bearer token: %r"
            % (len(unauthenticated), [(e["method"], e["path"]) for e in unauthenticated[:5]])
        )

    transport_errors = [e for e in entries if e.get("status") == 0]
    if transport_errors:
        failures.append(
            "%d request(s) never reached the engine: %r"
            % (len(transport_errors), [(e["method"], e["path"], e.get("error")) for e in transport_errors[:5]])
        )

    bad_status = [e for e in entries if not (200 <= e.get("status", 0) < 300)]
    if bad_status:
        failures.append(
            "the engine answered %d request(s) with a non-2xx status: %r"
            % (len(bad_status), [(e["method"], e["path"], e.get("status")) for e in bad_status[:10]])
        )

    redirects = [e for e in entries if 300 <= e.get("status", 0) < 400]
    if redirects:
        failures.append("%d redirect(s) were followed" % len(redirects))

    state_polls = [e for e in entries if e["path"].startswith("/state")]
    if not state_polls:
        failures.append("the frontend never polled GET /state, so no response shape was exercised")

    print("recorded %d request(s):" % len(entries))
    counts = {}
    for entry in entries:
        key = "%s %s" % (entry["method"], entry["path"].split("?")[0])
        counts[key] = counts.get(key, 0) + 1
    for key in sorted(counts):
        print("  %4d x %s" % (counts[key], key))

    if failures:
        for failure in failures:
            print("FAIL: %s" % failure, file=sys.stderr)
        return 1

    print("all %d request(s) were authenticated and answered 2xx (%d x GET /state)" % (len(entries), len(state_polls)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
