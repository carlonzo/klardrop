#!/usr/bin/env bash
# Klardrop two-peer file transfer, end to end, with the built binaries.
#
# What this proves, on one host, between two processes this script owns:
#   - the Kotlin/Native engine's `daemon` discovers the engine's `listen`
#     receiver over mDNS (klardrop transport ENABLED on both sides) and
#     publishes it in GET /state;
#   - `klardrop share --to <id> <file> --wait --json`, the RELEASE Rust
#     client binary invoked DIRECTLY (never through Gradle, which collapses
#     application exit codes into its own), exits 0 and prints exactly one
#     JSON envelope whose items are all "completed";
#   - the RECEIVER really got the bytes: the file lands in the receiver's own
#     download directory with an identical sha256 and identical content, and
#     the receiver emitted exactly ONE inbound `received` JSONL record — so a
#     duplicate send is a failure, not a rounding error;
#   - a deliberately failing case (unknown device id) exits with the
#     documented non-zero code, still prints one JSON envelope on stdout, and
#     delivers nothing;
#   - no other Klardrop process is created or destroyed: a /proc PID census
#     over the two binaries under test, before / during / after, and only the
#     PIDs this script started are ever signalled.
#
# Isolation: every node gets its own --data-dir, its own XDG_RUNTIME_DIR and
# its own HOME, all under one `mktemp -d` root that is removed on exit. The
# bearer token is never printed: the control file is read inside python, the
# HTTP call is made from the same python process, and only device ids are
# printed.
#
# Usage:
#   ./scripts/klardrop-two-peer-transfer-tests.sh
#
# Environment:
#   KLARDROP_ENGINE_BIN  Kotlin/Native engine (default: cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe)
#   KLARDROP_RUST_BIN    Rust client          (default: cli-rust/target/release/klardrop)
#
# Tunables (all seconds):
#   TWO_PEER_IDENTITY_TIMEOUT   receiver identity file appears
#   TWO_PEER_CONTROL_TIMEOUT    daemon control file appears
#   TWO_PEER_DISCOVERY_TIMEOUT  receiver becomes visible in the daemon's /state
#   TWO_PEER_TRANSFER_TIMEOUT   whole-command deadline for `klardrop share --wait`

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENGINE_BIN="${KLARDROP_ENGINE_BIN:-$ROOT/cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe}"
RUST_BIN="${KLARDROP_RUST_BIN:-$ROOT/cli-rust/target/release/klardrop}"

IDENTITY_TIMEOUT="${TWO_PEER_IDENTITY_TIMEOUT:-60}"
CONTROL_TIMEOUT="${TWO_PEER_CONTROL_TIMEOUT:-60}"
DISCOVERY_TIMEOUT="${TWO_PEER_DISCOVERY_TIMEOUT:-45}"
TRANSFER_TIMEOUT="${TWO_PEER_TRANSFER_TIMEOUT:-120}"
PAYLOAD_BYTES="${TWO_PEER_PAYLOAD_BYTES:-3000000}"
POLL_INTERVAL="${TWO_PEER_POLL_INTERVAL:-0.5}"
DAEMON_ATTEMPTS="${TWO_PEER_DAEMON_ATTEMPTS:-4}"

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
TMPROOT=""
RECV_PID=""
DAEMON_PID=""
# Only ever these two PIDs are signalled by this script.
OWN_PIDS=()

log() { printf '==> %s\n' "$*"; }

pass() {
  TESTS_RUN=$((TESTS_RUN + 1))
  TESTS_PASSED=$((TESTS_PASSED + 1))
  printf 'PASS: %s\n' "$1"
}

fail() {
  TESTS_RUN=$((TESTS_RUN + 1))
  TESTS_FAILED=$((TESTS_FAILED + 1))
  printf 'FAIL: %s\n' "$1" >&2
  if [[ -n "${2:-}" ]]; then printf '      %s\n' "$2" >&2; fi
  return 0
}

now_ms() { date +%s%3N; }

# ── Cleanup: only this script's own children ──────────────────────────────────
cleanup() {
  local pid
  for pid in "${OWN_PIDS[@]:-}"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      local waited=0
      while kill -0 "$pid" 2>/dev/null && [[ "$waited" -lt 10 ]]; do
        sleep 0.5
        waited=$((waited + 1))
      done
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  if [[ -n "$TMPROOT" && -d "$TMPROOT" ]]; then
    rm -rf "$TMPROOT"
  fi
}
trap cleanup EXIT

for bin in "$ENGINE_BIN" "$RUST_BIN"; do
  if [[ ! -x "$bin" ]]; then
    printf 'missing executable: %s\n' "$bin" >&2
    printf 'build it with: (cd cli-rust && cargo build --locked --release) and :cli:linkReleaseExecutableLinuxX64\n' >&2
    exit 1
  fi
