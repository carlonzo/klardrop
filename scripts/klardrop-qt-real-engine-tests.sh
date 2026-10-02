#!/usr/bin/env bash
# Qt frontend against the REAL control plane.
#
# Why this exists: `linux/qt/tests/test_integration.py` drives the Qt frontend
# against an in-process fake daemon. That is the right test for the Qt side's own
# behaviour (focus gating, credential rotation, composer, single instance), but it
# proves nothing about the *other* half of the contract: whether the engine that
# the shared control-plane component actually runs still answers the exact
# requests `linux/qt/clientbridge.cpp` sends, with the response shapes it parses.
# This work moved shared control code across three hosts, so "no Qt regression"
# has to be checked against a real engine, not asserted by grepping route strings.
#
# What this proves, on Linux x64, against one engine this script owns:
#   - every field `ClientBridge::handleState` reads out of GET /state is present
#     with the type it expects (self, settings.supportsBackgroundDiscovery,
#     settings.backgroundDiscoveryEnabled, devices[].trustStatus, transfers[],
#     update, pairingDialog, incoming, notifications, qrShare, version);
#   - every route the Qt frontend calls exists, with the verb and status it
#     expects, including the two additive ones this branch added (POST /share,
#     GET /transfers) and GET /capabilities;
#   - GET /state's `transfers[]` is still the file-transfer array it always was,
#     i.e. the new submission routes did not repurpose it;
#   - a request with no bearer token is rejected 401 (the listener is not open);
#   - POST /settings really round-trips through GET /state, and POST /history/read
#     is accepted for a device the engine knows nothing about (the focused/unfocused
#     decision belongs to Qt, and Qt must not have to guard against a 4xx here);
#   - THE REAL QT BINARY, pointed at a control file for a recording reverse proxy
#     in front of the real engine, starts, keeps running, issues authenticated
#     requests, receives only 2xx from the engine, emits no QML errors, and exits
#     without a signal. The proxy is what makes this a check of Qt's actual request
#     sequence rather than of a hand-written imitation of it.
#
# Usage:
#   ./scripts/klardrop-qt-real-engine-tests.sh
#
# Environment:
#   KLARDROP_ENGINE_BIN  Kotlin/Native engine (default: cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe)
#   KLARDROP_QT_PROBE_SECONDS  how long the real Qt binary runs (default: 10)
#
# Requires: qmake6 + a C++ toolchain + Qt 6 (the frontend's own documented build
# prerequisites, linux/qt/README.md). Without them the script reports SKIP and
# exits 0 — an absent display toolchain is not a product failure, and pretending
# otherwise is how a gap becomes a fake pass.
#
# Isolation: one mktemp root holds HOME, XDG_RUNTIME_DIR, the data dir, the Qt
# out-of-tree build and the proxy log. Only the engine and the Qt process this
# script starts are ever signalled. The bearer token is never printed: every
# diagnostic that could show it goes through redact_* below.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENGINE_BIN="${KLARDROP_ENGINE_BIN:-$ROOT/cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe}"
PROBE_SECONDS="${KLARDROP_QT_PROBE_SECONDS:-10}"

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0
TMPROOT=""
ENGINE_PID=""
QT_PID=""
PROXY_PID=""

log() { printf '==> %s\n' "$*"; }
pass() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_PASSED=$((TESTS_PASSED + 1)); printf 'PASS: %s\n' "$1"; }
skip() { TESTS_SKIPPED=$((TESTS_SKIPPED + 1)); printf 'SKIP: %s\n' "$1"; }
fail() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_FAILED=$((TESTS_FAILED + 1)); printf 'FAIL: %s\n' "$1" >&2; if [[ -n "${2:-}" ]]; then printf '      %s\n' "$2" >&2; fi; return 0; }

redact_text() {
  python3 -c 'import re,sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
sys.stdout.write(re.sub(r"(\"token\"\s*:\s*\")[^\"]*(\")", r"\1<redacted>\2", raw))' "$1"
}

cleanup() {
  local rc=$?
  for pid in "$QT_PID" "$PROXY_PID" "$ENGINE_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  if [[ -n "$TMPROOT" && -d "$TMPROOT" ]]; then rm -rf "$TMPROOT"; fi
  exit $rc
}
trap cleanup EXIT

# ── Preconditions ────────────────────────────────────────────────────────────

if [[ ! -x "$ENGINE_BIN" ]]; then
  printf 'missing executable: %s\n' "$ENGINE_BIN" >&2
  printf 'build it with: ./gradlew :cli:linkReleaseExecutableLinuxX64\n' >&2
  exit 1
fi
ENGINE_BASENAME="$(basename "$ENGINE_BIN")"
if [[ "$ENGINE_BASENAME" != "klardrop-engine.kexe" && "$ENGINE_BASENAME" != "klardrop-engine" ]]; then
  printf 'ENGINE_BIN must be the renamed engine binary (klardrop-engine.kexe), got: %s\n' "$ENGINE_BASENAME" >&2
  exit 1
fi
command -v python3 >/dev/null || { printf 'python3 is required\n' >&2; exit 1; }

