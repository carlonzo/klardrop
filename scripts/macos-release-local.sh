#!/usr/bin/env bash
# Local mirror of the macOS Developer ID release signing, plus the one thing CI
# can't do headless: actually LAUNCH the signed .app and confirm it spawns.
#
# Why this exists: codesign --verify, spctl, and notarization all PASS on an app
# that dies at launch with RunningBoard "Launchd job spawn failed" (POSIX 163) —
# e.g. a sandboxed app declaring an App Group with no embedded provisioning
# profile. The only check that catches it is starting the app. So we do that here.
#
# Usage:
#   1. Create scripts/macos-release-local.env (gitignored) with the same secret
#      values CI uses — base64 the .p12/.p8 with `base64 -i file | tr -d '\n'`:
#        MACOS_CERTIFICATE_P12_BASE64=   # Developer ID Application cert .p12, base64
#        MACOS_CERTIFICATE_PASSWORD=
#        APPSTORE_API_KEY_P8_BASE64=     # ASC API key .p8, base64
#        APPSTORE_API_KEY_ID=
#        APPSTORE_API_ISSUER_ID=
#        APPLE_TEAM_ID=D7T5425WSW        # optional, this is the default
#   2. ./scripts/macos-release-local.sh            # archive (cached) + export + resign + launch test
#      ./scripts/macos-release-local.sh --rebuild  # force a fresh archive
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="scripts/macos-release-local.env"
[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE (see the header of this script for the keys it needs)"; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${MACOS_CERTIFICATE_P12_BASE64:?}" "${MACOS_CERTIFICATE_PASSWORD:?}"
: "${APPSTORE_API_KEY_P8_BASE64:?}" "${APPSTORE_API_KEY_ID:?}" "${APPSTORE_API_ISSUER_ID:?}"
TEAM_ID="${APPLE_TEAM_ID:-D7T5425WSW}"

WORK="${TMPDIR:-/tmp}/klardrop-macos-local"
ARCHIVE="$WORK/KlardropMac.xcarchive"
EXPORT="$WORK/export"
APP="$EXPORT/Klardrop.app"
mkdir -p "$WORK/keys"

# --- ephemeral keychain with the Developer ID cert (mirrors CI) -------------
KC="$WORK/build.keychain"; KCPW="$(openssl rand -hex 16)"
cleanup() { security delete-keychain "$KC" 2>/dev/null || true; }
trap cleanup EXIT
security create-keychain -p "$KCPW" "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p "$KCPW" "$KC"
echo "$MACOS_CERTIFICATE_P12_BASE64" | base64 -d > "$WORK/cert.p12"
security import "$WORK/cert.p12" -k "$KC" -P "$MACOS_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
security list-keychains -d user -s "$KC" $(security list-keychains -d user | tr -d '"')
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KCPW" "$KC" >/dev/null
echo "$APPSTORE_API_KEY_P8_BASE64" | base64 -d > "$WORK/keys/AuthKey.p8"

# --- archive (cached unless --rebuild) -------------------------------------
if [ "${1:-}" = "--rebuild" ] || [ ! -d "$ARCHIVE" ]; then
  rm -rf "$ARCHIVE"
  xcodebuild \
    -workspace iosApp/iosApp.xcworkspace -scheme KlardropMac \
    -configuration Release -destination 'generic/platform=macOS' \
    -archivePath "$ARCHIVE" archive \
    CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=NO
else
  echo "reusing archive at $ARCHIVE (pass --rebuild to refresh)"
fi

# --- export (developer-id, auto-provisioning) ------------------------------
rm -rf "$EXPORT"
cat > "$WORK/exportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>developer-id</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>$TEAM_ID</string>
</dict></plist>
EOF
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" -exportOptionsPlist "$WORK/exportOptions.plist" \
  -exportPath "$EXPORT" -allowProvisioningUpdates \
  -authenticationKeyPath "$WORK/keys/AuthKey.p8" \
  -authenticationKeyID "$APPSTORE_API_KEY_ID" \
  -authenticationKeyIssuerID "$APPSTORE_API_ISSUER_ID"

# --- embed Developer ID provisioning profiles (mirrors CI) ------------------
# A sandboxed Developer ID app declaring an App Group must ship an embedded
# profile or launchd refuses to spawn it (163). exportArchive does not embed one.
CERT_SERIAL="$(openssl pkcs12 -legacy -in "$WORK/cert.p12" -nokeys -passin pass:"$MACOS_CERTIFICATE_PASSWORD" 2>/dev/null | openssl x509 -noout -serial | cut -d= -f2)"
VENV="$WORK/venv"; [ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet pyjwt cryptography
"$VENV/bin/python" scripts/embed_devid_profiles.py \
  --p8 "$WORK/keys/AuthKey.p8" --key-id "$APPSTORE_API_KEY_ID" \
  --issuer "$APPSTORE_API_ISSUER_ID" --team "$TEAM_ID" --cert-serial "$CERT_SERIAL" \
  --bundle "com.carlom.Klardrop=$APP/Contents/embedded.provisionprofile" \
  --bundle "com.carlom.Klardrop.MacShare=$APP/Contents/PlugIns/KlardropMacShare.appex/Contents/embedded.provisionprofile"

# --- resign WITH hardened runtime, from repo entitlements, sealing the profiles
resign() {
  local bundle="$1" src="$2" ent; ent="$(mktemp)"
  sed "s/\$(AppIdentifierPrefix)/${TEAM_ID}./g" "$src" > "$ent"
  codesign --force --options runtime --timestamp \
    --keychain "$KC" --sign "Developer ID Application" --entitlements "$ent" "$bundle"
}
resign "$APP/Contents/PlugIns/KlardropMacShare.appex" "iosApp/Klardrop Mac Share/KlardropMacShare.entitlements"
resign "$APP" "iosApp/iosApp/KlardropMac.entitlements"
codesign --verify --deep --strict --verbose=2 "$APP"

# --- the check CI can't do: does it actually launch? -----------------------
echo "== embedded provisioning profiles =="
for b in "$APP" "$APP/Contents/PlugIns/KlardropMacShare.appex"; do
  if [ -f "$b/Contents/embedded.provisionprofile" ]; then echo "  OK   $b"
  else echo "  NONE $b"; fi
done

echo "== launch smoke test =="
xattr -cr "$APP"  # local copy isn't notarized/quarantined; don't let Gatekeeper mask the result
# Match by process name, not path: launchd runs it from the resolved /private/var path,
# so a path substring (pgrep -f) won't match the /var symlink form we hold here.
pkill -x Klardrop 2>/dev/null || true   # clear any leftover instance from a prior run
sleep 1
open "$APP"
# A 163 spawn failure means the process NEVER appears; a real launch shows up within
# a few seconds (first launch validates the embedded profiles, so poll rather than sleep once).
for i in $(seq 1 15); do
  if pgrep -x Klardrop >/dev/null; then
    echo "PASS: app spawned and is running (no 163)"; pkill -x Klardrop 2>/dev/null || true; exit 0
  fi
  sleep 1
done
echo "FAIL: app never spawned within 15s (163 spawn failure)"; exit 1
