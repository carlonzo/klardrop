#!/usr/bin/env bash
# Phase-1 fixture checks for the native Rust client (cli-rust/).
#
# What this proves, against a REAL running engine:
#   - devices/status/discover answer from one already-running engine that was started
#     with every discovery transport disabled, so the client cannot have started one
#     itself or opened a listener of its own;
#   - absent / stale / malformed control metadata fails promptly with exit 3;
#   - `--json` stdout stays exactly one JSON value, even with --debug, on a pipe,
#     with stdin closed, and on failure;
#   - no second engine process ever appears (compared as a PID SET, see below);
#   - the client never reads or writes the developer's real control file or
#     identity store.
#
# Protocol/failure-path edge cases (oversized bodies, wedged daemon, old daemon
# without /capabilities, ...) are covered by `cargo test --locked` in cli-rust
# against that crate's fixture daemon; this script deliberately does not duplicate them.
#
# Usage:
#   ./scripts/klardrop-rust-fixture-tests.sh [path-to-klardrop-rust-client]
#
# Two binaries, two names (see cli/build.gradle.kts and
# packaging/linux/stage-native-tarball.sh):
#   klardrop        -> the Rust client under test (cli-rust/)
#   klardrop-engine -> the Kotlin/Native engine this script starts (daemon)
# A stale pre-rename `klardrop.kexe` is rejected below rather than silently passing.
#
# Environment:
#   KLARDROP_RUST_BIN    Rust client binary (default: cli-rust/target/release/klardrop)
#   KLARDROP_ENGINE_BIN  Engine binary     (default: cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe)
#
# Nothing outside a private mktemp directory is touched: KLARDROP_HOME and
# XDG_RUNTIME_DIR point into $TMPROOT, so the developer's own daemon, control file
# and identity store are never read or written. The real paths are snapshotted
# (existence + size + mtime, read-only) before and after the run and compared, so
# the isolation is evidence rather than a claim. Only the engine process this
# script starts itself is ever signalled.
#
# The bearer token is never printed: every failure path that would show the
# control file goes through `redact_control_file` / `redact_log`, which replace the
# token value with <redacted> (and, for an unparsable file, print a byte count
# instead of the bytes).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUST_BIN="${1:-${KLARDROP_RUST_BIN:-$ROOT/cli-rust/target/release/klardrop}}"
ENGINE_BIN="${KLARDROP_ENGINE_BIN:-$ROOT/cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe}"

TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
TMPROOT=""
ENGINE_PID=""

log() { printf '==> %s\n' "$*"; }
pass() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_PASSED=$((TESTS_PASSED + 1)); printf 'PASS: %s\n' "$1"; }
fail() { TESTS_RUN=$((TESTS_RUN + 1)); TESTS_FAILED=$((TESTS_FAILED + 1)); printf 'FAIL: %s\n' "$1" >&2; if [[ -n "${2:-}" ]]; then printf '      %s\n' "$2" >&2; fi; return 0; }

# ── Secret redaction ──────────────────────────────────────────────────────────
# control.json is {"port":N,"token":"<32 hex>","apiVersion":N,"capabilities":[...]}.
# Any diagnostic that shows it MUST go through here.

redact_control_file() {
  # $1: control file path. Prints it with the token value replaced by <redacted>.
  python3 - "$1" <<'PY'
import json, re, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
try:
    doc = json.loads(raw)
except Exception:
    # Unparsable: never echo bytes that could carry a token, just its size.
    print("<unparsable control file, %d bytes>" % len(raw))
    raise SystemExit(0)
if isinstance(doc, dict) and "token" in doc:
    doc["token"] = "<redacted>"
print(json.dumps(doc))
PY
}

redact_log() {
  # $1: any text file (engine log, stdout capture). Replaces every
  # "token":"..." occurrence, whether or not the file is valid JSON.
  python3 - "$1" <<'PY'
import re, sys
raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
sys.stdout.write(re.sub(r'("token"\s*:\s*")[^"]*(")', r'\1<redacted>\2', raw))
PY
}