if ! command -v qmake6 >/dev/null && ! command -v qmake >/dev/null; then
  skip "no qmake6/qmake on this host: the Qt-vs-real-engine leg cannot run here (build prerequisites: linux/qt/README.md)"
  printf '0 run, 0 passed, 0 failed, %d skipped\n' "$TESTS_SKIPPED"
  exit 0
fi
QMAKE="$(command -v qmake6 || command -v qmake)"

# ── Private profile ──────────────────────────────────────────────────────────

TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/klardrop-qt-real-engine.XXXXXX")"
export HOME="$TMPROOT/home"
export KLARDROP_HOME="$TMPROOT/data"
export XDG_RUNTIME_DIR="$TMPROOT/run"
export XDG_DATA_HOME="$TMPROOT/data"
export XDG_CONFIG_HOME="$TMPROOT/config"
export XDG_CACHE_HOME="$TMPROOT/cache"
mkdir -p "$HOME" "$KLARDROP_HOME" "$XDG_RUNTIME_DIR" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME"
CONTROL_FILE="$XDG_RUNTIME_DIR/klardrop/control.json"

# ── Build the real Qt frontend out of tree ───────────────────────────────────

log "Building klardrop-qt out of tree ($QMAKE)"
BUILD_DIR="$TMPROOT/qtbuild"
mkdir -p "$BUILD_DIR"
if ! (cd "$BUILD_DIR" && "$QMAKE" "$ROOT/linux/qt/klardrop-qt.pro" >"$TMPROOT/qmake.log" 2>&1 \
      && make -j"$(nproc)" >"$TMPROOT/qt-make.log" 2>&1); then
  fail "klardrop-qt builds out of tree" "see \$TMPROOT/qmake.log and qt-make.log (rerun with the logs kept)"
  printf '%s run, %d passed, %d failed, %d skipped\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED" "$TESTS_SKIPPED"
  exit 1
fi
QT_BIN="$BUILD_DIR/klardrop-qt"
if [[ ! -x "$QT_BIN" ]]; then
  fail "klardrop-qt builds out of tree" "qmake/make succeeded but $QT_BIN does not exist"
  exit 1
fi
pass "klardrop-qt builds out of tree ($QT_BIN)"

if ! "$QT_BIN" --check-runtime >"$TMPROOT/check-runtime.log" 2>&1; then
  fail "klardrop-qt --check-runtime succeeds with no control file" "$(redact_text "$TMPROOT/check-runtime.log")"
else
  pass "klardrop-qt --check-runtime succeeds with no control file (no IPC, no control file needed)"
fi

# ── Fixture engine ───────────────────────────────────────────────────────────

log "Starting fixture engine (no klardrop/nearby/ble transports)"
"$ENGINE_BIN" daemon --port 0 --no-klardrop --no-nearby --no-ble \
  >"$TMPROOT/engine.log" 2>&1 &
ENGINE_PID=$!

waited=0
while [[ ! -s "$CONTROL_FILE" ]]; do
  if ! kill -0 "$ENGINE_PID" 2>/dev/null; then
    printf 'fixture engine died during startup:\n' >&2
    redact_text "$TMPROOT/engine.log" >&2
    exit 1
  fi
  waited=$((waited + 1))
  if [[ "$waited" -gt 120 ]]; then
    printf 'timed out waiting for %s\n' "$CONTROL_FILE" >&2
    exit 1
  fi
  sleep 0.5
done
pass "fixture engine published its control file (pid $ENGINE_PID)"

ENGINE_PORT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["port"])' "$CONTROL_FILE")"

# ── Direct parity checks against the real engine ─────────────────────────────

log "Checking the real engine against the contract clientbridge.cpp depends on"
if python3 "$ROOT/scripts/klardrop-qt-real-engine-checks.py" "$CONTROL_FILE"; then
  pass "real engine satisfies the Qt frontend's response-shape and route contract"
else
  fail "real engine satisfies the Qt frontend's response-shape and route contract" \
    "scripts/klardrop-qt-real-engine-checks.py failed against $CONTROL_FILE (port $ENGINE_PORT)"
fi

# ── The real Qt binary, through a recording proxy in front of the real engine ─

PROXY_CONTROL="$TMPROOT/qt-proxy-control.json"
PROXY_LOG="$TMPROOT/proxy-requests.jsonl"
log "Starting recording proxy on 127.0.0.1 -> 127.0.0.1:$ENGINE_PORT"
python3 "$ROOT/scripts/klardrop-qt-proxy.py" \
  --control-file "$CONTROL_FILE" \
  --proxy-control-file "$PROXY_CONTROL" \
  --log "$PROXY_LOG" >"$TMPROOT/proxy.log" 2>&1 &
PROXY_PID=$!

waited=0
while [[ ! -s "$PROXY_CONTROL" ]]; do
  if ! kill -0 "$PROXY_PID" 2>/dev/null; then
    fail "recording proxy started and published a control file" "$(redact_text "$TMPROOT/proxy.log")"
    printf '%s run, %d passed, %d failed, %d skipped\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED" "$TESTS_SKIPPED"
    exit 1
  fi
  waited=$((waited + 1))
  [[ "$waited" -gt 60 ]] && { printf 'proxy did not start\n' >&2; exit 1; }
  sleep 0.25