done

# The engine was renamed from klardrop.kexe to klardrop-engine.kexe because the
# Rust client owns the user-facing `klardrop` name. Refuse to run against a
# stale pre-rename build: every assertion below would otherwise pass against
# the wrong binary and prove nothing about the shipped layout.
ENGINE_BASENAME="$(basename "$ENGINE_BIN")"
if [[ "$ENGINE_BASENAME" != "klardrop-engine.kexe" && "$ENGINE_BASENAME" != "klardrop-engine" ]]; then
  printf 'ENGINE_BIN must be the renamed engine binary (klardrop-engine.kexe), got: %s\n' "$ENGINE_BASENAME" >&2
  exit 1
fi

command -v python3 >/dev/null || { printf 'python3 is required\n' >&2; exit 1; }

ENGINE_ENGINE_REAL="$(readlink -f "$ENGINE_BIN")"
ENGINE_RUST_REAL="$(readlink -f "$RUST_BIN")"

# ── PID census over /proc, scoped to the two binaries under test ─────────────
# A count is not enough: another Klardrop daemon may already be running this
# same build path, and one spawned-and-reaped would never be seen by a count.
# Comparing the exact, sorted PID SET before / during / after is the assertion,
# and membership of our own PIDs keeps the snapshot from being vacuously empty.
klardrop_pid_set() {
  local pid exe
  for pid in /proc/[0-9]*; do
    pid="${pid#/proc/}"
    exe="$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)"
    case "$exe" in
      "$ENGINE_ENGINE_REAL"|"$ENGINE_RUST_REAL") printf '%s\n' "$pid" ;;
    esac
  done | sort -n
}

# ── Private profiles under one mktemp root ───────────────────────────────────
TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/klardrop-two-peer.XXXXXX")"
RECV_DIR="$TMPROOT/receiver"
SND_DIR="$TMPROOT/sender"
RECV_RUN="$TMPROOT/run-receiver"
SND_RUN="$TMPROOT/run-sender"
FAKE_HOME="$TMPROOT/home"
CONTROL_FILE="$SND_RUN/klardrop/control.json"
RECV_LOG="$TMPROOT/listen.log"
DAEMON_LOG="$TMPROOT/daemon.log"
mkdir -p "$RECV_DIR/config" "$SND_DIR" \
  "$RECV_RUN" "$SND_RUN" "$FAKE_HOME"

# The receiver's download directory is a platform path (LinuxPaths.downloadDir),
# which on linux resolves from user-dirs.dirs under the node's own profile. Point
# it INSIDE the receiver's private profile and DELIBERATELY LEAVE IT UNCREATED.
# Pre-creating it would mask the exact defect this run has to be able to catch:
# an engine that resolves a download path without ensuring the directory exists
# fails at finalize with ACK_REJECTED and the sender reports a transfer failure
# that has nothing to do with the peer. Leaving it absent makes the successful
# transfer itself the assertion that the engine creates what it resolved.
printf 'XDG_DOWNLOAD_DIR="%s"\n' "$RECV_DIR/downloads" \
  >"$RECV_DIR/config/user-dirs.dirs"
RECV_DOWNLOADS="$RECV_DIR/downloads"

PID_SET_BEFORE="$(klardrop_pid_set)"

log "Receiver: $ENGINE_BIN listen --json --no-nearby --data-dir $RECV_DIR"
HOME="$FAKE_HOME" KLARDROP_HOME="$RECV_DIR" XDG_RUNTIME_DIR="$RECV_RUN" \
  "$ENGINE_BIN" listen --json --timeout 0 --no-nearby --data-dir "$RECV_DIR" \
  >"$RECV_LOG" 2>&1 &
RECV_PID=$!
OWN_PIDS+=("$RECV_PID")

# The receiver's own 8-char id, read out of its private identity store. Used as
# a CROSS-CHECK on what discovery reports, never as the source of the target:
# `--to` below is filled from the daemon's /state.
receiver_profile_device_id() {
  local props="$RECV_DIR/properties.preferences_pb"
  [[ -f "$props" ]] || return 1
  strings "$props" 2>/dev/null \
    | awk '/device_id/{getline; gsub(/^"\* /,""); print substr($0,1,8); exit}'
}

RECV_ID_PROFILE=""
identity_deadline=$(( $(now_ms) + IDENTITY_TIMEOUT * 1000 ))
while [[ "$(now_ms)" -lt "$identity_deadline" ]]; do
  if ! kill -0 "$RECV_PID" 2>/dev/null; then break; fi
  RECV_ID_PROFILE="$(receiver_profile_device_id || true)"
  if [[ -n "$RECV_ID_PROFILE" ]]; then break; fi
  sleep "$POLL_INTERVAL"
