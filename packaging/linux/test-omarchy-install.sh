#!/usr/bin/env bash
#
# Comprehensive tests for packaging/install.sh (native engine, Omarchy integration,
# generic headless, architecture matrix, restart deferral vs explicit, uninstall
# sentinel preservation, dependency preflight, systemd verification, and safety guards).
#
# Run from the repo root or anywhere; paths are resolved relative to this file.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALLER="$ROOT/packaging/install.sh"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'ok: %s\n' "$*"; }

bash -n "$INSTALLER" || fail "bash -n packaging/install.sh"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck "$INSTALLER" || fail "shellcheck packaging/install.sh"
  pass "shellcheck"
else
  echo "note: shellcheck not installed, skipping"
fi

TMP="$(mktemp -d)"
MOCK_PID=""
cleanup() {
  if [ -n "$MOCK_PID" ]; then
    kill "$MOCK_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

# --- Fake native tarballs matching stage-native-tarball.sh layout ----------
# x64 tarball
STAGE_X64="$TMP/stage/klardrop-native-linux-x64"
mkdir -p "$STAGE_X64/bin" "$STAGE_X64/share/klardrop/omarchy-plugin" \
  "$STAGE_X64/share/klardrop/icons/128x128" "$STAGE_X64/share/klardrop/icons/256x256" \
  "$STAGE_X64/share/klardrop/applications"
cat > "$STAGE_X64/bin/klardrop" <<'EOF'
#!/usr/bin/env bash
echo "fake klardrop x64 $*"
EOF
chmod +x "$STAGE_X64/bin/klardrop"
cat > "$STAGE_X64/bin/klardrop-engine" <<'EOF'
#!/usr/bin/env bash
echo "fake klardrop-engine x64 $*"
EOF
chmod +x "$STAGE_X64/bin/klardrop-engine"
cat > "$STAGE_X64/bin/klardrop-qt" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "--check-runtime" ]; then
  if [ "${FAKE_QT_RUNTIME_FAIL:-0}" = 1 ]; then
    echo "QQmlApplicationEngine failed to load component" >&2
    exit 1
  fi
  exit 0
fi
echo "fake klardrop-qt x64 $*"
[ -n "${MOCK_LAUNCH_LOG:-}" ] && echo "launched" >> "$MOCK_LAUNCH_LOG"
EOF
chmod +x "$STAGE_X64/bin/klardrop-qt"
# The launcher fixture is the SHIPPED launcher: copied verbatim from the single
# file packaging/linux/stage-native-tarball.sh stages. Re-typing it here would
# let the shipped daemon hints drift uncaught, so the installer tests below
# assert against this copy on purpose.
cp "$ROOT/packaging/linux/klardrop-qt-launcher.sh" "$STAGE_X64/bin/klardrop-qt-launcher"
chmod 755 "$STAGE_X64/bin/klardrop-qt-launcher"
cat > "$STAGE_X64/share/klardrop/applications/klardrop.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Klardrop
Exec=klardrop-qt-launcher
Icon=klardrop
EOF
cp "$ROOT/linux/omarchy/plugin/manifest.json" "$STAGE_X64/share/klardrop/omarchy-plugin/"
cp "$ROOT/linux/omarchy/plugin/Main.qml" "$STAGE_X64/share/klardrop/omarchy-plugin/"
cp "$ROOT/linux/omarchy/plugin/Panel.qml" "$STAGE_X64/share/klardrop/omarchy-plugin/"
cp "$ROOT/brand/klardrop-icon-128.png" "$STAGE_X64/share/klardrop/icons/128x128/klardrop.png"
cp "$ROOT/brand/klardrop-icon-256.png" "$STAGE_X64/share/klardrop/icons/256x256/klardrop.png"
echo "9.9.9-test-x64" > "$STAGE_X64/VERSION"
NATIVE_TARBALL_X64="$TMP/klardrop-native-linux-x64.tar.gz"
tar -czf "$NATIVE_TARBALL_X64" -C "$TMP/stage" klardrop-native-linux-x64

# arm64 tarball
STAGE_ARM64="$TMP/stage/klardrop-native-linux-arm64"
mkdir -p "$STAGE_ARM64/bin" "$STAGE_ARM64/share/klardrop/omarchy-plugin" \
  "$STAGE_ARM64/share/klardrop/icons/128x128" "$STAGE_ARM64/share/klardrop/icons/256x256" \
  "$STAGE_ARM64/share/klardrop/applications"
cat > "$STAGE_ARM64/bin/klardrop" <<'EOF'
#!/usr/bin/env bash
echo "fake klardrop arm64 $*"
EOF
chmod +x "$STAGE_ARM64/bin/klardrop"
cat > "$STAGE_ARM64/bin/klardrop-engine" <<'EOF'
#!/usr/bin/env bash
echo "fake klardrop-engine arm64 $*"
EOF
chmod +x "$STAGE_ARM64/bin/klardrop-engine"
cat > "$STAGE_ARM64/bin/klardrop-qt" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "--check-runtime" ]; then
  if [ "${FAKE_QT_RUNTIME_FAIL:-0}" = 1 ]; then
    echo "QQmlApplicationEngine failed to load component" >&2
    exit 1
  fi
  exit 0
fi
echo "fake klardrop-qt arm64 $*"
EOF
chmod +x "$STAGE_ARM64/bin/klardrop-qt"
cp "$ROOT/packaging/linux/klardrop-qt-launcher.sh" "$STAGE_ARM64/bin/klardrop-qt-launcher"
chmod 755 "$STAGE_ARM64/bin/klardrop-qt-launcher"
cat > "$STAGE_ARM64/share/klardrop/applications/klardrop.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Klardrop
Exec=klardrop-qt-launcher
Icon=klardrop
EOF
cp "$ROOT/linux/omarchy/plugin/manifest.json" "$STAGE_ARM64/share/klardrop/omarchy-plugin/"
cp "$ROOT/linux/omarchy/plugin/Main.qml" "$STAGE_ARM64/share/klardrop/omarchy-plugin/"
cp "$ROOT/linux/omarchy/plugin/Panel.qml" "$STAGE_ARM64/share/klardrop/omarchy-plugin/"
cp "$ROOT/brand/klardrop-icon-128.png" "$STAGE_ARM64/share/klardrop/icons/128x128/klardrop.png"
cp "$ROOT/brand/klardrop-icon-256.png" "$STAGE_ARM64/share/klardrop/icons/256x256/klardrop.png"
echo "9.9.9-test-arm64" > "$STAGE_ARM64/VERSION"
NATIVE_TARBALL_ARM64="$TMP/klardrop-native-linux-arm64.tar.gz"
tar -czf "$NATIVE_TARBALL_ARM64" -C "$TMP/stage" klardrop-native-linux-arm64

# JVM tarball
STAGE_JVM="$TMP/stage/klardrop-linux-x64"
mkdir -p "$STAGE_JVM/klardrop/bin"
echo 'echo "fake jvm klardrop"' > "$STAGE_JVM/klardrop/bin/klardrop"
chmod +x "$STAGE_JVM/klardrop/bin/klardrop"
cat > "$STAGE_JVM/klardrop.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Klardrop
Exec=klardrop
Icon=klardrop
EOF
cat > "$STAGE_JVM/com.carlom.Klardrop.metainfo.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>com.carlom.Klardrop</id>
</component>
EOF
JVM_TARBALL="$TMP/klardrop-linux-x64.tar.gz"
tar -czf "$JVM_TARBALL" -C "$TMP/stage" klardrop-linux-x64

NATIVE_TARBALL="$NATIVE_TARBALL_X64"

# Tarball without the Rust client (bin/klardrop): must be rejected outright rather
# than installing an engine the user cannot drive.
mkdir -p "$TMP/stage-noclient"
cp -a "$STAGE_X64" "$TMP/stage-noclient/klardrop-native-linux-x64"
rm -f "$TMP/stage-noclient/klardrop-native-linux-x64/bin/klardrop"
NATIVE_TARBALL_NO_CLIENT="$TMP/klardrop-native-linux-x64-noclient.tar.gz"
tar -czf "$NATIVE_TARBALL_NO_CLIENT" -C "$TMP/stage-noclient" klardrop-native-linux-x64

# Tarball carrying the cli-rust test fixture daemon: must never be installed.
mkdir -p "$TMP/stage-fixture"
cp -a "$STAGE_X64" "$TMP/stage-fixture/klardrop-native-linux-x64"
cat > "$TMP/stage-fixture/klardrop-native-linux-x64/bin/klardrop-fixture-daemon" <<'EOF'
#!/usr/bin/env bash
echo "fake klardrop fixture daemon $*"
EOF
chmod +x "$TMP/stage-fixture/klardrop-native-linux-x64/bin/klardrop-fixture-daemon"
NATIVE_TARBALL_FIXTURE="$TMP/klardrop-native-linux-x64-fixture.tar.gz"
tar -czf "$NATIVE_TARBALL_FIXTURE" -C "$TMP/stage-fixture" klardrop-native-linux-x64

# Same layout, v2 payloads: lets the atomicity test tell "replaced" from "left alone".
mkdir -p "$TMP/stage-v2"
cp -a "$STAGE_X64" "$TMP/stage-v2/klardrop-native-linux-x64"
printf '#!/usr/bin/env bash\necho "fake klardrop x64 v2 $*"\n' > "$TMP/stage-v2/klardrop-native-linux-x64/bin/klardrop"
printf '#!/usr/bin/env bash\necho "fake klardrop-engine x64 v2 $*"\n' > "$TMP/stage-v2/klardrop-native-linux-x64/bin/klardrop-engine"
chmod +x "$TMP/stage-v2/klardrop-native-linux-x64/bin/klardrop" "$TMP/stage-v2/klardrop-native-linux-x64/bin/klardrop-engine"
NATIVE_TARBALL_V2="$TMP/klardrop-native-linux-x64-v2.tar.gz"
tar -czf "$NATIVE_TARBALL_V2" -C "$TMP/stage-v2" klardrop-native-linux-x64

# --- Stub commands -----------------------------------------------------------
STUBS="$TMP/stubs"
mkdir -p "$STUBS"
export ARGV_LOG="$TMP/argv.log"

cat > "$STUBS/uname" <<'EOF'
#!/usr/bin/env bash
if [ -n "${TEST_UNAME_M:-}" ]; then
  if [ "${1:-}" = "-m" ]; then
    echo "$TEST_UNAME_M"
    exit 0
  fi
fi
exec /usr/bin/uname "$@"
EOF
chmod +x "$STUBS/uname"

cat > "$STUBS/ldd" <<'EOF'
#!/usr/bin/env bash
if [ "${FAKE_LDD_ABSENT:-0}" = 1 ]; then
  echo "ldd: execution failure" >&2
  exit 1
fi
if [ "${FAKE_LDD_QT_FAIL:-0}" = 1 ] && [[ "${*:-}" == *"klardrop-qt"* ]]; then
  cat << 'LDDEOF'
linux-vdso.so.1 (0x00007ffc1234)
libQt6Core.so.6 => not found
libQt6Qml.so.6 => not found
LDDEOF
  exit 0
fi
if [ "${FAKE_LDD_CLIENT_FAIL:-0}" = 1 ] && [[ "${*:-}" == */bin/klardrop ]]; then
  cat << 'LDDEOF'
linux-vdso.so.1 (0x00007ffc1234)
libssl.so.3 => not found
libc.so.6 => /usr/lib/libc.so.6 (0x00007f1238)
LDDEOF
  exit 0
fi
if [ "${FAKE_LDD_FAIL:-0}" = 1 ]; then
  cat << 'LDDEOF'
linux-vdso.so.1 (0x00007ffc1234)
libavahi-client.so.3 => not found
libsqlite3.so.0 => not found
libssl.so.3 => not found
libsystemd.so.0 => not found
libc.so.6 => /usr/lib/libc.so.6 (0x00007f1238)
LDDEOF
  exit 0
fi
cat << 'LDDEOF'
linux-vdso.so.1 (0x00007ffc1234)
libavahi-client.so.3 => /usr/lib/libavahi-client.so.3 (0x00007f1234)
libsqlite3.so.0 => /usr/lib/libsqlite3.so.0 (0x00007f1235)
libssl.so.3 => /usr/lib/libssl.so.3 (0x00007f1236)
libsystemd.so.0 => /usr/lib/libsystemd.so.0 (0x00007f1237)
libc.so.6 => /usr/lib/libc.so.6 (0x00007f1238)
libQt6Core.so.6 => /usr/lib/libQt6Core.so.6 (0x00007f1239)
libQt6Gui.so.6 => /usr/lib/libQt6Gui.so.6 (0x00007f123a)
libQt6Qml.so.6 => /usr/lib/libQt6Qml.so.6 (0x00007f123b)
libQt6Quick.so.6 => /usr/lib/libQt6Quick.so.6 (0x00007f123c)
LDDEOF
exit 0
EOF
chmod +x "$STUBS/ldd"

cat > "$STUBS/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "21.0.2" 2024-01-16'
exit 0
EOF
chmod +x "$STUBS/java"

cat > "$STUBS/systemctl" <<'EOF'
#!/usr/bin/env bash
printf '%s %s\n' "systemctl" "$*" >> "$ARGV_LOG"
if [ "${SYSTEMCTL_FAIL:-0}" = 1 ]; then
  echo "Failed to connect to bus: No medium found" >&2
  exit 1
fi
if [ "${SYSTEMCTL_DISABLE_FAILS:-0}" = 1 ] && [ "${1:-}" = "--user" ] && [ "${2:-}" = "disable" ]; then
  echo "Failed to disable unit: unit is locked" >&2
  exit 1
fi
if [ "${1:-}" = "--user" ] && [ "${2:-}" = "show" ]; then
  if [ "${SYSTEMCTL_UNIT_MISSING:-0}" = 1 ]; then
    echo "not-found"
    exit 0
  fi
  echo "loaded"
  exit 0
fi
if [ "${1:-}" = "--user" ] && [ "${2:-}" = "start" ]; then
  if [ "${SYSTEMCTL_START_FAILS:-0}" = 1 ]; then
    echo "Failed to start klardrop.service: unit is masked" >&2
    exit 1
  fi
fi
if [ "${1:-}" = "--user" ] && [ "${2:-}" = "is-active" ]; then
  if [ "${SYSTEMCTL_ACTIVE:-0}" = 1 ]; then
    echo "active"
    exit 0
  else
    echo "inactive"
    exit 3
  fi
fi
exit 0
EOF
chmod +x "$STUBS/systemctl"

cat > "$STUBS/pgrep" <<'EOF'
#!/usr/bin/env bash
if [ "${PGREP_JVM_RUNNING:-0}" = 1 ]; then
  for arg in "$@"; do
    if [[ "$arg" == *"klardrop.launcher="* ]]; then
      echo 12345
      exit 0
    fi
  done
fi
if [ "${PGREP_DAEMON_RUNNING:-0}" = 1 ]; then
  for arg in "$@"; do
    if [[ "$arg" == *"klardrop.*daemon"* ]]; then
      echo 12346
      exit 0
    fi
  done
fi
exit 1
EOF
chmod +x "$STUBS/pgrep"

cat > "$STUBS/pacman" <<'EOF'
#!/usr/bin/env bash
if [ -n "${MOCK_PACKAGE_PATH:-}" ]; then
  for p in "$@"; do
    if [ "$p" = "$MOCK_PACKAGE_PATH" ]; then
      echo "$p is owned by mock-pkg 1.0"
      exit 0
    fi
  done
fi
if [ "${PACMAN_OWNS_BINARY:-0}" = 1 ]; then
  if [ "${1:-}" = "-Qo" ]; then
    echo "$2 is owned by klardrop-native-bin 1.0.0-1"
    exit 0
  fi
fi
if [ "${PACMAN_OWNS_QT_BINARY:-0}" = 1 ]; then
  if [ "${1:-}" = "-Qo" ] && [[ "${2:-}" == *"klardrop-qt"* || "${2:-}" == *"klardrop.desktop"* ]]; then
    echo "$2 is owned by klardrop-qt 1.0.0-1"
    exit 0
  fi
fi
exit 1
EOF
chmod +x "$STUBS/pacman"

cat > "$STUBS/install" <<'EOF'
#!/usr/bin/env bash
if [ "${FAIL_CLIENT_INSTALL:-0}" = 1 ]; then
  for a in "$@"; do
    case "$a" in
      */bin/klardrop) echo "install: cannot stat '$a'" >&2; exit 1 ;;
    esac
  done
fi
exec /usr/bin/install "$@"
EOF
chmod +x "$STUBS/install"

# Fails the swap of the CLIENT binary only (the engine has already been swapped
# in by then). This is the only deterministic way to exercise the second-mv
# rollback as an unprivileged user: a read-only BIN_DIR fails the earlier
# staging `install` instead, so it never reaches the swap at all.
cat > "$STUBS/mv" <<'EOF'
#!/usr/bin/env bash
if [ "${FAIL_CLIENT_MV:-0}" = 1 ]; then
  for a in "$@"; do
    case "$a" in
      */bin/klardrop) echo "mv: cannot move '$a': Permission denied" >&2; exit 1 ;;
    esac
  done
fi
exec /usr/bin/mv "$@"
EOF
chmod +x "$STUBS/mv"

# Minimal `curl -fsSL <url> -o <file>` so the installer tests can drive the real
# DOWNLOAD path (tarball + mandatory `.sha256` sidecar) without a network.
# Serves $MOCK_CURL_PAYLOAD for the tarball and $MOCK_CURL_SHA256 for the
# sidecar; an empty MOCK_CURL_SHA256 models the sidecar download failing.
cat > "$STUBS/curl" <<'EOF'
#!/usr/bin/env bash
out=""
url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -*) shift ;;
    *)  url="$1"; shift ;;
  esac
done
case "$url" in
  *.sha256)
    [ -n "${MOCK_CURL_SHA256:-}" ] || exit 22
    cp "$MOCK_CURL_SHA256" "$out"
    ;;
  *)
    [ -n "${MOCK_CURL_PAYLOAD:-}" ] || exit 22
    cp "$MOCK_CURL_PAYLOAD" "$out"
    ;;
esac
EOF
chmod +x "$STUBS/curl"

for cmd in omarchy omarchy-shell update-desktop-database xdg-mime notify-send omarchy-file-select gtk-update-icon-cache; do
  cat > "$STUBS/$cmd" <<EOF
#!/usr/bin/env bash
printf '%s %s\n' "$cmd" "\$*" >> "$ARGV_LOG"
exit 0
EOF
  chmod +x "$STUBS/$cmd"
done

# --- Isolated HOME & Runtime -------------------------------------------------
HOME_DIR="$TMP/home"
RUN_DIR="$TMP/run"
mkdir -p "$HOME_DIR/.config/omarchy/extensions" "$RUN_DIR"
chmod 700 "$RUN_DIR"

MENU_FILE="$HOME_DIR/.config/omarchy/extensions/omarchy-menu.jsonc"
cat > "$MENU_FILE" <<'EOF'
{
  // My personal stuff -- must survive klardrop's install/uninstall untouched
  "personal": {"icon":"","label":"Personal"},
  "personal.notes": {"icon":"","label":"Notes","action":"omarchy-launch-editor ~/notes"}
}
EOF
cp "$MENU_FILE" "$TMP/menu.orig"

run_installer() {
  HOME="$HOME_DIR" \
  XDG_DATA_HOME="$HOME_DIR/.local/share" \
  XDG_CONFIG_HOME="$HOME_DIR/.config" \
  XDG_CACHE_HOME="$HOME_DIR/.cache" \
  XDG_RUNTIME_DIR="$RUN_DIR" \
  SYSTEMD_UNIT_PATH="$HOME_DIR/.config/systemd/user:/usr/lib/systemd/user:/etc/systemd/user" \
  ARGV_LOG="$ARGV_LOG" \
  PATH="$STUBS:$PATH" \
  KLARDROP_LOCAL_TARBALL="${TEST_TARBALL:-$NATIVE_TARBALL}" \
  bash "$INSTALLER" "$@"
}

check_valid_jsonc() {
  python3 - "$MENU_FILE" <<'PYEOF'
import re, json, sys
s = open(sys.argv[1], encoding="utf-8").read()
stripped = re.sub(r"//.*", "", s)
json.loads(stripped)
PYEOF
}

# =============================================================================
# 1. Install (--omarchy)
# =============================================================================
rm -f "$ARGV_LOG"
run_installer --omarchy >"$TMP/install.out" 2>"$TMP/install.err" || fail "install failed: $(cat "$TMP/install.err")"

BIN="$HOME_DIR/.local/bin/klardrop"
ENGINE="$HOME_DIR/.local/bin/klardrop-engine"
HELPER="$HOME_DIR/.local/bin/klardrop-omarchy-share"
OPEN_HELPER="$HOME_DIR/.local/bin/klardrop-omarchy-open"
SHARE_PICK_HELPER="$HOME_DIR/.local/bin/klardrop-share-pick"
SERVICE="$HOME_DIR/.config/systemd/user/klardrop.service"
PLUGIN_DIR="$HOME_DIR/.config/omarchy/plugins/klardrop.omarchy"
DESKTOP="$HOME_DIR/.local/share/applications/klardrop.desktop"
APP_DESKTOP="$HOME_DIR/.local/share/applications/klardrop-app.desktop"
NAUTILUS="$HOME_DIR/.local/share/nautilus-python/extensions/klardrop.py"
MARKER="$HOME_DIR/.local/share/klardrop/.installer-marker"

[ -x "$BIN" ] || fail "klardrop client binary not installed executable"
[ -x "$ENGINE" ] || fail "klardrop engine binary not installed executable"
grep -q 'fake klardrop-engine x64' "$ENGINE" || fail "installed engine is not the staged engine payload"
grep -q 'fake klardrop x64' "$BIN" || fail "installed client is not the staged client payload"
[ -f "$MARKER" ] || fail "installer marker not created"
[ -x "$HELPER" ] || fail "klardrop-omarchy-share helper not installed executable"
[ -x "$OPEN_HELPER" ] || fail "klardrop-omarchy-open helper not installed executable"
[ -x "$SHARE_PICK_HELPER" ] || fail "klardrop-share-pick helper not installed executable"
bash -n "$SHARE_PICK_HELPER" || fail "klardrop-share-pick helper is not valid bash"
[ -f "$SERVICE" ] || fail "systemd unit not installed"
grep -q "^ExecStart=%h/.local/bin/klardrop-engine daemon$" "$SERVICE" || fail "systemd unit must start the engine (klardrop-engine), not the client"
grep -q "^ExecStart=%h/.local/bin/klardrop daemon$" "$SERVICE" && fail "systemd unit must not start the client binary"
grep -q "WantedBy=graphical-session.target" "$SERVICE" || fail "omarchy unit must use WantedBy=graphical-session.target"
grep -q "After=graphical-session.target" "$SERVICE" && fail "omarchy unit must not use After=graphical-session.target (causes ordering cycle with default dependencies)"
grep -q "^PrivateTmp" "$SERVICE" && fail "systemd unit must not set PrivateTmp (hides XDG_RUNTIME_DIR)"
[ -d "$PLUGIN_DIR" ] || fail "plugin dir not installed"
[ -f "$PLUGIN_DIR/manifest.json" ] || fail "plugin manifest missing"
[ -f "$DESKTOP" ] || fail ".desktop not installed"
# The entry must go through the klardrop-share-pick terminal bridge, NOT call
# `klardrop share --pick` directly: GIO gives a `Terminal=false` entry plain
# pipes on stdin AND stdout, the picker refuses that with exit 2, and sharing
# from the file manager silently does nothing.
grep -qF "Exec=\"$SHARE_PICK_HELPER\" %F" "$DESKTOP" || fail ".desktop wrong Exec line (must exec the klardrop-share-pick terminal bridge with %F)"
grep -q "^Exec=klardrop share" "$DESKTOP" && fail ".desktop must not exec 'klardrop share ...' directly (the picker needs a tty GIO never provides)"
grep -q "^NoDisplay=true$" "$DESKTOP" || fail ".desktop must set NoDisplay=true"
grep -q "^MimeType=" "$DESKTOP" || fail ".desktop missing MimeType"

