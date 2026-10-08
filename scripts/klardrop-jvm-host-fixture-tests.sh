#!/usr/bin/env bash
# Production JVM-host fixture for the native Rust client (cli-rust/).
#
# The native fixture (scripts/klardrop-rust-fixture-tests.sh) proves the client
# against a Kotlin/Native Linux engine. This script proves the SAME client against
# the OTHER production host: the Kotlin/JVM engine, i.e. the exact control-plane
# code path the macOS and Windows desktop apps use.
#
# Why the JVM CLI daemon and not the Compose desktop app: `desktop`'s JVM target is
# a Compose Desktop application, so it needs a display, and this machine has no
# `xvfb-run`. The `:cli` JVM target is headless and links the same
# `:control-plane` `desktopJvm` actuals, so it is a faithful production JVM host.
#
#   :cli/build.gradle.kts      -> kotlin { jvm { mainRun { mainClass = "com.carlom.klardrop.cli.MainKt" } } }
#   DaemonCommand.kt:70-76    -> ApplicationInfo(isDebug = debug, controlPort = port)
#   CliLogging.kt:4           -> var isDebugMode: Boolean = false
#   ControlCapabilities.kt:46 -> forBuild(isDebug) = PRODUCTION_CAPABILITIES when false
#
# Started WITHOUT `--debug`, the daemon therefore runs ControlPlane.forBuild(false):
# the production capability list, with no `logs`, `window` or `reset-identity`.
# That is the evidence this script exists to collect.
#
# Checks (the native fixture's read-only set, plus the production-not-debug proof):
#   - devices/status/discover answer from ONE already-running engine that was
#     started with every discovery transport disabled;
#   - absent / stale / malformed control metadata fails promptly with exit 3;
#   - `--json` stdout is exactly one JSON value, even with --debug and on failure;
#   - no second engine process appears (PID SET comparison, not a count);
#   - control.json is 0600, advertises apiVersion 1, and advertises NO debug-only
#     capability;
#   - the developer's real control file and identity store are untouched.
#
# Usage:
#   ./scripts/klardrop-jvm-host-fixture-tests.sh [path-to-klardrop-rust-binary]
#
# Environment:
#   KLARDROP_RUST_BIN    Rust client binary (default: cli-rust/target/release/klardrop)
#   KLARDROP_JVM_RUN_TASK  Gradle task that runs the CLI on the JVM
#                          (default: :cli:jvmRun — the `mainRun` block of
#                          cli/build.gradle.kts registers it for the `jvm` target)
#
# Nothing outside a private mktemp directory is touched: KLARDROP_HOME and
# XDG_RUNTIME_DIR point into $TMPROOT. The bearer token is never printed; every
# diagnostic that would show control.json goes through `redact_control_file` /
# `redact_log`, which replace the token value with <redacted>.
#
# Only processes this script starts are ever signalled: the Gradle launcher it
# spawns, and the JVM daemon carrying this run's unique mktemp marker.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUST_BIN="${1:-${KLARDROP_RUST_BIN:-$ROOT/cli-rust/target/release/klardrop}}"
JVM_RUN_TASK="${KLARDROP_JVM_RUN_TASK:-:cli:jvmRun}"
DAEMON_MARKER=""          # set once TMPROOT exists; unique to this run
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
TMPROOT=""
GRADLE_PID=""
DAEMON_LOG=""

log() { printf '==> %s\n' "$*"; }
pass() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_PASSED=$((TESTS_PASSED + 1)); printf 'PASS: %s\n' "$1"; }
fail() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_FAILED=$((TESTS_FAILED + 1)); printf 'FAIL: %s\n' "$1" >&2; if [[ -n "${2:-}" ]]; then printf '      %s\n' "$2" >&2; fi; return 0; }

die() { printf 'ERROR: %s\n' "$1" >&2; if [[ -n "${2:-}" ]]; then printf '%s\n' "$2" >&2; fi; exit 1; }

# ── Secret redaction ──────────────────────────────────────────────────────────

