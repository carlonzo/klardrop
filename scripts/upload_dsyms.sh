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
# Usage: scripts/upload_dsyms.sh <path/to/App.xcarchive>
#
# Environment:
#   SENTRY_AUTH_TOKEN   required — an auth token with project:releases scope
#   SENTRY_ORG          required — Sentry organization slug
#   SENTRY_PROJECT      required — Sentry project slug
#   SENTRY_URL          overrides the Sentry endpoint, e.g. for self-hosted (optional)

set -euo pipefail

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
  echo "→ installing sentry-cli"
  curl -sL https://sentry.io/get-cli/ | bash
fi

# Log what is about to go up, mirroring the old per-binary output: one bundle per binary — the
# app, the share extension, and any embedded framework.
while IFS= read -r dwarf; do
  uuids="$(dwarfdump --uuid "$dwarf" 2>/dev/null | awk '{ print $2 }' | paste -sd' ' - || true)"
  echo "→ $(basename "$dwarf") [${uuids:-unknown uuid}]"
done < <(find "$DSYM_ROOT" -type f -path '*/Contents/Resources/DWARF/*')

# sentry-cli walks the directory itself and uploads each debug file it recognises, so unlike the
# Bugsnag flow there is no per-file curl loop to tally. It exits non-zero if nothing was found.
sentry-cli debug-files upload --include-sources "$DSYM_ROOT"

echo "Sentry dSYM upload complete."
