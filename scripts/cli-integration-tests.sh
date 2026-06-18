#!/usr/bin/env bash
# Klardrop CLI integration tests (same-host, two isolated nodes).
#
# Model:
#   - Receiver (NODE_B): long-lived `listen` with its own --data-dir
#   - Sender   (NODE_A): fresh --data-dir per command (no cross-command reuse)
#   - Target device ID: read from receiver's properties after listener warms up
#
# Usage:
#   ./scripts/cli-integration-tests.sh [all|tier0|tier1|tier2|tier3|tier4]
#
# Requires: bash, python3, JDK 21, network loopback (mDNS/Bonjour on macOS)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TIER="${1:-all}"

CLI_TASK=":cli:jvmRun"
SETTLE_TIMEOUT="${KLARDROP_SETTLE_TIMEOUT:-25}"
LISTENER_WARMUP_SEC="${KLARDROP_LISTENER_WARMUP:-12}"
DISCOVER_TIMEOUT="${KLARDROP_DISCOVER_TIMEOUT:-20}"
RESTART_OFFLINE_SEC="${KLARDROP_RESTART_OFFLINE:-3}"
# Klardrop debounces mDNS browse refresh ~30s after ServiceLost; wait before re-discover.
RESTART_WARMUP_SEC="${KLARDROP_RESTART_WARMUP:-35}"
TIER4_SETTLE_TIMEOUT="${KLARDROP_TIER4_SETTLE:-60}"

TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/klardrop-cli-test.XXXXXX")"
LISTENER_PID=""
LISTENER_PIDS=()
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

log() { printf '==> %s\n' "$*"; }
pass() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_PASSED=$((TESTS_PASSED + 1)); printf 'PASS: %s\n' "$1"; }
fail() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_FAILED=$((TESTS_FAILED + 1)); printf 'FAIL: %s\n' "$1" >&2; [[ -n "${2:-}" ]] && printf '      %s\n' "$2" >&2; }

cleanup() {
  local pid
  for pid in "${LISTENER_PIDS[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  if [[ -n "$LISTENER_PID" ]] && kill -0 "$LISTENER_PID" 2>/dev/null; then
    kill "$LISTENER_PID" 2>/dev/null || true
    wait "$LISTENER_PID" 2>/dev/null || true
  fi
  if [[ -n "$TMPROOT" && -d "$TMPROOT" ]]; then
    rm -rf "$TMPROOT"
  fi
}
trap cleanup EXIT

# Run the CLI via Gradle. Remaining args are passed to klardrop after --args=.
# Sets RUN_CLI_EXIT (Gradle exit: 0 = JVM 0, non-zero = JVM failed or build error),
# RUN_CLI_STDOUT, and RUN_CLI_STDERR.
# Note: Gradle maps any non-zero JVM exit to task failure (usually Gradle exit 1),
# so usage-error assertions should check output, not the JVM exit code directly.
run_cli() {
  local args=("$@")
  local out err
  err="$(mktemp "$TMPROOT/gradle-err.XXXXXX")"
  out="$(./gradlew "$CLI_TASK" --quiet --args="${args[*]}" 2>"$err")" && RUN_CLI_EXIT=0 || RUN_CLI_EXIT=$?
  RUN_CLI_STDOUT="$out"
  RUN_CLI_STDERR="$(cat "$err")"
  rm -f "$err"
}

output_contains() {
  printf '%s\n%s' "$RUN_CLI_STDOUT" "$RUN_CLI_STDERR" | grep -Fq "$1"
}

# JSON commands emit a single object/array on stdout; pick the last valid JSON line.
cli_json_line() {
  printf '%s\n' "$RUN_CLI_STDOUT" | awk '
    /^[[:space:]]*\{.*\}[[:space:]]*$/ { line = $0 }
    /^[[:space:]]*\[.*\][[:space:]]*$/ { line = $0 }
    END { if (line != "") print line }
  '
}

# 8-char short device id from a node's data directory (after first init).
device_id_from_dir() {
  local dir="$1"
  local props="$dir/properties.preferences_pb"
  if [[ ! -f "$props" ]]; then
    return 1
  fi
  strings "$props" | awk '/device_id/{getline; gsub(/^"\* /,""); print substr($0,1,8)}'
}