cleanup() {
  local rc=$?
  if [[ -n "$ENGINE_PID" ]] && kill -0 "$ENGINE_PID" 2>/dev/null; then
    kill -TERM "$ENGINE_PID" 2>/dev/null || true
    wait "$ENGINE_PID" 2>/dev/null || true
  fi
  if [[ -n "$TMPROOT" && -d "$TMPROOT" ]]; then rm -rf "$TMPROOT"; fi
  exit $rc
}
trap cleanup EXIT

for f in "$RUST_BIN" "$ENGINE_BIN"; do
  if [[ ! -x "$f" ]]; then
    printf 'missing executable: %s\n' "$f" >&2
    printf 'build it with: (cd cli-rust && cargo build --locked --release) and :cli:linkReleaseExecutableLinuxX64\n' >&2
    exit 1
  fi
done

# The engine was renamed from `klardrop.kexe` to `klardrop-engine.kexe` because the
# Rust client owns the user-facing `klardrop` name. Refuse to run against a stale
# pre-rename build: every assertion below would otherwise pass against the wrong
# binary and prove nothing about the shipped layout.
ENGINE_BASENAME="$(basename "$ENGINE_BIN")"
if [[ "$ENGINE_BASENAME" != "klardrop-engine.kexe" && "$ENGINE_BASENAME" != "klardrop-engine" ]]; then
  printf 'ENGINE_BIN must be the renamed engine binary (klardrop-engine.kexe), got: %s\n' "$ENGINE_BASENAME" >&2
  printf 'rebuild it with: ./gradlew :cli:linkReleaseExecutableLinuxX64\n' >&2
  exit 1
fi

command -v python3 >/dev/null || { printf 'python3 is required\n' >&2; exit 1; }

# The developer's REAL control-file locations, captured before we export our own
# XDG_RUNTIME_DIR. Read-only: only used to prove we did not touch them.
REAL_XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}"
TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/klardrop-rust-fixture.XXXXXX")"
export KLARDROP_HOME="$TMPROOT/data"
export XDG_RUNTIME_DIR="$TMPROOT/run"
mkdir -p "$KLARDROP_HOME" "$XDG_RUNTIME_DIR"
CONTROL_FILE="$XDG_RUNTIME_DIR/klardrop/control.json"

# Run the Rust client with stdin closed and both streams redirected to files, so any
# prompt, escape sequence, or accidental terminal probe shows up as a diff, not a hang.
OUT=""
ERR=""
rc=0
CLIENT_RUNS=0
CLIENT_OUTPUTS=()
run_client() {
  # One capture file PER INVOCATION. Reusing a single path truncated every earlier run, so a
  # token printed by an earlier command would have been overwritten before the probe read it.
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

# Python rather than `grep -P`: when grep has no PCRE it exits 2, and `!` turns that into a
# silent PASS — exactly on the platforms where this script might be run.
stdout_has_no_escapes() {
  python3 -c 'import sys
data = open(sys.argv[1], "rb").read()
sys.exit(1 if (b"\x1b" in data or b"\x07" in data) else 0)' "$OUT"
}

json_field() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));
for k in sys.argv[2].split("."):
    d = d[int(k)] if k.lstrip("-").isdigit() else d[k]
print(d if isinstance(d,str) else json.dumps(d))' "$1" "$2"; }