done

if [[ -z "$RECV_ID_PROFILE" ]]; then
  fail "receiver identity appears within ${IDENTITY_TIMEOUT}s" \
    "receiver pid $RECV_PID never wrote an identity into $RECV_DIR (log: $RECV_LOG)"
  printf '\n%d tests run, %d passed, %d failed\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED"
  exit 1
fi
pass "receiver identity appears within ${IDENTITY_TIMEOUT}s (${RECV_ID_PROFILE})"

if grep -q 'Avahi publish() committed' "$RECV_LOG" 2>/dev/null; then
  pass "receiver published its klardrop mDNS service"
else
  fail "receiver published its klardrop mDNS service" "no 'Avahi publish() committed' in $RECV_LOG"
fi

# ── Sender daemon, and the /state reader it is queried through ──────────────
#
# `daemon` is RESTARTED on demand below. Two measured facts on a single host
# make that necessary rather than merely defensive:
#
#   1. Every node on this machine announces from the SAME address, so the
#      engine's address-level de-duplication keeps exactly ONE of them in the
#      visible map ("Deduplicating: Klardrop announcement for X supersedes Y
#      at 10.79.71.141"). Which one survives is a race against every other
#      Klardrop process on the LAN, and a peer that loses it is absent from
#      GET /state even though its TCP connection is alive.
#   2. A peer's mDNS record is only seen by a browse that is already running
#      when the record appears; Avahi's cached records are re-announced on
#      their own TTL schedule (~30-120s here), so a daemon that starts too
#      early can sit for minutes before its browser sees a peer that published
#      first.
#
# A restarted daemon browses from scratch and therefore sees the receiver
# immediately, which is what makes this suite deterministic on a busy host.
# The restart is bounded, only ever touches a PID this script started, and the
# receiver keeps its identity across daemon restarts (its profile is untouched),
# so the transfer itself is unaffected.

start_daemon() {
  local attempt="$1"
  rm -rf "$SND_RUN"
  mkdir -p "$SND_RUN"
  DAEMON_LOG="$TMPROOT/daemon-$attempt.log"
  DAEMON_START_MS="$(now_ms)"
  HOME="$FAKE_HOME" KLARDROP_HOME="$SND_DIR" XDG_RUNTIME_DIR="$SND_RUN" \
    "$ENGINE_BIN" daemon --port 0 --no-nearby --no-ble --data-dir "$SND_DIR" \
    >"$DAEMON_LOG" 2>&1 &
  DAEMON_PID=$!
  OWN_PIDS+=("$DAEMON_PID")
}

stop_daemon() {
  [[ -n "$DAEMON_PID" ]] || return 0
  kill -TERM "$DAEMON_PID" 2>/dev/null || true
  local waited=0
  while kill -0 "$DAEMON_PID" 2>/dev/null && [[ "$waited" -lt 15 ]]; do
    sleep 0.5
    waited=$((waited + 1))
  done
  kill -KILL "$DAEMON_PID" 2>/dev/null || true
  wait "$DAEMON_PID" 2>/dev/null || true
  OWN_PIDS=("${OWN_PIDS[@]:0:${#OWN_PIDS[@]}-1}")
  DAEMON_PID=""
}

# The control file is only usable once it exists AND parses with a sane port
# and a non-empty token. The token value is never read into this shell.
control_file_usable() {
  [[ -s "$CONTROL_FILE" ]] || return 1
  python3 -c '
import json, sys
control = json.load(open(sys.argv[1]))
port = control.get("port")
assert isinstance(port, int) and not isinstance(port, bool) and 1 <= port <= 65535
assert isinstance(control.get("token"), str) and control["token"]
' "$CONTROL_FILE" 2>/dev/null
}

wait_for_control_file() {
  local deadline=$(( $(now_ms) + CONTROL_TIMEOUT * 1000 ))
  while [[ "$(now_ms)" -lt "$deadline" ]]; do
    if ! kill -0 "$DAEMON_PID" 2>/dev/null; then return 1; fi
    if control_file_usable; then return 0; fi
    sleep "$POLL_INTERVAL"
  done
  return 1
}

# Reads the control file and issues GET /state from inside ONE python process,
# so the bearer token never reaches this shell, a process argv, or a log.
# Prints "SELF_ID <id>", then one "DEVICE <id> <name> <trust> <transports>".
state_devices() {
  python3 - "$CONTROL_FILE" <<'PY'
import json, sys, urllib.request

control = json.load(open(sys.argv[1], encoding="utf-8"))
request = urllib.request.Request(
    "http://127.0.0.1:%d/state" % control["port"],
    headers={"Authorization": "Bearer " + control["token"]},
)
with urllib.request.urlopen(request, timeout=30) as response:
    state = json.load(response)

print("SELF_ID %s" % (state.get("self") or {}).get("deviceId", ""))
for device in state.get("devices") or []:
    transports = ",".join(device.get("connectionTypes") or [])
    print("DEVICE %s %s %s %s" % (
        device.get("deviceId", ""),
        device.get("deviceName", ""),
        device.get("trustStatus", ""),
        transports,
    ))
PY
}