wait_for_receiver_id() {
  local dir="$1"
  local attempts=30
  local id=""
  while (( attempts > 0 )); do
    id="$(device_id_from_dir "$dir" 2>/dev/null || true)"
    if [[ -n "$id" ]]; then
      printf '%s' "$id"
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  return 1
}

start_receiver() {
  local dir="$1"
  local logfile="$2"
  local timeout="$3"
  local append="${4:-false}"
  mkdir -p "$dir"
  if [[ "$append" != "true" ]]; then
    : >"$logfile"
  fi
  ./gradlew "$CLI_TASK" --quiet --args="listen --json --timeout=$timeout --data-dir=$dir" \
    >>"$logfile" 2>&1 &
  LISTENER_PID=$!
  LISTENER_PIDS+=("$LISTENER_PID")
}

stop_listener() {
  if [[ -n "$LISTENER_PID" ]] && kill -0 "$LISTENER_PID" 2>/dev/null; then
    kill "$LISTENER_PID" 2>/dev/null || true
    wait "$LISTENER_PID" 2>/dev/null || true
  fi
  LISTENER_PID=""
}

wait_for_peer() {
  local expected_id="$1"
  local max_attempts="${2:-$DISCOVER_TIMEOUT}"
  local i=0
  while (( i < max_attempts )); do
    # Fresh data-dir per probe — reusing one dir across discover calls hits SQLite migrations.
    local sender_dir
    sender_dir="$(fresh_sender_dir)"
    run_cli discover --json --timeout=5 --data-dir="$sender_dir"
    local devices_json
    devices_json="$(cli_json_line)"
    if [[ $RUN_CLI_EXIT -eq 0 ]] && [[ "$(discover_includes_device "$devices_json" "$expected_id")" == "true" ]]; then
      return 0
    fi
    sleep 2
    i=$((i + 2))
  done
  return 1
}

discover_includes_device() {
  local json="$1"
  local expected_id="$2"
  local json_file="$TMPROOT/.discover.json"
  printf '%s' "$json" >"$json_file"
  python3 - "$json_file" "$expected_id" <<'PY'
import json, sys
devices = json.load(open(sys.argv[1], encoding="utf-8"))
target = sys.argv[2]
print("true" if any(d.get("device_id") == target for d in devices) else "false")
PY
}

listen_received_text() {
  local logfile="$1"
  local expected_content="$2"
  python3 - "$logfile" "$expected_content" <<'PY'
import json, sys
path, expected = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8", errors="replace").read()
for line in text.splitlines():
    line = line.strip()
    if not line.startswith("{"):
        continue
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        continue
    if obj.get("event") == "received" and obj.get("type") == "TEXT" and obj.get("content") == expected:
        print("true")
        sys.exit(0)
print("false")
PY
}

listen_received_file() {
  local logfile="$1"
  local expected_filename="$2"
  local expected_size="$3"
  python3 - "$logfile" "$expected_filename" "$expected_size" <<'PY'
import json, sys
path, filename, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
text = open(path, encoding="utf-8", errors="replace").read()
for line in text.splitlines():
    line = line.strip()
    if not line.startswith("{"):
        continue
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        continue
    if (
        obj.get("event") == "received"
        and obj.get("type") == "FILE"
        and obj.get("filename") == filename
        and obj.get("size") == size
    ):
        print("true")
        sys.exit(0)
print("false")
PY
}

fresh_sender_dir() {
  local dir="$TMPROOT/sender-$RANDOM-$RANDOM"
  mkdir -p "$dir"
  printf '%s' "$dir"
}

# ── Tier 0: single-process smoke (no peer required) ─────────────────────────