# ── Real-path snapshot (read-only) ────────────────────────────────────────────
# The paths a Klardrop host would touch when KLARDROP_HOME / XDG_RUNTIME_DIR are
# NOT overridden: ControlFile.linux.kt:resolveControlFilePath() (XDG_RUNTIME_DIR
# then $HOME/.cache) and LinuxPaths.resolve() (trust $HOME/.klardrop, data
# $XDG_DATA_HOME|~/.local/share, config $XDG_CONFIG_HOME|~/.config, cache
# $XDG_CACHE_HOME|~/.cache). Only existence + size + mtime are recorded; no file
# is ever read, written or removed here.
snapshot_real_paths() {
  python3 - "$1" "$REAL_XDG_RUNTIME_DIR" <<'PY'
import os, sys
out_path, real_xdg = sys.argv[1], sys.argv[2]
home = os.path.expanduser("~")

def env(name, default):
    value = os.environ.get(name) or ""
    return value if value.strip() else default

targets = []
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

# ── Engine process identity: a PID SET, never a count ─────────────────────────
# Why counting is not enough: `pgrep -fc -f "^$ENGINE_BIN"` only answers "how many".
# If a developer's own daemon (or a sibling fixture) is already running the same
# build path, the count is 2 before the client runs and 2 after — a second engine
# spawned by the client is invisible, and so is one it spawned and reaped. Worse,
# a count of 1 proves only that *something* matches, not that our own engine is
# the thing running. Comparing the exact, sorted PID set before and after proves
# the set of engine processes is unchanged; the separate membership assertion on
# $ENGINE_PID proves the snapshot is not vacuously empty.
engine_pid_set() {
  python3 - "$ENGINE_BIN" <<'PY'
import os, sys
target = os.path.realpath(sys.argv[1])
pids = []
for entry in os.listdir("/proc"):
    if not entry.isdigit():
        continue
    try:
        exe = os.readlink("/proc/%s/exe" % entry)
    except OSError:
        continue            # already exited, or a kernel thread
    if os.path.realpath(exe) == target:
        pids.append(int(entry))
print("\n".join(str(pid) for pid in sorted(pids)))
PY
}

# ── Fixture engine: control plane only, no discovery, no listeners ──────────

log "Starting fixture engine (no klardrop/nearby/ble transports)"
"$ENGINE_BIN" daemon --port 0 --no-klardrop --no-nearby --no-ble \
  >"$TMPROOT/engine.log" 2>&1 &
ENGINE_PID=$!

waited=0
while [[ ! -s "$CONTROL_FILE" ]]; do
  if ! kill -0 "$ENGINE_PID" 2>/dev/null; then
    printf 'fixture engine died during startup:\n' >&2
    redact_log "$TMPROOT/engine.log" >&2
    exit 1
  fi
  waited=$((waited + 1))
  if [[ "$waited" -gt 120 ]]; then
    printf 'timed out waiting for %s\n' "$CONTROL_FILE" >&2
    exit 1
  fi
  sleep 0.5
done

ENGINE_PIDS_BEFORE="$(engine_pid_set)"
if printf '%s\n' "$ENGINE_PIDS_BEFORE" | grep -qxF -- "$ENGINE_PID"; then
  pass "fixture engine published $CONTROL_FILE (pid $ENGINE_PID is in the engine pid set)"
else
  fail "fixture engine is running and published $CONTROL_FILE" \
    "engine pid set does not contain our pid $ENGINE_PID; set was: [${ENGINE_PIDS_BEFORE//$'\n'/ }]"
fi

# The process we just started must BE the renamed engine image, not a stale
# `klardrop.kexe` still sitting in the build tree. /proc/<pid>/exe is the running
# image itself (symlink-free, post-exec), so this catches a substitution that a
# pid-set membership check alone cannot.
ENGINE_EXE="$(readlink -f "/proc/$ENGINE_PID/exe" 2>/dev/null || true)"
ENGINE_EXPECTED="$(readlink -f "$ENGINE_BIN" 2>/dev/null || printf '%s' "$ENGINE_BIN")"
if [[ -n "$ENGINE_EXE" ]] && [[ "$ENGINE_EXE" == "$ENGINE_EXPECTED" ]] \
  && [[ "$(basename "$ENGINE_EXE")" == klardrop-engine.kexe ]]; then
  pass "fixture engine is the renamed klardrop-engine binary ($ENGINE_EXE)"
else
  fail "fixture engine is the renamed klardrop-engine binary" \
    "running image '${ENGINE_EXE:-<unreadable>}' is not $(basename "$ENGINE_EXPECTED") ($ENGINE_EXPECTED)"
fi

# The engine's own control file must advertise the api the client negotiates on.
API_VERSION="$(json_field "$CONTROL_FILE" apiVersion 2>/dev/null || true)"
if [[ "$API_VERSION" == "1" ]]; then
  pass "control file advertises apiVersion 1"
else
  fail "control file advertises apiVersion 1" "got '${API_VERSION:-<none>}' in $(redact_control_file "$CONTROL_FILE")"
fi

CONTROL_MODE="$(python3 -c 'import os,stat,sys;print("%o" % stat.S_IMODE(os.stat(sys.argv[1]).st_mode))' "$CONTROL_FILE" 2>/dev/null || true)"
if [[ "$CONTROL_MODE" == "600" ]]; then
  pass "control file is owner-only (0600)"
else
  fail "control file is owner-only (0600)" "mode=${CONTROL_MODE:-<unreadable>}"
fi

# ── Read-only commands against the live engine ──────────────────────────────

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
SELF_ID=""
if [[ $rc -eq 0 ]] && stdout_is_single_json && stdout_has_no_escapes; then
  SELF_ID="$(json_field "$OUT" self.deviceId 2>/dev/null || true)"
  if [[ -n "$SELF_ID" ]] && [[ "$(json_field "$OUT" daemon.apiVersion)" == "1" ]]; then
    pass "status --json reports the running engine (apiVersion 1, self $SELF_ID)"
  else
    fail "status --json reports the running engine" "stdout=$(redact_log "$OUT")"
  fi
else
  fail "status --json reports the running engine" "exit=$rc stdout=$(redact_log "$OUT")"
fi

run_client discover --json --wait 2 --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json && stdout_has_no_escapes \
  && python3 -c 'import json,sys;json.load(open(sys.argv[1]));' "$OUT" \
  && [[ "$(python3 -c 'import json,sys;print(type(json.load(open(sys.argv[1]))).__name__)' "$OUT")" == "list" ]]; then
  pass "discover --json keeps the legacy JSON array shape"
else
  fail "discover --json keeps the legacy JSON array shape" "exit=$rc stdout=$(redact_log "$OUT")"
fi

# --debug must move diagnostics to stderr and leave stdout parseable.
run_client devices --json --debug --control-file "$CONTROL_FILE"
if [[ $rc -eq 0 ]] && stdout_is_single_json && [[ -s "$ERR" ]]; then
  pass "--debug keeps stdout pure and writes diagnostics to stderr"
else
  fail "--debug keeps stdout pure" "exit=$rc stdout=$(redact_log "$OUT") stderr=$(redact_log "$ERR")"
fi

# The token must never leak into either stream. Read inside python so the token
# never appears in this shell's argv either (an argv is world-readable via /proc).
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

ENGINE_PIDS_AFTER="$(engine_pid_set)"
if [[ "$ENGINE_PIDS_BEFORE" == "$ENGINE_PIDS_AFTER" ]] \
  && printf '%s\n' "$ENGINE_PIDS_AFTER" | grep -qxF -- "$ENGINE_PID"; then
  ENGINE_COUNT="$(printf '%s\n' "$ENGINE_PIDS_AFTER" | grep -c . || true)"
  pass "read-only commands started no second engine (pid set unchanged: ${ENGINE_COUNT} process(es), ours $ENGINE_PID)"
else
  fail "read-only commands started no second engine" \
    "pid set before=[${ENGINE_PIDS_BEFORE//$'\n'/ }] after=[${ENGINE_PIDS_AFTER//$'\n'/ }] ours=$ENGINE_PID"
fi

# ── Daemon-metadata failure paths (must be prompt, exit 3) ──────────────────

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

# ── The developer's real state was never touched ─────────────────────────────
# The fixture must be hermetic: its own home/runtime dirs live inside $TMPROOT,
# and nothing outside $TMPROOT was created, resized or re-mtimed. The real paths
# are only ever stat()ed — this check reports, it never repairs.
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
