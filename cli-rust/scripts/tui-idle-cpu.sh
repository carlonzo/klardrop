#!/usr/bin/env bash
#
# Measures what the interactive TUI costs when nothing is happening.
#
# An animation that only runs when the daemon says something is still an
# animation that can be wrong in the other direction: a loop that redraws
# because the clock moved is a loop that burns a core. This script is the
# repeatable measurement of that, so the number in the README can be checked
# rather than believed.
#
# It drives the real client on a real pseudo terminal against the fixture
# daemon, samples utime+stime from /proc for a fixed window, and reports
# CPU-seconds and the percentage of one core. Two numbers are reported per run:
#
#   total     the whole process, which is what the machine actually pays for;
#   draw loop the main thread alone, which is the input/draw loop and so the
#             only thread motion can change.
#
# The split matters. The client's other thread long-polls the daemon, so how
# much *it* costs is a property of the daemon, not of the client; the fixture
# is started with `state_long_poll` so it holds the request open the way the
# real control plane does and that thread is waiting rather than spinning.
#
# The window is run twice, once with motion and once with --no-motion, because
# the question is not "is it cheap" but "what does motion cost".
#
# Usage:
#   scripts/tui-idle-cpu.sh [seconds]
#
# The window defaults to 10 seconds, which is long enough for a per-tick
# sampling to be meaningful and short enough to run in a test suite.
#
# Nothing outside this crate is started or signalled: the fixture daemon and
# the client are children of this script, and they are the only processes it
# ever touches.

set -euo pipefail

WINDOW=${1:-10}
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# The RELEASE binary, because that is the one the numbers this script produces
# are about: the release profile is what ships, and a debug build's redraw cost
# is not the shipped client's. Override with KLARDROP_BIN when testing a change
# before rebuilding.
BUILD=$(dirname -- "${KLARDROP_BIN:-$ROOT/target/release/klardrop}")
# A token for a daemon this script starts itself. It is never printed: the
# client is the only thing that reads it, out of its own environment.
FIXTURE_TOKEN=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')

if [ "$WINDOW" -lt 1 ]; then
    echo "the window must be at least one second" >&2
    exit 2
fi

# The clock the kernel counts CPU in. Everything below is in ticks; nothing is
# reported in ticks.
TICK=$(getconf CLK_TCK)

WORK=$(mktemp -d -t klardrop-idle-XXXXXX)
DAEMON=""
CLIENT=""

cleanup() {
    # Reverse acquisition order, and never let a failing kill hide the exit
    # status of the measurement itself.
    for pid in "$CLIENT" "$DAEMON"; do
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    rm -rf "$WORK"
}
trap cleanup EXIT

# utime + stime for one stat file, in clock ticks.
#
# Fields 14 and 15, but the second field is the executable name in parentheses
# and may itself contain spaces AND parentheses, so the split must start after
# the LAST `)`, not the first. `(a b) c` is a legal comm; splitting at the
# first `)` reads "b)" as field 3 and shifts every field by one — silently,
# because the result is still a plausible-looking number.
cpu_ticks() {
    awk '{
        n = 0
        for (i = length($0); i > 0; i--) {
            if (substr($0, i, 1) == ")") { n = i; break }
        }
        if (n == 0) { exit 1 }
        split(substr($0, n + 2), f, " ")
        printf "%d\n", f[12] + f[13]
    }' "/proc/$1/stat" 2>/dev/null
}

# Waits for a file to appear, or gives up after fifteen seconds.
wait_for_file() {
    local path=$1
    local deadline=$((SECONDS + 15))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if [ -f "$path" ]; then
            return 0
        fi
        sleep 0.05
    done
    return 1
}