run_tier0() {
  log "Tier 0 — CLI smoke"

  run_cli --help
  if [[ $RUN_CLI_EXIT -eq 0 ]] && printf '%s' "$RUN_CLI_STDOUT" | grep -q 'Commands:' \
    && printf '%s' "$RUN_CLI_STDOUT" | grep -q 'discover' \
    && printf '%s' "$RUN_CLI_STDOUT" | grep -q 'listen' \
    && printf '%s' "$RUN_CLI_STDOUT" | grep -q 'send' \
    && printf '%s' "$RUN_CLI_STDOUT" | grep -q 'status'; then
    pass "help lists discover, listen, send, status"
  else
    fail "help lists discover, listen, send, status"
  fi

  local dir
  dir="$(fresh_sender_dir)"
  run_cli status --json --data-dir="$dir"
  local status_json
  status_json="$(cli_json_line)"
  if [[ $RUN_CLI_EXIT -eq 0 ]] && printf '%s' "$status_json" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['running'] and d['device_count']==0" 2>/dev/null; then
    pass "status --json on fresh dir (running, no peers)"
  else
    fail "status --json on fresh dir" "exit=$RUN_CLI_EXIT json=$status_json"
  fi

  dir="$(fresh_sender_dir)"
  run_cli discover --json --timeout=2 --data-dir="$dir"
  local discover_json
  discover_json="$(cli_json_line)"
  if [[ $RUN_CLI_EXIT -eq 0 ]] && [[ "$discover_json" == "[]" ]]; then
    pass "discover --json --timeout=2 returns empty on isolated dir"
  else
    # Other LAN peers may appear; empty is ideal but not required on busy networks.
    if [[ $RUN_CLI_EXIT -eq 0 ]]; then
      pass "discover --json --timeout=2 completes (json=$discover_json)"
    else
      fail "discover --json --timeout=2" "exit=$RUN_CLI_EXIT"
    fi
  fi

  run_cli send
  if [[ $RUN_CLI_EXIT -ne 0 ]]; then
    pass "send without args fails"
  else
    fail "send without args should fail" "exit=$RUN_CLI_EXIT"
  fi

  dir="$(fresh_sender_dir)"
  run_cli send ffffffff --text=hi --data-dir="$dir" --settle-timeout=5
  if [[ $RUN_CLI_EXIT -ne 0 ]] && output_contains "not found"; then
    pass "send to unknown device reports not found"
  else
    fail "send to unknown device should report not found" "exit=$RUN_CLI_EXIT"
  fi
}

# ── Tier 1–2: two-node (receiver + fresh sender per command) ────────────────

