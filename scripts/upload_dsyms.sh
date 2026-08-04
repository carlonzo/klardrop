#!/usr/bin/env bash
#
# Upload every dSYM in an Xcode archive to Bugsnag.
#
# Without this, an Apple crash report reaches Bugsnag as bare load addresses
# ("Klardrop 0x1013b9810 0x100bc4000 + 8345616") and there is no way to tell which Kotlin
# coroutine died — the release binary is stripped, and the DWARF that would name those frames
# only exists in the archive's dSYM bundle on the CI runner.
#
# Bugsnag matches a dSYM to an event by Mach-O UUID, so the upload has to happen for the exact
# archive that ships. Re-signing during export does not change LC_UUID (codesign only rewrites the
# signature blob), so uploading straight after `xcodebuild archive` is correct for both the signed
# and unsigned paths.
#
# Usage: scripts/upload_dsyms.sh <path/to/App.xcarchive>
#
# Environment:
#   BUGSNAG_API_KEY     overrides the key parsed out of BugsnagConfig (optional)
#   BUGSNAG_UPLOAD_URL  overrides the upload endpoint, e.g. for Bugsnag Enterprise (optional)

set -euo pipefail

ARCHIVE="${1:-}"
if [ -z "$ARCHIVE" ]; then
  echo "usage: $0 <path/to/App.xcarchive>" >&2
  exit 2
fi

UPLOAD_URL="${BUGSNAG_UPLOAD_URL:-https://upload.bugsnag.com/dsym}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The API key lives in BugsnagConfig (it is a client-side key and already public in the repo and
# in every shipped binary), so parse it from there rather than duplicating it in CI config. A
# BUGSNAG_API_KEY secret still wins if one is set.
KEY_SOURCE="$REPO_ROOT/common/src/commonMain/kotlin/com/carlom/klardrop/common/BugsnagWrapper.kt"
API_KEY="${BUGSNAG_API_KEY:-}"
if [ -z "$API_KEY" ] && [ -f "$KEY_SOURCE" ]; then
  API_KEY="$(sed -n 's/.*apiKey *= *"\([^"]*\)".*/\1/p' "$KEY_SOURCE" | head -1)"
fi
if [ -z "$API_KEY" ]; then
  echo "::error::No Bugsnag API key (set BUGSNAG_API_KEY or restore BugsnagConfig.apiKey)." >&2
  exit 1
fi

DSYM_ROOT="$ARCHIVE/dSYMs"
if [ ! -d "$DSYM_ROOT" ]; then
  # Not a soft failure: a Release archive is configured for dwarf-with-dsym, so an archive with
  # no dSYMs means the build settings regressed and every future crash report is unreadable.
  echo "::error::No dSYMs in $ARCHIVE — check DEBUG_INFORMATION_FORMAT for the Release config." >&2
  exit 1
fi

uploaded=0
failed=0

# The DWARF binary inside each bundle is what Bugsnag wants, not the .dSYM directory itself.
# One per binary: the app, the share extension, and any embedded framework.
while IFS= read -r dwarf; do
  uuids="$(dwarfdump --uuid "$dwarf" 2>/dev/null | awk '{ print $2 }' | paste -sd' ' - || true)"
  echo "→ $(basename "$dwarf") [${uuids:-unknown uuid}]"

  # -o /dev/null: the success body is noise; --show-error still surfaces failures on stderr.
  if curl --fail --silent --show-error --retry 3 --retry-delay 5 --retry-connrefused \
       -o /dev/null \
       -F "apiKey=$API_KEY" \
       -F "projectRoot=$REPO_ROOT" \
       -F "dsym=@$dwarf" \
       "$UPLOAD_URL"; then
    echo "  uploaded"
    uploaded=$((uploaded + 1))
  else
    echo "::warning::Bugsnag upload failed for $(basename "$dwarf")"
    failed=$((failed + 1))
  fi
done < <(find "$DSYM_ROOT" -type f -path '*/Contents/Resources/DWARF/*')

echo "Bugsnag dSYM upload: $uploaded succeeded, $failed failed"

if [ "$uploaded" -eq 0 ]; then
  echo "::error::No dSYMs were uploaded — crash reports for this build will not symbolicate." >&2
  exit 1
fi
exit "$([ "$failed" -eq 0 ] && echo 0 || echo 1)"