redact_control_file() {
  # $1: control file path. Prints it with the token value replaced by <redacted>.
  python3 - "$1" <<'PY'
import json, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
try:
    doc = json.loads(raw)
except Exception:
    print("<unparsable control file, %d bytes>" % len(raw))
    raise SystemExit(0)
if isinstance(doc, dict) and "token" in doc:
    doc["token"] = "<redacted>"
print(json.dumps(doc))
PY
}

redact_log() {
  # $1: any text file. Replaces every "token":"..." occurrence.
  python3 - "$1" <<'PY'
import re, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
sys.stdout.write(re.sub(r'("token"\s*:\s*")[^"]*(")', r'\1<redacted>\2', raw))
PY
}

# ── Preconditions ─────────────────────────────────────────────────────────────
# Every failure here prints the exact, runnable command that fixes it. Nothing is
# skipped silently: an unavailable JVM run is a red test, not a green no-op.

if [[ ! -x "$RUST_BIN" ]]; then
  die "missing Rust client binary: $RUST_BIN" "build it with: (cd cli-rust && cargo build --locked --release)"
fi
if ! command -v python3 >/dev/null; then
  die "python3 is required (JSON assertions + the redactor)"
fi
if [[ ! -x "$ROOT/gradlew" ]]; then
  die "missing Gradle wrapper: $ROOT/gradlew" "run this from a checkout of the klardrop repository"
fi
if ! command -v java >/dev/null; then
  die "no 'java' on PATH — the JVM host cannot start" "install a JDK 21+ (CI uses actions/setup-java with zulu 25) and re-run"
fi

# ── Private root ──────────────────────────────────────────────────────────────
REAL_XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}"
TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/klardrop-jvm-host-fixture.XXXXXX")"
export KLARDROP_HOME="$TMPROOT/data"
export XDG_RUNTIME_DIR="$TMPROOT/run"
mkdir -p "$KLARDROP_HOME" "$XDG_RUNTIME_DIR"
CONTROL_FILE="$XDG_RUNTIME_DIR/klardrop/control.json"
DAEMON_MARKER="--data-dir=$KLARDROP_HOME"
DAEMON_LOG="$TMPROOT/jvm-daemon.log"