run_two_node_tests() {
  local include_send="${1:-false}"
  local include_file="${2:-false}"

  local node_b="$TMPROOT/receiver"
  local listen_log="$TMPROOT/listen.log"
  local listener_timeout=180

  log "Starting receiver (listen --json, data-dir=$node_b)"
  start_receiver "$node_b" "$listen_log" "$listener_timeout"

  log "Waiting for receiver identity (up to ${LISTENER_WARMUP_SEC}s)..."
  local receiver_id=""
  local i=0
  while (( i < LISTENER_WARMUP_SEC )); do
    receiver_id="$(device_id_from_dir "$node_b" 2>/dev/null || true)"
    if [[ -n "$receiver_id" ]]; then
      break
    fi
    sleep 1
    i=$((i + 1))
  done

  if [[ -z "$receiver_id" ]]; then
    fail "receiver device id available after warmup"
    tail -20 "$listen_log" >&2 || true
    return 1
  fi
  log "Receiver device id: $receiver_id"

  if [[ "$TIER" == "all" || "$TIER" == "tier1" ]]; then
    log "Tier 1 — discovery"

    local sender_discover
    sender_discover="$(fresh_sender_dir)"
    run_cli discover --json --timeout="$DISCOVER_TIMEOUT" --data-dir="$sender_discover"
    local devices_json
    devices_json="$(cli_json_line)"
    if [[ $RUN_CLI_EXIT -eq 0 ]] && [[ "$(discover_includes_device "$devices_json" "$receiver_id")" == "true" ]]; then
      pass "discover finds receiver by device_id ($receiver_id)"
    else
      fail "discover finds receiver by device_id ($receiver_id)" "exit=$RUN_CLI_EXIT json=$devices_json"
    fi

    # status is an immediate snapshot (no settle wait); peer visibility is
    # already covered by discover above. Here we only assert valid JSON shape.
    local sender_status
    sender_status="$(fresh_sender_dir)"
    run_cli status --json --data-dir="$sender_status"
    local status_json
    status_json="$(cli_json_line)"
    if [[ $RUN_CLI_EXIT -eq 0 ]] && printf '%s' "$status_json" | python3 -c "
import json, sys
d = json.load(sys.stdin)
assert d['running'] is True
assert 'device_count' in d and 'devices' in d
" 2>/dev/null; then
      pass "status --json returns valid schema while receiver is up"
    else
      fail "status --json returns valid schema while receiver is up" "json=$status_json"
    fi
  fi

  if [[ "$include_send" == "true" ]] && { [[ "$TIER" == "all" ]] || [[ "$TIER" == "tier2" ]]; }; then
    log "Tier 2 — text send"

    local payload="klardrop-cli-integration-$(date +%s)"
    local sender_send
    sender_send="$(fresh_sender_dir)"
    run_cli send "$receiver_id" --text="$payload" --data-dir="$sender_send" --settle-timeout="$SETTLE_TIMEOUT"
    if [[ $RUN_CLI_EXIT -eq 0 ]]; then
      pass "send text exits 0"
    else
      fail "send text exits 0" "exit=$RUN_CLI_EXIT"
    fi

    # Give the receiver a moment to flush JSONL to the log.
    sleep 2
    if [[ "$(listen_received_text "$listen_log" "$payload")" == "true" ]]; then
      pass "receiver got text payload in listen --json output"
    else
      fail "receiver got text payload in listen --json output" "expected content=$payload"
      grep -E 'received|TEXT|Failed' "$listen_log" | tail -10 >&2 || true
    fi

    local sender_bad_file
    sender_bad_file="$(fresh_sender_dir)"
    run_cli send "$receiver_id" --file=/nonexistent/klardrop-test-file --data-dir="$sender_bad_file" --settle-timeout="$SETTLE_TIMEOUT"
    if [[ $RUN_CLI_EXIT -ne 0 ]] && output_contains "File not found"; then
      pass "send missing file reports file not found"
    else
      fail "send missing file should report file not found" "exit=$RUN_CLI_EXIT"
    fi
  fi

  if [[ "$include_file" == "true" ]] && { [[ "$TIER" == "all" ]] || [[ "$TIER" == "tier3" ]]; }; then
    log "Tier 3 — empty file transfer"

    local empty_file="$TMPROOT/empty.bin"
    touch "$empty_file"

    local sender_file
    sender_file="$(fresh_sender_dir)"
    run_cli send "$receiver_id" --file="$empty_file" --data-dir="$sender_file" --settle-timeout="$SETTLE_TIMEOUT"
    if [[ $RUN_CLI_EXIT -eq 0 ]]; then
      pass "send empty file exits 0"
    else
      fail "send empty file exits 0" "exit=$RUN_CLI_EXIT"
    fi

    sleep 2
    if [[ "$(listen_received_file "$listen_log" "empty.bin" 0)" == "true" ]]; then
      pass "receiver got empty file in listen --json output"
    else
      fail "receiver got empty file in listen --json output" "file=$empty_file"
      grep -E 'received|FILE|Failed' "$listen_log" | tail -10 >&2 || true
    fi
  fi
}

# ── Tier 4: peer goes offline then back; stayer sends again ─────────────────
#
# Layout:
#   - Receiver B: `listen` — killed and restarted mid-test (offline → online)
#   - Stayer A:   `listen` — stays up for the whole test (the surviving instance)
#   - Sends:      fresh sender dir per message (CLI cannot listen+send same data-dir)
#
# Flow:
#   1. Start B (receiver) and A (stayer)
#   2. Send msg1 → B (establishes connection while A is online)
#   3. Kill + restart B (same data-dir → same device id)
#   4. Wait until B is discoverable again
#   5. Send msg2 → B (stayer still up; verifies connection rebuilds after peer restart)

start_listener_bg() {
  local dir="$1"
  local logfile="$2"
  local timeout="$3"
  local append="${4:-false}"
  mkdir -p "$dir"
  if [[ "$append" != "true" ]]; then
    : >"$logfile"
  fi
  ./gradlew "$CLI_TASK" --quiet --args="listen --json --timeout=$timeout --data-dir=$dir" \
    >>"$logfile" 2>&1 &
  local pid=$!
  LISTENER_PIDS+=("$pid")
  printf '%s' "$pid"
}