# The pid of the client that names this control file.
#
# Found by argv rather than by process tree: `script` runs the command through a
# shell, and how that shell is parented varies between util-linux versions, so
# the reliable identity is the binary itself plus the control file — which is
# unique to this run. Nothing is signalled by the lookup; it only reads /proc.
client_pid() {
    local control=$1
    local entry pid argv0
    for entry in /proc/[0-9]*; do
        pid=${entry#/proc/}
        [ -r "$entry/cmdline" ] || continue
        IFS= read -r -d '' argv0 <"$entry/cmdline" 2>/dev/null || true
        [ "$argv0" = "$BUILD/klardrop" ] || continue
        if tr '\0' '\n' <"$entry/cmdline" | grep -qxF -- "$control"; then
            printf '%s\n' "$pid"
            return 0
        fi
    done
    return 1
}

# Runs one window and prints its result line.
#
# The client is started under `script`, the portable way to give a command a
# pseudo terminal from a shell. Three details matter:
#
#   * `stty rows/cols` first, because `script` copies the window size from a
#     terminal this script does not have, and a 0x0 window makes the client
#     print its line-mode answer and exit — which would measure nothing;
#   * `TERM` pinned, because this script may be run from a context that has
#     none — where the client's own answer is again line mode, and again
#     nothing would be measured;
#   * `exec` in the command line, so the client replaces the shell `script`
#     would otherwise leave between us and it.
measure() {
    local label=$1
    shift
    local control="$WORK/$label.json"
    local daemon_log="$WORK/$label.daemon.log"
    local client_log="$WORK/$label.client.log"

    KLARDROP_FIXTURE_CONTROL_FILE="$control" \
    KLARDROP_FIXTURE_TOKEN="$FIXTURE_TOKEN" \
    KLARDROP_FIXTURE_BEHAVIORS="state_long_poll" \
        "$BUILD/klardrop-fixture-daemon" >/dev/null 2>"$daemon_log" &
    DAEMON=$!
    if ! wait_for_file "$control"; then
        echo "the fixture daemon never wrote its control file:" >&2
        cat "$daemon_log" >&2
        exit 1
    fi

    # shellcheck disable=SC2086 # the caller's flags are separate arguments
    script -qec "stty rows 40 cols 120; export TERM=xterm-256color; exec '$BUILD/klardrop' --control-file '$control' interactive --timeout 10 --no-color --no-mouse $*" \
        /dev/null >"$client_log" 2>&1 &
    local wrapper=$!

    CLIENT=""
    local deadline=$((SECONDS + 15))
    while [ "$SECONDS" -lt "$deadline" ]; do
        CLIENT=$(client_pid "$control" || true)
        if [ -n "$CLIENT" ]; then
            break
        fi
        sleep 0.05
    done
    if [ -z "$CLIENT" ]; then
        echo "the client never started on the pseudo terminal; the daemon and the client said:" >&2
        cat "$daemon_log" "$client_log" >&2
        exit 1
    fi

    sleep 2
    local total_before total_after loop_before loop_after total_ticks loop_ticks
    total_before=$(cpu_ticks "$CLIENT")
    loop_before=$(cpu_ticks "$CLIENT/task/$CLIENT")
    sleep "$WINDOW"
    total_after=$(cpu_ticks "$CLIENT")
    loop_after=$(cpu_ticks "$CLIENT/task/$CLIENT")
    total_ticks=$((total_after - total_before))
    loop_ticks=$((loop_after - loop_before))

    kill "$CLIENT" 2>/dev/null || true
    wait "$wrapper" 2>/dev/null || true
    kill "$DAEMON" 2>/dev/null || true
    wait "$DAEMON" 2>/dev/null || true
    CLIENT=""
    DAEMON=""

    # No awk function here: mawk and busybox awk disagree about where one may
    # be declared, and this report is not worth a portability argument.
    awk -v label="$label" -v total="$total_ticks" -v loop="$loop_ticks" \
        -v hz="$TICK" -v window="$WINDOW" 'BEGIN {
        total_s = total / hz
        loop_s = loop / hz
        printf "%-11s total %6.3f CPU-s (%5.2f%%)   draw loop %6.3f CPU-s (%5.2f%%)   over %ss\n",
            label, total_s, total_s / window * 100, loop_s, loop_s / window * 100, window
    }'
}

echo "idle CPU, ${WINDOW}s window, $TICK Hz clock, fixture holding /state open"
measure motion-on
measure motion-off --no-motion