# The JVM host is a headless daemon; never let an AWT/Skiko probe reach a display.
if [[ "${JAVA_TOOL_OPTIONS:-}" != *"-Djava.awt.headless=true"* ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.awt.headless=true"
fi

cleanup() {
  local rc=$?
  local pid
  local waited=0
  # Only processes this script started are ever signalled: the JVM daemons carrying
  # this run's unique mktemp marker, and the Gradle launcher we spawned ourselves.
  if [[ -n "$DAEMON_MARKER" ]] && declare -F daemon_pid_set >/dev/null; then
    while read -r pid; do
      [[ -n "$pid" ]] || continue
      if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
      fi
    done < <(daemon_pid_set 2>/dev/null || true)
    # Give the daemon's shutdown hook time to run so it deletes its own control.json.
    while [[ -n "$(daemon_pid_set 2>/dev/null || true)" ]]; do
      waited=$((waited + 1))
      if [[ "$waited" -gt 20 ]]; then
        while read -r pid; do
          [[ -n "$pid" ]] || continue
          kill -KILL "$pid" 2>/dev/null || true
        done < <(daemon_pid_set 2>/dev/null || true)
        break
      fi
      sleep 0.5
    done
  fi
  if [[ -n "$GRADLE_PID" ]] && kill -0 "$GRADLE_PID" 2>/dev/null; then
    sleep 1
    kill -TERM "$GRADLE_PID" 2>/dev/null || true
    sleep 1
    kill -KILL "$GRADLE_PID" 2>/dev/null || true
    wait "$GRADLE_PID" 2>/dev/null || true
  fi
  if [[ -n "$TMPROOT" && -d "$TMPROOT" ]]; then rm -rf "$TMPROOT"; fi
  exit $rc
}
trap cleanup EXIT

# ── Helpers ───────────────────────────────────────────────────────────────────

# PIDs of every JVM Klardrop daemon started by THIS run. Identity is the unique
# mktemp marker passed as --data-dir (plus the CLI's main class), so a developer's
# own daemon can never be selected, and a daemon the client might have started on
# its own would carry the same env and therefore the same marker.
#
# Process enumeration needs procfs. On a host without it (macOS) the two
# process-identity checks below report themselves as skipped with a reason —
# they are never quietly reported as passing. CI runs this fixture on Linux.
PROCFS_AVAILABLE=0
if [[ -d /proc ]]; then PROCFS_AVAILABLE=1; fi

# Why a PID SET and not a count: a count cannot distinguish "one engine, as before"
# from "two engines, one of which the client started and reaped" nor from "a
# developer's daemon was already using this build path". Comparing the exact
# sorted PID set before and after proves the set is unchanged; the separate
# non-empty assertion proves the snapshot was not vacuous.

daemon_pid_set() {
  python3 - "$DAEMON_MARKER" <<'PY'
import os, sys
marker = sys.argv[1].encode("utf-8")
main_class = b"com.carlom.klardrop.cli.MainKt"
pids = []
for entry in os.listdir("/proc"):
    if not entry.isdigit():
        continue
    try:
        with open("/proc/%s/cmdline" % entry, "rb") as handle:
            cmdline = handle.read()
    except OSError:
        continue            # already exited
    if main_class in cmdline and marker in cmdline:
        pids.append(int(entry))
print("\n".join(str(pid) for pid in sorted(pids)))
PY
}

CLIENT_RUNS=0
CLIENT_OUTPUTS=()
run_client() {
  # One capture file PER INVOCATION: reusing a single path truncated every earlier run, so a token
  # printed by an earlier command would have been overwritten before the probe read it.
  CLIENT_RUNS="$((CLIENT_RUNS + 1))"
  OUT="$TMPROOT/out.$$.$CLIENT_RUNS"
  ERR="$TMPROOT/err.$$.$CLIENT_RUNS"
  CLIENT_OUTPUTS+=("$OUT" "$ERR")
  rc=0
  "$RUST_BIN" "$@" >"$OUT" 2>"$ERR" </dev/null || rc=$?
}

stdout_is_single_json() {
  python3 - "$OUT" <<'PY'
import json, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
json.loads(raw)          # raises unless stdout is exactly one JSON value
PY
}

stdout_has_no_escapes() {
  # python rather than `grep -P`: BSD grep (macOS) has no PCRE, and the fixture
  # must stay runnable on the same hosts the Rust shell targets.
  python3 -c 'import sys
raw = open(sys.argv[1], "rb").read()
raise SystemExit(1 if (b"\x1b" in raw or b"\x07" in raw) else 0)' "$OUT"
}

json_field() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));
for k in sys.argv[2].split("."):
    d = d[int(k)] if k.lstrip("-").isdigit() else d[k]
print(d if isinstance(d,str) else json.dumps(d))' "$1" "$2"; }

snapshot_real_paths() {
  # Read-only record of every path a Klardrop host touches when KLARDROP_HOME /
  # XDG_RUNTIME_DIR are NOT overridden: ControlFile.desktopJvm.kt's
  # resolveControlFilePath() (XDG_RUNTIME_DIR, else $HOME/.cache; LOCALAPPDATA on
  # Windows) and the platform data dirs. Only existence + size + mtime.
  python3 - "$1" "$REAL_XDG_RUNTIME_DIR" <<'PY'
import os, sys
out_path, real_xdg = sys.argv[1], sys.argv[2]
home = os.path.expanduser("~")
def env(name, default):
    value = os.environ.get(name) or ""
    return value if value.strip() else default
targets = []
if sys.platform == "win32":
    targets.append(os.path.join(env("LOCALAPPDATA", os.path.join(home, "AppData", "Local")), "Klardrop"))
else:
    if real_xdg.strip():
        targets.append(os.path.join(real_xdg, "klardrop"))
    targets += [
        os.path.join(env("XDG_CACHE_HOME", os.path.join(home, ".cache")), "klardrop"),
        os.path.join(home, ".klardrop"),
        os.path.join(env("XDG_DATA_HOME", os.path.join(home, ".local", "share")), "klardrop"),
        os.path.join(env("XDG_CONFIG_HOME", os.path.join(home, ".config")), "klardrop"),
    ]
lines = []
for root in targets:
    if not os.path.lexists(root):
        lines.append("%s\t<absent>" % root)
        continue
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames) + sorted(dirnames):
            path = os.path.join(dirpath, name)
            try:
                st = os.lstat(path)
                lines.append("%s\t%d\t%d" % (path, st.st_size, st.st_mtime_ns))
            except OSError as exc:
                lines.append("%s\t<stat failed errno=%s>" % (path, exc.errno))
    try:
        st = os.lstat(root)
        lines.append("%s\t%d\t%d" % (root, st.st_size, st.st_mtime_ns))
    except OSError:
        pass