stop_pid() {
  local pid="$1"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

run_tier4_peer_restart() {
  local node_b="$TMPROOT/receiver-restart"
  local node_a="$TMPROOT/stayer-sender"
  local listen_b_log="$TMPROOT/listen-b-restart.log"
  local listen_a_log="$TMPROOT/listen-a-stayer.log"
  local listener_timeout=240
  local receiver_id=""
  local stayer_id=""
  local b_pid=""
  local a_pid=""

  log "Tier 4 — connection resumes after peer restart"

  log "Starting receiver B (listen --json, data-dir=$node_b)"
  b_pid="$(start_listener_bg "$node_b" "$listen_b_log" "$listener_timeout" false)"

  local i=0
  while (( i < LISTENER_WARMUP_SEC )); do
    receiver_id="$(device_id_from_dir "$node_b" 2>/dev/null || true)"
    if [[ -n "$receiver_id" ]]; then
      break
    fi
    sleep 1
    i=$((i + 1))
  done
  if [[ -z "$receiver_id" ]]; then
    fail "tier4: receiver B device id available after warmup"
    return 1
  fi
  log "Receiver B device id: $receiver_id"

  log "Starting stayer A (listen --json, data-dir=$node_a)"
  a_pid="$(start_listener_bg "$node_a" "$listen_a_log" "$listener_timeout" false)"

  i=0
  while (( i < LISTENER_WARMUP_SEC )); do
    stayer_id="$(device_id_from_dir "$node_a" 2>/dev/null || true)"
    if [[ -n "$stayer_id" ]]; then
      break
    fi
    sleep 1
    i=$((i + 1))
  done
  if [[ -z "$stayer_id" ]]; then
    fail "tier4: stayer A device id available after warmup"
    return 1
  fi
  log "Stayer A device id: $stayer_id"

  local payload1="restart-peer-1-$(date +%s)"
  local payload2="restart-peer-2-$(date +%s)"

  send_to_receiver() {
    local payload="$1"
    local sender_dir
    sender_dir="$(fresh_sender_dir)"
    run_cli send "$receiver_id" --text="$payload" --data-dir="$sender_dir" --settle-timeout="$TIER4_SETTLE_TIMEOUT"
    return "$RUN_CLI_EXIT"
  }

  log "Sending first message → B (while stayer A is online)"
  if send_to_receiver "$payload1"; then
    pass "tier4: first send succeeds while both instances are up"
  else
    fail "tier4: first send succeeds while both instances are up" "exit=$?"
  fi

  sleep 2
  if [[ "$(listen_received_text "$listen_b_log" "$payload1")" == "true" ]]; then
    pass "tier4: receiver B got first message"
  else
    fail "tier4: receiver B got first message" "payload=$payload1"
  fi

  log "Restarting receiver B (simulate offline → online, same data-dir)"
  stop_pid "$b_pid"
  b_pid=""
  log "Peer offline for ${RESTART_WARMUP_SEC}s (mDNS debounce)..."
  sleep "$RESTART_WARMUP_SEC"

  b_pid="$(start_listener_bg "$node_b" "$listen_b_log" "$listener_timeout" true)"

  log "Waiting ${LISTENER_WARMUP_SEC}s for restarted B to publish..."
  sleep "$LISTENER_WARMUP_SEC"

  if wait_for_peer "$receiver_id" 10; then
    log "Restarted B visible in discover"
  else
    log "Restarted B not yet in discover; relying on send settle-timeout (${TIER4_SETTLE_TIMEOUT}s)"
  fi

  log "Sending second message → B (stayer A still up, B restarted)"
  if send_to_receiver "$payload2"; then
    pass "tier4: second send succeeds after peer restart"
  else
    fail "tier4: second send succeeds after peer restart" "exit=$?"
  fi

  sleep 2
  if [[ "$(listen_received_text "$listen_b_log" "$payload2")" == "true" ]]; then
    pass "tier4: receiver B got second message after restart"
  else
    fail "tier4: receiver B got second message after restart" "payload=$payload2"
    grep -E 'received|TEXT|Failed|disconnect' "$listen_b_log" | tail -15 >&2 || true
  fi
}

# ── Main ────────────────────────────────────────────────────────────────────

case "$TIER" in
  all)
    run_tier0
    run_two_node_tests true true
    run_tier4_peer_restart
    ;;
  tier0)
    run_tier0
    ;;
  tier1)
    run_two_node_tests false false
    ;;
  tier2)
    run_two_node_tests true false
    ;;
  tier3)
    run_two_node_tests true true
    ;;
  tier4)
    run_tier4_peer_restart
    ;;
  *)
    printf 'Usage: %s [all|tier0|tier1|tier2|tier3|tier4]\n' "$0" >&2
    exit 2
    ;;
esac

printf '\n%d tests run, %d passed, %d failed\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED"
if (( TESTS_FAILED > 0 )); then
  exit 1
fi