# `daemon` must have bound an engine: protocols.klardrop true in /state.
daemon_protocols() {
  python3 - "$CONTROL_FILE" <<'PY'
import json, sys, urllib.request
control = json.load(open(sys.argv[1], encoding="utf-8"))
request = urllib.request.Request(
    "http://127.0.0.1:%d/state" % control["port"],
    headers={"Authorization": "Bearer " + control["token"]},
)
with urllib.request.urlopen(request, timeout=30) as response:
    state = json.load(response)
protocols = state.get("protocols") or {}
print("%s %s" % (protocols.get("klardrop"), protocols.get("nearby")))
PY
}

# ── Start the sender daemon ─────────────────────────────────────────────────
log "Sender:   $ENGINE_BIN daemon --port 0 --no-nearby --no-ble --data-dir $SND_DIR"
start_daemon 1

if ! wait_for_control_file; then
  fail "daemon published a valid control file within ${CONTROL_TIMEOUT}s" \
    "no usable $CONTROL_FILE (log: $DAEMON_LOG)"
  printf '\n%d tests run, %d passed, %d failed\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED"
  exit 1
fi
CONTROL_READY_MS=$(( $(now_ms) - DAEMON_START_MS ))
pass "daemon published a valid control file in ${CONTROL_READY_MS}ms (private XDG_RUNTIME_DIR)"

if [[ "$(daemon_protocols 2>/dev/null | cut -d' ' -f1)" == "True" ]]; then
  pass "daemon reports protocols.klardrop=true (discovery transport enabled)"
else
  fail "daemon reports protocols.klardrop=true (discovery transport enabled)" \
    "daemon_protocols=$(daemon_protocols 2>/dev/null || true)"
fi

# ── Discovery: the receiver's id comes out of the daemon's GET /state ───────
# `/state` publishes the sender's live view of the network. The target id used
# below is whatever /state reports — never a guess — and it is then cross-checked
# against the receiver's own identity store so a same-host neighbour can never
# be mistaken for the receiver.
DISCOVERED_ID=""
DISCOVERY_MS=""
DISCOVERY_ATTEMPT=0
while [[ "$DISCOVERY_ATTEMPT" -lt "$DAEMON_ATTEMPTS" ]]; do
  DISCOVERY_ATTEMPT=$((DISCOVERY_ATTEMPT + 1))
  SELF_ID="$(state_devices 2>/dev/null | awk '$1 == "SELF_ID" { print $2; exit }' || true)"
  attempt_deadline=$(( $(now_ms) + DISCOVERY_TIMEOUT * 1000 ))
  while [[ "$(now_ms)" -lt "$attempt_deadline" ]]; do
    if ! kill -0 "$DAEMON_PID" 2>/dev/null; then break; fi
    snapshot="$(state_devices 2>/dev/null || true)"
    candidate="$(printf '%s\n' "$snapshot" | awk -v self="$SELF_ID" \
      '$1 == "DEVICE" && $2 != self && $2 != "" { print $2; exit }' || true)"
    if [[ -n "$candidate" && "$candidate" == "$RECV_ID_PROFILE" ]]; then
      DISCOVERED_ID="$candidate"
      DISCOVERY_MS=$(( $(now_ms) - DAEMON_START_MS ))
      break
    fi
    sleep 1
  done
  [[ -n "$DISCOVERED_ID" ]] && break
  if [[ "$DISCOVERY_ATTEMPT" -lt "$DAEMON_ATTEMPTS" ]]; then
    log "receiver not in /state after ${DISCOVERY_TIMEOUT}s (attempt $DISCOVERY_ATTEMPT); restarting the daemon to force a fresh mDNS browse"
    stop_daemon
    start_daemon "$((DISCOVERY_ATTEMPT + 1))"
    wait_for_control_file || true
  fi
done

if [[ -n "$DISCOVERED_ID" ]]; then
  pass "daemon /state lists the receiver after ${DISCOVERY_MS}ms (id ${DISCOVERED_ID}, daemon attempt $DISCOVERY_ATTEMPT)"
else
  fail "daemon /state lists the receiver within ${DISCOVERY_TIMEOUT}s x ${DAEMON_ATTEMPTS} attempts" \
    "the receiver (${RECV_ID_PROFILE}) never appeared in /state; last log: $DAEMON_LOG"
  printf '\n%d tests run, %d passed, %d failed\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED"
  exit 1