with open(out_path, "w") as handle:
    handle.write("\n".join(lines) + "\n")
PY
}

snapshot_real_paths "$TMPROOT/real-before.txt"

# ── Start the production JVM host ─────────────────────────────────────────────
# No --debug: ApplicationInfo(isDebug = false) => ControlPlane.forBuild(false),
# i.e. PRODUCTION_CAPABILITIES only.
log "Starting production JVM host: ./gradlew $JVM_RUN_TASK --args=\"daemon --port 0 --no-klardrop --no-nearby --no-ble\""
: >"$DAEMON_LOG"
./gradlew "$JVM_RUN_TASK" \
  --args="daemon --port 0 --no-klardrop --no-nearby --no-ble $DAEMON_MARKER" \
  >"$DAEMON_LOG" 2>&1 &
GRADLE_PID=$!

waited=0
while [[ ! -s "$CONTROL_FILE" ]]; do
  if grep -qE "Task '[^']+' not found in root project|Unknown command '[^']*'" "$DAEMON_LOG" 2>/dev/null; then
    die "the JVM run task '$JVM_RUN_TASK' is not available in this checkout" \
"Gradle reported:
$(redact_log "$DAEMON_LOG")

Run it once by hand to see the failure, e.g.:
  ./gradlew $JVM_RUN_TASK --args=\"daemon --port 0 --no-klardrop --no-nearby --no-ble\"
The task comes from the 'mainRun { mainClass = \"com.carlom.klardrop.cli.MainKt\" }' block
of the 'jvm { }' target in cli/build.gradle.kts; override the name with
KLARDROP_JVM_RUN_TASK if this checkout registers it differently."
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    die "the JVM host exited before publishing $CONTROL_FILE" "$(redact_log "$DAEMON_LOG")"
  fi
  waited=$((waited + 1))
  if [[ "$waited" -gt 1200 ]]; then   # 10 min: a cold :cli:jvmRun JVM build plus JVM start
    die "timed out waiting for $CONTROL_FILE" "$(redact_log "$DAEMON_LOG")"
  fi
  sleep 0.5
done

DAEMON_PIDS_BEFORE=""
if [[ "$PROCFS_AVAILABLE" -eq 1 ]]; then
  DAEMON_PIDS_BEFORE="$(daemon_pid_set)"
  if [[ -n "$DAEMON_PIDS_BEFORE" ]]; then
    pass "production JVM host published $CONTROL_FILE (daemon pid set: ${DAEMON_PIDS_BEFORE//$'\n'/ })"
  else
    fail "production JVM host is running and published $CONTROL_FILE" \
      "no JVM process carries this run's marker ($DAEMON_MARKER)"
  fi
else
  log "SKIPPED: no /proc on this host, so the daemon PID-set check cannot run"
fi

# ── The control file is a PRODUCTION control file ─────────────────────────────

API_VERSION="$(json_field "$CONTROL_FILE" apiVersion 2>/dev/null || true)"
if [[ "$API_VERSION" == "1" ]]; then
  pass "control file advertises apiVersion 1"
else
  fail "control file advertises apiVersion 1" "got '${API_VERSION:-<none>}' in $(redact_control_file "$CONTROL_FILE")"
fi

CONTROL_MODE="$(python3 -c 'import os,stat,sys;print("%o" % stat.S_IMODE(os.lstat(sys.argv[1]).st_mode))' "$CONTROL_FILE" 2>/dev/null || true)"
if [[ "$CONTROL_MODE" == "600" ]]; then
  pass "control file is owner-only (0600)"
else
  fail "control file is owner-only (0600)" "mode=${CONTROL_MODE:-<unreadable>}"