[ -f "$APP_DESKTOP" ] || fail "visible launcher .desktop not installed"
grep -qF "Exec=\"$OPEN_HELPER\"" "$APP_DESKTOP" || fail "launcher .desktop wrong Exec line (expected absolute helper path)"
grep -q "^Icon=klardrop$" "$APP_DESKTOP" || fail "launcher .desktop missing Icon=klardrop"
grep -q "^NoDisplay=" "$APP_DESKTOP" && fail "launcher .desktop must be visible (no NoDisplay)"

[ -f "$NAUTILUS" ] || fail "nautilus extension not installed"
# Same contract for the Nautilus action: Gio.Subprocess children get pipes, so
# the extension must spawn the helper with the selected paths, not the client.
grep -F 'os.path.expanduser("~/.local/bin/klardrop-share-pick")' "$NAUTILUS" >/dev/null || fail "nautilus extension no longer resolves the klardrop-share-pick helper"
grep -F 'Gio.Subprocess.new([helper] + paths, Gio.SubprocessFlags.NONE)' "$NAUTILUS" >/dev/null || fail "nautilus extension must spawn the klardrop-share-pick helper with the selected paths"
grep -F 'shutil.which("klardrop-share-pick")' "$NAUTILUS" >/dev/null || fail "nautilus extension no longer falls back to PATH for the share-pick helper"
grep -F '"share", "--pick"' "$NAUTILUS" >/dev/null && fail "nautilus extension must not invoke 'klardrop share --pick' directly (Gio.Subprocess provides pipes, not a tty)"

# Omarchy menu helper must go through the client's explicit picker entry point.
grep -F 'exec "$KLARDROP" share --pick --clipboard' "$HELPER" >/dev/null || fail "menu helper clipboard case must run 'share --pick --clipboard'"
grep -F 'exec "$KLARDROP" share --pick "${files[@]}"' "$HELPER" >/dev/null || fail "menu helper file case must run 'share --pick <files>'"
grep -F 'exec "$KLARDROP" share --pick "$picked"' "$HELPER" >/dev/null || fail "menu helper folder case must run 'share --pick <folder>'"
[ "$(grep -cF 'exec "$KLARDROP" share --pick' "$HELPER")" -eq 3 ] || fail "menu helper must route all three cases through 'share --pick'"
ICON128="$HOME_DIR/.local/share/icons/hicolor/128x128/apps/klardrop.png"
ICON256="$HOME_DIR/.local/share/icons/hicolor/256x256/apps/klardrop.png"
[ -f "$ICON128" ] || fail "128x128 icon not installed"
[ -f "$ICON256" ] || fail "256x256 icon not installed"

# Hermetic systemd verification: temp HOME, %h resolution, user target search path
if command -v systemd-analyze >/dev/null 2>&1; then
  mkdir -p "$HOME_DIR/.config/systemd/user/graphical-session.target.wants"
  ln -sf "../klardrop.service" "$HOME_DIR/.config/systemd/user/graphical-session.target.wants/klardrop.service"
  HOME="$HOME_DIR" \
  XDG_RUNTIME_DIR="$RUN_DIR" \
  SYSTEMD_UNIT_PATH="$HOME_DIR/.config/systemd/user:/usr/lib/systemd/user:/etc/systemd/user" \
  systemd-analyze --user verify "$SERVICE" || fail "systemd-analyze --user verify failed on the installed unit with graphical-session.target.wants symlink"
  rm -rf "$HOME_DIR/.config/systemd/user/graphical-session.target.wants"
  pass "systemd-analyze --user verify (omarchy unit with graphical-session.target.wants)"
else
  echo "note: systemd-analyze not available, skipping unit verify"
fi

grep -q "^systemctl --user enable --now klardrop.service$" "$ARGV_LOG" || fail "did not enable+start the systemd unit"
grep -q "^omarchy-shell shell rescanPlugins$" "$ARGV_LOG" || fail "did not rescan plugins before enabling"
grep -q "^omarchy plugin enable klardrop.omarchy$" "$ARGV_LOG" || fail "did not enable the plugin via the real CLI"

check_valid_jsonc || fail "menu file is not valid JSONC after install"
grep -q "My personal stuff" "$MENU_FILE" || fail "pre-existing menu comment lost"
grep -q '"personal.notes"' "$MENU_FILE" || fail "pre-existing menu entry lost"
grep -q "trigger.share.klardrop-file" "$MENU_FILE" || fail "trigger.share.klardrop-file missing"
grep -q '"action":"omarchy-shell klardrop.omarchy toggle"' "$MENU_FILE" || fail "trigger.share.klardrop does not call the panel toggle IPC"
[ "$(grep -c "BEGIN klardrop.omarchy" "$MENU_FILE")" -eq 1 ] || fail "expected exactly one klardrop block after install"

pass "install (--omarchy)"

# =============================================================================
# 2. Launcher helper (klardrop-omarchy-open)
# =============================================================================
rm -f "$ARGV_LOG"
HOME="$HOME_DIR" XDG_RUNTIME_DIR="$RUN_DIR" PATH="$STUBS:$PATH" "$OPEN_HELPER" \
  || fail "launcher helper failed with a working omarchy-shell stub"
grep -q "^systemctl --user start klardrop.service$" "$ARGV_LOG" || fail "launcher helper did not start klardrop.service"
grep -q "^omarchy-shell klardrop.omarchy openWindow$" "$ARGV_LOG" || fail "launcher helper did not call omarchy-shell openWindow"

pass "launcher helper"

# =============================================================================
# 2b. Share-pick helper (klardrop-share-pick), EXECUTED
# =============================================================================
# The two file-manager callers (NoDisplay .desktop entry, Nautilus action) get
# pipes instead of a terminal from GIO, so the helper is the bridge: start the
# engine, then re-run `klardrop share --pick <paths>` inside a terminal emulator.
# This section EXECUTES the installed helper against a stubbed client and
# stubbed terminal emulators and asserts the argv the client actually received —
# a source grep could not tell a forwarded path from a dropped one.
PICK_HOME="$TMP/pick-home"
PICK_LOG="$TMP/pick-argv.log"
mkdir -p "$PICK_HOME/.local/bin"

# Client stub: records argv one argument per line after a marker, so a lost,
# reordered or re-split argument shows up as a diff rather than a `$*` match.
cat > "$PICK_HOME/.local/bin/klardrop" <<'STUB'
#!/usr/bin/env bash
printf 'KLARDROP_ARGV\n' >> "$PICK_LOG"
for a in "$@"; do printf '%s\n' "$a" >> "$PICK_LOG"; done
exit 0
STUB
chmod +x "$PICK_HOME/.local/bin/klardrop"

# A terminal stub enforces the flag its real emulator requires, logs which one
# was used, then actually RUNS the command it was handed — so the client stub
# is only ever reached through the helper's own exec path.
make_term_stub() {
  local dir="$1" name="$2" flag="$3"
  mkdir -p "$dir"
  cat > "$dir/$name" <<STUB
#!/usr/bin/env bash
if [ "\$1" != "$flag" ]; then
  echo "$name stub: expected first argument '$flag', got '\${1:-<none>}'" >&2
  exit 64
fi
printf 'TERM %s %s\n' "\${0##*/}" "\$*" >> "\$PICK_LOG"
shift
exec "\$@"
STUB
  chmod +x "$dir/$name"
}

make_term_stub "$TMP/terms-foot" foot -e
make_term_stub "$TMP/terms-gnome" gnome-terminal --
make_term_stub "$TMP/terms-both" foot -e
make_term_stub "$TMP/terms-both" kitty -e

# Every case below runs with a RESTRICTED PATH (stub terminal dir + stub
# commands only), so a real emulator on the machine running the suite can never
# be picked up. bash itself still has to be reachable for the `#!/usr/bin/env
# bash` shebangs, hence this one-link directory.
PICK_BIN="$TMP/pick-bin"
mkdir -p "$PICK_BIN"
ln -sf "$(command -v bash)" "$PICK_BIN/bash"

# Expected argv as the client must see it, including a path with a space in it.
PICK_FILE_A="$PICK_HOME/holiday photo.jpg"
PICK_FILE_B="$PICK_HOME/report final.pdf"
cat > "$TMP/pick-expected" <<EOF
share
--pick
$PICK_FILE_A
$PICK_FILE_B
EOF

run_pick_helper() {
  # $1 = log, $2 = PATH, $3.. = helper arguments. Terminal selection must not
  # leak between cases, so TERMINAL is always set explicitly by the caller.
  local log="$1" path="$2"
  shift 2
  rm -f "$log"
  PICK_LOG="$log" ARGV_LOG="$ARGV_LOG" HOME="$PICK_HOME" PATH="$PICK_BIN:$path" \
    "$SHARE_PICK_HELPER" "$@"
}

klardrop_argv_from() {
  awk '/^KLARDROP_ARGV$/{f=1; next} f' "$1"
}

# 2b-1: foot (first in the fallback list) gets `-e`, and the client is reached
# with exactly `share --pick <both files>`.
rm -f "$ARGV_LOG"
run_pick_helper "$PICK_LOG" "$TMP/terms-foot:$STUBS" "$PICK_FILE_A" "$PICK_FILE_B" \
  >"$TMP/pick_foot.out" 2>"$TMP/pick_foot.err" \
  || fail "share-pick helper failed with a foot stub: $(cat "$TMP/pick_foot.err")"
grep -q "^TERM foot -e " "$PICK_LOG" || fail "share-pick helper did not launch foot with -e"
klardrop_argv_from "$PICK_LOG" > "$TMP/pick-actual"
diff -u "$TMP/pick-expected" "$TMP/pick-actual" > "$TMP/pick_diff" \
  || fail "share-pick helper did not reach 'klardrop share --pick' with both forwarded files:
$(cat "$TMP/pick_diff")"
grep -q "^systemctl --user start klardrop.service$" "$ARGV_LOG" \
  || fail "share-pick helper did not start klardrop.service before opening the terminal"

# 2b-2: the gnome-terminal family must be invoked with `--`, not `-e`.
run_pick_helper "$PICK_LOG" "$TMP/terms-gnome:$STUBS" "$PICK_FILE_A" \
  >"$TMP/pick_gnome.out" 2>"$TMP/pick_gnome.err" \
  || fail "share-pick helper failed with a gnome-terminal stub: $(cat "$TMP/pick_gnome.err")"
grep -q "^TERM gnome-terminal -- " "$PICK_LOG" || fail "share-pick helper must pass '--' to gnome-terminal"
[ "$(grep -c '^KLARDROP_ARGV$' "$PICK_LOG")" -eq 1 ] \
  || fail "share-pick helper did not reach the client exactly once via gnome-terminal"

# 2b-3: $TERMINAL wins over the built-in search order, even when foot is also
# installed; an unresolvable $TERMINAL falls back to that same order.
TERMINAL=kitty run_pick_helper "$PICK_LOG" "$TMP/terms-both:$STUBS" "$PICK_FILE_A" \
  >"$TMP/pick_term.out" 2>"$TMP/pick_term.err" \
  || fail "share-pick helper failed with \$TERMINAL=kitty: $(cat "$TMP/pick_term.err")"
grep -q "^TERM kitty -e " "$PICK_LOG" || fail "share-pick helper ignored \$TERMINAL (foot is earlier in the list)"

TERMINAL=definitely-not-a-terminal-xyz run_pick_helper "$PICK_LOG" "$TMP/terms-both:$STUBS" "$PICK_FILE_A" \
  >"$TMP/pick_termbad.out" 2>"$TMP/pick_termbad.err" \
  || fail "share-pick helper failed to fall back from an unresolvable \$TERMINAL"
grep -q "^TERM foot -e " "$PICK_LOG" || fail "an unresolvable \$TERMINAL must fall back to the search order (foot)"

# 2b-4: no emulator at all -> non-zero exit, an explanation on stderr, a
# notification, and NO client invocation (a silent no-op is what made this bug
# hard to see from the file manager).
rm -f "$ARGV_LOG"
if run_pick_helper "$PICK_LOG" "$STUBS" "$PICK_FILE_A" \
    >"$TMP/pick_none.out" 2>"$TMP/pick_none.err"; then
  fail "share-pick helper must fail when no terminal emulator exists"
fi
grep -qi "terminal emulator" "$TMP/pick_none.err" || fail "missing no-terminal diagnostic on stderr"
grep -q "^notify-send Klardrop " "$ARGV_LOG" || fail "share-pick helper must notify the user when no terminal is found"
[ ! -f "$PICK_LOG" ] || fail "share-pick helper invoked the client even with no terminal emulator"

pass "share-pick helper (executed: terminal selection, -e vs --, argv forwarding, no-emulator failure)"

# =============================================================================
# 3. Re-install (idempotency)
# =============================================================================
rm -f "$ARGV_LOG"
run_installer --omarchy >"$TMP/reinstall.out" 2>"$TMP/reinstall.err" || fail "re-install failed: $(cat "$TMP/reinstall.err")"
[ "$(grep -c "BEGIN klardrop.omarchy" "$MENU_FILE")" -eq 1 ] || fail "menu block duplicated on re-install"
check_valid_jsonc || fail "menu file is not valid JSONC after re-install"
[ -x "$BIN" ] || fail "klardrop binary missing after re-install"

pass "idempotent re-install"

# =============================================================================
# 4. Uninstall with data sentinel preservation
# =============================================================================
# Seed data sentinels that must survive uninstall untouched:
mkdir -p "$HOME_DIR/.local/share/klardrop/databases" "$HOME_DIR/.config/klardrop" "$HOME_DIR/.klardrop"
echo "sentinel-database" > "$HOME_DIR/.local/share/klardrop/databases/sentinel.db"
echo "sentinel-config"   > "$HOME_DIR/.config/klardrop/sentinel.conf"
echo "sentinel-trust"    > "$HOME_DIR/.klardrop/sentinel.key"

rm -f "$ARGV_LOG"
run_installer --omarchy --uninstall >"$TMP/uninstall.out" 2>"$TMP/uninstall.err" \
  || fail "uninstall failed: $(cat "$TMP/uninstall.err")"

[ ! -e "$BIN" ] || fail "klardrop binary still present after uninstall"
[ ! -e "$ENGINE" ] || fail "klardrop engine binary still present after uninstall"
[ ! -e "$MARKER" ] || fail "marker file still present after uninstall"
[ ! -e "$HELPER" ] || fail "klardrop-omarchy-share helper still present after uninstall"
[ ! -e "$OPEN_HELPER" ] || fail "klardrop-omarchy-open helper still present after uninstall"
[ ! -e "$SHARE_PICK_HELPER" ] || fail "klardrop-share-pick helper still present after uninstall"
[ ! -e "$SERVICE" ] || fail "systemd unit still present after uninstall"
[ ! -e "$DESKTOP" ] || fail ".desktop still present after uninstall"
[ ! -e "$APP_DESKTOP" ] || fail "launcher .desktop still present after uninstall"
[ ! -e "$NAUTILUS" ] || fail "nautilus extension still present after uninstall"
[ ! -d "$PLUGIN_DIR" ] || fail "plugin dir still present (not backed up) after uninstall"
[ ! -e "$ICON128" ] || fail "128x128 icon still present after uninstall"
[ ! -e "$ICON256" ] || fail "256x256 icon still present after uninstall"

# Verify sentinels are preserved:
[ -f "$HOME_DIR/.local/share/klardrop/databases/sentinel.db" ] || fail "sentinel.db deleted"
[ "$(< "$HOME_DIR/.local/share/klardrop/databases/sentinel.db")" = "sentinel-database" ] || fail "sentinel.db modified"
[ -f "$HOME_DIR/.config/klardrop/sentinel.conf" ] || fail "sentinel.conf deleted"
[ "$(< "$HOME_DIR/.config/klardrop/sentinel.conf")" = "sentinel-config" ] || fail "sentinel.conf modified"
[ -f "$HOME_DIR/.klardrop/sentinel.key" ] || fail "sentinel.key deleted"
[ "$(< "$HOME_DIR/.klardrop/sentinel.key")" = "sentinel-trust" ] || fail "sentinel.key modified"

diff -q "$TMP/menu.orig" "$MENU_FILE" >/dev/null || fail "menu file should be restored byte-for-byte after uninstall"

pass "uninstall (data sentinels preserved)"

# Clean up sentinels for subsequent tests
rm -rf "$HOME_DIR/.local/share/klardrop" "$HOME_DIR/.config/klardrop" "$HOME_DIR/.klardrop"

# =============================================================================
# 5. Generic headless install (--native): no Omarchy mutation & default.target verify
# =============================================================================
rm -f "$ARGV_LOG"
cp "$TMP/menu.orig" "$MENU_FILE"
run_installer --native >"$TMP/generic.out" 2>"$TMP/generic.err" || fail "generic install failed: $(cat "$TMP/generic.err")"

[ -x "$BIN" ] || fail "generic install: klardrop binary missing"
[ -f "$MARKER" ] || fail "generic install: marker missing"
[ -f "$SERVICE" ] || fail "generic install: systemd unit missing"
grep -q "WantedBy=default.target" "$SERVICE" || fail "generic install: systemd unit must use WantedBy=default.target"
grep -q "After=default.target" "$SERVICE" && fail "generic install: systemd unit must NOT have After=default.target (causes ordering cycle)"

# Hermetic verification with default.target.wants symlink
mkdir -p "$HOME_DIR/.config/systemd/user/default.target.wants"
ln -sf "../klardrop.service" "$HOME_DIR/.config/systemd/user/default.target.wants/klardrop.service"
if command -v systemd-analyze >/dev/null 2>&1; then
  HOME="$HOME_DIR" \
  XDG_RUNTIME_DIR="$RUN_DIR" \
  SYSTEMD_UNIT_PATH="$HOME_DIR/.config/systemd/user:/usr/lib/systemd/user:/etc/systemd/user" \
  systemd-analyze --user verify "$SERVICE" || fail "systemd-analyze --user verify failed on generic unit with default.target.wants symlink"
  pass "systemd-analyze --user verify (generic headless with default.target.wants)"
fi
rm -rf "$HOME_DIR/.config/systemd/user/default.target.wants"

# Must NOT install desktop integration, shell helpers, plugin, icons or edit menu:
[ ! -e "$HELPER" ] || fail "generic install must not install omarchy share helper"
[ ! -e "$OPEN_HELPER" ] || fail "generic install must not install omarchy open helper"
[ ! -e "$HOME_DIR/.local/bin/klardrop-share-pick" ] || fail "generic install must not install the share-pick helper"
[ ! -e "$DESKTOP" ] || fail "generic install must not install klardrop.desktop"
[ ! -e "$APP_DESKTOP" ] || fail "generic install must not install klardrop-app.desktop"
[ ! -e "$NAUTILUS" ] || fail "generic install must not install nautilus extension"
[ ! -e "$HOME_DIR/.config/omarchy/plugins/klardrop.omarchy" ] || fail "generic install must not install plugin"
[ ! -e "$ICON128" ] || fail "generic install must not install icons"
diff -q "$TMP/menu.orig" "$MENU_FILE" >/dev/null || fail "generic install must not touch omarchy menu"

# Generic uninstall removes engine + service without touching Omarchy files
run_installer --native --uninstall >"$TMP/generic_un.out" 2>"$TMP/generic_un.err" || fail "generic uninstall failed: $(cat "$TMP/generic_un.err")"
[ ! -e "$BIN" ] || fail "generic uninstall: binary still present"
[ ! -e "$ENGINE" ] || fail "generic uninstall: engine binary still present"
[ ! -e "$SERVICE" ] || fail "generic uninstall: service still present"

pass "generic headless install (--native, no Omarchy mutation)"

# =============================================================================
# 6. Architecture support: arm64/aarch64
# =============================================================================
rm -f "$ARGV_LOG"
TEST_UNAME_M="aarch64" TEST_TARBALL="$NATIVE_TARBALL_ARM64" run_installer --native >"$TMP/arm64.out" 2>"$TMP/arm64.err" \
  || fail "arm64 install failed: $(cat "$TMP/arm64.err")"

[ -x "$BIN" ] || fail "arm64 install: binary not installed"
grep -q "fake klardrop arm64" <("$BIN") || fail "arm64 binary does not match arm64 payload"
run_installer --native --uninstall >/dev/null 2>&1 || true

pass "architecture support (aarch64/arm64)"

# =============================================================================
# 7. Active daemon handling: Python idle check, whitespace JSON, token secrecy
# =============================================================================
# Start isolated python loopback mock server
MOCK_SERVER_PY="$TMP/mock_server.py"
MOCK_STATE_FILE="$TMP/mock_state.json"
MOCK_PORT_FILE="$TMP/mock_port.txt"
cat > "$MOCK_SERVER_PY" <<'EOF'
import http.server, socketserver, sys

state_file = sys.argv[1]
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        auth = self.headers.get("Authorization")
        if not auth or "mock-token-xyz" not in auth:
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"ok":false,"error":"unauthorized"}')
            return
        if self.path == "/state":
            try:
                with open(state_file, "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)
            except Exception:
                self.send_response(500)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, *args):
        pass

s = socketserver.TCPServer(("127.0.0.1", 0), Handler)
with open(sys.argv[2], "w") as f:
    f.write(str(s.server_address[1]))
s.serve_forever()
EOF

python3 "$MOCK_SERVER_PY" "$MOCK_STATE_FILE" "$MOCK_PORT_FILE" &
MOCK_PID=$!
for i in {1..50}; do
  [ -f "$MOCK_PORT_FILE" ] && break
  sleep 0.05
done
MOCK_PORT="$(cat "$MOCK_PORT_FILE")"

CONTROL_DIR="$RUN_DIR/klardrop"
mkdir -p "$CONTROL_DIR"
# Test whitespace resilience in control.json
cat > "$CONTROL_DIR/control.json" <<EOF
{
  "port" : $MOCK_PORT,
  "token" : "mock-token-xyz"
}
EOF