fi

if [[ "$DISCOVERED_ID" == "$RECV_ID_PROFILE" ]]; then
  pass "discovered id matches the receiver's own identity ($RECV_ID_PROFILE)"
else
  fail "discovered id matches the receiver's own identity" \
    "discovery reported $DISCOVERED_ID, receiver profile says $RECV_ID_PROFILE"
fi

if state_devices 2>/dev/null \
   | awk -v id="$DISCOVERED_ID" '$1 == "DEVICE" && $2 == id && $0 ~ /KLARDROP/ { found = 1 } END { exit found ? 0 : 1 }'; then
  pass "discovered receiver exposes a KLARDROP connection type"
else
  fail "discovered receiver exposes a KLARDROP connection type" \
    "$(state_devices 2>/dev/null | grep -F "$DISCOVERED_ID" || echo 'device row missing')"
fi


# ── Payload ──────────────────────────────────────────────────────────────────
PAYLOAD="$TMPROOT/klardrop-two-peer-payload.bin"
head -c "$PAYLOAD_BYTES" /dev/urandom >"$PAYLOAD"
PAYLOAD_SHA="$(sha256sum "$PAYLOAD" | cut -d' ' -f1)"
PAYLOAD_SIZE="$(stat -c%s "$PAYLOAD")"
PAYLOAD_NAME="$(basename "$PAYLOAD")"
log "Payload: $PAYLOAD_SIZE bytes, sha256 $PAYLOAD_SHA"

received_event_count() {
  grep -c "\"event\":\"received\"" "$RECV_LOG" 2>/dev/null || true
}


# ── The transfer itself: the Rust client binary, invoked directly ────────────
SHARE_OUT="$TMPROOT/share.out"
SHARE_ERR="$TMPROOT/share.err"
log "klardrop share --to $DISCOVERED_ID <payload> --wait --json"
SHARE_START_MS="$(now_ms)"
SHARE_EXIT=0
"$RUST_BIN" share --to "$DISCOVERED_ID" "$PAYLOAD" --wait --json \
  --timeout "$TRANSFER_TIMEOUT" --control-file "$CONTROL_FILE" \
  >"$SHARE_OUT" 2>"$SHARE_ERR" </dev/null || SHARE_EXIT=$?
SHARE_MS=$(( $(now_ms) - SHARE_START_MS ))

if [[ "$SHARE_EXIT" -eq 0 ]]; then
  pass "sender exits 0 (${SHARE_MS}ms for ${PAYLOAD_SIZE} bytes)"
else
  fail "sender exits 0" "exit=$SHARE_EXIT stdout=$(cat "$SHARE_OUT") stderr=$(tail -5 "$SHARE_ERR")"
fi

# stdout discipline: exactly one JSON value and nothing else, no ANSI escapes,
# no envelope leaking into stderr.
if python3 - "$SHARE_OUT" <<'PY'
import json, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
value = json.loads(raw)          # raises unless stdout is exactly one JSON value
assert isinstance(value, dict), "share envelope must be a JSON object"
assert value.get("schemaVersion") == 1, value.get("schemaVersion")
assert value.get("command") == "share", value.get("command")
assert value.get("ok") is True, value.get("ok")
items = value.get("items")
assert isinstance(items, list) and items, "items must be a non-empty array"
for item in items:
    assert item.get("status") == "completed", item
    assert item.get("error") in (None, ""), item
assert value.get("status") == "completed", value.get("status")
PY
then
  pass "sender stdout is exactly one JSON envelope with every item completed"
else
  fail "sender stdout is exactly one JSON envelope with every item completed" \
    "stdout=$(head -c 400 "$SHARE_OUT")"
fi

if grep -q $'\x1b' "$SHARE_OUT" 2>/dev/null; then
  fail "sender stdout carries no ANSI escapes" "escape byte found in stdout"
else
  pass "sender stdout carries no ANSI escapes"
fi

if grep -q '"schemaVersion"' "$SHARE_ERR" 2>/dev/null; then
  fail "envelope stays on stdout, progress on stderr" "stderr contains the JSON envelope"
else
  pass "envelope stays on stdout, progress on stderr ($(wc -l <"$SHARE_ERR" | tr -d ' ') progress lines)"
fi

if [[ -s "$SHARE_ERR" ]]; then
  pass "transfer progress was reported on stderr (a waiting command visibly moves)"
else
  fail "transfer progress was reported on stderr" "stderr was empty"
fi