done
chmod 600 "$PROXY_CONTROL"
pass "recording proxy started and published a control file for 127.0.0.1"

log "Running the real klardrop-qt against the real engine for ${PROBE_SECONDS}s"
QML_DISABLE_DISK_CACHE=1 "$QT_BIN" -platform offscreen --control-file "$PROXY_CONTROL" \
  >"$TMPROOT/qt.out" 2>"$TMPROOT/qt.err" &
QT_PID=$!

sleep 2
if kill -0 "$QT_PID" 2>/dev/null; then
  pass "klardrop-qt is still running 2s after launch against the real engine"
else
  fail "klardrop-qt is still running 2s after launch against the real engine" \
    "exited early; stderr: $(redact_text "$TMPROOT/qt.err")"
fi

sleep "$((PROBE_SECONDS > 2 ? PROBE_SECONDS - 2 : 0))"

if kill -0 "$QT_PID" 2>/dev/null; then
  pass "klardrop-qt stayed up for the whole ${PROBE_SECONDS}s probe"
else
  fail "klardrop-qt stayed up for the whole ${PROBE_SECONDS}s probe" \
    "exited during the probe; stderr: $(redact_text "$TMPROOT/qt.err")"
fi

if [[ -s "$PROXY_LOG" ]]; then
  pass "klardrop-qt actually talked to the real engine ($(wc -l <"$PROXY_LOG") requests recorded through the proxy)"
else
  fail "klardrop-qt actually talked to the real engine" "the proxy recorded zero requests"
fi

# Every request the frontend made must be one the engine accepted, and must have
# carried the bearer token. A 401/404/5xx here is a protocol regression even when
# the Qt app itself looks fine, because it would be a dead control in the UI.
if python3 "$ROOT/scripts/klardrop-qt-proxy-report.py" --log "$PROXY_LOG"; then
  pass "every request klardrop-qt made was authenticated and answered 2xx by the real engine"
else
  fail "every request klardrop-qt made was authenticated and answered 2xx by the real engine" \
    "see scripts/klardrop-qt-proxy-report.py output above"
fi

if grep -q "FAIL: QML errors detected" "$TMPROOT/qt.err"; then
  fail "klardrop-qt reports no QML binding/component errors" "$(redact_text "$TMPROOT/qt.err")"
elif grep -Eq "^(QML|file:).*(is not a type|Unable to assign|Binding loop|Cannot assign)" "$TMPROOT/qt.err"; then
  fail "klardrop-qt reports no QML binding/component errors" "$(redact_text "$TMPROOT/qt.err")"
else
  pass "klardrop-qt reports no QML binding/component errors against the real engine's payloads"
fi

if grep -Eqi "could not connect|connection refused|Host not found" "$TMPROOT/qt.err"; then
  fail "klardrop-qt never reported a connection failure to the real engine" "$(redact_text "$TMPROOT/qt.err")"
else
  pass "klardrop-qt never reported a connection failure to the real engine"
fi

# Teardown crash check. SIGTERM's default disposition is to die *on the signal*,
# so rc 143 is what a healthy non-handling process returns and must not be read
# as a failure. What must never happen is a crash on the way out — SIGSEGV,
# SIGABRT, SIGBUS, SIGILL or SIGFPE — which is precisely the class of bug the
# held-poll / credential-rotation / close-lifecycle work can introduce.
kill -TERM "$QT_PID" 2>/dev/null || true
QT_RC=0
wait "$QT_PID" 2>/dev/null || QT_RC=$?
QT_PID=""
if [[ "$QT_RC" -eq 139 || "$QT_RC" -eq 134 || "$QT_RC" -eq 135 || "$QT_RC" -eq 132 || "$QT_RC" -eq 136 ]]; then
  fail "klardrop-qt tears down on SIGTERM without crashing" \
    "died on signal $((QT_RC - 128)) (rc $QT_RC): $(redact_text "$TMPROOT/qt.err")"
else
  pass "klardrop-qt tears down on SIGTERM without crashing (rc $QT_RC)"
fi

kill -TERM "$PROXY_PID" 2>/dev/null || true
wait "$PROXY_PID" 2>/dev/null || true
PROXY_PID=""

log "Fixture engine still healthy after the Qt session"
if kill -0 "$ENGINE_PID" 2>/dev/null; then
  pass "the real engine was never stopped or restarted by the Qt session"
else
  fail "the real engine was never stopped or restarted by the Qt session" "engine pid $ENGINE_PID is gone"
fi

if python3 - "$CONTROL_FILE" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
sys.exit(0 if isinstance(doc.get("port"), int) and doc.get("token") else 1)
PY
then
  pass "the engine's own control file is still valid after the Qt session"
else
  fail "the engine's own control file is still valid after the Qt session" "$(redact_text "$CONTROL_FILE")"
fi

# ── Result ───────────────────────────────────────────────────────────────────

printf '\n%s run, %d passed, %d failed, %d skipped\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED" "$TESTS_SKIPPED"
[[ "$TESTS_FAILED" -eq 0 ]] || exit 1
exit 0