# Case A1: Active transfers -> deferred restart
echo '{"ok":true,"transfers":[{"id":"x1","fileName":"movie.mkv"}],"incoming":[],"pairingDialog":null,"qrShare":{"active":false}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer.out" 2>"$TMP/defer.err" || fail "deferred install failed: $(cat "$TMP/defer.err")"
grep -qi "restart deferred to preserve transfers" "$TMP/defer.out" || fail "did not report deferred restart"
grep -q "systemctl --user restart klardrop.service" "$TMP/defer.out" || fail "did not print exact restart command"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when active transfers exist"

# Case A2: Incoming transfer pending/active -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[{"receiveId":"inc1"}],"pairingDialog":null,"qrShare":{"active":false}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_inc.out" 2>"$TMP/defer_inc.err" || fail "incoming defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_inc.out" || fail "did not defer restart on incoming receives"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when incoming receive active"

# Case A3: Pairing dialog active -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":{"deviceId":"peer1"},"qrShare":{"active":false}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_pair.out" 2>"$TMP/defer_pair.err" || fail "pair defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_pair.out" || fail "did not defer restart on pairing dialog"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when pairing dialog active"

# Case A4: QR share active -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":null,"qrShare":{"active":true}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_qr.out" 2>"$TMP/defer_qr.err" || fail "qr defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_qr.out" || fail "did not defer restart on active qr share"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when qr share active"

# Case A4b: Missing pairingDialog -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"qrShare":{"active":false}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_missing_pairing.out" 2>"$TMP/defer_missing_pairing.err" || fail "missing pairing defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_missing_pairing.out" || fail "did not defer restart on missing pairingDialog"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when pairingDialog is missing"

# Case A4c: Missing qrShare -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":null}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_missing_qr.out" 2>"$TMP/defer_missing_qr.err" || fail "missing qr defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_missing_qr.out" || fail "did not defer restart on missing qrShare"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when qrShare is missing"

# Case A4d: String qrShare.active -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":null,"qrShare":{"active":"false"}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_str_qr.out" 2>"$TMP/defer_str_qr.err" || fail "string qr defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_str_qr.out" || fail "did not defer restart on string qrShare.active"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when qrShare.active is a string"

# Case A4e: Null qrShare.active -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":null,"qrShare":{"active":null}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_null_qr.out" 2>"$TMP/defer_null_qr.err" || fail "null qr defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_null_qr.out" || fail "did not defer restart on null qrShare.active"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when qrShare.active is null"

# Case A4f: Missing active in qrShare -> deferred restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":null,"qrShare":{}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_missing_act_qr.out" 2>"$TMP/defer_missing_act_qr.err" || fail "missing active in qr defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_missing_act_qr.out" || fail "did not defer restart on missing active in qrShare"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service when active in qrShare is missing"

# Case A5: Malformed JSON -> deferred restart
echo '{"ok":true,"transfers":' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_malformed.out" 2>"$TMP/defer_malformed.err" || fail "malformed defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_malformed.out" || fail "did not defer restart on malformed state"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service on malformed state"

# Case A6: Unauthorized token -> deferred restart
cat > "$CONTROL_DIR/control.json" <<EOF
{"port": $MOCK_PORT, "token": "wrong-token-abc"}
EOF
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/defer_unauth.out" 2>"$TMP/defer_unauth.err" || fail "unauth defer install failed"
grep -qi "restart deferred to preserve transfers" "$TMP/defer_unauth.out" || fail "did not defer restart on unauth"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" && fail "must not restart service on unauth"

# Restore valid control.json
cat > "$CONTROL_DIR/control.json" <<EOF
{"port": $MOCK_PORT, "token": "mock-token-xyz"}
EOF

# Case B: Verified idle -> automatic restart
echo '{"ok":true,"transfers":[],"incoming":[],"pairingDialog":null,"qrShare":{"active":false}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native >"$TMP/idle.out" 2>"$TMP/idle.err" || fail "idle install failed: $(cat "$TMP/idle.err")"
grep -qi "restarting klardrop.service with updated binary" "$TMP/idle.out" || fail "did not report restarting idle daemon"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" || fail "did not restart service for idle daemon"

# Case C: Active transfers but explicit --restart-daemon -> restart with warning
echo '{"ok":true,"transfers":[{"id":"x1"}],"incoming":[],"pairingDialog":null,"qrShare":{"active":false}}' > "$MOCK_STATE_FILE"
rm -f "$ARGV_LOG"
SYSTEMCTL_ACTIVE=1 run_installer --native --restart-daemon >"$TMP/forced.out" 2>"$TMP/forced.err" || fail "forced install failed: $(cat "$TMP/forced.err")"
grep -qi "active transfers will be interrupted" "$TMP/forced.err" || fail "missing active transfers warning on --restart-daemon"
grep -q "systemctl --user restart klardrop.service" "$ARGV_LOG" || fail "did not restart service on --restart-daemon"

# Case D: Token secrecy: token must never appear in logs or argv
grep -q "mock-token-xyz" "$TMP"/defer*.out "$TMP"/defer*.err "$TMP"/idle*.out "$TMP"/idle*.err "$ARGV_LOG" 2>/dev/null \
  && fail "mock token was exposed in output or argv log!"

# Stop mock server
kill "$MOCK_PID" 2>/dev/null || true
MOCK_PID=""
run_installer --native --uninstall >/dev/null 2>&1 || true
rm -rf "$CONTROL_DIR"

pass "active daemon handling (safe python idle check, deferral, and token secrecy)"

# =============================================================================
# 8. Dependency missing preflight: ldd reports missing shared library
# =============================================================================
rm -f "$ARGV_LOG"
# Pre-seed an existing binary to verify it is NOT deleted or mutated when preflight fails
mkdir -p "$HOME_DIR/.local/bin" "$HOME_DIR/.local/share/klardrop"
echo "pre-existing-binary" > "$HOME_DIR/.local/bin/klardrop"
chmod +x "$HOME_DIR/.local/bin/klardrop"
touch "$HOME_DIR/.local/share/klardrop/.installer-marker"

if FAKE_LDD_FAIL=1 run_installer --native >"$TMP/ldd.out" 2>"$TMP/ldd.err"; then
  fail "installer should fail when ldd reports missing libraries"
fi
grep -q "Missing shared library dependencies" "$TMP/ldd.err" || fail "missing dependency preflight message"
grep -q "pacman -S avahi" "$TMP/ldd.err" || fail "missing pacman avahi hint"
grep -q "sudo systemctl enable --now avahi-daemon" "$TMP/ldd.err" || fail "missing avahi runtime service guidance"
grep -q "libssl3t64" "$TMP/ldd.err" || fail "missing libssl3t64 hint"
grep -q "dnf install" "$TMP/ldd.err" || fail "missing dnf hint"
[ "$(< "$HOME_DIR/.local/bin/klardrop")" = "pre-existing-binary" ] || fail "installer mutated install directory despite failing preflight"

# Missing or failing ldd command must also fail before mutation
if FAKE_LDD_ABSENT=1 run_installer --native >"$TMP/ldd_absent.out" 2>"$TMP/ldd_absent.err"; then
  fail "installer should fail when ldd command fails"
fi
grep -qi "failed to inspect binary dependencies with ldd" "$TMP/ldd_absent.err" || fail "missing ldd inspection failure message"
[ "$(< "$HOME_DIR/.local/bin/klardrop")" = "pre-existing-binary" ] || fail "failing ldd modified binary"

rm -rf "$HOME_DIR/.local/bin/klardrop" "$HOME_DIR/.local/share/klardrop/.installer-marker"

pass "dependency missing preflight (leaves install untouched)"

# =============================================================================
# 9. Systemd unavailable / manual diagnostic
# =============================================================================
rm -f "$ARGV_LOG"
SYSTEMCTL_FAIL=1 run_installer --native >"$TMP/no_systemd.out" 2>"$TMP/no_systemd.err" || fail "install should succeed even when systemd is unavailable"

grep -qi "systemd --user session is not available or not accessible" "$TMP/no_systemd.err" || fail "missing systemd unavailable diagnostic warning"
grep -qi "start the engine manually" "$TMP/no_systemd.err" || fail "missing manual start instruction"
[ -x "$BIN" ] || fail "binary should still be installed when systemd is unavailable"

run_installer --native --uninstall >/dev/null 2>&1 || true

pass "systemd unavailable / manual diagnostic"

# =============================================================================
# 10. Contradictions and unknown flags (asserting exact error messages)
# =============================================================================
if run_installer --bogus-flag >"$TMP/bogus.out" 2>"$TMP/bogus.err"; then
  fail "should reject unknown flags"
fi
grep -q "unknown option '--bogus-flag'" "$TMP/bogus.err" || fail "missing unknown option error"

if run_installer --native --omarchy >"$TMP/contra1.out" 2>"$TMP/contra1.err"; then
  fail "should reject --native and --omarchy"
fi
grep -q "conflicting installation flags" "$TMP/contra1.err" || fail "missing conflicting flags error"

if run_installer --native --jvm >"$TMP/contra2.out" 2>"$TMP/contra2.err"; then
  fail "should reject --native and --jvm"
fi
grep -q "conflicting installation flags" "$TMP/contra2.err" || fail "missing conflicting flags error"

if run_installer --omarchy --jvm >"$TMP/contra3.out" 2>"$TMP/contra3.err"; then
  fail "should reject --omarchy and --jvm"
fi
grep -q "conflicting installation flags" "$TMP/contra3.err" || fail "missing conflicting flags error"

if run_installer --stable --nightly >"$TMP/contra4.out" 2>"$TMP/contra4.err"; then
  fail "should reject --stable and --nightly"
fi
grep -q "conflicting channel flags" "$TMP/contra4.err" || fail "missing conflicting channel flags error"

if KLARDROP_CHANNEL="invalid-channel" run_installer --native >"$TMP/channel.out" 2>"$TMP/channel.err"; then
  fail "should reject invalid KLARDROP_CHANNEL"
fi
grep -q "invalid KLARDROP_CHANNEL" "$TMP/channel.err" || fail "missing invalid KLARDROP_CHANNEL error"

if run_installer --jvm --restart-daemon >"$TMP/restart1.out" 2>"$TMP/restart1.err"; then
  fail "should reject --restart-daemon on JVM"
fi
grep -q -- "--restart-daemon is only valid for native daemon" "$TMP/restart1.err" || fail "missing restart flag validation error on jvm"

if run_installer --uninstall --restart-daemon >"$TMP/restart2.out" 2>"$TMP/restart2.err"; then
  fail "should reject --restart-daemon on uninstall"
fi
grep -q -- "--restart-daemon is only valid for native daemon" "$TMP/restart2.err" || fail "missing restart flag validation error on uninstall"

pass "contradictions and unknown flags rejection"

# =============================================================================
# 11. JVM migration: running JVM app rejection & data preservation
# =============================================================================
JVM_HOME="$TMP/jvm-home"
JVM_APP_DIR="$JVM_HOME/.local/lib/klardrop"
JVM_BIN="$JVM_HOME/.local/bin/klardrop"
DATA_DIR="$JVM_HOME/.local/share/klardrop"
mkdir -p "$JVM_APP_DIR/bin" "$JVM_HOME/.local/bin" "$DATA_DIR/databases"
echo dummy > "$JVM_APP_DIR/bin/klardrop"
ln -sf "$JVM_APP_DIR/bin/klardrop" "$JVM_BIN"
echo "device-identity-marker" > "$DATA_DIR/databases/klardrop.db"

# When JVM app is running, installer MUST fail early requiring quit:
if HOME="$JVM_HOME" \
   XDG_DATA_HOME="$JVM_HOME/.local/share" \
   XDG_CONFIG_HOME="$JVM_HOME/.config" \
   XDG_CACHE_HOME="$JVM_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   PGREP_JVM_RUNNING=1 \
   bash "$INSTALLER" --omarchy >"$TMP/jvm_fail.out" 2>"$TMP/jvm_fail.err"; then
  fail "installer should reject migration when JVM app is running"
fi
grep -qi "JVM Klardrop app is currently running" "$TMP/jvm_fail.err" || fail "missing running JVM error message"
[ -d "$JVM_APP_DIR" ] || fail "running JVM app directory was deleted despite rejection"
[ "$(< "$DATA_DIR/databases/klardrop.db")" = "device-identity-marker" ] || fail "data modified during running JVM rejection"

# When JVM app is quit, migration replaces JVM app and preserves data:
HOME="$JVM_HOME" \
XDG_DATA_HOME="$JVM_HOME/.local/share" \
XDG_CONFIG_HOME="$JVM_HOME/.config" \
XDG_CACHE_HOME="$JVM_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
PGREP_JVM_RUNNING=0 \
bash "$INSTALLER" --omarchy >"$TMP/jvm_ok.out" 2>"$TMP/jvm_ok.err" || fail "migration failed when JVM stopped: $(cat "$TMP/jvm_ok.err")"

grep -qi "replaced the jvm klardrop app" "$TMP/jvm_ok.out" || fail "missing JVM replaced message"
[ ! -e "$JVM_APP_DIR" ] || fail "JVM app dir still present after migration"
[ -x "$JVM_BIN" ] || fail "native binary not installed"
[ "$(< "$DATA_DIR/databases/klardrop.db")" = "device-identity-marker" ] || fail "data lost during migration"

pass "JVM migration (running rejection and clean migration)"

# =============================================================================
# 12. Package-managed and unrelated binary refusal
# =============================================================================
PKG_HOME="$TMP/pkg-home"
mkdir -p "$PKG_HOME/.local/bin"
echo "package-binary" > "$PKG_HOME/.local/bin/klardrop"
chmod +x "$PKG_HOME/.local/bin/klardrop"

# Case A: pacman-managed binary refusal on install and uninstall
if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   PACMAN_OWNS_BINARY=1 \
   bash "$INSTALLER" --native >"$TMP/pkg_refuse.out" 2>"$TMP/pkg_refuse.err"; then
  fail "installer should refuse to overwrite pacman-managed binary"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_refuse.err" || fail "missing pacman package ownership error message"
[ "$(< "$PKG_HOME/.local/bin/klardrop")" = "package-binary" ] || fail "package binary was overwritten despite refusal"

if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   PACMAN_OWNS_BINARY=1 \
   bash "$INSTALLER" --native --uninstall >"$TMP/pkg_un_refuse.out" 2>"$TMP/pkg_un_refuse.err"; then
  fail "uninstall should refuse to delete pacman-managed binary"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_un_refuse.err" || fail "missing pacman uninstall refusal error"
[ "$(< "$PKG_HOME/.local/bin/klardrop")" = "package-binary" ] || fail "package binary was deleted despite uninstall refusal"

# Case B: Unrelated binary (no marker file, no unit file) refusal
echo "unrelated-user-binary" > "$PKG_HOME/.local/bin/klardrop"
if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   PACMAN_OWNS_BINARY=0 \
   bash "$INSTALLER" --native >"$TMP/unrelated_refuse.out" 2>"$TMP/unrelated_refuse.err"; then
  fail "installer should refuse to overwrite unrelated binary"
fi
grep -qi "exists and is not managed by this installer" "$TMP/unrelated_refuse.err" || fail "missing unrelated overwrite refusal error"
[ "$(< "$PKG_HOME/.local/bin/klardrop")" = "unrelated-user-binary" ] || fail "unrelated binary was overwritten"

if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   PACMAN_OWNS_BINARY=0 \
   bash "$INSTALLER" --native --uninstall >"$TMP/unrelated_un_refuse.out" 2>"$TMP/unrelated_un_refuse.err"; then
  fail "uninstall should refuse to remove unrelated binary"
fi
grep -qi "does not appear to be an installer-managed klardrop binary" "$TMP/unrelated_un_refuse.err" || fail "missing unrelated uninstall refusal error"
[ "$(< "$PKG_HOME/.local/bin/klardrop")" = "unrelated-user-binary" ] || fail "unrelated binary was deleted on uninstall"
rm -f "$PKG_HOME/.local/bin/klardrop"

# Case C: Unrelated service file refusal independently of whether binary exists
mkdir -p "$PKG_HOME/.config/systemd/user"
echo "ExecStart=/usr/bin/unrelated-daemon" > "$PKG_HOME/.config/systemd/user/klardrop.service"
if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --native >"$TMP/unrelated_svc.out" 2>"$TMP/unrelated_svc.err"; then
  fail "installer should refuse to overwrite unrelated service file"
fi
grep -qi "exists and is not managed by this installer" "$TMP/unrelated_svc.err" || fail "missing unrelated service overwrite refusal error"
[ "$(< "$PKG_HOME/.config/systemd/user/klardrop.service")" = "ExecStart=/usr/bin/unrelated-daemon" ] || fail "unrelated service was overwritten"

if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   bash "$INSTALLER" --native --uninstall >"$TMP/unrelated_svc_un.out" 2>"$TMP/unrelated_svc_un.err"; then
  fail "uninstall should refuse to remove unrelated service file"
fi
grep -qi "exists and is not managed by this installer" "$TMP/unrelated_svc_un.err" || fail "missing unrelated service uninstall refusal error"
[ "$(< "$PKG_HOME/.config/systemd/user/klardrop.service")" = "ExecStart=/usr/bin/unrelated-daemon" ] || fail "unrelated service was removed on uninstall"
rm -f "$PKG_HOME/.config/systemd/user/klardrop.service"

# Case D: Uninstall aborts before deleting files if stopping service fails and service is confirmed active
mkdir -p "$PKG_HOME/.local/bin" "$PKG_HOME/.local/share/klardrop" "$PKG_HOME/.config/systemd/user"
echo "native-binary" > "$PKG_HOME/.local/bin/klardrop"
chmod +x "$PKG_HOME/.local/bin/klardrop"
touch "$PKG_HOME/.local/share/klardrop/.installer-marker"
echo "native-engine-binary" > "$PKG_HOME/.local/bin/klardrop-engine"
chmod +x "$PKG_HOME/.local/bin/klardrop-engine"
echo "ExecStart=%h/.local/bin/klardrop-engine daemon" > "$PKG_HOME/.config/systemd/user/klardrop.service"

if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   SYSTEMCTL_ACTIVE=1 \
   SYSTEMCTL_DISABLE_FAILS=1 \
   bash "$INSTALLER" --native --uninstall >"$TMP/un_active_fail.out" 2>"$TMP/un_active_fail.err"; then
  fail "uninstall should abort when stopping active service fails"
fi
grep -qi "aborting uninstall before deleting files" "$TMP/un_active_fail.err" || fail "missing abort before deleting files message"
[ -f "$PKG_HOME/.local/bin/klardrop" ] || fail "binary was deleted despite failed service stop"
[ -f "$PKG_HOME/.local/bin/klardrop-engine" ] || fail "engine binary was deleted despite failed service stop"
[ -f "$PKG_HOME/.local/share/klardrop/.installer-marker" ] || fail "marker was deleted despite failed service stop"
[ -f "$PKG_HOME/.config/systemd/user/klardrop.service" ] || fail "service file was deleted despite failed service stop"

# Case E: Explicit --jvm --uninstall must refuse an identified native install before deleting executable
if HOME="$PKG_HOME" \
   XDG_DATA_HOME="$PKG_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_HOME/.config" \
   XDG_CACHE_HOME="$PKG_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   bash "$INSTALLER" --jvm --uninstall >"$TMP/jvm_un_refuse.out" 2>"$TMP/jvm_un_refuse.err"; then
  fail "--jvm --uninstall should refuse an identified native install"
fi
grep -qi "belongs to an identified klardrop native installation" "$TMP/jvm_un_refuse.err" || fail "missing native install refusal error in --jvm --uninstall"
[ -f "$PKG_HOME/.local/bin/klardrop" ] || fail "native binary was deleted by --jvm --uninstall"
[ -f "$PKG_HOME/.local/share/klardrop/.installer-marker" ] || fail "native marker was deleted by --jvm --uninstall"
[ -f "$PKG_HOME/.config/systemd/user/klardrop.service" ] || fail "native service file was deleted by --jvm --uninstall"

# Case F: Lookalike ExecStart paths (regex metacharacters, prefixes, suffixes)
mkdir -p "$PKG_HOME/.config/systemd/user"
rm -f "$PKG_HOME/.local/share/klardrop/.installer-marker" "$PKG_HOME/.local/bin/klardrop" "$PKG_HOME/.local/bin/klardrop-engine"
for lookalike in \
  "ExecStart=%h/.local/bin/klardrop daemon --lookalike" \
  "ExecStart=%h/.local/bin/klardrop daemon2" \
  "ExecStart=/home/other.local/bin/klardrop daemon" \
  "ExecStart=%h/.local/bin/klardrop-engine daemon --lookalike" \
  "ExecStart=%h/.local/bin/klardrop-engine daemon2" \
  "ExecStart=%h/Xlocal/bin/klardrop-engine daemon" \
  "ExecStart=%h/Xlocal/bin/klardrop daemon"; do
  echo "$lookalike" > "$PKG_HOME/.config/systemd/user/klardrop.service"
  if HOME="$PKG_HOME" \
     XDG_DATA_HOME="$PKG_HOME/.local/share" \
     XDG_CONFIG_HOME="$PKG_HOME/.config" \
     XDG_CACHE_HOME="$PKG_HOME/.cache" \
     XDG_RUNTIME_DIR="$RUN_DIR" \
     PATH="$STUBS:$PATH" \
     KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
     bash "$INSTALLER" --native >"$TMP/lookalike.out" 2>"$TMP/lookalike.err"; then
    fail "installer should refuse lookalike ExecStart: $lookalike"
  fi
  grep -qi "exists and is not managed by this installer" "$TMP/lookalike.err" || fail "missing unrelated service error on lookalike: $lookalike"
  [ "$(< "$PKG_HOME/.config/systemd/user/klardrop.service")" = "$lookalike" ] || fail "lookalike unit was overwritten: $lookalike"
done
rm -f "$PKG_HOME/.config/systemd/user/klardrop.service"

rm -rf "$PKG_HOME"

pass "package-managed, unrelated binary/service, lookalikes, and uninstall abort/refusal guards"

# =============================================================================
# 13. Native -> JVM active daemon rejection
# =============================================================================
JVM_TEST_HOME="$TMP/jvm-active-test"
mkdir -p "$JVM_TEST_HOME/.local/bin"

if HOME="$JVM_TEST_HOME" \
   XDG_DATA_HOME="$JVM_TEST_HOME/.local/share" \
   XDG_CONFIG_HOME="$JVM_TEST_HOME/.config" \
   XDG_CACHE_HOME="$JVM_TEST_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$JVM_TARBALL" \
   SYSTEMCTL_ACTIVE=1 \
   bash "$INSTALLER" --jvm >"$TMP/jvm_active.out" 2>"$TMP/jvm_active.err"; then
  fail "JVM installer should reject active native daemon"
fi
grep -qi "active klardrop native daemon" "$TMP/jvm_active.err" || fail "missing active native daemon rejection message in JVM install"
[ ! -d "$JVM_TEST_HOME/.local/lib/klardrop" ] || fail "JVM install proceeded despite active native daemon"