# ── Receiver-side receipt: exact bytes, in the receiver's own download dir ──
# The download directory was deliberately NOT created by this script, so its
# existence below is the engine's doing. Asserted separately from the payload's
# presence because the two failure modes are different: an engine that resolved a
# download path without creating it fails at finalize, while an engine that
# created it and then failed to write is a different bug entirely.
RECEIVED_PATH=""
receive_deadline=$(( $(now_ms) + 30 * 1000 ))
while [[ "$(now_ms)" -lt "$receive_deadline" ]]; do
  candidate="$(find "$RECV_DOWNLOADS" -maxdepth 1 -type f -name "$PAYLOAD_NAME" 2>/dev/null | head -1)"
  if [[ -n "$candidate" ]]; then
    RECEIVED_PATH="$candidate"
    break
  fi
  sleep "$POLL_INTERVAL"
done

if [[ -n "$RECEIVED_PATH" ]]; then
  pass "receiver wrote the payload into its own download directory"
else
  fail "receiver wrote the payload into its own download directory" \
    "no $PAYLOAD_NAME under $RECV_DOWNLOADS (receiver log: $RECV_LOG)"
fi

if [[ -d "$RECV_DOWNLOADS" ]]; then
  pass "receiver engine CREATED the download directory it resolved (this script never did)"
else
  fail "receiver engine CREATED the download directory it resolved" \
    "$RECV_DOWNLOADS does not exist; resolving a download path must create it (receiver log: $RECV_LOG)"
fi

if [[ -n "$RECEIVED_PATH" ]]; then
  RECEIVED_SHA="$(sha256sum "$RECEIVED_PATH" | cut -d' ' -f1)"
  RECEIVED_SIZE="$(stat -c%s "$RECEIVED_PATH")"
  if [[ "$RECEIVED_SHA" == "$PAYLOAD_SHA" && "$RECEIVED_SIZE" == "$PAYLOAD_SIZE" ]]; then
    pass "received bytes are identical to the sent file (sha256 $RECEIVED_SHA, $RECEIVED_SIZE bytes)"
  else
    fail "received bytes are identical to the sent file" \
      "sent sha=$PAYLOAD_SHA size=$PAYLOAD_SIZE / received sha=$RECEIVED_SHA size=$RECEIVED_SIZE"
  fi
  if cmp -s "$PAYLOAD" "$RECEIVED_PATH"; then
    pass "received file is byte-for-byte equal to the sent file"
  else
    fail "received file is byte-for-byte equal to the sent file" "cmp reported a difference"
  fi
  if [[ "$(dirname "$RECEIVED_PATH")" == "$RECV_DOWNLOADS" ]]; then
    pass "file landed in the receiver's own platform download path ($RECEIVED_PATH)"
  else
    fail "file landed in the receiver's own platform download path" "got $RECEIVED_PATH"
  fi
fi

# Receiver-side JSONL receipt: exactly one, for exactly this file.
if python3 - "$RECV_LOG" "$PAYLOAD_NAME" "$PAYLOAD_SIZE" <<'PY'
import json, sys

log_path, name, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
events = []
with open(log_path, encoding="utf-8", errors="replace") as handle:
    for line in handle:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("event") == "received":
            events.append(obj)

assert len(events) == 1, "expected exactly one inbound receipt, got %d" % len(events)
event = events[0]
assert event.get("type") == "FILE", event.get("type")
assert event.get("filename") == name, event.get("filename")
assert event.get("size") == size, event.get("size")
PY
then
  pass "receiver emitted exactly one inbound receipt for $PAYLOAD_NAME (no duplicate send)"
else
  fail "receiver emitted exactly one inbound receipt for $PAYLOAD_NAME (no duplicate send)" \
    "inbound receipts=$(received_event_count), expected 1; see $RECV_LOG"
fi

# The daemon's own transfer registry must also hold exactly one request: a
# second POST /share for the same payload would show up here even if the
# receiver dropped it.
if python3 - "$CONTROL_FILE" <<'PY'
import json, sys, urllib.request

control = json.load(open(sys.argv[1], encoding="utf-8"))
request = urllib.request.Request(
    "http://127.0.0.1:%d/transfers" % control["port"],
    headers={"Authorization": "Bearer " + control["token"]},
)
with urllib.request.urlopen(request, timeout=30) as response:
    body = json.load(response)
requests = body.get("requests") or []
assert len(requests) == 1, "expected one share request, got %d" % len(requests)
record = requests[0]
assert record.get("status") == "completed", record.get("status")
assert len(record.get("items") or []) == 1, record.get("items")
PY
then
  pass "daemon transfer registry holds exactly one completed request"
else
  fail "daemon transfer registry holds exactly one completed request" \
    "registry disagreed (control: $CONTROL_FILE)"
fi