fi

# The production-not-debug evidence: DEBUG_ONLY_CAPABILITIES (ControlCapabilities.kt)
# must be absent, and the production list must actually be there.
if python3 - "$CONTROL_FILE" <<'PY'
import json, sys
caps = json.load(open(sys.argv[1])).get("capabilities")
if not isinstance(caps, list):
    print("capabilities is not a list")
    raise SystemExit(1)
debug_only = {"logs", "window", "reset-identity"}
leaked = sorted(debug_only.intersection(caps))
if leaked:
    print("debug-only capabilities advertised: %s" % ", ".join(leaked))
    raise SystemExit(1)
required = {"state", "capabilities", "send-text", "send-file", "share", "transfers"}
missing = sorted(required.difference(caps))
if missing:
    print("production capabilities missing: %s" % ", ".join(missing))
    raise SystemExit(1)
print(", ".join(caps))
PY
then
  pass "capabilities are the production list (no logs/window/reset-identity)"
else
  fail "capabilities are the production list (no logs/window/reset-identity)" \
    "control file: $(redact_control_file "$CONTROL_FILE")"
fi

# ── Read-only commands against the live production JVM host ───────────────────

run_client devices --json --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json && stdout_has_no_escapes \
  && [[ "$(json_field "$OUT" ok)" == "true" ]] \
  && [[ "$(json_field "$OUT" schemaVersion)" == "1" ]] \
  && [[ "$(json_field "$OUT" command)" == "devices" ]]; then
  pass "devices --json returns one clean envelope"
else
  fail "devices --json returns one clean envelope" "exit=$rc stdout=$(redact_log "$OUT")"
fi

run_client status --json --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json && stdout_has_no_escapes; then
  SELF_ID="$(json_field "$OUT" self.deviceId 2>/dev/null || true)"
  if [[ -n "$SELF_ID" ]] && [[ "$(json_field "$OUT" daemon.apiVersion)" == "1" ]]; then
    pass "status --json reports the running JVM host (apiVersion 1, self $SELF_ID)"
  else
    fail "status --json reports the running JVM host" "stdout=$(redact_log "$OUT")"
  fi
else
  fail "status --json reports the running JVM host" "exit=$rc stdout=$(redact_log "$OUT")"
fi

run_client discover --json --wait 2 --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json && stdout_has_no_escapes \
  && [[ "$(python3 -c 'import json,sys;print(type(json.load(open(sys.argv[1]))).__name__)' "$OUT" 2>/dev/null || true)" == "list" ]]; then
  pass "discover --json keeps the legacy JSON array shape"
else
  fail "discover --json keeps the legacy JSON array shape" "exit=$rc stdout=$(redact_log "$OUT")"
fi

# ── The submission routes, exercised against the real production JVM host ──────
# This host runs with every transport disabled, so it has no peers and can never
# complete a delivery. What it CAN prove is that `POST /share` and `GET /transfers`
# are reachable and authenticated from the Rust client, and that an unknown device
# is refused instead of being queued at nobody.
run_client transfers --json --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json \
  && [[ "$(json_field "$OUT" command)" == "transfers" ]] \
  && python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["requests"] == []' "$OUT" 2>/dev/null; then
  pass "GET /transfers answers an empty list on the production JVM host"
else
  fail "GET /transfers answers an empty list on the production JVM host" "exit=$rc stdout=$(redact_log "$OUT")"
fi

run_client transfers --id req-nosuchrequest --json --control-file "$CONTROL_FILE"
if [[ $rc -eq 3 ]] && [[ "$(json_field "$OUT" error.code 2>/dev/null || true)" == "transfer_unknown" ]]; then
  pass "an unknown request id is transfer_unknown (exit 3), never a delivery result"
else
  fail "an unknown request id is transfer_unknown (exit 3)" "exit=$rc stdout=$(redact_log "$OUT")"
fi

run_client share --to 11111111 --text "fixture" --json --control-file "$CONTROL_FILE"
if [[ $rc -eq 2 ]] && [[ "$(json_field "$OUT" error.code 2>/dev/null || true)" == "device_not_found" ]]; then
  pass "the client refuses an unknown --to before it reaches the daemon (exit 2)"