pass "native -> JVM active daemon rejection"

# =============================================================================
# 14. Stopped native -> JVM install refusal (data, binary, unit, marker preserved)
# =============================================================================
JVM_STOPPED_HOME="$TMP/jvm-stopped-native-test"
mkdir -p "$JVM_STOPPED_HOME/.local/bin" "$JVM_STOPPED_HOME/.local/share/klardrop" "$JVM_STOPPED_HOME/.config/systemd/user"
echo "native-binary-payload" > "$JVM_STOPPED_HOME/.local/bin/klardrop"
chmod +x "$JVM_STOPPED_HOME/.local/bin/klardrop"
touch "$JVM_STOPPED_HOME/.local/share/klardrop/.installer-marker"
echo "ExecStart=%h/.local/bin/klardrop daemon" > "$JVM_STOPPED_HOME/.config/systemd/user/klardrop.service"
echo "persisted-data" > "$JVM_STOPPED_HOME/.local/share/klardrop/data.txt"

if HOME="$JVM_STOPPED_HOME" \
   XDG_DATA_HOME="$JVM_STOPPED_HOME/.local/share" \
   XDG_CONFIG_HOME="$JVM_STOPPED_HOME/.config" \
   XDG_CACHE_HOME="$JVM_STOPPED_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$JVM_TARBALL" \
   SYSTEMCTL_ACTIVE=0 \
   bash "$INSTALLER" --jvm >"$TMP/jvm_stopped.out" 2>"$TMP/jvm_stopped.err"; then
  fail "JVM installer should refuse existing stopped native installation"
fi
grep -qi "existing klardrop native installation was detected" "$TMP/jvm_stopped.err" || fail "missing stopped native rejection message in JVM install"
grep -qi "uninstall it first with --native --uninstall" "$TMP/jvm_stopped.err" || fail "missing actionable uninstall hint in JVM install rejection"

[ "$(< "$JVM_STOPPED_HOME/.local/bin/klardrop")" = "native-binary-payload" ] || fail "native binary modified during stopped JVM rejection"
[ -f "$JVM_STOPPED_HOME/.local/share/klardrop/.installer-marker" ] || fail "native marker removed during stopped JVM rejection"
[ "$(< "$JVM_STOPPED_HOME/.config/systemd/user/klardrop.service")" = "ExecStart=%h/.local/bin/klardrop daemon" ] || fail "native unit modified during stopped JVM rejection"
[ "$(< "$JVM_STOPPED_HOME/.local/share/klardrop/data.txt")" = "persisted-data" ] || fail "user data modified during stopped JVM rejection"
[ ! -d "$JVM_STOPPED_HOME/.local/lib/klardrop" ] || fail "JVM install directory created despite rejection"

pass "stopped native -> JVM refusal (data, binary, unit, marker preserved)"

# =============================================================================
# 15. Standalone Qt flavor (--qt) installation, upgrade, switching, and safety
# =============================================================================
QT_TEST_HOME="$TMP/qt-flavor-test"
mkdir -p "$QT_TEST_HOME"

# 15a: Clean --qt installation
HOME="$QT_TEST_HOME" \
XDG_DATA_HOME="$QT_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_TEST_HOME/.config" \
XDG_CACHE_HOME="$QT_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/qt_install.out" 2>"$TMP/qt_install.err" || fail "--qt install failed"