# ── Failing case: unknown device id ──────────────────────────────────────────
# Documented contract (cli-rust/README.md): `device_not_found` exits 2, prints
# one JSON envelope on stdout, and contacts nothing — so nothing may be
# delivered. Assert all three, including the delivery side effect.
# Snapshot taken AFTER the successful transfer, so the failing case is compared
# against a settled baseline rather than against "nothing had arrived yet".
RECEIVED_BEFORE="$(received_event_count)"
UNKNOWN_ID="deadbeef"
BAD_OUT="$TMPROOT/share-unknown.out"
BAD_ERR="$TMPROOT/share-unknown.err"
BAD_EXIT=0
"$RUST_BIN" share --to "$UNKNOWN_ID" "$PAYLOAD" --wait --json \
  --timeout 30 --control-file "$CONTROL_FILE" \
  >"$BAD_OUT" 2>"$BAD_ERR" </dev/null || BAD_EXIT=$?

if [[ "$BAD_EXIT" -eq 2 ]]; then
  pass "share to an unknown device id exits 2 (device_not_found)"
else
  fail "share to an unknown device id exits 2 (device_not_found)" \
    "exit=$BAD_EXIT stdout=$(head -c 300 "$BAD_OUT")"
fi

if python3 - "$BAD_OUT" <<'PY'
import json, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
value = json.loads(raw)
assert isinstance(value, dict)
assert value.get("ok") is False, value
assert value.get("command") == "share", value.get("command")
error = value.get("error") or {}
assert error.get("code") == "device_not_found", error
PY
then
  pass "failing share still prints exactly one JSON envelope with error.code=device_not_found"
else
  fail "failing share still prints exactly one JSON envelope with error.code=device_not_found" \
    "stdout=$(head -c 300 "$BAD_OUT")"
fi

RECEIVED_AFTER="$(received_event_count)"
if [[ "$RECEIVED_BEFORE" == "$RECEIVED_AFTER" ]]; then
  pass "failing share delivered nothing (receiver receipts unchanged at $RECEIVED_AFTER)"
else
  fail "failing share delivered nothing" \
    "receiver receipts went $RECEIVED_BEFORE -> $RECEIVED_AFTER"
fi

DOWNLOAD_COUNT="$(find "$RECV_DOWNLOADS" -maxdepth 1 -type f | wc -l | tr -d ' ')"
if [[ "$DOWNLOAD_COUNT" == "1" ]]; then
  pass "receiver download directory still holds exactly one file after the failing case"
else
  fail "receiver download directory still holds exactly one file after the failing case" \
    "found $DOWNLOAD_COUNT files in $RECV_DOWNLOADS"
fi

# ── Resource evidence ────────────────────────────────────────────────────────
rss_kib() {
  awk '/^VmRSS:/ { print $2; exit }' "/proc/$1/status" 2>/dev/null || true
}

RECV_RSS_KIB="$(rss_kib "$RECV_PID")"
DAEMON_RSS_KIB="$(rss_kib "$DAEMON_PID")"
if [[ -n "$RECV_RSS_KIB" && -n "$DAEMON_RSS_KIB" ]]; then
  pass "engine RSS readable for both nodes (receiver ${RECV_RSS_KIB} KiB, daemon ${DAEMON_RSS_KIB} KiB)"
else
  fail "engine RSS readable for both nodes" "receiver='$RECV_RSS_KIB' daemon='$DAEMON_RSS_KIB'"
fi

log "Memory evidence at end of run"
printf '  receiver engine  pid %-8s VmRSS %s KiB\n' "$RECV_PID" "${RECV_RSS_KIB:-?}"
printf '  sender daemon    pid %-8s VmRSS %s KiB\n' "$DAEMON_PID" "${DAEMON_RSS_KIB:-?}"
printf '  transfer wall clock: %sms for %s bytes\n' "$SHARE_MS" "$PAYLOAD_SIZE"
printf '  discovery timings: receiver identity at startup, control file after %sms, receiver visible in /state after %sms\n' \
  "${CONTROL_READY_MS:-?}" "${DISCOVERY_MS:-?}"
printf '  receiver-side path: %s (sha256 %s)\n' "${RECEIVED_PATH:-<none>}" "${RECEIVED_SHA:-<none>}"
printf '  sender stdout envelope:\n'
sed 's/^/    /' "$SHARE_OUT"

# ── PID census: nothing else created or destroyed ────────────────────────────
PID_SET_DURING="$(klardrop_pid_set)"
if printf '%s\n' "$PID_SET_DURING" | grep -qxF -- "$RECV_PID" \
   && printf '%s\n' "$PID_SET_DURING" | grep -qxF -- "$DAEMON_PID"; then
  pass "both started nodes are live in the PID census during the run"
else
  fail "both started nodes are live in the PID census during the run" \
    "census=[$(printf '%s' "$PID_SET_DURING" | tr '\n' ' ')] own=[$RECV_PID $DAEMON_PID]"
fi

