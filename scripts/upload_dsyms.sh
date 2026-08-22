#!/usr/bin/env bash
#
# Upload every dSYM in an Xcode archive to Sentry.
#
# Without this, an Apple crash report reaches Sentry as bare load addresses
# ("Klardrop 0x1013b9810 0x100bc4000 + 8345616") and there is no way to tell which Kotlin
# coroutine died — the release binary is stripped, and the DWARF that would name those frames
# only exists in the archive's dSYM bundle on the CI runner.
#
# Sentry matches a debug file to an event by Mach-O UUID (its "debug ID"), so the upload has to
# happen for the exact archive that ships. Re-signing during export does not change LC_UUID
# (codesign only rewrites the signature blob), so uploading straight after `xcodebuild archive`
# is correct for both the signed and unsigned paths.
#
# The release workflows call this with `continue-on-error: true` so a Sentry outage cannot block
# a release. That makes the exit code invisible to a human — the `::error::` annotations below
# are the only thing that surfaces "this build will never symbolicate", so every failure path
# here has to emit one before it returns.
#
# Usage: scripts/upload_dsyms.sh <path/to/App.xcarchive>
#
# Environment:
#   SENTRY_AUTH_TOKEN   required — an auth token with project:releases scope
#   SENTRY_ORG          required — Sentry organization slug
#   SENTRY_PROJECT      required — Sentry project slug
#   SENTRY_URL          overrides the Sentry endpoint, e.g. for self-hosted (optional)

set -euo pipefail

# Pinned deliberately. Installing sentry-cli means piping a remote script into bash on the runner
# that holds the Developer ID signing identity and the App Store Connect API key, so "whatever is
# latest today" is an unreviewed dependency sitting next to the release credentials. A fixed
# version is reproducible and can be audited before it is bumped; bump it as an explicit commit.
SENTRY_CLI_VERSION_PIN="3.6.2"

ARCHIVE="${1:-}"
if [ -z "$ARCHIVE" ]; then
  echo "usage: $0 <path/to/App.xcarchive>" >&2
  exit 2
fi

# Unlike the Bugsnag API key this replaces, a Sentry auth token is a real secret and cannot be
# parsed out of the source tree — it has to come from CI configuration.
: "${SENTRY_AUTH_TOKEN:?::error::SENTRY_AUTH_TOKEN is not set — dSYMs cannot be uploaded.}"
: "${SENTRY_ORG:?::error::SENTRY_ORG is not set — dSYMs cannot be uploaded.}"
: "${SENTRY_PROJECT:?::error::SENTRY_PROJECT is not set — dSYMs cannot be uploaded.}"

DSYM_ROOT="$ARCHIVE/dSYMs"
if [ ! -d "$DSYM_ROOT" ]; then
  # Not a soft failure: a Release archive is configured for dwarf-with-dsym, so an archive with
  # no dSYMs means the build settings regressed and every future crash report is unreadable.
  echo "::error::No dSYMs in $ARCHIVE — check DEBUG_INFORMATION_FORMAT for the Release config." >&2
  exit 1
fi

if ! command -v sentry-cli >/dev/null 2>&1; then
  echo "→ installing sentry-cli $SENTRY_CLI_VERSION_PIN"
  # The installer at sentry.io/get-cli reads SENTRY_CLI_VERSION and asks the release registry for
  # that exact build (it defaults to "latest" when unset), so the pin needs no separate download
  # path. -f matters as much as the pin: without it curl hands a 404 body to bash as a script.
  if ! curl -fsSL https://sentry.io/get-cli/ | SENTRY_CLI_VERSION="$SENTRY_CLI_VERSION_PIN" bash; then
    echo "::error::Could not install sentry-cli $SENTRY_CLI_VERSION_PIN — no dSYMs were uploaded." >&2
    exit 1
  fi
fi
# Logged rather than asserted: a runner image that already ships sentry-cli bypasses the pin
# above, and this line is what makes that visible in the build log when a symbolication bug is
# being chased months later.
echo "→ using $(sentry-cli --version 2>/dev/null || echo 'sentry-cli (version unknown)')"

# Walk the archive once and let that single result drive both the log line and the sanity check
# after the upload. `find` runs in a process substitution, so the loop body stays in this shell
# and dwarf_count survives it; it also means a failing `find` is not caught by `set -e` — that is
# fine, because a failed walk yields zero entries and is reported by the count check below.
dwarf_count=0
while IFS= read -r dwarf; do
  # dwarfdump is only for the log. `|| true` covers the whole pipeline so a fat-binary quirk
  # cannot take the script down through `pipefail` before anything has been uploaded.
  uuids="$(dwarfdump --uuid "$dwarf" 2>/dev/null | awk '{ print $2 }' | paste -sd' ' - || true)"
  echo "→ $(basename "$dwarf") [${uuids:-unknown uuid}]"
  dwarf_count=$((dwarf_count + 1))
done < <(find "$DSYM_ROOT" -type f -path '*/Contents/Resources/DWARF/*')

if [ "$dwarf_count" -eq 0 ]; then
  echo "::error::$DSYM_ROOT contains no DWARF binaries — crash reports for this build will not symbolicate." >&2
  exit 1
fi

# sentry-cli walks the directory itself and uploads each debug file it recognises, so unlike the
# Bugsnag flow there is no per-file curl loop to tally. It also does NOT fail when it finds
# nothing: `debug-files upload` prints "No debug information files found" and returns success
# (getsentry/sentry-cli, src/utils/dif_upload/mod.rs — the empty-result branch returns Ok). Only
# --require-all with explicit --id, or a server-side processing error, produce a non-zero exit.
# So the output is captured and checked; otherwise a build whose dSYMs sentry-cli refused to
# parse would report a clean, green upload of nothing.
#
# --include-sources bundles the source files the DWARF references and ships them to Sentry, which
# the Bugsnag flow did not do. Kept on purpose: it puts readable Kotlin and Swift context next to
# Apple stack traces, and this repo is public, so the bundle carries nothing that is not already
# on GitHub. Drop this flag if that ever stops being true.
UPLOAD_LOG="$(mktemp "${TMPDIR:-/tmp}/sentry-dsym-upload.XXXXXX")"
trap 'rm -f "$UPLOAD_LOG"' EXIT

if sentry-cli debug-files upload --include-sources "$DSYM_ROOT" 2>&1 | tee "$UPLOAD_LOG"; then
  upload_failed=0
else
  upload_failed=1
fi

if [ "$upload_failed" -ne 0 ]; then
  echo "::error::sentry-cli failed uploading dSYMs from $DSYM_ROOT — crash reports for this build will not symbolicate." >&2
  exit 1
fi

if grep -q "No debug information files found" "$UPLOAD_LOG"; then
  echo "::error::sentry-cli recognised none of the $dwarf_count DWARF binaries in $DSYM_ROOT — crash reports for this build will not symbolicate." >&2
  exit 1
fi

echo "Sentry dSYM upload complete ($dwarf_count DWARF binaries offered)."