[ -x "$QT_TEST_HOME/.local/bin/klardrop" ] || fail "Rust client not installed in --qt"
[ -x "$QT_TEST_HOME/.local/bin/klardrop-engine" ] || fail "engine not installed in --qt"
[ -f "$QT_TEST_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt binary not installed in --qt"
[ -f "$QT_TEST_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher not installed in --qt"
[ -f "$QT_TEST_HOME/.local/share/applications/klardrop.desktop" ] || fail "klardrop.desktop not installed in --qt"
[ -f "$QT_TEST_HOME/.local/share/klardrop/.installer-marker" ] || fail "installer marker not created in --qt"
[ "$(< "$QT_TEST_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker does not record 'qt' flavor"
[ -f "$QT_TEST_HOME/.config/systemd/user/klardrop.service" ] || fail "systemd service unit not installed in --qt"
grep -q "WantedBy=default.target" "$QT_TEST_HOME/.config/systemd/user/klardrop.service" || fail "service unit in --qt should use default.target"

# Verify no Omarchy artifacts were installed
[ ! -d "$QT_TEST_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "omarchy plugin installed in --qt flavor"
[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-omarchy-share" ] || fail "omarchy menu helper installed in --qt flavor"
[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-omarchy-open" ] || fail "omarchy open helper installed in --qt flavor"
[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-share-pick" ] || fail "share-pick helper installed in --qt flavor"
[ ! -f "$QT_TEST_HOME/.local/share/nautilus-python/extensions/klardrop.py" ] || fail "nautilus extension installed in --qt flavor"

# 15b: Bare reinstall on existing qt marker retains qt flavor even with omarchy present
HOME="$QT_TEST_HOME" \
XDG_DATA_HOME="$QT_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_TEST_HOME/.config" \
XDG_CACHE_HOME="$QT_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" >"$TMP/qt_reinstall.out" 2>"$TMP/qt_reinstall.err" || fail "bare reinstall on qt marker failed"

[ -f "$QT_TEST_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt binary missing after bare reinstall"
[ "$(< "$QT_TEST_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker lost 'qt' flavor after bare reinstall"
[ ! -d "$QT_TEST_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "omarchy plugin installed during bare reinstall on qt marker"

# 15c: Flavor switching: switch to --omarchy, then back to --qt, then to --native
HOME="$QT_TEST_HOME" \
XDG_DATA_HOME="$QT_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_TEST_HOME/.config" \
XDG_CACHE_HOME="$QT_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --omarchy >"$TMP/switch_omarchy.out" 2>"$TMP/switch_omarchy.err" || fail "switch to --omarchy failed"

[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt binary not cleaned on switch to omarchy"
[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher not cleaned on switch to omarchy"
[ -d "$QT_TEST_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "omarchy plugin missing after switch to omarchy"
[ "$(< "$QT_TEST_HOME/.local/share/klardrop/.installer-marker")" = "omarchy" ] || fail "marker not updated to omarchy"
[ -x "$QT_TEST_HOME/.local/bin/klardrop" ] || fail "Rust client lost on switch qt -> omarchy"
[ -x "$QT_TEST_HOME/.local/bin/klardrop-engine" ] || fail "engine lost on switch qt -> omarchy"

HOME="$QT_TEST_HOME" \
XDG_DATA_HOME="$QT_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_TEST_HOME/.config" \
XDG_CACHE_HOME="$QT_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/switch_back_qt.out" 2>"$TMP/switch_back_qt.err" || fail "switch back to --qt failed"

[ -f "$QT_TEST_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt missing after switch back to qt"
[ ! -d "$QT_TEST_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "omarchy plugin not cleaned on switch to qt"
[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-omarchy-share" ] || fail "omarchy menu helper not cleaned on switch to qt"
[ "$(< "$QT_TEST_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker not updated back to qt"
[ -x "$QT_TEST_HOME/.local/bin/klardrop" ] || fail "Rust client lost on switch omarchy -> qt"
[ -x "$QT_TEST_HOME/.local/bin/klardrop-engine" ] || fail "engine lost on switch omarchy -> qt"

HOME="$QT_TEST_HOME" \
XDG_DATA_HOME="$QT_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_TEST_HOME/.config" \
XDG_CACHE_HOME="$QT_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/switch_native.out" 2>"$TMP/switch_native.err" || fail "switch to --native failed"

[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt not cleaned on switch to native"
[ ! -f "$QT_TEST_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher not cleaned on switch to native"
[ ! -f "$QT_TEST_HOME/.local/share/applications/klardrop.desktop" ] || fail "desktop entry not cleaned on switch to native"
[ "$(< "$QT_TEST_HOME/.local/share/klardrop/.installer-marker")" = "native" ] || fail "marker not updated to native"
[ -x "$QT_TEST_HOME/.local/bin/klardrop" ] || fail "Rust client lost on switch qt -> native"
[ -x "$QT_TEST_HOME/.local/bin/klardrop-engine" ] || fail "engine lost on switch qt -> native"

# 15d: Conflicting flags
for flag in --omarchy --native --jvm; do
  if HOME="$QT_TEST_HOME" \
     XDG_DATA_HOME="$QT_TEST_HOME/.local/share" \
     XDG_CONFIG_HOME="$QT_TEST_HOME/.config" \
     XDG_CACHE_HOME="$QT_TEST_HOME/.cache" \
     XDG_RUNTIME_DIR="$RUN_DIR" \
     PATH="$STUBS:$PATH" \
     KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
     bash "$INSTALLER" --qt "$flag" >"$TMP/conflict_qt.out" 2>"$TMP/conflict_qt.err"; then
    fail "installer should reject conflicting flags: --qt $flag"
  fi
  grep -qi "conflicting installation flags" "$TMP/conflict_qt.err" || fail "missing conflicting flags error for --qt $flag"
done

# 15e: Missing Qt ELF dependencies fails before mutation
QT_FAIL_HOME="$TMP/qt-fail-home"
mkdir -p "$QT_FAIL_HOME"
if HOME="$QT_FAIL_HOME" \
   XDG_DATA_HOME="$QT_FAIL_HOME/.local/share" \
   XDG_CONFIG_HOME="$QT_FAIL_HOME/.config" \
   XDG_CACHE_HOME="$QT_FAIL_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   FAKE_LDD_QT_FAIL=1 \
   bash "$INSTALLER" --qt >"$TMP/qt_ldd_fail.out" 2>"$TMP/qt_ldd_fail.err"; then
  fail "--qt install should fail on missing Qt ELF dependencies"
fi
grep -qi "missing shared library dependencies for klardrop qt binary" "$TMP/qt_ldd_fail.err" || fail "missing Qt ELF dep error message"
[ ! -e "$QT_FAIL_HOME/.local/bin/klardrop" ] || fail "binary installed despite Qt ELF dep failure"
[ ! -e "$QT_FAIL_HOME/.local/bin/klardrop-qt" ] || fail "Qt binary installed despite Qt ELF dep failure"

# 15f: Failing QML --check-runtime fails before mutation
if HOME="$QT_FAIL_HOME" \
   XDG_DATA_HOME="$QT_FAIL_HOME/.local/share" \
   XDG_CONFIG_HOME="$QT_FAIL_HOME/.config" \
   XDG_CACHE_HOME="$QT_FAIL_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   FAKE_QT_RUNTIME_FAIL=1 \
   bash "$INSTALLER" --qt >"$TMP/qt_runtime_fail.out" 2>"$TMP/qt_runtime_fail.err"; then
  fail "--qt install should fail on broken QML runtime"
fi
grep -qi "qt qml runtime check failed" "$TMP/qt_runtime_fail.err" || fail "missing QML runtime failure error message"
grep -qi "pacman -s qt6-declarative" "$TMP/qt_runtime_fail.err" || fail "missing actionable Arch package hint"
[ ! -e "$QT_FAIL_HOME/.local/bin/klardrop" ] || fail "binary installed despite QML runtime check failure"

# 15g: Package-owned klardrop-qt binary refusal
mkdir -p "$QT_FAIL_HOME/.local/bin"
touch "$QT_FAIL_HOME/.local/bin/klardrop-qt"
if HOME="$QT_FAIL_HOME" \
   XDG_DATA_HOME="$QT_FAIL_HOME/.local/share" \
   XDG_CONFIG_HOME="$QT_FAIL_HOME/.config" \
   XDG_CACHE_HOME="$QT_FAIL_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   PACMAN_OWNS_QT_BINARY=1 \
   bash "$INSTALLER" --qt >"$TMP/qt_pkg_owned.out" 2>"$TMP/qt_pkg_owned.err"; then
  fail "--qt install should refuse package-managed klardrop-qt binary"
fi
grep -qi "managed by a system package manager" "$TMP/qt_pkg_owned.err" || fail "missing package-managed refusal error for klardrop-qt"
rm -rf "$QT_FAIL_HOME"

# 15h: --qt --uninstall cleanly uninstalls Qt frontend, engine, service and preserves user data
QT_UN_HOME="$TMP/qt-uninstall-test"
mkdir -p "$QT_UN_HOME"
HOME="$QT_UN_HOME" \
XDG_DATA_HOME="$QT_UN_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_UN_HOME/.config" \
XDG_CACHE_HOME="$QT_UN_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/qt_un_setup.out" 2>"$TMP/qt_un_setup.err" || fail "--qt install for uninstall test failed"

echo "keep-user-history" > "$QT_UN_HOME/.local/share/klardrop/history.db"

HOME="$QT_UN_HOME" \
XDG_DATA_HOME="$QT_UN_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_UN_HOME/.config" \
XDG_CACHE_HOME="$QT_UN_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
bash "$INSTALLER" --qt --uninstall >"$TMP/qt_uninstall.out" 2>"$TMP/qt_uninstall.err" || fail "--qt --uninstall failed"

[ ! -f "$QT_UN_HOME/.local/bin/klardrop" ] || fail "klardrop binary not removed by --qt --uninstall"
[ ! -f "$QT_UN_HOME/.local/bin/klardrop-engine" ] || fail "engine binary not removed by --qt --uninstall"
[ ! -f "$QT_UN_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt binary not removed by --qt --uninstall"
[ ! -f "$QT_UN_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher not removed by --qt --uninstall"
[ ! -f "$QT_UN_HOME/.local/share/applications/klardrop.desktop" ] || fail "desktop file not removed by --qt --uninstall"
[ ! -f "$QT_UN_HOME/.local/share/klardrop/.installer-marker" ] || fail "marker not removed by --qt --uninstall"
[ ! -f "$QT_UN_HOME/.config/systemd/user/klardrop.service" ] || fail "service file not removed by --qt --uninstall"
[ -f "$QT_UN_HOME/.local/share/klardrop/history.db" ] || fail "user data wiped by --qt --uninstall"
rm -rf "$QT_UN_HOME"

# 15i: Unowned Qt uninstall refuses without mutation (preserves manual sentinels and dangling symlink, exit code != 0)
QT_UNOWNED_HOME="$TMP/qt-unowned-un"
mkdir -p "$QT_UNOWNED_HOME/.local/bin" "$QT_UNOWNED_HOME/.local/share/applications"
echo "manual-qt-bin" > "$QT_UNOWNED_HOME/.local/bin/klardrop-qt"
echo "manual-qt-launcher" > "$QT_UNOWNED_HOME/.local/bin/klardrop-qt-launcher"
echo "manual-desktop" > "$QT_UNOWNED_HOME/.local/share/applications/klardrop.desktop"
ln -s "/nonexistent/dangling/path" "$QT_UNOWNED_HOME/.local/bin/klardrop-qt-dangling"

if HOME="$QT_UNOWNED_HOME" \
   XDG_DATA_HOME="$QT_UNOWNED_HOME/.local/share" \
   XDG_CONFIG_HOME="$QT_UNOWNED_HOME/.config" \
   XDG_CACHE_HOME="$QT_UNOWNED_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   bash "$INSTALLER" --qt --uninstall >"$TMP/unowned_un.out" 2>"$TMP/unowned_un.err"; then
  fail "unowned Qt uninstall should refuse when no installer marker is present"
fi
grep -qi "no installer-managed qt installation detected" "$TMP/unowned_un.err" || fail "missing unowned Qt uninstall refusal error message"
[ "$(< "$QT_UNOWNED_HOME/.local/bin/klardrop-qt")" = "manual-qt-bin" ] || fail "manual klardrop-qt removed by unowned uninstall"
[ "$(< "$QT_UNOWNED_HOME/.local/bin/klardrop-qt-launcher")" = "manual-qt-launcher" ] || fail "manual klardrop-qt-launcher removed by unowned uninstall"
[ "$(< "$QT_UNOWNED_HOME/.local/share/applications/klardrop.desktop")" = "manual-desktop" ] || fail "manual desktop removed by unowned uninstall"
[ -L "$QT_UNOWNED_HOME/.local/bin/klardrop-qt-dangling" ] || fail "dangling symlink removed by unowned uninstall"
rm -rf "$QT_UNOWNED_HOME"

# 15j: Headless (--native) and --omarchy installs preserve unrelated manual Qt files and dangling symlinks
QT_PRESERVE_HOME="$TMP/qt-preserve-home"
mkdir -p "$QT_PRESERVE_HOME/.local/bin" "$QT_PRESERVE_HOME/.local/share/applications"
echo "manual-qt-bin" > "$QT_PRESERVE_HOME/.local/bin/klardrop-qt"
echo "manual-qt-launcher" > "$QT_PRESERVE_HOME/.local/bin/klardrop-qt-launcher"
echo "manual-desktop" > "$QT_PRESERVE_HOME/.local/share/applications/klardrop.desktop"
ln -s "/nonexistent/target" "$QT_PRESERVE_HOME/.local/bin/klardrop-qt-dangling"

HOME="$QT_PRESERVE_HOME" \
XDG_DATA_HOME="$QT_PRESERVE_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_PRESERVE_HOME/.config" \
XDG_CACHE_HOME="$QT_PRESERVE_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/native_preserve.out" 2>"$TMP/native_preserve.err" || fail "headless install failed"

[ "$(< "$QT_PRESERVE_HOME/.local/bin/klardrop-qt")" = "manual-qt-bin" ] || fail "manual klardrop-qt removed by headless install"
[ "$(< "$QT_PRESERVE_HOME/.local/bin/klardrop-qt-launcher")" = "manual-qt-launcher" ] || fail "manual klardrop-qt-launcher removed by headless install"
[ "$(< "$QT_PRESERVE_HOME/.local/share/applications/klardrop.desktop")" = "manual-desktop" ] || fail "manual desktop removed by headless install"
[ -L "$QT_PRESERVE_HOME/.local/bin/klardrop-qt-dangling" ] || fail "dangling symlink removed by headless install"
[ -f "$QT_PRESERVE_HOME/.local/bin/klardrop" ] || fail "engine binary missing after headless install"
rm -rf "$QT_PRESERVE_HOME"

# 15k: New --qt install refuses to overwrite unowned/manual frontend files or dangling symlinks before engine/service mutation
QT_REFUSE_HOME="$TMP/qt-refuse-home"
mkdir -p "$QT_REFUSE_HOME/.local/bin" "$QT_REFUSE_HOME/.local/share/applications"
echo "foreign-qt-bin" > "$QT_REFUSE_HOME/.local/bin/klardrop-qt"
echo "foreign-launcher" > "$QT_REFUSE_HOME/.local/bin/klardrop-qt-launcher"
echo "foreign-desktop" > "$QT_REFUSE_HOME/.local/share/applications/klardrop.desktop"

if HOME="$QT_REFUSE_HOME" \
   XDG_DATA_HOME="$QT_REFUSE_HOME/.local/share" \
   XDG_CONFIG_HOME="$QT_REFUSE_HOME/.config" \
   XDG_CACHE_HOME="$QT_REFUSE_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --qt >"$TMP/qt_refuse.out" 2>"$TMP/qt_refuse.err"; then
  fail "--qt install should refuse unowned frontend files"
fi
grep -qi "not managed by this installer" "$TMP/qt_refuse.err" || fail "missing unmanaged file rejection message"
[ "$(< "$QT_REFUSE_HOME/.local/bin/klardrop-qt")" = "foreign-qt-bin" ] || fail "foreign klardrop-qt modified on refusal"
[ "$(< "$QT_REFUSE_HOME/.local/bin/klardrop-qt-launcher")" = "foreign-launcher" ] || fail "foreign launcher modified on refusal"
[ "$(< "$QT_REFUSE_HOME/.local/share/applications/klardrop.desktop")" = "foreign-desktop" ] || fail "foreign desktop modified on refusal"
[ ! -e "$QT_REFUSE_HOME/.local/bin/klardrop" ] || fail "engine binary created despite frontend refusal"
[ ! -e "$QT_REFUSE_HOME/.config/systemd/user/klardrop.service" ] || fail "service unit created despite frontend refusal"
rm -rf "$QT_REFUSE_HOME"

# 15l: Package-owned Qt frontend guard runs BEFORE JVM migration
PKG_MIGRATE_HOME="$TMP/pkg-migrate-home"
mkdir -p "$PKG_MIGRATE_HOME/.local/lib/klardrop/bin" "$PKG_MIGRATE_HOME/.local/bin" "$PKG_MIGRATE_HOME/.local/share/klardrop"
echo "jvm-sentinel" > "$PKG_MIGRATE_HOME/.local/lib/klardrop/bin/klardrop"
chmod +x "$PKG_MIGRATE_HOME/.local/lib/klardrop/bin/klardrop"
ln -s "$PKG_MIGRATE_HOME/.local/lib/klardrop/bin/klardrop" "$PKG_MIGRATE_HOME/.local/bin/klardrop"
echo "qt" > "$PKG_MIGRATE_HOME/.local/share/klardrop/.installer-marker"
echo "pkg-owned-qt" > "$PKG_MIGRATE_HOME/.local/bin/klardrop-qt"

if HOME="$PKG_MIGRATE_HOME" \
   XDG_DATA_HOME="$PKG_MIGRATE_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_MIGRATE_HOME/.config" \
   XDG_CACHE_HOME="$PKG_MIGRATE_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   MOCK_PACKAGE_PATH="$PKG_MIGRATE_HOME/.local/bin/klardrop-qt" \
   bash "$INSTALLER" --native >"$TMP/pkg_mig.out" 2>"$TMP/pkg_mig.err"; then
  fail "--native flavor switch should refuse when existing Qt frontend is package-owned"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_mig.err" || fail "missing package-managed refusal error"
[ -f "$PKG_MIGRATE_HOME/.local/lib/klardrop/bin/klardrop" ] || fail "JVM app directory wiped despite package guard failure"
[ -L "$PKG_MIGRATE_HOME/.local/bin/klardrop" ] || fail "JVM launcher symlink deleted despite package guard failure"
[ "$(< "$PKG_MIGRATE_HOME/.local/bin/klardrop-qt")" = "pkg-owned-qt" ] || fail "package-owned Qt binary modified"
rm -rf "$PKG_MIGRATE_HOME"

# 15m: Failing QML runtime check preserves JVM install intact
RUNTIME_JVM_HOME="$TMP/runtime-jvm-home"
mkdir -p "$RUNTIME_JVM_HOME/.local/lib/klardrop/bin" "$RUNTIME_JVM_HOME/.local/bin"
echo "jvm-sentinel" > "$RUNTIME_JVM_HOME/.local/lib/klardrop/bin/klardrop"
chmod +x "$RUNTIME_JVM_HOME/.local/lib/klardrop/bin/klardrop"
ln -s "$RUNTIME_JVM_HOME/.local/lib/klardrop/bin/klardrop" "$RUNTIME_JVM_HOME/.local/bin/klardrop"

if HOME="$RUNTIME_JVM_HOME" \
   XDG_DATA_HOME="$RUNTIME_JVM_HOME/.local/share" \
   XDG_CONFIG_HOME="$RUNTIME_JVM_HOME/.config" \
   XDG_CACHE_HOME="$RUNTIME_JVM_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   FAKE_QT_RUNTIME_FAIL=1 \
   bash "$INSTALLER" --qt >"$TMP/rt_jvm.out" 2>"$TMP/rt_jvm.err"; then
  fail "--qt install should fail on broken QML runtime"
fi
[ -f "$RUNTIME_JVM_HOME/.local/lib/klardrop/bin/klardrop" ] || fail "JVM app deleted after broken QML runtime check"
[ -L "$RUNTIME_JVM_HOME/.local/bin/klardrop" ] || fail "JVM launcher symlink deleted after broken QML runtime check"
rm -rf "$RUNTIME_JVM_HOME"

# 15n: Absent timeout command fails closed before running unbounded verification
NO_TIMEOUT_HOME="$TMP/no-timeout-home"
mkdir -p "$NO_TIMEOUT_HOME"
NO_TIMEOUT_BIN="$TMP/bin-no-timeout"
mkdir -p "$NO_TIMEOUT_BIN"
for cmd in bash sh cat grep sed uname mktemp chmod install id awk tr cut pgrep touch ls rm mv cp dirname tar gzip curl wget ldd sha256sum env; do
  target="$(command -v "$cmd" 2>/dev/null || true)"
  [ -n "$target" ] && ln -sf "$target" "$NO_TIMEOUT_BIN/$cmd"
done
for stub in "$STUBS"/*; do
  [ -e "$stub" ] && ln -sf "$stub" "$NO_TIMEOUT_BIN/$(basename "$stub")"
done
rm -f "$NO_TIMEOUT_BIN/timeout"

if HOME="$NO_TIMEOUT_HOME" \
   XDG_DATA_HOME="$NO_TIMEOUT_HOME/.local/share" \
   XDG_CONFIG_HOME="$NO_TIMEOUT_HOME/.config" \
   XDG_CACHE_HOME="$NO_TIMEOUT_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$NO_TIMEOUT_BIN" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --qt >"$TMP/no_timeout.out" 2>"$TMP/no_timeout.err"; then
  fail "--qt install should fail when timeout command is absent"
fi
grep -qi "'timeout' (from coreutils) is required" "$TMP/no_timeout.err" || fail "missing timeout requirement error message"
[ ! -e "$NO_TIMEOUT_HOME/.local/bin/klardrop" ] || fail "engine created despite missing timeout"
[ ! -e "$NO_TIMEOUT_HOME/.local/bin/klardrop-qt" ] || fail "Qt binary created despite missing timeout"
[ ! -e "$NO_TIMEOUT_HOME/.local/bin/klardrop-qt-launcher" ] || fail "launcher created despite missing timeout"
[ ! -e "$NO_TIMEOUT_HOME/.local/share/applications/klardrop.desktop" ] || fail "desktop file created despite missing timeout"
rm -rf "$NO_TIMEOUT_HOME"

# 15o: Desktop entry contains properly quoted absolute launcher path working with spaces in HOME and no ~/.local/bin in PATH
SPACE_HOME="$TMP/user test space home"
mkdir -p "$SPACE_HOME"
HOME="$SPACE_HOME" \
XDG_DATA_HOME="$SPACE_HOME/.local/share" \
XDG_CONFIG_HOME="$SPACE_HOME/.config" \
XDG_CACHE_HOME="$SPACE_HOME/.cache" \
XDG_RUNTIME_DIR="$RUN_DIR" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/space_install.out" 2>"$TMP/space_install.err" || fail "--qt install into space home failed"

DESK_FILE="$SPACE_HOME/.local/share/applications/klardrop.desktop"
[ -f "$DESK_FILE" ] || fail "desktop file missing in space home"
grep -F "Exec=/usr/bin/env \"$SPACE_HOME/.local/bin/klardrop-qt-launcher\"" "$DESK_FILE" || fail "desktop file Exec does not have quoted absolute launcher path"

exec_line="$(grep "^Exec=" "$DESK_FILE" | cut -d= -f2-)"
launcher_exec="${exec_line#/usr/bin/env \"}"
launcher_exec="${launcher_exec%\"}"
[ -x "$launcher_exec" ] || fail "parsed desktop launcher $launcher_exec is not executable"

# 15p: Launcher unit missing, failed start, absent systemctl, and manual daemon handling
LAUNCHER_BIN="$SPACE_HOME/.local/bin/klardrop-qt-launcher"
[ -x "$LAUNCHER_BIN" ] || fail "installed launcher missing"

# Missing unit: systemctl show LoadState=not-found
err_out="$(HOME="$SPACE_HOME" XDG_RUNTIME_DIR="$RUN_DIR" PATH="$STUBS:/bin:/usr/bin" SYSTEMCTL_UNIT_MISSING=1 bash "$LAUNCHER_BIN" 2>&1 || true)"
echo "$err_out" | grep -qi "unit (klardrop.service) is not loaded or missing" || fail "launcher missing unit diagnostic not emitted"

# Start fails: systemctl start fails
err_out="$(HOME="$SPACE_HOME" XDG_RUNTIME_DIR="$RUN_DIR" PATH="$STUBS:/bin:/usr/bin" SYSTEMCTL_START_FAILS=1 bash "$LAUNCHER_BIN" 2>&1 || true)"
echo "$err_out" | grep -qi "failed to start" || fail "launcher start failure diagnostic not emitted"

# Absent systemctl
LAUNCHER_MIN_BIN="$TMP/bin-launcher-minimal"
mkdir -p "$LAUNCHER_MIN_BIN"
BASH_BIN="$(command -v bash)"
ln -sf "$BASH_BIN" "$LAUNCHER_MIN_BIN/bash"
ln -sf "$(command -v dirname)" "$LAUNCHER_MIN_BIN/dirname"
if [ -e "$STUBS/notify-send" ]; then
  ln -sf "$STUBS/notify-send" "$LAUNCHER_MIN_BIN/notify-send"
else
  cat > "$LAUNCHER_MIN_BIN/notify-send" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod +x "$LAUNCHER_MIN_BIN/notify-send"
fi

err_out="$(HOME="$SPACE_HOME" XDG_RUNTIME_DIR="$RUN_DIR" PATH="$LAUNCHER_MIN_BIN" "$BASH_BIN" "$LAUNCHER_BIN" 2>&1 || true)"
echo "$err_out" | grep -qi "systemctl is not available" || fail "launcher absent systemctl diagnostic not emitted"

# Manual guidance emitted unconditionally when systemctl absent / unit missing (no heuristics)
err_out="$(HOME="$SPACE_HOME" XDG_RUNTIME_DIR="$RUN_DIR" PATH="$LAUNCHER_MIN_BIN" "$BASH_BIN" "$LAUNCHER_BIN" 2>&1 || true)"
echo "$err_out" | grep -qi "systemctl is not available" || fail "launcher absent systemctl diagnostic not emitted"
rm -rf "$SPACE_HOME"

# 15q: Marker malformed, NUL byte, oversize, symlink, and FIFO bounded refusal preserving all files
for bad_marker_type in "malformed" "nul" "oversize" "symlink" "fifo"; do
  MARKER_TEST_HOME="$TMP/bad-marker-$bad_marker_type"
  mkdir -p "$MARKER_TEST_HOME/.local/bin" "$MARKER_TEST_HOME/.local/share/klardrop" "$MARKER_TEST_HOME/.local/share/applications"
  echo "manual-sentinel-bin" > "$MARKER_TEST_HOME/.local/bin/klardrop-qt"
  echo "manual-sentinel-launcher" > "$MARKER_TEST_HOME/.local/bin/klardrop-qt-launcher"
  echo "manual-sentinel-desk" > "$MARKER_TEST_HOME/.local/share/applications/klardrop.desktop"
  target_marker="$MARKER_TEST_HOME/.local/share/klardrop/.installer-marker"

  case "$bad_marker_type" in
    malformed)
      printf 'q t\n' > "$target_marker"
      ;;
    nul)
      printf 'qt\0\n' > "$target_marker"
      ;;
    oversize)
      python3 -c "import sys; open(sys.argv[1], 'w').write('qt' + 'x' * 70)" "$target_marker"
      ;;
    symlink)
      other_marker="$MARKER_TEST_HOME/other-marker"
      printf 'qt\n' > "$other_marker"
      ln -s "$other_marker" "$target_marker"
      ;;
    fifo)
      mkfifo "$target_marker"
      ;;
  esac

  if HOME="$MARKER_TEST_HOME" \
     XDG_DATA_HOME="$MARKER_TEST_HOME/.local/share" \
     XDG_CONFIG_HOME="$MARKER_TEST_HOME/.config" \
     XDG_CACHE_HOME="$MARKER_TEST_HOME/.cache" \
     XDG_RUNTIME_DIR="$RUN_DIR" \
     PATH="$STUBS:$PATH" \
     KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
     timeout 5s bash "$INSTALLER" --qt >"$TMP/bad_marker.out" 2>"$TMP/bad_marker.err"; then
    fail "installer did not refuse $bad_marker_type marker"
  fi

  # Verify refusal diagnostic emitted
  grep -qi "marker.*is invalid, linked, or non-regular" "$TMP/bad_marker.err" || fail "missing bad marker refusal diagnostic for $bad_marker_type"

  # Verify all existing sentinels preserved
  [ "$(< "$MARKER_TEST_HOME/.local/bin/klardrop-qt")" = "manual-sentinel-bin" ] || fail "klardrop-qt modified on $bad_marker_type marker refusal"
  [ "$(< "$MARKER_TEST_HOME/.local/bin/klardrop-qt-launcher")" = "manual-sentinel-launcher" ] || fail "launcher modified on $bad_marker_type marker refusal"
  [ "$(< "$MARKER_TEST_HOME/.local/share/applications/klardrop.desktop")" = "manual-sentinel-desk" ] || fail "desktop modified on $bad_marker_type marker refusal"
  [ ! -e "$MARKER_TEST_HOME/.local/bin/klardrop" ] || fail "engine created on $bad_marker_type marker refusal"

  if [ "$bad_marker_type" = "symlink" ]; then
    [ "$(< "$other_marker")" = "qt" ] || fail "symlink target overwritten through link"
  fi

  rm -rf "$MARKER_TEST_HOME"
done

# 15r: Executable path containing '=' refused early before any mutation
EQUAL_HOME="$TMP/equal=home"
mkdir -p "$EQUAL_HOME/.local/bin" "$EQUAL_HOME/.local/share/applications"
echo "manual-sentinel" > "$EQUAL_HOME/.local/bin/klardrop-qt"

if HOME="$EQUAL_HOME" \
   XDG_DATA_HOME="$EQUAL_HOME/.local/share" \
   XDG_CONFIG_HOME="$EQUAL_HOME/.config" \
   XDG_CACHE_HOME="$EQUAL_HOME/.cache" \
   XDG_RUNTIME_DIR="$RUN_DIR" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --qt >"$TMP/equal.out" 2>"$TMP/equal.err"; then
  fail "installer should refuse executable path containing '='"
fi
grep -qi "contains '='" "$TMP/equal.err" || fail "missing '=' in path refusal error"
[ "$(< "$EQUAL_HOME/.local/bin/klardrop-qt")" = "manual-sentinel" ] || fail "sentinel modified on '=' refusal"
[ ! -e "$EQUAL_HOME/.local/bin/klardrop" ] || fail "engine created despite '=' in path"
rm -rf "$EQUAL_HOME"

# 15s: Special characters in HOME / desktop entry Exec escaping
for special_label in "exec-amp-pipe" "exec-percent" "exec-shell-chars" "exec-backslash"; do
  case "$special_label" in
    exec-amp-pipe)    special_dir="home & pipe| name" ;;
    exec-percent)     special_dir="home %foo" ;;
    exec-shell-chars) special_dir="home \$cash \`tick\` \"quote\"" ;;
    exec-backslash)   special_dir="home \\slash" ;;
  esac

  SPEC_HOME="$TMP/$special_dir"
  mkdir -p "$SPEC_HOME"
  SPEC_RUN="$SPEC_HOME/runtime"
  mkdir -p "$SPEC_RUN"
  chmod 700 "$SPEC_RUN"

  HOME="$SPEC_HOME" \
  XDG_DATA_HOME="$SPEC_HOME/.local/share" \
  XDG_CONFIG_HOME="$SPEC_HOME/.config" \
  XDG_CACHE_HOME="$SPEC_HOME/.cache" \
  XDG_RUNTIME_DIR="$SPEC_RUN" \
  PATH="$STUBS:$PATH" \
  KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
  bash "$INSTALLER" --qt >"$TMP/spec_install.out" 2>"$TMP/spec_install.err" || fail "--qt install into $special_label failed"

  SPEC_DESK="$SPEC_HOME/.local/share/applications/klardrop.desktop"
  [ -f "$SPEC_DESK" ] || fail "desktop file missing in $special_label"

  # Validate Exec key quoting and escaping using Python stdlib Desktop Entry spec parser
  python3 -c '
import sys
spec_desk = sys.argv[1]
expected_launcher = sys.argv[2]
content = open(spec_desk, "r", encoding="utf-8").read()
exec_lines = [l for l in content.splitlines() if l.startswith("Exec=")]
assert len(exec_lines) == 1, f"Expected 1 Exec line, got {exec_lines}"
line = exec_lines[0]
assert line.startswith("Exec=/usr/bin/env \"") and line.endswith("\""), f"Exec not quoted with env prefix: {line}"
val = line[len("Exec=/usr/bin/env \""):-1]

# FreeDesktop string layer unescaping
s = ""
i = 0
while i < len(val):
    if val[i] == "\\" and i + 1 < len(val):
        nxt = val[i+1]
        if nxt == "\\": s += "\\"; i += 2
        elif nxt == "s": s += " "; i += 2
        elif nxt == "n": s += "\n"; i += 2
        elif nxt == "t": s += "\t"; i += 2
        elif nxt == "r": s += "\r"; i += 2
        else: s += val[i] + nxt; i += 2
    else:
        s += val[i]; i += 1

# Exec quote layer unescaping
q = ""
i = 0
while i < len(s):
    if i + 1 < len(s) and s[i] == "\\" and s[i+1] in ("\"", "`", "$", "\\"):
        q += s[i+1]; i += 2
    else:
        q += s[i]; i += 1

# Field code macro unescaping
m = ""
i = 0
while i < len(q):
    if i + 1 < len(q) and q[i] == "%" and q[i+1] == "%":
        m += "%"; i += 2
    else:
        m += q[i]; i += 1

assert m == expected_launcher, f"Parsed exec mismatch: {m} != {expected_launcher}"
' "$SPEC_DESK" "$SPEC_HOME/.local/bin/klardrop-qt-launcher" || fail "Desktop Entry spec validation failed for $special_label"

  # If gio is available and can launch, verify gio launch works as well across all paths including percent
  if command -v gio >/dev/null 2>&1; then
    LAUNCH_LOG="$SPEC_HOME/launched"
    env -u DBUS_SESSION_BUS_ADDRESS HOME="$SPEC_HOME" XDG_RUNTIME_DIR="$SPEC_RUN" MOCK_LAUNCH_LOG="$LAUNCH_LOG" gio launch "$SPEC_DESK" >/dev/null 2>&1 || fail "gio launch failed for $special_label"
    sleep 0.2
    [ -f "$LAUNCH_LOG" ] || fail "gio launch did not execute launcher for $special_label"
  fi

  rm -rf "$SPEC_HOME"
done

pass "standalone Qt flavor (--qt) installation, upgrade, switching, preflight, and uninstall"

# 16: Preserved icon symlink and referenced file untouched on --qt install & uninstall
ICON_TEST_HOME="$TMP/icon-test-home"
mkdir -p "$ICON_TEST_HOME"
ICON_TEST_RUN="$ICON_TEST_HOME/runtime"
mkdir -p "$ICON_TEST_RUN"
chmod 700 "$ICON_TEST_RUN"

REF_TARGET="$ICON_TEST_HOME/precious-custom-icon.png"
printf 'precious-user-icon-data\n' > "$REF_TARGET"

mkdir -p "$ICON_TEST_HOME/.local/share/icons/hicolor/128x128/apps"
ln -s "$REF_TARGET" "$ICON_TEST_HOME/.local/share/icons/hicolor/128x128/apps/klardrop.png"

# Dangling symlink in 256x256
mkdir -p "$ICON_TEST_HOME/.local/share/icons/hicolor/256x256/apps"
ln -s "$ICON_TEST_HOME/nonexistent.png" "$ICON_TEST_HOME/.local/share/icons/hicolor/256x256/apps/klardrop.png"

HOME="$ICON_TEST_HOME" \
XDG_DATA_HOME="$ICON_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$ICON_TEST_HOME/.config" \
XDG_CACHE_HOME="$ICON_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$ICON_TEST_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/icon_install.out" 2>"$TMP/icon_install.err" || fail "--qt install with icon symlink failed"

[ "$(< "$REF_TARGET")" = "precious-user-icon-data" ] || fail "referenced file behind icon symlink was modified"
[ -L "$ICON_TEST_HOME/.local/share/icons/hicolor/128x128/apps/klardrop.png" ] || fail "128x128 icon symlink was replaced with file"
[ -L "$ICON_TEST_HOME/.local/share/icons/hicolor/256x256/apps/klardrop.png" ] || fail "256x256 dangling icon symlink was replaced"

# Uninstall should also preserve the symlink and referenced file
HOME="$ICON_TEST_HOME" \
XDG_DATA_HOME="$ICON_TEST_HOME/.local/share" \
XDG_CONFIG_HOME="$ICON_TEST_HOME/.config" \
XDG_CACHE_HOME="$ICON_TEST_HOME/.cache" \
XDG_RUNTIME_DIR="$ICON_TEST_RUN" \
PATH="$STUBS:$PATH" \
bash "$INSTALLER" --uninstall >"$TMP/icon_uninst.out" 2>"$TMP/icon_uninst.err" || fail "--uninstall with icon symlink failed"

[ "$(< "$REF_TARGET")" = "precious-user-icon-data" ] || fail "referenced file modified during uninstall"
[ -L "$ICON_TEST_HOME/.local/share/icons/hicolor/128x128/apps/klardrop.png" ] || fail "128x128 icon symlink was removed during uninstall"
rm -rf "$ICON_TEST_HOME"

pass "icon symlinks and referenced files preserved untouched across install and uninstall"

# 17: Installer shared desktop migration ownership:
# Omarchy -> Qt migration succeeds, legitimate JVM -> Qt migration succeeds,
# and unrelated desktop refusal before any mutation.
SHARED_DESK_HOME="$TMP/shared-desk-home"
mkdir -p "$SHARED_DESK_HOME/.local/bin" "$SHARED_DESK_HOME/.local/share/applications" \
  "$SHARED_DESK_HOME/.local/share/klardrop" "$SHARED_DESK_HOME/.config/systemd/user" \
  "$SHARED_DESK_HOME/.config/omarchy/plugins/klardrop.omarchy"
SHARED_DESK_RUN="$SHARED_DESK_HOME/runtime"
mkdir -p "$SHARED_DESK_RUN"
chmod 700 "$SHARED_DESK_RUN"

# 17a: Successful Omarchy -> Qt migration
echo "omarchy" > "$SHARED_DESK_HOME/.local/share/klardrop/.installer-marker"
echo "engine-bin" > "$SHARED_DESK_HOME/.local/bin/klardrop"
chmod 755 "$SHARED_DESK_HOME/.local/bin/klardrop"
echo "helper" > "$SHARED_DESK_HOME/.local/bin/klardrop-omarchy-share"
chmod 755 "$SHARED_DESK_HOME/.local/bin/klardrop-omarchy-share"
echo "Exec=klardrop share %F" > "$SHARED_DESK_HOME/.local/share/applications/klardrop.desktop"
cat > "$SHARED_DESK_HOME/.config/systemd/user/klardrop.service" <<'EOF'
[Service]
ExecStart=%h/.local/bin/klardrop daemon
EOF

HOME="$SHARED_DESK_HOME" \
XDG_DATA_HOME="$SHARED_DESK_HOME/.local/share" \
XDG_CONFIG_HOME="$SHARED_DESK_HOME/.config" \
XDG_CACHE_HOME="$SHARED_DESK_HOME/.cache" \
XDG_RUNTIME_DIR="$SHARED_DESK_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/omarchy_to_qt.out" 2>"$TMP/omarchy_to_qt.err" || fail "Omarchy -> Qt migration failed"

[ "$(< "$SHARED_DESK_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker not updated to qt after Omarchy->Qt migration"
[ -f "$SHARED_DESK_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt missing after Omarchy->Qt migration"
[ -f "$SHARED_DESK_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher missing after Omarchy->Qt migration"
grep -q "klardrop-qt-launcher" "$SHARED_DESK_HOME/.local/share/applications/klardrop.desktop" || fail "desktop file not updated for Qt launcher"
rm -rf "$SHARED_DESK_HOME"

# 17b: Legitimate JVM -> Qt migration succeeds
JVM_TO_QT_HOME="$TMP/jvm-to-qt-home"
mkdir -p "$JVM_TO_QT_HOME/.local/bin" "$JVM_TO_QT_HOME/.local/lib/klardrop/bin" \
  "$JVM_TO_QT_HOME/.local/share/applications"
JVM_TO_QT_RUN="$JVM_TO_QT_HOME/runtime"
mkdir -p "$JVM_TO_QT_RUN"
chmod 700 "$JVM_TO_QT_RUN"

echo "fake-jvm-bin" > "$JVM_TO_QT_HOME/.local/lib/klardrop/bin/klardrop"
chmod 755 "$JVM_TO_QT_HOME/.local/lib/klardrop/bin/klardrop"
ln -s "$JVM_TO_QT_HOME/.local/lib/klardrop/bin/klardrop" "$JVM_TO_QT_HOME/.local/bin/klardrop"
printf '[Desktop Entry]\nType=Application\nExec="%s"\n' "$JVM_TO_QT_HOME/.local/lib/klardrop/bin/klardrop" > "$JVM_TO_QT_HOME/.local/share/applications/klardrop.desktop"

HOME="$JVM_TO_QT_HOME" \
XDG_DATA_HOME="$JVM_TO_QT_HOME/.local/share" \
XDG_CONFIG_HOME="$JVM_TO_QT_HOME/.config" \
XDG_CACHE_HOME="$JVM_TO_QT_HOME/.cache" \
XDG_RUNTIME_DIR="$JVM_TO_QT_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/jvm_to_qt.out" 2>"$TMP/jvm_to_qt.err" || fail "JVM -> Qt migration failed"

[ "$(< "$JVM_TO_QT_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker not created as qt after JVM->Qt migration"
[ ! -d "$JVM_TO_QT_HOME/.local/lib/klardrop" ] || fail "JVM app directory was not wiped after migration"
[ -f "$JVM_TO_QT_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt missing after JVM->Qt migration"
[ -f "$JVM_TO_QT_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher missing after JVM->Qt migration"
grep -q "klardrop-qt-launcher" "$JVM_TO_QT_HOME/.local/share/applications/klardrop.desktop" || fail "desktop file not updated for Qt launcher"
rm -rf "$JVM_TO_QT_HOME"

# 17c: Unrelated desktop file refused before any mutation
UNRELATED_HOME="$TMP/unrelated-desk-home"
mkdir -p "$UNRELATED_HOME/.local/share/applications"
UNRELATED_RUN="$UNRELATED_HOME/runtime"
mkdir -p "$UNRELATED_RUN"
chmod 700 "$UNRELATED_RUN"
echo "Exec=other-app %u" > "$UNRELATED_HOME/.local/share/applications/klardrop.desktop"

if HOME="$UNRELATED_HOME" \
   XDG_DATA_HOME="$UNRELATED_HOME/.local/share" \
   XDG_CONFIG_HOME="$UNRELATED_HOME/.config" \
   XDG_CACHE_HOME="$UNRELATED_HOME/.cache" \
   XDG_RUNTIME_DIR="$UNRELATED_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --qt >"$TMP/unrel_desk.out" 2>"$TMP/unrel_desk.err"; then
  fail "installer should refuse unrelated desktop file"
fi
grep -qi "not managed by this installer" "$TMP/unrel_desk.err" || fail "missing unrelated desktop refusal message"
[ "$(< "$UNRELATED_HOME/.local/share/applications/klardrop.desktop")" = "Exec=other-app %u" ] || fail "unrelated desktop modified on refusal"
[ ! -e "$UNRELATED_HOME/.local/bin/klardrop" ] || fail "engine binary created despite desktop refusal"
[ ! -e "$UNRELATED_HOME/.config/systemd/user/klardrop.service" ] || fail "service unit created despite desktop refusal"
rm -rf "$UNRELATED_HOME"

# 17d: Genuine generated JVM desktop under special HOME migrated to Qt
for spec_jvm_label in "percent" "shell" "backslash"; do
  case "$spec_jvm_label" in
    percent)   spec_jvm_name="home %foo" ;;
    shell)     spec_jvm_name="home \$cash \`tick\` \"quote\"" ;;
    backslash) spec_jvm_name="home \\slash" ;;
  esac
  SPEC_JVM_HOME="$TMP/jvm-special-$spec_jvm_label/$spec_jvm_name"
  mkdir -p "$SPEC_JVM_HOME/.local/bin" "$SPEC_JVM_HOME/.local/lib/klardrop/bin" \
    "$SPEC_JVM_HOME/.local/share/applications"
  SPEC_JVM_RUN="$SPEC_JVM_HOME/runtime"
  mkdir -p "$SPEC_JVM_RUN"
  chmod 700 "$SPEC_JVM_RUN"

  echo "fake-jvm-bin" > "$SPEC_JVM_HOME/.local/lib/klardrop/bin/klardrop"
  chmod 755 "$SPEC_JVM_HOME/.local/lib/klardrop/bin/klardrop"
  ln -s "$SPEC_JVM_HOME/.local/lib/klardrop/bin/klardrop" "$SPEC_JVM_HOME/.local/bin/klardrop"

  # Genuine installer generated JVM desktop with escaped Exec
  escaped_jvm_path="$SPEC_JVM_HOME/.local/lib/klardrop/bin/klardrop"
  escaped_jvm_path="${escaped_jvm_path//\\/\\\\\\\\}"
  escaped_jvm_path="${escaped_jvm_path//\"/\\\\\"}"
  escaped_jvm_path="${escaped_jvm_path//\$/\\\\\$}"
  escaped_jvm_path="${escaped_jvm_path//\`/\\\\\`}"
  escaped_jvm_path="${escaped_jvm_path//%/%%}"
  printf '[Desktop Entry]\nType=Application\nName=Klardrop\nExec=/usr/bin/env "%s"\nIcon=klardrop\nTerminal=false\n' "$escaped_jvm_path" > "$SPEC_JVM_HOME/.local/share/applications/klardrop.desktop"

  HOME="$SPEC_JVM_HOME" \
  XDG_DATA_HOME="$SPEC_JVM_HOME/.local/share" \
  XDG_CONFIG_HOME="$SPEC_JVM_HOME/.config" \
  XDG_CACHE_HOME="$SPEC_JVM_HOME/.cache" \
  XDG_RUNTIME_DIR="$SPEC_JVM_RUN" \
  PATH="$STUBS:$PATH" \
  KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
  bash "$INSTALLER" --qt >"$TMP/jvm_spec_${spec_jvm_label}.out" 2>"$TMP/jvm_spec_${spec_jvm_label}.err" || fail "JVM -> Qt migration failed under special HOME ($spec_jvm_label)"

  [ "$(< "$SPEC_JVM_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker not created as qt after JVM->Qt migration ($spec_jvm_label)"
  [ ! -d "$SPEC_JVM_HOME/.local/lib/klardrop" ] || fail "JVM app directory was not wiped after migration ($spec_jvm_label)"
  [ -f "$SPEC_JVM_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt missing after JVM->Qt migration ($spec_jvm_label)"
  [ -f "$SPEC_JVM_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher missing after JVM->Qt migration ($spec_jvm_label)"
  grep -q "klardrop-qt-launcher" "$SPEC_JVM_HOME/.local/share/applications/klardrop.desktop" || fail "desktop file not updated for Qt launcher ($spec_jvm_label)"
  rm -rf "$TMP/jvm-special-$spec_jvm_label"
done

pass "shared desktop migration ownership: Omarchy->Qt, JVM->Qt, and unrelated refusal"

# 18: Metadata must not write through links: symlink desktop and service files refused before any mutation
LINK_TEST_HOME="$TMP/link-metadata-home"
mkdir -p "$LINK_TEST_HOME/.local/share/applications" "$LINK_TEST_HOME/.local/share/klardrop" \
  "$LINK_TEST_HOME/.config/systemd/user"
LINK_TEST_RUN="$LINK_TEST_HOME/runtime"
mkdir -p "$LINK_TEST_RUN"
chmod 700 "$LINK_TEST_RUN"

# 18a: Symlink desktop file rejected even with valid marker, referenced file preserved
echo "qt" > "$LINK_TEST_HOME/.local/share/klardrop/.installer-marker"
PRECIOUS_DESK="$LINK_TEST_HOME/precious-desktop.target"
echo "precious-desk-content" > "$PRECIOUS_DESK"
ln -s "$PRECIOUS_DESK" "$LINK_TEST_HOME/.local/share/applications/klardrop.desktop"

if HOME="$LINK_TEST_HOME" \
   XDG_DATA_HOME="$LINK_TEST_HOME/.local/share" \
   XDG_CONFIG_HOME="$LINK_TEST_HOME/.config" \
   XDG_CACHE_HOME="$LINK_TEST_HOME/.cache" \
   XDG_RUNTIME_DIR="$LINK_TEST_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --qt >"$TMP/symlink_desk.out" 2>"$TMP/symlink_desk.err"; then
  fail "installer should refuse symlink desktop file"
fi
grep -qi "symbolic link" "$TMP/symlink_desk.err" || fail "missing symlink desktop refusal message"
[ "$(< "$PRECIOUS_DESK")" = "precious-desk-content" ] || fail "referenced file behind desktop symlink was overwritten"
[ ! -e "$LINK_TEST_HOME/.local/bin/klardrop" ] || fail "engine binary created despite symlink desktop refusal"

# 18b: Symlink service file rejected before mutation, referenced file preserved
rm -f "$LINK_TEST_HOME/.local/share/applications/klardrop.desktop"
PRECIOUS_SVC="$LINK_TEST_HOME/precious-service.target"
echo "precious-service-content" > "$PRECIOUS_SVC"
ln -s "$PRECIOUS_SVC" "$LINK_TEST_HOME/.config/systemd/user/klardrop.service"

if HOME="$LINK_TEST_HOME" \
   XDG_DATA_HOME="$LINK_TEST_HOME/.local/share" \
   XDG_CONFIG_HOME="$LINK_TEST_HOME/.config" \
   XDG_CACHE_HOME="$LINK_TEST_HOME/.cache" \
   XDG_RUNTIME_DIR="$LINK_TEST_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --native >"$TMP/symlink_svc.out" 2>"$TMP/symlink_svc.err"; then
  fail "installer should refuse symlink service file"
fi
grep -qi "symbolic link" "$TMP/symlink_svc.err" || fail "missing symlink service refusal message"
[ "$(< "$PRECIOUS_SVC")" = "precious-service-content" ] || fail "referenced file behind service symlink was overwritten"
[ ! -e "$LINK_TEST_HOME/.local/bin/klardrop" ] || fail "engine binary created despite symlink service refusal"
rm -rf "$LINK_TEST_HOME"

pass "installer metadata rejects writing through symlinks and preserves referenced files"

# 19: Uninstall flavor routing: owned Omarchy --native --uninstall dispatches properly (no exit 127),
# and owned Qt --omarchy --uninstall cleans Qt executables
ROUTING_HOME="$TMP/uninstall-routing-home"
mkdir -p "$ROUTING_HOME"
ROUTING_RUN="$ROUTING_HOME/runtime"
mkdir -p "$ROUTING_RUN"
chmod 700 "$ROUTING_RUN"

# 19a: Owned Omarchy uninstalled with --native --uninstall
HOME="$ROUTING_HOME" \
XDG_DATA_HOME="$ROUTING_HOME/.local/share" \
XDG_CONFIG_HOME="$ROUTING_HOME/.config" \
XDG_CACHE_HOME="$ROUTING_HOME/.cache" \
XDG_RUNTIME_DIR="$ROUTING_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --omarchy >"$TMP/omarchy_setup.out" 2>"$TMP/omarchy_setup.err" || fail "omarchy setup failed"

echo "keep-omarchy-history" > "$ROUTING_HOME/.local/share/klardrop/history.db"

HOME="$ROUTING_HOME" \
XDG_DATA_HOME="$ROUTING_HOME/.local/share" \
XDG_CONFIG_HOME="$ROUTING_HOME/.config" \
XDG_CACHE_HOME="$ROUTING_HOME/.cache" \
XDG_RUNTIME_DIR="$ROUTING_RUN" \
PATH="$STUBS:$PATH" \
bash "$INSTALLER" --native --uninstall >"$TMP/omarchy_native_un.out" 2>"$TMP/omarchy_native_un.err" || fail "owned Omarchy --native --uninstall failed"

[ ! -e "$ROUTING_HOME/.local/bin/klardrop" ] || fail "klardrop binary not removed"
[ ! -e "$ROUTING_HOME/.local/bin/klardrop-omarchy-share" ] || fail "menu helper not removed"
[ ! -e "$ROUTING_HOME/.local/bin/klardrop-omarchy-open" ] || fail "open helper not removed"
[ ! -e "$ROUTING_HOME/.local/bin/klardrop-share-pick" ] || fail "share-pick helper not removed"
[ ! -e "$ROUTING_HOME/.local/share/klardrop/.installer-marker" ] || fail "marker not removed"
[ ! -e "$ROUTING_HOME/.config/systemd/user/klardrop.service" ] || fail "service not removed"
[ -f "$ROUTING_HOME/.local/share/klardrop/history.db" ] || fail "user history wiped by uninstall"
rm -rf "$ROUTING_HOME"

# 19b: Owned Qt uninstalled with --omarchy --uninstall cleans Qt executables (does not orphan them)
QT_ORPHAN_HOME="$TMP/qt-orphan-home"
mkdir -p "$QT_ORPHAN_HOME"
QT_ORPHAN_RUN="$QT_ORPHAN_HOME/runtime"
mkdir -p "$QT_ORPHAN_RUN"
chmod 700 "$QT_ORPHAN_RUN"

HOME="$QT_ORPHAN_HOME" \
XDG_DATA_HOME="$QT_ORPHAN_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_ORPHAN_HOME/.config" \
XDG_CACHE_HOME="$QT_ORPHAN_HOME/.cache" \
XDG_RUNTIME_DIR="$QT_ORPHAN_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/qt_orphan_setup.out" 2>"$TMP/qt_orphan_setup.err" || fail "qt setup failed"

HOME="$QT_ORPHAN_HOME" \
XDG_DATA_HOME="$QT_ORPHAN_HOME/.local/share" \
XDG_CONFIG_HOME="$QT_ORPHAN_HOME/.config" \
XDG_CACHE_HOME="$QT_ORPHAN_HOME/.cache" \
XDG_RUNTIME_DIR="$QT_ORPHAN_RUN" \
PATH="$STUBS:$PATH" \
bash "$INSTALLER" --omarchy --uninstall >"$TMP/qt_omarchy_un.out" 2>"$TMP/qt_omarchy_un.err" || fail "owned Qt --omarchy --uninstall failed"

[ ! -e "$QT_ORPHAN_HOME/.local/bin/klardrop" ] || fail "Rust client not removed by --omarchy --uninstall"
[ ! -e "$QT_ORPHAN_HOME/.local/bin/klardrop-engine" ] || fail "engine not removed by --omarchy --uninstall"
[ ! -e "$QT_ORPHAN_HOME/.local/bin/klardrop-qt" ] || fail "klardrop-qt was orphaned by --omarchy --uninstall"
[ ! -e "$QT_ORPHAN_HOME/.local/bin/klardrop-qt-launcher" ] || fail "klardrop-qt-launcher was orphaned by --omarchy --uninstall"
[ ! -e "$QT_ORPHAN_HOME/.local/share/applications/klardrop.desktop" ] || fail "desktop file not removed"
[ ! -e "$QT_ORPHAN_HOME/.local/share/klardrop/.installer-marker" ] || fail "marker not removed"
rm -rf "$QT_ORPHAN_HOME"

pass "uninstall flavor routing dispatches cleanly and prevents orphaned executables"

# 20: JVM migration preserves package-owned 128px icon
PKG_ICON_HOME="$TMP/pkg-icon-home"
mkdir -p "$PKG_ICON_HOME/.local/bin" "$PKG_ICON_HOME/.local/lib/klardrop/bin" \
  "$PKG_ICON_HOME/.local/share/applications" "$PKG_ICON_HOME/.local/share/icons/hicolor/128x128/apps"
PKG_ICON_RUN="$PKG_ICON_HOME/runtime"
mkdir -p "$PKG_ICON_RUN"
chmod 700 "$PKG_ICON_RUN"

echo "fake-jvm" > "$PKG_ICON_HOME/.local/lib/klardrop/bin/klardrop"
chmod 755 "$PKG_ICON_HOME/.local/lib/klardrop/bin/klardrop"
ln -s "$PKG_ICON_HOME/.local/lib/klardrop/bin/klardrop" "$PKG_ICON_HOME/.local/bin/klardrop"
echo "precious-pkg-icon" > "$PKG_ICON_HOME/.local/share/icons/hicolor/128x128/apps/klardrop.png"
PKG_ICON_FILE="$PKG_ICON_HOME/.local/share/icons/hicolor/128x128/apps/klardrop.png"

HOME="$PKG_ICON_HOME" \
XDG_DATA_HOME="$PKG_ICON_HOME/.local/share" \
XDG_CONFIG_HOME="$PKG_ICON_HOME/.config" \
XDG_CACHE_HOME="$PKG_ICON_HOME/.cache" \
XDG_RUNTIME_DIR="$PKG_ICON_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
MOCK_PACKAGE_PATH="$PKG_ICON_FILE" \
bash "$INSTALLER" --native >"$TMP/pkg_icon_mig.out" 2>"$TMP/pkg_icon_mig.err" || fail "JVM -> native migration failed"

[ -f "$PKG_ICON_FILE" ] || fail "package-owned 128px icon was deleted during JVM migration"
[ "$(< "$PKG_ICON_FILE")" = "precious-pkg-icon" ] || fail "package-owned 128px icon content was modified during JVM migration"
rm -rf "$PKG_ICON_HOME"

pass "package-owned 128px icon preserved during genuine JVM migration"

# =============================================================================
# 21. Default installer routing & regression checks
#     - Clean non-Omarchy with no flags: installs native engine + Qt GUI (not JVM)
#     - Clean Omarchy with no flags: installs native engine + Omarchy integration
#     - Explicit --jvm with no prior install: installs JVM app-image
#     - Bare reinstall on owned native (headless) preserves native flavor
#     - Bare reinstall on owned qt preserves qt flavor
# =============================================================================

# 21a: Clean non-Omarchy install with no flags defaults to native + Qt (not JVM)
NON_OMAR_HOME="$TMP/clean-non-omar-home"
mkdir -p "$NON_OMAR_HOME"
NON_OMAR_RUN="$NON_OMAR_HOME/runtime"
mkdir -p "$NON_OMAR_RUN"
chmod 700 "$NON_OMAR_RUN"

# Build isolated PATH without omarchy binaries
NON_OMARCHY_BIN="$TMP/non-omarchy-bin"
mkdir -p "$NON_OMARCHY_BIN"
for f in /usr/bin/*; do
  case "$(basename "$f")" in
    omarchy*) ;;
    *) ln -s "$f" "$NON_OMARCHY_BIN/" 2>/dev/null || true ;;
  esac
done

NON_OMARCHY_STUBS="$TMP/non-omarchy-stubs"
mkdir -p "$NON_OMARCHY_STUBS"
for s in "$STUBS"/*; do
  case "$(basename "$s")" in
    omarchy*) ;;
    *) ln -s "$s" "$NON_OMARCHY_STUBS/" 2>/dev/null || true ;;
  esac
done

HOME="$NON_OMAR_HOME" \
XDG_DATA_HOME="$NON_OMAR_HOME/.local/share" \
XDG_CONFIG_HOME="$NON_OMAR_HOME/.config" \
XDG_CACHE_HOME="$NON_OMAR_HOME/.cache" \
XDG_RUNTIME_DIR="$NON_OMAR_RUN" \
PATH="$NON_OMARCHY_STUBS:$NON_OMARCHY_BIN" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" >"$TMP/clean_non_omar.out" 2>"$TMP/clean_non_omar.err" \
  || fail "clean non-Omarchy install failed: $(cat "$TMP/clean_non_omar.err")"

# Asserts qtmarker/nativeengine/gui/launcher/userunit/noJVMappimage
[ -f "$NON_OMAR_HOME/.local/share/klardrop/.installer-marker" ] || fail "installer marker not created on clean non-Omarchy install"
[ "$(< "$NON_OMAR_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "clean non-Omarchy default must be qt flavor"
[ -x "$NON_OMAR_HOME/.local/bin/klardrop" ] || fail "Rust client missing in clean non-Omarchy install"
[ -x "$NON_OMAR_HOME/.local/bin/klardrop-engine" ] || fail "engine missing in clean non-Omarchy install"
[ -x "$NON_OMAR_HOME/.local/bin/klardrop-qt" ] || fail "Qt GUI binary missing in clean non-Omarchy install"
[ -x "$NON_OMAR_HOME/.local/bin/klardrop-qt-launcher" ] || fail "Qt launcher missing in clean non-Omarchy install"
[ -f "$NON_OMAR_HOME/.local/share/applications/klardrop.desktop" ] || fail "desktop entry missing in clean non-Omarchy install"
[ -f "$NON_OMAR_HOME/.config/systemd/user/klardrop.service" ] || fail "systemd user unit missing in clean non-Omarchy install"
[ ! -e "$NON_OMAR_HOME/.local/lib/klardrop" ] || fail "JVM app-image directory created in clean non-Omarchy install"
[ ! -e "$NON_OMAR_HOME/.local/bin/klardrop-omarchy-share" ] || fail "Omarchy helper installed on non-Omarchy system"
[ ! -d "$NON_OMAR_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "Omarchy plugin installed on non-Omarchy system"

# 21b: Clean Omarchy install with no flags remains Omarchy flavor
CLEAN_OMAR_HOME="$TMP/clean-omar-home"
mkdir -p "$CLEAN_OMAR_HOME"
CLEAN_OMAR_RUN="$CLEAN_OMAR_HOME/runtime"
mkdir -p "$CLEAN_OMAR_RUN"
chmod 700 "$CLEAN_OMAR_RUN"

HOME="$CLEAN_OMAR_HOME" \
XDG_DATA_HOME="$CLEAN_OMAR_HOME/.local/share" \
XDG_CONFIG_HOME="$CLEAN_OMAR_HOME/.config" \
XDG_CACHE_HOME="$CLEAN_OMAR_HOME/.cache" \
XDG_RUNTIME_DIR="$CLEAN_OMAR_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" >"$TMP/clean_omar.out" 2>"$TMP/clean_omar.err" \
  || fail "clean Omarchy install failed: $(cat "$TMP/clean_omar.err")"

[ -f "$CLEAN_OMAR_HOME/.local/share/klardrop/.installer-marker" ] || fail "installer marker not created on clean Omarchy install"
[ "$(< "$CLEAN_OMAR_HOME/.local/share/klardrop/.installer-marker")" = "omarchy" ] || fail "clean Omarchy default must remain omarchy flavor"
[ -x "$CLEAN_OMAR_HOME/.local/bin/klardrop" ] || fail "Rust client missing in clean Omarchy install"
[ -x "$CLEAN_OMAR_HOME/.local/bin/klardrop-engine" ] || fail "engine missing in clean Omarchy install"
[ -x "$CLEAN_OMAR_HOME/.local/bin/klardrop-omarchy-share" ] || fail "Omarchy share helper missing in clean Omarchy install"
[ -x "$CLEAN_OMAR_HOME/.local/bin/klardrop-omarchy-open" ] || fail "Omarchy open helper missing in clean Omarchy install"
[ -x "$CLEAN_OMAR_HOME/.local/bin/klardrop-share-pick" ] || fail "Omarchy share-pick helper missing in clean Omarchy install"
[ -d "$CLEAN_OMAR_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "Omarchy plugin missing in clean Omarchy install"
[ ! -e "$CLEAN_OMAR_HOME/.local/bin/klardrop-qt" ] || fail "Qt binary installed in Omarchy flavor"
[ ! -e "$CLEAN_OMAR_HOME/.local/lib/klardrop" ] || fail "JVM app-image directory created in clean Omarchy install"
rm -rf "$CLEAN_OMAR_HOME"

# 21c: Explicit --jvm in clean home still installs JVM app-image
JVM_EXP_HOME="$TMP/jvm-exp-home"
mkdir -p "$JVM_EXP_HOME"
JVM_EXP_RUN="$JVM_EXP_HOME/runtime"
mkdir -p "$JVM_EXP_RUN"
chmod 700 "$JVM_EXP_RUN"

HOME="$JVM_EXP_HOME" \
XDG_DATA_HOME="$JVM_EXP_HOME/.local/share" \
XDG_CONFIG_HOME="$JVM_EXP_HOME/.config" \
XDG_CACHE_HOME="$JVM_EXP_HOME/.cache" \
XDG_RUNTIME_DIR="$JVM_EXP_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$JVM_TARBALL" \
bash "$INSTALLER" --jvm >"$TMP/jvm_exp.out" 2>"$TMP/jvm_exp.err" \
  || fail "explicit --jvm install failed: $(cat "$TMP/jvm_exp.err")"

[ -d "$JVM_EXP_HOME/.local/lib/klardrop" ] || fail "JVM app directory missing for explicit --jvm"
[ -f "$JVM_EXP_HOME/.local/lib/klardrop/bin/klardrop" ] || fail "JVM binary missing for explicit --jvm"
[ -L "$JVM_EXP_HOME/.local/bin/klardrop" ] || fail "JVM launcher symlink missing for explicit --jvm"
[ ! -e "$JVM_EXP_HOME/.local/share/klardrop/.installer-marker" ] || fail "installer marker should not exist for JVM install"
[ ! -e "$JVM_EXP_HOME/.local/bin/klardrop-qt" ] || fail "Qt binary installed for explicit --jvm"
[ ! -d "$JVM_EXP_HOME/.config/omarchy" ] || fail "Omarchy config created for explicit --jvm"
rm -rf "$JVM_EXP_HOME"

# 21d: Bare reinstall on owned native (headless) preserves native flavor
NAT_RE_HOME="$TMP/nat-re-home"
mkdir -p "$NAT_RE_HOME"
NAT_RE_RUN="$NAT_RE_HOME/runtime"
mkdir -p "$NAT_RE_RUN"
chmod 700 "$NAT_RE_RUN"

HOME="$NAT_RE_HOME" \
XDG_DATA_HOME="$NAT_RE_HOME/.local/share" \
XDG_CONFIG_HOME="$NAT_RE_HOME/.config" \
XDG_CACHE_HOME="$NAT_RE_HOME/.cache" \
XDG_RUNTIME_DIR="$NAT_RE_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/nat_pre.out" 2>"$TMP/nat_pre.err" || fail "--native initial install failed"

[ "$(< "$NAT_RE_HOME/.local/share/klardrop/.installer-marker")" = "native" ] || fail "marker not native"

HOME="$NAT_RE_HOME" \
XDG_DATA_HOME="$NAT_RE_HOME/.local/share" \
XDG_CONFIG_HOME="$NAT_RE_HOME/.config" \
XDG_CACHE_HOME="$NAT_RE_HOME/.cache" \
XDG_RUNTIME_DIR="$NAT_RE_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" >"$TMP/nat_bare.out" 2>"$TMP/nat_bare.err" || fail "bare reinstall on owned native failed"

[ "$(< "$NAT_RE_HOME/.local/share/klardrop/.installer-marker")" = "native" ] || fail "marker not preserved as native on bare reinstall"
[ -x "$NAT_RE_HOME/.local/bin/klardrop" ] || fail "Rust client missing after bare reinstall"
[ -x "$NAT_RE_HOME/.local/bin/klardrop-engine" ] || fail "engine missing after bare reinstall"
[ ! -e "$NAT_RE_HOME/.local/bin/klardrop-qt" ] || fail "Qt binary installed during bare reinstall on native marker"
[ ! -d "$NAT_RE_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "Omarchy plugin installed during bare reinstall on native marker"
rm -rf "$NAT_RE_HOME"

# 21e: Bare reinstall on owned qt preserves qt flavor
HOME="$NON_OMAR_HOME" \
XDG_DATA_HOME="$NON_OMAR_HOME/.local/share" \
XDG_CONFIG_HOME="$NON_OMAR_HOME/.config" \
XDG_CACHE_HOME="$NON_OMAR_HOME/.cache" \
XDG_RUNTIME_DIR="$NON_OMAR_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" >"$TMP/qt_bare.out" 2>"$TMP/qt_bare.err" || fail "bare reinstall on owned qt failed"

[ "$(< "$NON_OMAR_HOME/.local/share/klardrop/.installer-marker")" = "qt" ] || fail "marker not preserved as qt on bare reinstall"
[ -x "$NON_OMAR_HOME/.local/bin/klardrop-qt" ] || fail "Qt binary missing after bare reinstall on qt marker"
[ ! -d "$NON_OMAR_HOME/.config/omarchy/plugins/klardrop.omarchy" ] || fail "Omarchy plugin installed during bare reinstall on qt marker"
rm -rf "$NON_OMAR_HOME"

pass "clean default installer routing (non-Omarchy -> Qt, Omarchy -> Omarchy, explicit JVM, bare reinstall preservation)"

# =============================================================================
# 22. Rust client / Kotlin engine split
#     - both binaries installed and executable in every native flavor
#     - systemd unit starts the engine for omarchy / native / qt
#     - both binaries removable by --uninstall in every flavor
#     - package-owned and unowned binaries refused (install and uninstall)
#     - defective tarballs (missing client, test fixture daemon) rejected
#     - ELF preflight covers the client as well as the engine
# =============================================================================

# 22a: both binaries installed, both removed by --native --uninstall
SPLIT_HOME="$TMP/split-home"
SPLIT_RUN="$TMP/split-run"
mkdir -p "$SPLIT_HOME" "$SPLIT_RUN"
chmod 700 "$SPLIT_RUN"

HOME="$SPLIT_HOME" \
XDG_DATA_HOME="$SPLIT_HOME/.local/share" \
XDG_CONFIG_HOME="$SPLIT_HOME/.config" \
XDG_CACHE_HOME="$SPLIT_HOME/.cache" \
XDG_RUNTIME_DIR="$SPLIT_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/split_install.out" 2>"$TMP/split_install.err" || fail "split install failed: $(cat "$TMP/split_install.err")"

[ -x "$SPLIT_HOME/.local/bin/klardrop" ] || fail "Rust client not installed executable"
[ -x "$SPLIT_HOME/.local/bin/klardrop-engine" ] || fail "engine not installed executable"
grep -q 'fake klardrop x64' "$SPLIT_HOME/.local/bin/klardrop" || fail "installed client is not the staged client"
grep -q 'fake klardrop-engine x64' "$SPLIT_HOME/.local/bin/klardrop-engine" || fail "installed engine is not the staged engine"
grep -q "^ExecStart=%h/.local/bin/klardrop-engine daemon$" "$SPLIT_HOME/.config/systemd/user/klardrop.service" || fail "--native unit must start klardrop-engine"
ls "$SPLIT_HOME/.local/bin" | grep -q '^\.klardrop' && fail "temporary install artifacts left behind in BIN_DIR"

HOME="$SPLIT_HOME" \
XDG_DATA_HOME="$SPLIT_HOME/.local/share" \
XDG_CONFIG_HOME="$SPLIT_HOME/.config" \
XDG_CACHE_HOME="$SPLIT_HOME/.cache" \
XDG_RUNTIME_DIR="$SPLIT_RUN" \
PATH="$STUBS:$PATH" \
bash "$INSTALLER" --native --uninstall >"$TMP/split_uninstall.out" 2>"$TMP/split_uninstall.err" || fail "split uninstall failed: $(cat "$TMP/split_uninstall.err")"

[ ! -e "$SPLIT_HOME/.local/bin/klardrop" ] || fail "--native --uninstall left the Rust client behind"
[ ! -e "$SPLIT_HOME/.local/bin/klardrop-engine" ] || fail "--native --uninstall left the engine behind"
rm -rf "$SPLIT_HOME"

# 22b: package-owned Rust client refuses overwrite and refuses removal
PKG_CLIENT_HOME="$TMP/pkg-client-home"
mkdir -p "$PKG_CLIENT_HOME/.local/bin" "$PKG_CLIENT_HOME/.local/share/klardrop"
echo "pkg-client" > "$PKG_CLIENT_HOME/.local/bin/klardrop"
echo "pkg-engine" > "$PKG_CLIENT_HOME/.local/bin/klardrop-engine"
echo "native" > "$PKG_CLIENT_HOME/.local/share/klardrop/.installer-marker"

if HOME="$PKG_CLIENT_HOME" \
   XDG_DATA_HOME="$PKG_CLIENT_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_CLIENT_HOME/.config" \
   XDG_CACHE_HOME="$PKG_CLIENT_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   MOCK_PACKAGE_PATH="$PKG_CLIENT_HOME/.local/bin/klardrop" \
   bash "$INSTALLER" --native >"$TMP/pkg_client_install.out" 2>"$TMP/pkg_client_install.err"; then
  fail "installer should refuse to overwrite a package-owned Rust client"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_client_install.err" || fail "missing package-ownership refusal for the Rust client"
[ "$(< "$PKG_CLIENT_HOME/.local/bin/klardrop")" = "pkg-client" ] || fail "package-owned Rust client was overwritten"
[ "$(< "$PKG_CLIENT_HOME/.local/bin/klardrop-engine")" = "pkg-engine" ] || fail "engine was replaced despite the Rust client refusal"

if HOME="$PKG_CLIENT_HOME" \
   XDG_DATA_HOME="$PKG_CLIENT_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_CLIENT_HOME/.config" \
   XDG_CACHE_HOME="$PKG_CLIENT_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   MOCK_PACKAGE_PATH="$PKG_CLIENT_HOME/.local/bin/klardrop" \
   bash "$INSTALLER" --native --uninstall >"$TMP/pkg_client_uninstall.out" 2>"$TMP/pkg_client_uninstall.err"; then
  fail "uninstall should refuse to delete a package-owned Rust client"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_client_uninstall.err" || fail "missing package-ownership uninstall refusal for the Rust client"
[ "$(< "$PKG_CLIENT_HOME/.local/bin/klardrop")" = "pkg-client" ] || fail "package-owned Rust client was deleted"
[ "$(< "$PKG_CLIENT_HOME/.local/bin/klardrop-engine")" = "pkg-engine" ] || fail "engine was deleted despite the Rust client refusal"
rm -rf "$PKG_CLIENT_HOME"

# 22c: package-owned engine refuses overwrite (same guard, other binary)
PKG_ENGINE_HOME="$TMP/pkg-engine-home"
mkdir -p "$PKG_ENGINE_HOME/.local/bin"
echo "pkg-engine" > "$PKG_ENGINE_HOME/.local/bin/klardrop-engine"

if HOME="$PKG_ENGINE_HOME" \
   XDG_DATA_HOME="$PKG_ENGINE_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_ENGINE_HOME/.config" \
   XDG_CACHE_HOME="$PKG_ENGINE_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   MOCK_PACKAGE_PATH="$PKG_ENGINE_HOME/.local/bin/klardrop-engine" \
   bash "$INSTALLER" --native >"$TMP/pkg_engine_install.out" 2>"$TMP/pkg_engine_install.err"; then
  fail "installer should refuse to overwrite a package-owned engine"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_engine_install.err" || fail "missing package-ownership refusal for the engine"
[ "$(< "$PKG_ENGINE_HOME/.local/bin/klardrop-engine")" = "pkg-engine" ] || fail "package-owned engine was overwritten"
[ ! -e "$PKG_ENGINE_HOME/.local/bin/klardrop" ] || fail "client installed despite the engine package-ownership refusal"

# A package-owned engine must also survive --uninstall.
mkdir -p "$PKG_ENGINE_HOME/.local/share/klardrop"
echo "native" > "$PKG_ENGINE_HOME/.local/share/klardrop/.installer-marker"
if HOME="$PKG_ENGINE_HOME" \
   XDG_DATA_HOME="$PKG_ENGINE_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_ENGINE_HOME/.config" \
   XDG_CACHE_HOME="$PKG_ENGINE_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   MOCK_PACKAGE_PATH="$PKG_ENGINE_HOME/.local/bin/klardrop-engine" \
   bash "$INSTALLER" --native --uninstall >"$TMP/pkg_engine_uninstall.out" 2>"$TMP/pkg_engine_uninstall.err"; then
  fail "uninstall should refuse to delete a package-owned engine"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_engine_uninstall.err" || fail "missing package-ownership uninstall refusal for the engine"
[ "$(< "$PKG_ENGINE_HOME/.local/bin/klardrop-engine")" = "pkg-engine" ] || fail "package-owned engine was deleted by --uninstall"

# 22d-bis: a package-owned klardrop-share-pick helper must block the install the
# same way it blocks the other two helpers (install-time guard list), and the
# flavor-switch guard list must treat it identically. Note the omarchy
# UNINSTALL path carries no helper ownership guard at all -- not for
# MENU_HELPER/OPEN_HELPER either -- so there is nothing to assert there beyond
# plain removal, which section 19a already covers.
PKG_PICK_HOME="$TMP/pkg-pick-home"
PKG_PICK_RUN="$TMP/pkg-pick-run"
mkdir -p "$PKG_PICK_HOME" "$PKG_PICK_RUN"
chmod 700 "$PKG_PICK_RUN"

HOME="$PKG_PICK_HOME" \
XDG_DATA_HOME="$PKG_PICK_HOME/.local/share" \
XDG_CONFIG_HOME="$PKG_PICK_HOME/.config" \
XDG_CACHE_HOME="$PKG_PICK_HOME/.cache" \
XDG_RUNTIME_DIR="$PKG_PICK_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --omarchy >"$TMP/pkg_pick_setup.out" 2>"$TMP/pkg_pick_setup.err" \
  || fail "package-owned share-pick setup install failed: $(cat "$TMP/pkg_pick_setup.err")"
[ -x "$PKG_PICK_HOME/.local/bin/klardrop-share-pick" ] || fail "share-pick helper not installed in the package-ownership setup"
printf '#!/usr/bin/env bash\nexit 0\n' > "$PKG_PICK_HOME/.local/bin/klardrop-share-pick"
cp "$PKG_PICK_HOME/.local/bin/klardrop-share-pick" "$TMP/pkg_pick.payload"
chmod 755 "$PKG_PICK_HOME/.local/bin/klardrop-share-pick"

if HOME="$PKG_PICK_HOME" \
   XDG_DATA_HOME="$PKG_PICK_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_PICK_HOME/.config" \
   XDG_CACHE_HOME="$PKG_PICK_HOME/.cache" \
   XDG_RUNTIME_DIR="$PKG_PICK_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   MOCK_PACKAGE_PATH="$PKG_PICK_HOME/.local/bin/klardrop-share-pick" \
   bash "$INSTALLER" --omarchy >"$TMP/pkg_pick_reinstall.out" 2>"$TMP/pkg_pick_reinstall.err"; then
  fail "installer should refuse to overwrite a package-owned share-pick helper"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_pick_reinstall.err" \
  || fail "missing package-ownership refusal for the share-pick helper: $(cat "$TMP/pkg_pick_reinstall.err")"
diff -q "$TMP/pkg_pick.payload" "$PKG_PICK_HOME/.local/bin/klardrop-share-pick" >/dev/null \
  || fail "package-owned share-pick helper was overwritten"

# Switching away from Omarchy tears the frontend down via clean_omarchy_frontend
# and must refuse there too (that is the path --omarchy --uninstall shares with
# the other helpers' guard).
if HOME="$PKG_PICK_HOME" \
   XDG_DATA_HOME="$PKG_PICK_HOME/.local/share" \
   XDG_CONFIG_HOME="$PKG_PICK_HOME/.config" \
   XDG_CACHE_HOME="$PKG_PICK_HOME/.cache" \
   XDG_RUNTIME_DIR="$PKG_PICK_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   MOCK_PACKAGE_PATH="$PKG_PICK_HOME/.local/bin/klardrop-share-pick" \
   bash "$INSTALLER" --native >"$TMP/pkg_pick_switch.out" 2>"$TMP/pkg_pick_switch.err"; then
  fail "flavor-switch cleanup should refuse to delete a package-owned share-pick helper"
fi
grep -qi "managed by a system package manager" "$TMP/pkg_pick_switch.err" \
  || fail "missing flavor-switch ownership refusal for the share-pick helper: $(cat "$TMP/pkg_pick_switch.err")"
[ -f "$PKG_PICK_HOME/.local/bin/klardrop-share-pick" ] \
  || fail "package-owned share-pick helper was deleted during flavor switch"
rm -rf "$PKG_PICK_HOME" "$PKG_PICK_RUN"

# A symlink sitting on the engine path is never ours (the client path may still
# legitimately be the legacy JVM launcher symlink; the engine path may not):
# refuse it and preserve whatever it points at.
ENGINE_LINK_HOME="$TMP/engine-link-home"
mkdir -p "$ENGINE_LINK_HOME/.local/bin"
PRECIOUS_ENGINE="$ENGINE_LINK_HOME/precious-engine.target"
echo "precious-engine-payload" > "$PRECIOUS_ENGINE"
ln -s "$PRECIOUS_ENGINE" "$ENGINE_LINK_HOME/.local/bin/klardrop-engine"

if HOME="$ENGINE_LINK_HOME" \
   XDG_DATA_HOME="$ENGINE_LINK_HOME/.local/share" \
   XDG_CONFIG_HOME="$ENGINE_LINK_HOME/.config" \
   XDG_CACHE_HOME="$ENGINE_LINK_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --native >"$TMP/engine_link.out" 2>"$TMP/engine_link.err"; then
  fail "installer should refuse to write the engine through a symlink"
fi
grep -qi "symbolic link" "$TMP/engine_link.err" || fail "missing symlink refusal for the engine path"
[ "$(< "$PRECIOUS_ENGINE")" = "precious-engine-payload" ] || fail "file behind the engine symlink was overwritten"
[ -L "$ENGINE_LINK_HOME/.local/bin/klardrop-engine" ] || fail "engine symlink was replaced by the installer"
[ ! -e "$ENGINE_LINK_HOME/.local/bin/klardrop" ] || fail "client installed despite the engine symlink refusal"
rm -rf "$ENGINE_LINK_HOME"
rm -rf "$PKG_ENGINE_HOME"

# 22d: an unowned engine is never removed by --uninstall
UNOWNED_ENGINE_HOME="$TMP/unowned-engine-home"
mkdir -p "$UNOWNED_ENGINE_HOME/.local/bin"
echo "not-ours" > "$UNOWNED_ENGINE_HOME/.local/bin/klardrop-engine"

# No installer marker and no unit file: this engine is not ours, so --uninstall
# must refuse to touch it rather than deleting an unrelated file.

if HOME="$UNOWNED_ENGINE_HOME" \
   XDG_DATA_HOME="$UNOWNED_ENGINE_HOME/.local/share" \
   XDG_CONFIG_HOME="$UNOWNED_ENGINE_HOME/.config" \
   XDG_CACHE_HOME="$UNOWNED_ENGINE_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   bash "$INSTALLER" --native --uninstall >"$TMP/unowned_engine.out" 2>"$TMP/unowned_engine.err"; then
  fail "uninstall should not remove an unowned engine file"
fi
[ "$(< "$UNOWNED_ENGINE_HOME/.local/bin/klardrop-engine")" = "not-ours" ] || fail "unowned engine was removed"
rm -rf "$UNOWNED_ENGINE_HOME"

# 22e: a tarball without bin/klardrop is rejected before anything is installed
NOCLIENT_HOME="$TMP/noclient-home"
mkdir -p "$NOCLIENT_HOME"
if TEST_TARBALL="$NATIVE_TARBALL_NO_CLIENT" run_installer --native >"$TMP/noclient.out" 2>"$TMP/noclient.err"; then
  fail "installer should reject a tarball without bin/klardrop"
fi
grep -q "missing bin/klardrop" "$TMP/noclient.err" || fail "missing tarball layout error for absent bin/klardrop"
[ ! -e "$NOCLIENT_HOME/.local/bin/klardrop" ] || fail "client installed from a tarball without one"
[ ! -e "$NOCLIENT_HOME/.local/bin/klardrop-engine" ] || fail "engine installed from a rejected tarball"
[ ! -e "$NOCLIENT_HOME/.config/systemd/user/klardrop.service" ] || fail "service unit created from a rejected tarball"
rm -rf "$NOCLIENT_HOME"

# 22f: a tarball carrying the cli-rust test fixture daemon is rejected outright
FIXTURE_HOME="$TMP/fixture-home"
mkdir -p "$FIXTURE_HOME"
if TEST_TARBALL="$NATIVE_TARBALL_FIXTURE" run_installer --native >"$TMP/fixture.out" 2>"$TMP/fixture.err"; then
  fail "installer should reject a tarball containing klardrop-fixture-daemon"
fi
grep -q "klardrop-fixture-daemon" "$TMP/fixture.err" || fail "missing fixture-daemon rejection message"
[ ! -e "$FIXTURE_HOME/.local/bin/klardrop-fixture-daemon" ] || fail "test fixture daemon installed"
[ ! -e "$FIXTURE_HOME/.local/bin/klardrop" ] || fail "client installed from a rejected fixture tarball"
[ ! -e "$FIXTURE_HOME/.local/bin/klardrop-engine" ] || fail "engine installed from a rejected fixture tarball"
rm -rf "$FIXTURE_HOME"

# 22g: ELF preflight covers the Rust client, not just the engine
CLIENT_LDD_HOME="$TMP/client-ldd-home"
mkdir -p "$CLIENT_LDD_HOME"
if FAKE_LDD_CLIENT_FAIL=1 run_installer --native >"$TMP/client_ldd.out" 2>"$TMP/client_ldd.err"; then
  fail "installer should fail when the Rust client has missing shared libraries"
fi
grep -qi "missing shared library dependencies" "$TMP/client_ldd.err" || fail "missing dependency error for the Rust client"
grep -q "native binary (client)" "$TMP/client_ldd.err" || fail "missing-library error does not identify the client binary"
grep -q "pacman -S openssl" "$TMP/client_ldd.err" || fail "missing openssl hint for the client binary"
[ ! -e "$CLIENT_LDD_HOME/.local/bin/klardrop" ] || fail "client installed despite its own ELF preflight failure"
[ ! -e "$CLIENT_LDD_HOME/.local/bin/klardrop-engine" ] || fail "engine installed despite the client ELF preflight failure"
rm -rf "$CLIENT_LDD_HOME"

# 22h: the engine's own ELF preflight names the engine
ENGINE_LDD_HOME="$TMP/engine-ldd-home"
mkdir -p "$ENGINE_LDD_HOME"
if FAKE_LDD_FAIL=1 run_installer --native >"$TMP/engine_ldd.out" 2>"$TMP/engine_ldd.err"; then
  fail "installer should fail when the engine has missing shared libraries"
fi
grep -q "native binary (engine)" "$TMP/engine_ldd.err" || fail "missing-library error does not identify the engine binary"
[ ! -e "$ENGINE_LDD_HOME/.local/bin/klardrop-engine" ] || fail "engine installed despite its own ELF preflight failure"
rm -rf "$ENGINE_LDD_HOME"

# 22i: a pre-split install (single binary at ~/.local/bin/klardrop, unit starting
# that path) upgrades in place: it must be recognized as ours, gain the engine,
# and have its unit rewritten to the engine.
LEGACY_HOME="$TMP/legacy-split-home"
LEGACY_RUN="$TMP/legacy-split-run"
mkdir -p "$LEGACY_HOME/.local/bin" "$LEGACY_HOME/.local/share/klardrop" "$LEGACY_HOME/.config/systemd/user" "$LEGACY_RUN"
chmod 700 "$LEGACY_RUN"
echo "native" > "$LEGACY_HOME/.local/share/klardrop/.installer-marker"
echo "pre-split-engine" > "$LEGACY_HOME/.local/bin/klardrop"
chmod 755 "$LEGACY_HOME/.local/bin/klardrop"
cat > "$LEGACY_HOME/.config/systemd/user/klardrop.service" <<'EOF'
[Service]
ExecStart=%h/.local/bin/klardrop daemon
EOF

HOME="$LEGACY_HOME" \
XDG_DATA_HOME="$LEGACY_HOME/.local/share" \
XDG_CONFIG_HOME="$LEGACY_HOME/.config" \
XDG_CACHE_HOME="$LEGACY_HOME/.cache" \
XDG_RUNTIME_DIR="$LEGACY_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/legacy_split.out" 2>"$TMP/legacy_split.err" || fail "pre-split install refused: $(cat "$TMP/legacy_split.err")"

[ -x "$LEGACY_HOME/.local/bin/klardrop" ] || fail "pre-split install did not gain the Rust client"
[ -x "$LEGACY_HOME/.local/bin/klardrop-engine" ] || fail "pre-split install did not gain the engine"
grep -q "^ExecStart=%h/.local/bin/klardrop-engine daemon$" "$LEGACY_HOME/.config/systemd/user/klardrop.service" || fail "pre-split unit was not migrated to the engine"

HOME="$LEGACY_HOME" \
XDG_DATA_HOME="$LEGACY_HOME/.local/share" \
XDG_CONFIG_HOME="$LEGACY_HOME/.config" \
XDG_CACHE_HOME="$LEGACY_HOME/.cache" \
XDG_RUNTIME_DIR="$LEGACY_RUN" \
PATH="$STUBS:$PATH" \
bash "$INSTALLER" --native --uninstall >"$TMP/legacy_split_un.out" 2>"$TMP/legacy_split_un.err" || fail "pre-split uninstall failed: $(cat "$TMP/legacy_split_un.err")"
[ ! -e "$LEGACY_HOME/.local/bin/klardrop" ] || fail "pre-split uninstall left the client behind"
[ ! -e "$LEGACY_HOME/.local/bin/klardrop-engine" ] || fail "pre-split uninstall left the engine behind"
rm -rf "$LEGACY_HOME"

# 22j/22k: the package-ownership refusal must hold for every uninstall flavor, for
# both binaries — the omarchy and Qt rm lists carry the Rust client and the engine
# too, so neither may be deleted without the guard firing.
for split_flavor in omarchy qt; do
  FLAVOR_PKG_HOME="$TMP/split-flavor-pkg-$split_flavor"
  mkdir -p "$FLAVOR_PKG_HOME"
  HOME="$FLAVOR_PKG_HOME" \
  XDG_DATA_HOME="$FLAVOR_PKG_HOME/.local/share" \
  XDG_CONFIG_HOME="$FLAVOR_PKG_HOME/.config" \
  XDG_CACHE_HOME="$FLAVOR_PKG_HOME/.cache" \
  XDG_RUNTIME_DIR="$SPLIT_RUN" \
  PATH="$STUBS:$PATH" \
  KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
  bash "$INSTALLER" "--$split_flavor" >"$TMP/split_flavor_$split_flavor.out" 2>"$TMP/split_flavor_$split_flavor.err" \
    || fail "$split_flavor setup install failed: $(cat "$TMP/split_flavor_$split_flavor.err")"

  # Hand both binaries over to a "package manager", then reinstall over them.
  MOCK_PACKAGE_PATH="$FLAVOR_PKG_HOME/.local/bin/klardrop" \
  HOME="$FLAVOR_PKG_HOME" \
  XDG_DATA_HOME="$FLAVOR_PKG_HOME/.local/share" \
  XDG_CONFIG_HOME="$FLAVOR_PKG_HOME/.config" \
  XDG_CACHE_HOME="$FLAVOR_PKG_HOME/.cache" \
  XDG_RUNTIME_DIR="$SPLIT_RUN" \
  PATH="$STUBS:$PATH" \
  KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
  bash "$INSTALLER" "--$split_flavor" >"$TMP/split_flavor_$split_flavor.reinstall.out" 2>"$TMP/split_flavor_$split_flavor.reinstall.err" \
    && fail "$split_flavor install should refuse to overwrite a package-owned Rust client"
  grep -qi "managed by a system package manager" "$TMP/split_flavor_$split_flavor.reinstall.err" || fail "$split_flavor install lost the package-ownership refusal for the Rust client"
  grep -q 'fake klardrop x64' "$FLAVOR_PKG_HOME/.local/bin/klardrop" || fail "$split_flavor refused install still replaced the client payload"

  if MOCK_PACKAGE_PATH="$FLAVOR_PKG_HOME/.local/bin/klardrop-engine" \
     HOME="$FLAVOR_PKG_HOME" \
     XDG_DATA_HOME="$FLAVOR_PKG_HOME/.local/share" \
     XDG_CONFIG_HOME="$FLAVOR_PKG_HOME/.config" \
     XDG_CACHE_HOME="$FLAVOR_PKG_HOME/.cache" \
     XDG_RUNTIME_DIR="$SPLIT_RUN" \
     PATH="$STUBS:$PATH" \
     bash "$INSTALLER" "--$split_flavor" --uninstall >"$TMP/split_flavor_$split_flavor.uninstall.out" 2>"$TMP/split_flavor_$split_flavor.uninstall.err"; then
    fail "$split_flavor uninstall should refuse to delete a package-owned engine"
  fi
  grep -qi "managed by a system package manager" "$TMP/split_flavor_$split_flavor.uninstall.err" || fail "$split_flavor uninstall lost the package-ownership refusal for the engine"
  [ -f "$FLAVOR_PKG_HOME/.local/bin/klardrop-engine" ] || fail "$split_flavor uninstall deleted a package-owned engine"
  [ -f "$FLAVOR_PKG_HOME/.local/bin/klardrop" ] || fail "$split_flavor uninstall deleted the Rust client alongside the refusal"
  [ -f "$FLAVOR_PKG_HOME/.config/systemd/user/klardrop.service" ] || fail "$split_flavor uninstall removed the unit despite the refusal"
  rm -rf "$FLAVOR_PKG_HOME"
done

# 22l: a failure while installing the second binary must not leave a half-swapped
# pair. Upgrading from v1 to v2 with `install` failing on the Rust client has to
# leave BOTH v1 binaries in place and no temporary files behind.
ATOMIC_HOME="$TMP/atomic-home"
mkdir -p "$ATOMIC_HOME"
ATOMIC_RUN="$TMP/atomic-run"
mkdir -p "$ATOMIC_RUN"
chmod 700 "$ATOMIC_RUN"

HOME="$ATOMIC_HOME" \
XDG_DATA_HOME="$ATOMIC_HOME/.local/share" \
XDG_CONFIG_HOME="$ATOMIC_HOME/.config" \
XDG_CACHE_HOME="$ATOMIC_HOME/.cache" \
XDG_RUNTIME_DIR="$ATOMIC_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/atomic_v1.out" 2>"$TMP/atomic_v1.err" || fail "atomicity setup install failed: $(cat "$TMP/atomic_v1.err")"
grep -q 'fake klardrop x64' "$ATOMIC_HOME/.local/bin/klardrop" || fail "atomicity setup: v1 client missing"
grep -q 'fake klardrop-engine x64' "$ATOMIC_HOME/.local/bin/klardrop-engine" || fail "atomicity setup: v1 engine missing"

if FAIL_CLIENT_INSTALL=1 HOME="$ATOMIC_HOME" \
   XDG_DATA_HOME="$ATOMIC_HOME/.local/share" \
   XDG_CONFIG_HOME="$ATOMIC_HOME/.config" \
   XDG_CACHE_HOME="$ATOMIC_HOME/.cache" \
   XDG_RUNTIME_DIR="$ATOMIC_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL_V2" \
   bash "$INSTALLER" --native >"$TMP/atomic_v2.out" 2>"$TMP/atomic_v2.err"; then
  fail "installer should fail when the client binary cannot be installed"
fi
grep -qi "failed to install the klardrop client binary" "$TMP/atomic_v2.err" || fail "missing client-install failure diagnostic"
grep -q 'v2' "$ATOMIC_HOME/.local/bin/klardrop-engine" && fail "engine was upgraded even though the client install failed"
grep -q 'v2' "$ATOMIC_HOME/.local/bin/klardrop" && fail "v2 client payload installed despite the failure"
ls -A "$ATOMIC_HOME/.local/bin" | grep -q '^\.klardrop' && fail "temporary install files left behind after a failed pair install"
rm -rf "$ATOMIC_HOME"

# =============================================================================
# 23. Regressions for the pair-install safety fixes
#     - a DIRECTORY on either binary path is refused before any mutation
#       (`mv -f <file> <dir>` returns 0 after moving the file INTO the dir)
#     - a failed client swap rolls the engine back instead of leaving it new
#     - the SHIPPED Qt launcher carries the post-split daemon hints
#     - a downloaded tarball is only accepted with a matching .sha256 sidecar
# =============================================================================

# 23a: a DIRECTORY sitting on the client path, with a VALID installer marker so
# every other guard (ownership, package manager, symlink) passes. `mv -f
# staged_client <dir>` does not replace the directory: it moves the file INTO
# it and still exits 0. Without the non-regular guard the installer therefore
# reported success having installed no client at all, beside a brand-new engine
# it had just swapped in.
CLIENT_DIR_HOME="$TMP/client-dir-home"
mkdir -p "$CLIENT_DIR_HOME/.local/bin/klardrop" "$CLIENT_DIR_HOME/.local/share/klardrop"
echo "precious-client-payload" > "$CLIENT_DIR_HOME/.local/bin/klardrop/precious"
echo "native" > "$CLIENT_DIR_HOME/.local/share/klardrop/.installer-marker"

if HOME="$CLIENT_DIR_HOME" \
   XDG_DATA_HOME="$CLIENT_DIR_HOME/.local/share" \
   XDG_CONFIG_HOME="$CLIENT_DIR_HOME/.config" \
   XDG_CACHE_HOME="$CLIENT_DIR_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --native >"$TMP/client_dir.out" 2>"$TMP/client_dir.err"; then
  fail "installer must refuse a directory sitting on the client path"
fi
grep -qi "not a regular file" "$TMP/client_dir.err" || fail "missing non-regular refusal for the client path"
if grep -q "Installing to\|installed:" "$TMP/client_dir.out" "$TMP/client_dir.err"; then
  fail "installer announced an install while refusing the client directory"
fi
[ -d "$CLIENT_DIR_HOME/.local/bin/klardrop" ] || fail "the directory on the client path was replaced"
[ "$(< "$CLIENT_DIR_HOME/.local/bin/klardrop/precious")" = "precious-client-payload" ] || fail "content behind the client directory was touched"
[ "$(ls -A "$CLIENT_DIR_HOME/.local/bin/klardrop")" = "precious" ] || fail "installer moved its staged client into the user's directory"
[ ! -e "$CLIENT_DIR_HOME/.local/bin/klardrop-engine" ] || fail "engine installed alongside a refused client directory"
rm -rf "$CLIENT_DIR_HOME"

# 23b: the mirror case on the engine path. Same failure mode, opposite binary:
# `mv -f staged_engine <dir>` would bury the engine and leave the new client
# beside nothing that can serve it.
ENGINE_DIR_HOME="$TMP/engine-dir-home"
mkdir -p "$ENGINE_DIR_HOME/.local/bin/klardrop-engine" "$ENGINE_DIR_HOME/.local/share/klardrop"
echo "precious-engine-payload" > "$ENGINE_DIR_HOME/.local/bin/klardrop-engine/precious"
echo "native" > "$ENGINE_DIR_HOME/.local/share/klardrop/.installer-marker"

if HOME="$ENGINE_DIR_HOME" \
   XDG_DATA_HOME="$ENGINE_DIR_HOME/.local/share" \
   XDG_CONFIG_HOME="$ENGINE_DIR_HOME/.config" \
   XDG_CACHE_HOME="$ENGINE_DIR_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
   bash "$INSTALLER" --native >"$TMP/engine_dir.out" 2>"$TMP/engine_dir.err"; then
  fail "installer must refuse a directory sitting on the engine path"
fi
grep -qi "not a regular file" "$TMP/engine_dir.err" || fail "missing non-regular refusal for the engine path"
if grep -q "Installing to\|installed:" "$TMP/engine_dir.out" "$TMP/engine_dir.err"; then
  fail "installer announced an install while refusing the engine directory"
fi
[ -d "$ENGINE_DIR_HOME/.local/bin/klardrop-engine" ] || fail "the directory on the engine path was replaced"
[ "$(< "$ENGINE_DIR_HOME/.local/bin/klardrop-engine/precious")" = "precious-engine-payload" ] || fail "content behind the engine directory was touched"
[ "$(ls -A "$ENGINE_DIR_HOME/.local/bin/klardrop-engine")" = "precious" ] || fail "installer moved its staged engine into the user's directory"
[ ! -e "$ENGINE_DIR_HOME/.local/bin/klardrop" ] || fail "client installed alongside a refused engine directory"
rm -rf "$ENGINE_DIR_HOME"

# 23c: the engine swap succeeds, the client swap cannot. The engine must be put
# BACK the way it was, and the old client must be untouched — not merely
# "not v2". The old assertions here were `[ -e X ] && grep ... && fail`, which
# short-circuits to success whenever X is absent: deleting the engine outright
# passed them, which is exactly what the installer used to do.
SWAP_HOME="$TMP/swap-home"
SWAP_RUN="$TMP/swap-run"
mkdir -p "$SWAP_HOME" "$SWAP_RUN"
chmod 700 "$SWAP_RUN"

HOME="$SWAP_HOME" \
XDG_DATA_HOME="$SWAP_HOME/.local/share" \
XDG_CONFIG_HOME="$SWAP_HOME/.config" \
XDG_CACHE_HOME="$SWAP_HOME/.cache" \
XDG_RUNTIME_DIR="$SWAP_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --native >"$TMP/swap_v1.out" 2>"$TMP/swap_v1.err" || fail "swap setup install failed: $(cat "$TMP/swap_v1.err")"

if FAIL_CLIENT_MV=1 HOME="$SWAP_HOME" \
   XDG_DATA_HOME="$SWAP_HOME/.local/share" \
   XDG_CONFIG_HOME="$SWAP_HOME/.config" \
   XDG_CACHE_HOME="$SWAP_HOME/.cache" \
   XDG_RUNTIME_DIR="$SWAP_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL_V2" \
   bash "$INSTALLER" --native >"$TMP/swap_v2.out" 2>"$TMP/swap_v2.err"; then
  fail "installer should fail when the client binary cannot be moved into place"
fi
# Positive assertions. The engine must still EXIST and be the v1 payload: the old
# checks were `[ -e X ] && grep ... && fail`, which short-circuit to success
# whenever X is absent — so "the installer deleted the engine and reported
# success" passed them, which is exactly what it used to do.
[ -f "$SWAP_HOME/.local/bin/klardrop-engine" ] \
  || fail "the engine was deleted by the failed client swap; there is nothing left to restore"
grep -q 'fake klardrop-engine x64' "$SWAP_HOME/.local/bin/klardrop-engine" \
  || fail "the restored engine is not the v1 payload"
if grep -q 'v2' "$SWAP_HOME/.local/bin/klardrop-engine"; then
  fail "engine left as v2 after the client swap failed; the previous engine was not restored"
fi
grep -q 'fake klardrop x64' "$SWAP_HOME/.local/bin/klardrop" || fail "the v1 client was disturbed by the failed swap"
if grep -q 'v2' "$SWAP_HOME/.local/bin/klardrop"; then
  fail "v2 client payload in place after the failed swap"
fi
if ls -A "$SWAP_HOME/.local/bin" | grep -q '^\.klardrop'; then
  fail "temporary install files left behind after the failed client swap"
fi
grep -qi "previous engine was restored" "$TMP/swap_v2.err" \
  || fail "missing restore diagnostic (the installer must say the old engine came back)"
if grep -q "installed:" "$TMP/swap_v2.out"; then
  fail "installer announced an install despite the failed client swap"
fi
rm -rf "$SWAP_HOME" "$SWAP_RUN"

# 23d: the Qt launcher that actually ships. The fixture tarball copies
# packaging/linux/klardrop-qt-launcher.sh — the same single file
# packaging/linux/stage-native-tarball.sh stages — so the daemon hints below are
# the shipped ones, and reverting the stager's `klardrop-engine daemon` text
# fails here instead of going unnoticed behind a hand-typed fixture.
LAUNCHER_HOME="$TMP/launcher-home"
mkdir -p "$LAUNCHER_HOME"
HOME="$LAUNCHER_HOME" \
XDG_DATA_HOME="$LAUNCHER_HOME/.local/share" \
XDG_CONFIG_HOME="$LAUNCHER_HOME/.config" \
XDG_CACHE_HOME="$LAUNCHER_HOME/.cache" \
XDG_RUNTIME_DIR="$SPLIT_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_LOCAL_TARBALL="$NATIVE_TARBALL" \
bash "$INSTALLER" --qt >"$TMP/launcher_qt.out" 2>"$TMP/launcher_qt.err" || fail "--qt install for the launcher check failed: $(cat "$TMP/launcher_qt.err")"
SHIPPED_LAUNCHER="$LAUNCHER_HOME/.local/bin/klardrop-qt-launcher"
[ -x "$SHIPPED_LAUNCHER" ] || fail "shipped Qt launcher not installed"
cmp -s "$SHIPPED_LAUNCHER" "$ROOT/packaging/linux/klardrop-qt-launcher.sh" \
  || fail "installed launcher differs from the file the stager ships"
grep -q 'klardrop-engine daemon' "$SHIPPED_LAUNCHER" || fail "shipped launcher lost the post-split 'klardrop-engine daemon' hint"
if grep -q 'klardrop daemon' "$SHIPPED_LAUNCHER"; then
  fail "shipped launcher still tells the user to run the pre-split 'klardrop daemon'"
fi
rm -rf "$LAUNCHER_HOME"

# 23e/23f/23g: the download path, where the checksum is MANDATORY. Without a
# downloadable sidecar the release could not be verified at all, and a silently
# skipped check is not a check — so a missing sidecar and a wrong digest are both
# fatal, and only a matching digest installs. 23g is the positive control: it
# proves the refusals above are the sidecar check firing, not a stub that fails
# every download.
DOWNLOAD_URL="https://example.invalid/klardrop-native-linux-x64.tar.gz"
GOOD_SIDECAR="$TMP/good.sha256"
sha256sum "$NATIVE_TARBALL" | awk '{print $1}' > "$GOOD_SIDECAR"
BAD_SIDECAR="$TMP/bad.sha256"
sha256sum "$NATIVE_TARBALL_ARM64" | awk '{print $1}' > "$BAD_SIDECAR"   # right shape, wrong tarball

# 23e: no sidecar published at all -> refuse, install nothing.
NO_SIDECAR_HOME="$TMP/no-sidecar-home"
mkdir -p "$NO_SIDECAR_HOME"
if MOCK_CURL_PAYLOAD="$NATIVE_TARBALL" \
   HOME="$NO_SIDECAR_HOME" \
   XDG_DATA_HOME="$NO_SIDECAR_HOME/.local/share" \
   XDG_CONFIG_HOME="$NO_SIDECAR_HOME/.config" \
   XDG_CACHE_HOME="$NO_SIDECAR_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_TARBALL_URL="$DOWNLOAD_URL" \
   bash "$INSTALLER" --native >"$TMP/no_sidecar.out" 2>"$TMP/no_sidecar.err"; then
  fail "installer must refuse a download with no .sha256 sidecar"
fi
grep -qi "checksum sidecar could not be downloaded" "$TMP/no_sidecar.err" || fail "missing mandatory-sidecar diagnostic"
[ ! -e "$NO_SIDECAR_HOME/.local/bin/klardrop" ] || fail "client installed from an unverifiable download"
[ ! -e "$NO_SIDECAR_HOME/.local/bin/klardrop-engine" ] || fail "engine installed from an unverifiable download"
[ ! -e "$NO_SIDECAR_HOME/.local/share/klardrop/.installer-marker" ] || fail "marker written for an unverifiable download"
rm -rf "$NO_SIDECAR_HOME"

# 23f: sidecar present but the digest does not match the payload -> refuse.
BAD_DIGEST_HOME="$TMP/bad-digest-home"
mkdir -p "$BAD_DIGEST_HOME"
if MOCK_CURL_PAYLOAD="$NATIVE_TARBALL" MOCK_CURL_SHA256="$BAD_SIDECAR" \
   HOME="$BAD_DIGEST_HOME" \
   XDG_DATA_HOME="$BAD_DIGEST_HOME/.local/share" \
   XDG_CONFIG_HOME="$BAD_DIGEST_HOME/.config" \
   XDG_CACHE_HOME="$BAD_DIGEST_HOME/.cache" \
   XDG_RUNTIME_DIR="$SPLIT_RUN" \
   PATH="$STUBS:$PATH" \
   KLARDROP_TARBALL_URL="$DOWNLOAD_URL" \
   bash "$INSTALLER" --native >"$TMP/bad_digest.out" 2>"$TMP/bad_digest.err"; then
  fail "installer must refuse a download whose digest does not match"
fi
grep -qi "checksum mismatch" "$TMP/bad_digest.err" || fail "missing checksum-mismatch diagnostic"
[ ! -e "$BAD_DIGEST_HOME/.local/bin/klardrop" ] || fail "client installed despite a checksum mismatch"
[ ! -e "$BAD_DIGEST_HOME/.local/bin/klardrop-engine" ] || fail "engine installed despite a checksum mismatch"
rm -rf "$BAD_DIGEST_HOME"

# 23g: matching sidecar -> verified and installed (positive control).
VERIFIED_HOME="$TMP/verified-home"
mkdir -p "$VERIFIED_HOME"
MOCK_CURL_PAYLOAD="$NATIVE_TARBALL" MOCK_CURL_SHA256="$GOOD_SIDECAR" \
HOME="$VERIFIED_HOME" \
XDG_DATA_HOME="$VERIFIED_HOME/.local/share" \
XDG_CONFIG_HOME="$VERIFIED_HOME/.config" \
XDG_CACHE_HOME="$VERIFIED_HOME/.cache" \
XDG_RUNTIME_DIR="$SPLIT_RUN" \
PATH="$STUBS:$PATH" \
KLARDROP_TARBALL_URL="$DOWNLOAD_URL" \
bash "$INSTALLER" --native >"$TMP/verified.out" 2>"$TMP/verified.err" || fail "verified download refused: $(cat "$TMP/verified.err")"
grep -q "Checksum verified" "$TMP/verified.out" || fail "a matching sidecar did not report a verified checksum"
[ -x "$VERIFIED_HOME/.local/bin/klardrop" ] || fail "verified download installed no client"
[ -x "$VERIFIED_HOME/.local/bin/klardrop-engine" ] || fail "verified download installed no engine"
rm -rf "$VERIFIED_HOME"
pass "pair-install safety: non-regular paths refused, failed swap rolled back, shipped launcher hints, mandatory download checksum"

pass "Rust client / engine split: install, uninstall, guards, tarball layout and ELF preflight"

printf '\nALL INSTALLER TESTS PASSED\n'