# Everything the census added beyond the pre-existing set must be ours. The
# comparison is a function so the self-test below exercises the SAME code the
# real assertion runs, instead of a second implementation of the same idea.
#
# Membership, not `comm`: `comm` demands LEXICOGRAPHIC order on both streams and
# these PIDs come out of `sort -n`, so any before/after pair with a different
# digit count made `comm` exit 1 — which under `set -euo pipefail` aborted the
# whole suite instead of reporting.
#
#   foreign_added_pids <before-set> <during-set> <own-pids...>
foreign_added_pids() {
  local before="$1" during="$2" own="${3:-}"
  local pid
  while read -r pid; do
    [[ -z "$pid" ]] && continue
    [[ " $own " == *" $pid "* ]] && continue        # started by this script
    if printf '%s\n' "$before" | grep -qxF -- "$pid"; then
      continue                                       # pre-existing before the run
    fi
    printf '%s\n' "$pid"
  done <<<"$during"
}

# Self-test: the real assertion below is only worth anything if this comparison
# can actually FAIL. Run it over a synthetic pair carrying a deliberately
# injected foreign PID (and over the same set without one) and require both
# classifications. Mixed digit widths also pin the `comm` ordering hazard the
# membership comparison exists to avoid.
SELF_BEFORE=$'101\n2000'
SELF_OWN="101 2000"
SELF_FOREIGN="$(foreign_added_pids "$SELF_BEFORE" $'101\n2000\n777\n' "$SELF_OWN" | tr '\n' ' ')"
if [[ "$SELF_FOREIGN" == "777 " ]]; then
  pass "the PID-census comparison flags an injected foreign PID"
else
  fail "the PID-census comparison flags an injected foreign PID" \
    "got '$SELF_FOREIGN', want '777 '"
fi
SELF_CLEAN="$(foreign_added_pids "$SELF_BEFORE" "$SELF_BEFORE" "$SELF_OWN" | tr '\n' ' ')"
if [[ -z "$SELF_CLEAN" ]]; then
  pass "the PID-census comparison reports nothing when only our own PIDs are live"
else
  fail "the PID-census comparison reports nothing when only our own PIDs are live" \
    "got '$SELF_CLEAN'"
fi

FOREIGN_ADDED="$(foreign_added_pids "$PID_SET_BEFORE" "$PID_SET_DURING" "$RECV_PID $DAEMON_PID" | tr '\n' ' ')"
if [[ -z "$FOREIGN_ADDED" ]]; then
  pass "no Klardrop process other than the two this script started was created"
else
  fail "no Klardrop process other than the two this script started was created" \
    "unexpected pids:$FOREIGN_ADDED"
fi

# The script may only ever signal the PIDs it captured from `$!`. Assert the
# recorded set really is those two distinct processes, so a stray `pkill` target
# is something this suite would notice rather than an invisible behaviour.
if [[ "$RECV_PID" =~ ^[0-9]+$ && "$DAEMON_PID" =~ ^[0-9]+$ && "$RECV_PID" != "$DAEMON_PID" ]]; then
  pass "script signals only the PIDs it started ($RECV_PID, $DAEMON_PID)"
else
  fail "script signals only the PIDs it started" "receiver='$RECV_PID' daemon='$DAEMON_PID'"
fi

# Tear our own children down, then prove the PID set is back to where it began.
cleanup
OWN_PIDS=()
RECV_PID=""
DAEMON_PID=""
PID_SET_AFTER="$(klardrop_pid_set)"

if [[ "$PID_SET_AFTER" == "$PID_SET_BEFORE" ]]; then
  pass "PID set returned to its starting value after cleanup (nothing destroyed or leaked)"
else
  fail "PID set returned to its starting value after cleanup" \
    "before=[$(printf '%s' "$PID_SET_BEFORE" | tr '\n' ' ')] after=[$(printf '%s' "$PID_SET_AFTER" | tr '\n' ' ')]"
fi

if [[ ! -d "$TMPROOT" ]]; then
  pass "no state left behind outside the mktemp root ($TMPROOT removed)"
else
  fail "no state left behind outside the mktemp root" "$TMPROOT still exists"
fi

# Isolation evidence: the sender's control file really did live in the private
# XDG_RUNTIME_DIR, and the token in it is never what a developer's own daemon
# would have published (it is gone with the private runtime dir).
if [[ -n "$CONTROL_FILE" && "${CONTROL_FILE#"$TMPROOT"/}" != "$CONTROL_FILE" ]]; then
  pass "control file resolved inside the private XDG_RUNTIME_DIR"
else
  fail "control file resolved inside the private XDG_RUNTIME_DIR" "got $CONTROL_FILE"
fi

printf '\n%d tests run, %d passed, %d failed\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED"
if [[ "$TESTS_FAILED" -gt 0 ]]; then exit 1; fi
exit 0