else
  fail "the client refuses an unknown --to before it reaches the daemon" "exit=$rc stdout=$(redact_log "$OUT")"
fi

# The client cannot reach a 2xx here: this host runs with every transport disabled, so `/state`
# lists no devices and `--to` is always refused client-side. Drive the routes directly with the
# token the production host actually published, so their success path is covered here too.
if python3 - "$CONTROL_FILE" <<'PY'
import json, sys, urllib.request, urllib.error

control = json.load(open(sys.argv[1]))
token, port = control["token"], control["port"]
base = "http://127.0.0.1:%d" % port


def call(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Authorization", "Bearer " + token)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode())


status, body = call("POST", "/share", {"deviceId": "dev-jvm-fixture", "text": "hello"})
if status != 200:
    print("POST /share -> %s %s" % (status, body))
    raise SystemExit(1)
request_id = body.get("requestId", "")
if not request_id.startswith("req-"):
    print("POST /share did not mint a request id: %s" % body)
    raise SystemExit(1)
if body.get("status") != "queued":
    print("POST /share reported %r, expected queued" % body.get("status"))
    raise SystemExit(1)

status, body = call("GET", "/transfers?id=" + request_id)
if status != 200 or body.get("request", {}).get("requestId") != request_id:
    print("GET /transfers?id -> %s %s" % (status, body))
    raise SystemExit(1)

status, body = call("POST", "/share", {"deviceId": "dev-jvm-fixture", "text": "a", "clipboard": True})
if status != 400:
    print("mixed payload should be 400, got %s %s" % (status, body))
    raise SystemExit(1)

status, body = call("POST", "/share", {"deviceId": "dev-jvm-fixture"})
if status != 400:
    print("empty payload should be 400, got %s %s" % (status, body))
    raise SystemExit(1)
PY
then
  pass "POST /share and GET /transfers serve a real request on the production JVM host"
else
  fail "POST /share and GET /transfers serve a real request on the production JVM host" "see the python output above"
fi

# --debug must move diagnostics to stderr and leave stdout parseable.
run_client devices --json --debug --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json && [[ -s "$ERR" ]]; then
  pass "--debug keeps stdout pure and writes diagnostics to stderr"
else
  fail "--debug keeps stdout pure" "exit=$rc stdout=$(redact_log "$OUT") stderr=$(redact_log "$ERR")"
fi

# The token must never leak into either stream. Compared inside python so the
# token never reaches this shell's argv (argv is readable through /proc).
if python3 - "$CONTROL_FILE" "${CLIENT_OUTPUTS[@]}" <<'PY'
import json, sys
try:
    token = json.load(open(sys.argv[1])).get("token")
except Exception:
    raise SystemExit(0)          # nothing published, nothing to leak
if not isinstance(token, str) or not token:
    raise SystemExit(0)
needle = token.encode("utf-8")
for path in sys.argv[2:]:
    if needle in open(path, "rb").read():
        print("token leaked into %s" % path)
        raise SystemExit(1)
raise SystemExit(0)
PY
then
  pass "bearer token never appears in client output"
else
  fail "bearer token never appears in client output" "the token from $CONTROL_FILE was found in client stdout or stderr"
fi

if [[ "$PROCFS_AVAILABLE" -eq 1 ]]; then
  DAEMON_PIDS_AFTER="$(daemon_pid_set)"
  if [[ "$DAEMON_PIDS_BEFORE" == "$DAEMON_PIDS_AFTER" ]]; then
    DAEMON_COUNT="$(printf '%s\n' "$DAEMON_PIDS_AFTER" | grep -c . || true)"
    pass "read-only commands started no second engine (daemon pid set unchanged: ${DAEMON_COUNT} process(es))"
  else
    fail "read-only commands started no second engine" \
      "pid set before=[${DAEMON_PIDS_BEFORE//$'\n'/ }] after=[${DAEMON_PIDS_AFTER//$'\n'/ }]"
  fi
else
  log "SKIPPED: no /proc on this host, so the no-second-engine PID-set check cannot run"
fi

# ── Daemon-metadata failure paths (must be prompt, exit 3) ────────────────────

start=$(date +%s)
run_client devices --json --control-file "$TMPROOT/absent.json"
elapsed=$(( $(date +%s) - start ))
if [[ $rc -eq 3 ]] && [[ "$elapsed" -lt 10 ]] \
  && [[ "$(json_field "$OUT" error.code 2>/dev/null || true)" == "daemon_not_running" ]]; then
  pass "absent control file exits 3 with daemon_not_running (${elapsed}s)"
else
  fail "absent control file exits 3 with daemon_not_running" "exit=$rc after ${elapsed}s stdout=$(redact_log "$OUT")"
fi

printf '{"port": 65536, "token": "abc123"}' >"$TMPROOT/bad-port.json"
run_client devices --json --control-file "$TMPROOT/bad-port.json"
if [[ $rc -eq 3 ]] && [[ "$(json_field "$OUT" error.code 2>/dev/null || true)" == "daemon_control_invalid" ]]; then
  pass "out-of-range port exits 3 with daemon_control_invalid"
else
  fail "out-of-range port exits 3 with daemon_control_invalid" "exit=$rc stdout=$(redact_log "$OUT")"
fi

printf 'not json at all' >"$TMPROOT/garbage.json"
run_client devices --json --control-file "$TMPROOT/garbage.json"
if [[ $rc -eq 3 ]] && [[ "$(json_field "$OUT" error.code 2>/dev/null || true)" == "daemon_control_invalid" ]]; then
  pass "malformed control file exits 3 with daemon_control_invalid"
else
  fail "malformed control file exits 3 with daemon_control_invalid" "exit=$rc stdout=$(redact_log "$OUT")"
fi

# Stale metadata: a valid control file pointing at a port nobody is listening on.
printf '{"port": 1, "token": "abc123"}' >"$TMPROOT/stale.json"
start=$(date +%s)
run_client status --json --control-file "$TMPROOT/stale.json"
elapsed=$(( $(date +%s) - start ))
if [[ $rc -eq 3 ]] && [[ "$elapsed" -lt 10 ]]; then
  pass "stale control file exits 3 promptly (${elapsed}s)"
else
  fail "stale control file exits 3 promptly" "exit=$rc after ${elapsed}s stdout=$(redact_log "$OUT")"
fi

run_client status --json --timeout 0 --control-file "$CONTROL_FILE"
if [[ $rc -eq 2 ]] && [[ "$(json_field "$OUT" error.code 2>/dev/null || true)" == "invalid_argument" ]]; then
  pass "non-positive --timeout is rejected before any request"
else
  fail "non-positive --timeout is rejected before any request" "exit=$rc stdout=$(redact_log "$OUT")"
fi

# ── The developer's real state was never touched ──────────────────────────────
ISOLATION_OK=1
for var_name in KLARDROP_HOME XDG_RUNTIME_DIR; do
  var_value="${!var_name}"
  if [[ "$var_value" == "$TMPROOT"/* ]]; then
    pass "$var_name is inside the fixture root ($var_value)"
  else
    ISOLATION_OK=0
    fail "$var_name is inside the fixture root" "got '$var_value', expected a path under $TMPROOT"
  fi
done

snapshot_real_paths "$TMPROOT/real-after.txt"
if diff -u "$TMPROOT/real-before.txt" "$TMPROOT/real-after.txt" >"$TMPROOT/real.diff" 2>&1; then
  pass "no file outside the fixture root was created or modified (real control file + identity store unchanged)"
else
  ISOLATION_OK=0
  fail "no file outside the fixture root was created or modified" \
    "real-path snapshot diff (paths, sizes, mtimes only — nothing was modified by this script; a concurrently running daemon could also explain it):
$(cat "$TMPROOT/real.diff")"
fi
if [[ "$ISOLATION_OK" -eq 1 ]]; then
  log "Isolation evidence: fixture state lives entirely under $TMPROOT"
fi

printf '\n%d tests run, %d passed, %d failed\n' "$TESTS_RUN" "$TESTS_PASSED" "$TESTS_FAILED"
if [[ "$TESTS_FAILED" -gt 0 ]]; then exit 1; fi
exit 0
