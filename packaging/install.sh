#!/usr/bin/env bash
#
# Klardrop Linux installer.
#
#   curl -fsSL https://raw.githubusercontent.com/carlonzo/klardrop/main/packaging/install.sh | bash
#
# Downloads and installs Klardrop native Linux engine and desktop integration:
# on Omarchy it automatically configures Omarchy desktop integration; on other
# Linux distributions it defaults to the native engine + Qt desktop frontend.
# Native installations are strictly per-user under ~/.local (no root/sudo needed).
#
# Every native flavor installs two binaries from one tarball: ~/.local/bin/klardrop
# (the Rust client, the user-facing CLI) and ~/.local/bin/klardrop-engine (the
# Kotlin/Native engine the systemd unit runs).
#
# Explicit flavors:
#   --qt:       explicit standalone Qt Quick desktop frontend
#   --native:   generic headless native engine (systemd user unit, no GUI)
#   --omarchy:  explicit Omarchy shell integration
#   --jvm:      legacy universal JVM Compose app (requires Java 21+; per-user or sudo /opt)
#
# Re-running upgrades in place, preserving existing installed flavor.
# Uninstall with:  curl -fsSL <url> | bash -s -- --uninstall
set -euo pipefail

say()  { printf '\033[1;34m::\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m::\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

REPO="carlonzo/klardrop"
TARBALL="klardrop-linux-x64.tar.gz"

# Channel + action. Stable (default) pulls the latest non-prerelease; nightly pulls the
# rolling `nightly` prerelease (which carries a build that self-updates on the nightly
# channel). Select with `--nightly` (or KLARDROP_CHANNEL=nightly); `--uninstall` removes.
#   stable:  curl … | bash
#   nightly: curl … | bash -s -- --nightly
CHANNEL="${KLARDROP_CHANNEL:-stable}"
ACTION="install"
FORCE_JVM=0
FORCE_OMARCHY=0
FORCE_NATIVE=0
FORCE_QT=0
RESTART_DAEMON=0
CHANNEL_SET_NIGHTLY=0
CHANNEL_SET_STABLE=0

for arg in "$@"; do
  case "$arg" in
    --uninstall)      ACTION="uninstall" ;;
    --nightly)        CHANNEL="nightly"; CHANNEL_SET_NIGHTLY=1 ;;
    --stable)         CHANNEL="stable"; CHANNEL_SET_STABLE=1 ;;
    --native)         FORCE_NATIVE=1 ;;
    --omarchy)        FORCE_OMARCHY=1 ;;
    --qt)             FORCE_QT=1 ;;
    --jvm)            FORCE_JVM=1 ;;
    --restart-daemon) RESTART_DAEMON=1 ;;
    *)                die "unknown option '$arg'" ;;
  esac
done

case "$CHANNEL" in
  stable|nightly) ;;
  *) die "invalid KLARDROP_CHANNEL '$CHANNEL' (must be 'stable' or 'nightly')." ;;
esac

if [ "$CHANNEL_SET_NIGHTLY" = 1 ] && [ "$CHANNEL_SET_STABLE" = 1 ]; then
  die "conflicting channel flags: cannot specify both --stable and --nightly."
fi

flavor_count=0
[ "$FORCE_JVM" = 1 ] && flavor_count=$((flavor_count + 1))
[ "$FORCE_NATIVE" = 1 ] && flavor_count=$((flavor_count + 1))
[ "$FORCE_OMARCHY" = 1 ] && flavor_count=$((flavor_count + 1))
[ "$FORCE_QT" = 1 ] && flavor_count=$((flavor_count + 1))
if [ "$flavor_count" -gt 1 ]; then
  die "conflicting installation flags: specify at most one of --native, --omarchy, --jvm, or --qt."
fi

read_installer_marker() {
  local marker_file="$1"
  [ -e "$marker_file" ] || [ -L "$marker_file" ] || return 1
  if [ -L "$marker_file" ] || [ ! -f "$marker_file" ]; then
    return 1
  fi
  local raw_hex=""
  local rc=0
  if command -v timeout >/dev/null 2>&1; then
    raw_hex="$(timeout 1s od -v -An -tx1 -N 65 "$marker_file" 2>/dev/null)" || rc=$?
  elif command -v python3 >/dev/null 2>&1; then
    raw_hex="$(python3 -c '
import sys, os
try:
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NONBLOCK)
    data = os.read(fd, 65)
    os.close(fd)
    sys.stdout.write(" ".join(f"{b:02x}" for b in data))
except Exception:
    sys.exit(1)
' "$marker_file" 2>/dev/null)" || rc=$?
  else
    return 1
  fi
  [ "$rc" -ne 0 ] && return 1

  local hex
  hex="$(printf '%s' "$raw_hex" | tr -d ' \t\r\n')"
  case "$hex" in
    "")
      # Genuine 0-byte legacy marker: verify file is genuinely empty
      [ -f "$marker_file" ] && [ ! -s "$marker_file" ] || return 1
      printf '\n'
      return 0
      ;;
    "0a")
      # legacy empty marker (single LF)
      printf '\n'
      return 0
      ;;
    "7174"|"71740a")
      printf 'qt\n'
      return 0
      ;;
    "6e6174697665"|"6e61746976650a")
      printf 'native\n'
      return 0
      ;;
    "6f6d6172636879"|"6f6d61726368790a")
      printf 'omarchy\n'
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

escape_desktop_exec_path() {
  local p="$1"
  p="${p//\\/\\\\\\\\}"
  p="${p//\"/\\\\\"}"
  p="${p//\$/\\\\\$}"
  p="${p//\`/\\\\\`}"
  p="${p//%/%%}"
  printf '%s' "$p"
}

MARKER_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop/.installer-marker"
STORED_FLAVOR=""
MARKER_EXISTS=0
if [ -e "$MARKER_FILE" ] || [ -L "$MARKER_FILE" ]; then
  MARKER_EXISTS=1
  if ! STORED_FLAVOR="$(read_installer_marker "$MARKER_FILE")"; then
    die "Existing installation marker at '$MARKER_FILE' is invalid, linked, or non-regular. Refusing to continue."
  fi
fi

# Flavor resolution:
# --native explicitly avoids Omarchy integration even on Omarchy desktops.
# --omarchy forces Omarchy integration.
# --qt forces the standalone Qt Quick desktop frontend.
# --jvm forces the JVM universal tarball.
# Without flags:
# - Reinstall/upgrade on an existing installer-owned install preserves its flavor (even on Omarchy).
# - A clean NEW auto-detection selects Omarchy if omarchy CLI is present; otherwise standalone Qt.
if [ "$FORCE_JVM" = 1 ]; then
  FLAVOR="jvm"
elif [ "$FORCE_NATIVE" = 1 ]; then
  FLAVOR="native"
elif [ "$FORCE_OMARCHY" = 1 ]; then
  FLAVOR="omarchy"
elif [ "$FORCE_QT" = 1 ]; then
  FLAVOR="qt"
else
  # Inspect existing installation marker and filesystem before falling back to new auto-install
  if [ "$MARKER_EXISTS" = 1 ]; then
    if [ "$STORED_FLAVOR" = "qt" ] || [ "$STORED_FLAVOR" = "omarchy" ] || [ "$STORED_FLAVOR" = "native" ]; then
      FLAVOR="$STORED_FLAVOR"
    elif [ -d "$HOME/.config/omarchy/plugins/klardrop.omarchy" ] || [ -f "$HOME/.local/bin/klardrop-omarchy-share" ]; then
      # Legacy empty marker with Omarchy present -> Omarchy
      FLAVOR="omarchy"
    else
      # Legacy empty marker -> native
      FLAVOR="native"
    fi
  elif [ -d "$HOME/.config/omarchy/plugins/klardrop.omarchy" ] || [ -f "$HOME/.local/bin/klardrop-omarchy-share" ]; then
    FLAVOR="omarchy"
  elif [ -d "$HOME/.local/lib/klardrop" ] || [ -d "${XDG_DATA_HOME:-$HOME/.local/share}/klardrop/bin" ]; then
    FLAVOR="jvm"
  elif [ -f "$HOME/.config/systemd/user/klardrop.service" ] || [ -f "$HOME/.local/bin/klardrop" ] || [ -f "$HOME/.local/bin/klardrop-engine" ]; then
    FLAVOR="native"
  elif command -v omarchy >/dev/null 2>&1; then
    FLAVOR="omarchy"
  else
    FLAVOR="qt"
  fi
fi

if [ "$RESTART_DAEMON" = 1 ]; then
  if [ "$ACTION" != "install" ] || [ "$FLAVOR" = "jvm" ]; then
    die "--restart-daemon is only valid for native daemon installations (--native, --omarchy, or --qt)."
  fi
fi

if [ "$CHANNEL" = "nightly" ]; then
  BASE="https://github.com/${REPO}/releases/download/nightly"
else
  BASE="https://github.com/${REPO}/releases/latest/download"
fi

# --- Native flavors (headless, Omarchy, or standalone Qt) -----------------
# Generic headless native (--native) installs the native engine and a systemd
# --user service, avoiding any desktop/shell integration.
# Omarchy flavor (--omarchy, or auto-detected when omarchy is present) installs
# the native engine plus the Omarchy shell plugin, menu entries, .desktop and Nautilus action.
# Qt flavor (--qt) installs the native engine plus the standalone Qt Quick desktop frontend.
# All native flavors are per-user only.

# Every native flavor installs TWO binaries, taken from the same staged tarball:
#   klardrop        — the Rust client, i.e. the user-facing `klardrop` CLI
#   klardrop-engine — the Kotlin/Native engine, which owns `daemon`
# The names are deliberately distinct: `klardrop daemon` forwards to this sibling
# binary, so no client invocation can start a second engine by accident.
if [ "$FLAVOR" = "native" ] || [ "$FLAVOR" = "omarchy" ] || [ "$FLAVOR" = "qt" ]; then
  [ "$(id -u)" -eq 0 ] && die "Klardrop native engine is per-user: the background service runs as a systemd --user unit. Run this installer as your normal user (not with sudo)."

  NATIVE_TARBALL="klardrop-native-linux-x64.tar.gz"
  BIN_DIR="$HOME/.local/bin"
  KLARDROP_BIN="$BIN_DIR/klardrop"
  KLARDROP_ENGINE_BIN="$BIN_DIR/klardrop-engine"
  KLARDROP_QT_BIN="$BIN_DIR/klardrop-qt"
  KLARDROP_QT_LAUNCHER="$BIN_DIR/klardrop-qt-launcher"
  MENU_HELPER="$BIN_DIR/klardrop-omarchy-share"
  OPEN_HELPER="$BIN_DIR/klardrop-omarchy-open"
  SHARE_PICK_HELPER="$BIN_DIR/klardrop-share-pick"
  SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
  SERVICE_FILE="$SYSTEMD_USER_DIR/klardrop.service"
  PLUGIN_DEST="$HOME/.config/omarchy/plugins/klardrop.omarchy"
  MENU_FILE="$HOME/.config/omarchy/extensions/omarchy-menu.jsonc"
  APPS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  DESKTOP_FILE="$APPS_DIR/klardrop.desktop"
  APP_DESKTOP_FILE="$APPS_DIR/klardrop-app.desktop"
  NAUTILUS_EXT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/nautilus-python/extensions"
  NAUTILUS_EXT_FILE="$NAUTILUS_EXT_DIR/klardrop.py"
  ICON_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
  ICON_SIZES="128 256"
  MARKER_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop/.installer-marker"

  path_exists() {
    [ -e "$1" ] || [ -L "$1" ]
  }

  is_package_owned() {
    local target="$1"
    { [ -e "$target" ] || [ -L "$target" ]; } || return 1
    if command -v pacman >/dev/null 2>&1 && pacman -Qo "$target" >/dev/null 2>&1; then
      return 0
    fi
    if command -v dpkg-query >/dev/null 2>&1 && dpkg-query -S "$target" >/dev/null 2>&1; then
      return 0
    fi
    if command -v rpm >/dev/null 2>&1 && rpm -qf "$target" >/dev/null 2>&1; then
      return 0
    fi
    return 1
  }

  is_our_native_binary() {
    local bin="$1"
    path_exists "$bin" || return 0
    if [ "$MARKER_EXISTS" = 1 ]; then
      return 0
    fi
    if [ -f "$SERVICE_FILE" ] && grep -Fx -e 'ExecStart=%h/.local/bin/klardrop daemon' -e 'ExecStart=%h/.local/bin/klardrop-engine daemon' -e "ExecStart=$KLARDROP_BIN daemon" -e "ExecStart=$KLARDROP_ENGINE_BIN daemon" "$SERVICE_FILE" >/dev/null 2>&1; then
      return 0
    fi
    # Recognize only exact symlinks to the current or legacy JVM launcher as legitimate JVM migration evidence
    if [ -L "$bin" ]; then
      local target target_f
      target="$(readlink "$bin" 2>/dev/null || true)"
      target_f="$(readlink -f "$bin" 2>/dev/null || true)"
      local jvm_launcher="$HOME/.local/lib/klardrop/bin/klardrop"
      local legacy_launcher="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop/bin/klardrop"
      if [ "$target" = "$jvm_launcher" ] || [ "$target" = "$legacy_launcher" ] || \
         [ "$target_f" = "$jvm_launcher" ] || [ "$target_f" = "$legacy_launcher" ]; then
        return 0
      fi
    fi
    return 1
  }

  is_our_service_file() {
    local sfile="$1"
    [ -e "$sfile" ] || return 0
    if [ "$MARKER_EXISTS" = 1 ]; then
      return 0
    fi
    if grep -Fx -e 'ExecStart=%h/.local/bin/klardrop daemon' -e 'ExecStart=%h/.local/bin/klardrop-engine daemon' -e "ExecStart=$KLARDROP_BIN daemon" -e "ExecStart=$KLARDROP_ENGINE_BIN daemon" "$sfile" >/dev/null 2>&1; then
      return 0
    fi
    return 1
  }

  # Split so the client path can keep its documented symlink allowance (a pre-split install
  # legitimately leaves ~/.local/bin/klardrop as a symlink into the app-image, and `mv -f`
  # replaces the LINK, never the file it points at) while still refusing a DIRECTORY — which
  # `mv -f` would happily move the staged client INTO, leaving a new engine beside no client
  # at all and reporting success.
  guard_not_symlink() {
    local target="$1"
    local desc="${2:-target}"
    if [ -L "$target" ]; then
      die "$desc '$target' is a symbolic link. Refusing to write through symbolic link (preserving referenced file)."
    fi
  }

  guard_not_nonregular() {
    local target="$1"
    local desc="${2:-target}"
    if path_exists "$target" && [ ! -f "$target" ]; then
      die "$desc '$target' is not a regular file. Refusing to overwrite."
    fi
  }

  guard_not_symlink_or_nonregular() {
    guard_not_symlink "$1" "${2:-target}"
    guard_not_nonregular "$1" "${2:-target}"
  }

  # Both native binaries — the Rust client ($KLARDROP_BIN) and the Kotlin engine
  # ($KLARDROP_ENGINE_BIN) — are guarded identically: package-owned files are
  # never touched, and a file this installer did not put there is never
  # overwritten or removed. Only the SYMLINK rule differs, and only because of the legacy
  # JVM-launcher link a pre-split install (and any JVM install) leaves on the client path;
  # is_our_native_binary recognizes exactly that. The NON-REGULAR rule is identical for both,
  # because `mv -f` moves a file INTO a directory instead of replacing it.
  guard_native_binary_for_write() {
    local bin="$1"
    path_exists "$bin" || return 0
    if [ "$bin" = "$KLARDROP_ENGINE_BIN" ]; then
      guard_not_symlink_or_nonregular "$bin" "Klardrop engine binary"
    else
      guard_not_nonregular "$bin" "Klardrop client binary"
    fi
    if is_package_owned "$bin"; then
      die "$bin is managed by a system package manager. Refusing to overwrite. Update via your package manager instead."
    fi
    if ! is_our_native_binary "$bin"; then
      die "$bin exists and is not managed by this installer (no installer marker or service unit found). Refusing to overwrite unrelated file."
    fi
  }

  guard_native_binary_for_remove() {
    local bin="$1"
    path_exists "$bin" || return 0
    if [ "$bin" = "$KLARDROP_ENGINE_BIN" ]; then
      guard_not_symlink_or_nonregular "$bin" "Klardrop engine binary"
    else
      guard_not_nonregular "$bin" "Klardrop client binary"
    fi
    if is_package_owned "$bin"; then
      die "$bin is managed by a system package manager. Refusing to remove."
    fi
    if ! is_our_native_binary "$bin"; then
      die "$bin does not appear to be an installer-managed Klardrop binary (no marker file or unit found). Refusing to remove unrelated file."
    fi
  }

  is_our_desktop_file() {
    local dfile="$1"
    path_exists "$dfile" || return 0
    if is_package_owned "$dfile"; then
      return 1
    fi
    # Current Qt or Omarchy marker ownership
    if [ "$STORED_FLAVOR" = "qt" ] || [ "$STORED_FLAVOR" = "omarchy" ]; then
      return 0
    fi
    # Legacy empty marker with Omarchy frontend present
    if [ "$MARKER_EXISTS" = 1 ] && [ -z "$STORED_FLAVOR" ]; then
      if [ -d "$HOME/.config/omarchy/plugins/klardrop.omarchy" ] || [ -f "$HOME/.local/bin/klardrop-omarchy-share" ]; then
        return 0
      fi
    fi
    # Genuine JVM install: require exact launcher symlink + app-image evidence + matching desktop Exec
    local jvm_app_dir="$HOME/.local/lib/klardrop"
    local legacy_app_dir="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop"
    local jvm_bin="$jvm_app_dir/bin/klardrop"
    local legacy_bin="$legacy_app_dir/bin/klardrop"

    local has_launcher_symlink=0
    if [ -L "$KLARDROP_BIN" ]; then
      local link_target link_target_f
      link_target="$(readlink "$KLARDROP_BIN" 2>/dev/null || true)"
      link_target_f="$(readlink -f "$KLARDROP_BIN" 2>/dev/null || true)"
      if [ "$link_target" = "$jvm_bin" ] || [ "$link_target" = "$legacy_bin" ] || \
         [ "$link_target_f" = "$jvm_bin" ] || [ "$link_target_f" = "$legacy_bin" ]; then
        has_launcher_symlink=1
      fi
    fi

    local has_app_image=0
    if { [ -d "$jvm_app_dir" ] && [ -f "$jvm_bin" ]; } || \
       { { [ -d "$legacy_app_dir/bin" ] || [ -d "$legacy_app_dir/lib" ]; } && [ -f "$legacy_bin" ]; }; then
      has_app_image=1
    fi

    if [ "$has_launcher_symlink" = 1 ] && [ "$has_app_image" = 1 ]; then
      if [ -f "$dfile" ]; then
        local exec_line=""
        exec_line="$(grep '^Exec=' "$dfile" 2>/dev/null || true)"
        local exec_val="${exec_line#Exec=}"
        case "$exec_val" in
          /usr/bin/env\ *) exec_val="${exec_val#/usr/bin/env }" ;;
        esac
        exec_val="${exec_val#\"}"
        exec_val="${exec_val%\"}"
        local escaped_jvm_bin escaped_legacy_bin
        escaped_jvm_bin="$(escape_desktop_exec_path "$jvm_bin")"
        escaped_legacy_bin="$(escape_desktop_exec_path "$legacy_bin")"
        case "$exec_val" in
          "$jvm_bin"|"$escaped_jvm_bin"|"$legacy_bin"|"$escaped_legacy_bin"|klardrop|klardrop\ *)
            return 0
            ;;
        esac
      fi
    fi

    return 1
  }

  check_daemon_idle() {
    local ctrl="$1"
    [ -f "$ctrl" ] || return 1
    command -v python3 >/dev/null 2>&1 || return 1
    python3 -c '
import sys, json, urllib.request

try:
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        ctrl = json.load(f)
    port = int(ctrl.get("port"))
    token = str(ctrl.get("token"))
    if not port or not token:
        sys.exit(1)

    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/state",
        headers={"Authorization": f"Bearer {token}"}
    )
    with urllib.request.urlopen(req, timeout=3) as resp:
        if resp.status != 200:
            sys.exit(1)
        data = json.loads(resp.read().decode("utf-8"))

    if not isinstance(data, dict):
        sys.exit(1)
    transfers = data.get("transfers")
    if not isinstance(transfers, list) or len(transfers) != 0:
        sys.exit(1)
    incoming = data.get("incoming")
    if not isinstance(incoming, list) or len(incoming) != 0:
        sys.exit(1)
    # Require pairingDialog to be present and null
    if "pairingDialog" not in data or data["pairingDialog"] is not None:
        sys.exit(1)
    # Require qrShare.active to be exactly boolean False
    qr_share = data.get("qrShare")
    if not isinstance(qr_share, dict) or qr_share.get("active") is not False:
        sys.exit(1)

    sys.exit(0)
except Exception:
    sys.exit(1)
' "$ctrl" 2>/dev/null
  }

  install_systemd_unit() {
    mkdir -p "$SYSTEMD_USER_DIR"
    if [ "$FLAVOR" = "omarchy" ]; then
      cat > "$SERVICE_FILE" <<'EOF'
[Unit]
Description=Klardrop nearby sharing daemon

[Service]
ExecStart=%h/.local/bin/klardrop-engine daemon
Restart=on-failure
RestartSec=2

NoNewPrivileges=true
ProtectClock=true
ProtectKernelLogs=true
ProtectKernelModules=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictRealtime=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=graphical-session.target
EOF
    else
      cat > "$SERVICE_FILE" <<'EOF'
[Unit]
Description=Klardrop nearby sharing daemon

[Service]
ExecStart=%h/.local/bin/klardrop-engine daemon
Restart=on-failure
RestartSec=2

NoNewPrivileges=true
ProtectClock=true
ProtectKernelLogs=true
ProtectKernelModules=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictRealtime=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=default.target
EOF
    fi
  }

  install_menu_helper() {
    cat > "$MENU_HELPER" <<'EOF'
#!/usr/bin/env bash
# Klardrop menu integration helper, installed by install.sh --omarchy.
# Mirrors omarchy-menu-share's pick-then-run shape for the omarchy-menu.jsonc
# trigger.share.klardrop-* entries.
# Every case below goes through `share --pick`: the Rust client requires an
# explicit target for plain `share` (--to <device-id>), and this helper IS the
# caller that must choose the device itself, so it opens the TUI device picker.
set -euo pipefail
KLARDROP="$HOME/.local/bin/klardrop"
MODE="${1:-}"
case "$MODE" in
  clipboard)
    exec "$KLARDROP" share --pick --clipboard
    ;;
  file)
    picked=$(omarchy-file-select --title "Share via Klardrop" --multiple) || exit 0
    [ -n "$picked" ] || exit 0
    readarray -t files <<<"$picked"
    exec "$KLARDROP" share --pick "${files[@]}"
    ;;
  folder)
    picked=$(omarchy-file-select --title "Share a folder via Klardrop" --directory) || exit 0
    [ -n "$picked" ] || exit 0
    exec "$KLARDROP" share --pick "$picked"
    ;;
  *)
    echo "Usage: klardrop-omarchy-share <clipboard|file|folder>" >&2
    exit 1
    ;;
esac
EOF
    chmod 755 "$MENU_HELPER"
  }

  install_open_helper() {
    cat > "$OPEN_HELPER" <<'EOF'
#!/usr/bin/env bash
# Klardrop launcher helper, installed by install.sh --omarchy. Exec target of
# the visible klardrop-app.desktop entry: makes sure the daemon is up, then
# asks the running Omarchy shell to open (or focus) the app window.
set -u

daemon_failed=0
systemctl --user start klardrop.service >/dev/null 2>&1 || daemon_failed=1

shell_ok=0
if command -v omarchy-shell >/dev/null 2>&1; then
  # Captured, not discarded: omarchy-shell also exits 1 with "Target not
  # found." when the plugin isn't enabled/loaded, which is worth telling the
  # user rather than a generic "not running" guess.
  if err=$(omarchy-shell klardrop.omarchy openWindow 2>&1); then
    shell_ok=1
  fi
else
  err="omarchy-shell is not installed"
fi

if [ "$shell_ok" != 1 ]; then
  msg="Could not open the Klardrop window: ${err:-unknown error}. Make sure the klardrop.omarchy plugin is enabled and omarchy-shell is running."
  [ "$daemon_failed" = 1 ] && msg="$msg (the Klardrop daemon also failed to start)"
  notify-send "Klardrop" "$msg" >/dev/null 2>&1
  exit 1
fi
EOF
    chmod 755 "$OPEN_HELPER"
  }

  # Terminal bridge for the GUI callers that cannot supply a tty.
  install_share_pick_helper() {
    cat > "$SHARE_PICK_HELPER" <<'EOF'
#!/usr/bin/env bash
# Klardrop share helper, installed by install.sh --omarchy.
#
# Exec target of the two file-manager share callers: the NoDisplay
# "Open with..." klardrop.desktop entry and the Nautilus "Send with Klardrop"
# action. `klardrop share --pick` draws an on-screen device picker that requires
# a real terminal on BOTH stdin and stdout; GIO hands .desktop entries and
# Gio.Subprocess children plain pipes, so invoking the client directly from
# either caller exits 2 ("share --pick chooses a device on screen, so it needs
# a real terminal on both stdin and stdout") and file-manager sharing is dead.
# This helper is the bridge: it brings the engine up, then runs the picker
# inside the user's terminal emulator.
set -u

KLARDROP="$HOME/.local/bin/klardrop"

# Best effort: the picker asks the engine for the device list, so it has to be
# running before the terminal opens.
systemctl --user start klardrop.service >/dev/null 2>&1

argv=("$KLARDROP" share --pick "$@")

terminal=""
if [ -n "${TERMINAL:-}" ] && command -v "$TERMINAL" >/dev/null 2>&1; then
  terminal="$TERMINAL"
else
  for candidate in foot kgx gnome-terminal alacritty kitty wezterm xterm; do
    if command -v "$candidate" >/dev/null 2>&1; then
      terminal="$candidate"
      break
    fi
  done
fi

if [ -z "$terminal" ]; then
  msg="Cannot share from the file manager: no terminal emulator found for the Klardrop device picker. Install foot, kgx, gnome-terminal, alacritty, kitty, wezterm or xterm, or point \$TERMINAL at one."
  echo "$msg" >&2
  notify-send "Klardrop" "$msg" >/dev/null 2>&1
  exit 1
fi

# The two families disagree on the separator: gnome-terminal and its kgx fork
# take `--`, everything else takes `-e`.
case "${terminal##*/}" in
  kgx|gnome-terminal)
    exec "$terminal" -- "${argv[@]}"
    ;;
  *)
    exec "$terminal" -e "${argv[@]}"
    ;;
esac
EOF
    chmod 755 "$SHARE_PICK_HELPER"
  }

  install_plugin() {
    local plugin_src="$1"
    mkdir -p "$(dirname "$PLUGIN_DEST")"
    rm -rf "$PLUGIN_DEST"
    cp -a "$plugin_src" "$PLUGIN_DEST"
    # Hand-installed (not `omarchy plugin add`), so the shell needs telling
    # before it knows the id exists — see "Installing by hand" in the shell docs.
    if command -v omarchy-shell >/dev/null 2>&1; then
      omarchy-shell shell rescanPlugins >/dev/null 2>&1 || true
    fi
    if command -v omarchy >/dev/null 2>&1; then
      # `enable` also drops a bar-widget plugin into the bar (defaultSection, or
      # center) — nothing further is needed to "add it to the bar".
      if omarchy plugin enable klardrop.omarchy >/dev/null 2>&1; then
        say "Klardrop bar widget enabled."
      else
        warn "Could not enable the Klardrop bar widget automatically. Enable it with: omarchy plugin enable klardrop.omarchy"
      fi
    else
      warn "omarchy CLI not found — enable the plugin manually: omarchy plugin enable klardrop.omarchy"
    fi
  }

  install_icons() {
    local icon_src="$1"
    for s in $ICON_SIZES; do
      local target="$ICON_DIR/${s}x${s}/apps/klardrop.png"
      local src_img="$icon_src/${s}x${s}/klardrop.png"
      if [ -f "$src_img" ]; then
        if [ -L "$target" ]; then
          warn "Skipping icon installation for $target (is a symlink; preserving referenced file)."
          continue
        fi
        if path_exists "$target" && [ ! -f "$target" ]; then
          warn "Skipping icon installation for $target (not a regular file)."
          continue
        fi
        if is_package_owned "$target"; then
          warn "Skipping icon installation for $target (managed by package manager)."
          continue
        fi
        mkdir -p "$ICON_DIR/${s}x${s}/apps"
        cp "$src_img" "$target"
      fi
    done
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
      gtk-update-icon-cache -qtf "$ICON_DIR" 2>/dev/null || true
    fi
  }

  remove_icon_file() {
    local target="$1"
    if [ -L "$target" ]; then
      warn "Preserving icon symlink $target."
      return 0
    fi
    if path_exists "$target" && [ ! -f "$target" ]; then
      warn "Preserving non-regular icon $target."
      return 0
    fi
    if is_package_owned "$target"; then
      warn "Preserving package-managed icon $target."
      return 0
    fi
    rm -f "$target"
  }

  remove_icons() {
    local sizes="${1:-$ICON_SIZES}"
    for s in $sizes; do
      remove_icon_file "$ICON_DIR/${s}x${s}/apps/klardrop.png"
    done
    remove_icon_file "$ICON_DIR/scalable/apps/klardrop.svg"
  }

  install_desktop_entry() {
    mkdir -p "$APPS_DIR"
    # The helper (not `klardrop share --pick`): the Rust client requires an
    # explicit target device for `share`, so the entry wants the device picker --
    # but the picker needs a real terminal on stdin AND stdout, and GIO gives a
    # `Terminal=false` .desktop entry plain pipes. Called directly, the client
    # exits 2 and sharing from the file manager does nothing. klardrop-share-pick
    # starts the engine and re-runs the picker inside the user's terminal.
    local escaped_exec
    escaped_exec="$(escape_desktop_exec_path "$SHARE_PICK_HELPER")"
    cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=Klardrop
GenericName=Share with Klardrop
Comment=Send files to a nearby device with Klardrop
Exec="$escaped_exec" %F
Icon=klardrop
Terminal=false
NoDisplay=true
Categories=Network;FileTransfer;
MimeType=application/octet-stream;image/jpeg;image/png;image/gif;image/webp;video/mp4;video/quicktime;video/x-matroska;audio/mpeg;audio/ogg;audio/flac;text/plain;application/pdf;application/zip;application/gzip;application/x-tar;
EOF
    chmod 644 "$DESKTOP_FILE"
    if command -v update-desktop-database >/dev/null 2>&1; then
      update-desktop-database "$APPS_DIR" 2>/dev/null || true
    fi
  }

  # Visible launcher entry (separate from the NoDisplay "Open with..." share
  # entry above): opens the app window via klardrop-omarchy-open instead of
  # sharing a file.
  install_app_desktop_entry() {
    mkdir -p "$APPS_DIR"
    local escaped_exec
    escaped_exec="$(escape_desktop_exec_path "$OPEN_HELPER")"
    printf '[Desktop Entry]\nType=Application\nName=Klardrop\nGenericName=Nearby file sharing\nComment=Send files to a nearby device with Klardrop\nExec="%s"\nIcon=klardrop\nTerminal=false\nCategories=Network;FileTransfer;\n' "$escaped_exec" > "$APP_DESKTOP_FILE"
    chmod 644 "$APP_DESKTOP_FILE"
    if command -v update-desktop-database >/dev/null 2>&1; then
      update-desktop-database "$APPS_DIR" 2>/dev/null || true
    fi
  }

  install_nautilus_extension() {
    mkdir -p "$NAUTILUS_EXT_DIR"
    cat > "$NAUTILUS_EXT_FILE" <<'EOF'
import os
import shutil

from gi import require_version

require_version("Nautilus", "4.1")

from gi.repository import GObject, Gio, Nautilus


class SendWithKlardropAction(GObject.GObject, Nautilus.MenuProvider):
    def _klardrop_helper(self):
        helper = os.path.expanduser("~/.local/bin/klardrop-share-pick")
        if os.access(helper, os.X_OK):
            return helper
        # Installed under a different HOME than Nautilus sees (e.g. a relocated
        # bin dir): fall back to PATH so the action still works.
        return shutil.which("klardrop-share-pick")

    def _selected_paths(self, files):
        paths = []
        for file in files:
            location = file.get_location()
            if not location:
                continue
            path = location.get_path()
            if path and path not in paths:
                paths.append(path)
        return paths

    def _make_item(self, paths):
        label = "Send with Klardrop" if len(paths) == 1 else "Send selected with Klardrop"
        item = Nautilus.MenuItem(
            name="KlardropNautilus::send_with_klardrop",
            label=label,
            icon="klardrop",
        )
        item.connect("activate", self._on_activate, paths)
        return item

    def _on_activate(self, _menu, paths):
        # Never `klardrop share --pick` directly: Gio.Subprocess hands the child
        # pipes, not a terminal, and the picker needs a real tty on stdin AND
        # stdout, so the client would exit 2 and sharing from Nautilus would do
        # nothing. klardrop-share-pick starts the engine and runs the picker
        # inside a terminal emulator.
        helper = self._klardrop_helper()
        if not helper:
            return
        Gio.Subprocess.new([helper] + paths, Gio.SubprocessFlags.NONE)

    def get_file_items(self, *args):
        files = args[0] if len(args) == 1 else args[1]
        paths = self._selected_paths(files)
        if not paths or not self._klardrop_helper():
            return []
        return [self._make_item(paths)]
EOF
  }

  # Marker-delimited block inserted before the file's final closing brace, so
  # re-running is idempotent (the old block is stripped first) and the user's
  # own entries/comments are left untouched. jq is deliberately not used here:
  # it would parse-and-reprint the file, destroying every comment in it.
  merge_menu_extension() {
    mkdir -p "$(dirname "$MENU_FILE")"
    if [ ! -f "$MENU_FILE" ]; then
      printf '{\n}\n' > "$MENU_FILE"
    fi
    cp "$MENU_FILE" "$MENU_FILE.bak.$(date -u +%s)"
    perl -CSD -0pi -e '
      s/[ \t]*\/\/ BEGIN klardrop\.omarchy.*?\/\/ END klardrop\.omarchy\n?//s;
      my $pos = rindex($_, "}");
      die "no closing brace found in menu file\n" if $pos < 0;
      my $head = substr($_, 0, $pos);
      my $tail = substr($_, $pos);
      $head =~ s/[ \t\r\n]+\z//;
      $head .= "," if $head !~ /[{,]\z/;
      my $block = <<"BLOCK";
  // BEGIN klardrop.omarchy -- managed by klardrop install.sh, do not edit by hand
  "trigger.share.klardrop-file": {"icon":"","label":"Klardrop file","description":"Send file(s) with Klardrop","action":"klardrop-omarchy-share file"},
  "trigger.share.klardrop-folder": {"icon":"","label":"Klardrop folder","description":"Send a folder with Klardrop","action":"klardrop-omarchy-share folder"},
  "trigger.share.klardrop-clipboard": {"icon":"","label":"Klardrop clipboard","description":"Send the clipboard with Klardrop","action":"klardrop-omarchy-share clipboard"},
  "trigger.share.klardrop": {"icon":"󰅟","label":"Klardrop panel","description":"Open the Klardrop quick panel","action":"omarchy-shell klardrop.omarchy toggle"}
  // END klardrop.omarchy
BLOCK
      $_ = $head . "\n" . $block . $tail;
    ' "$MENU_FILE"
  }

  remove_menu_extension() {
    [ -f "$MENU_FILE" ] || return 0
    grep -q '// BEGIN klardrop.omarchy' "$MENU_FILE" 2>/dev/null || return 0
    cp "$MENU_FILE" "$MENU_FILE.bak.$(date -u +%s)"
    # Also eat one preceding comma: it was only ever added (by merge_menu_extension)
    # to separate this block from what came before, so removing it too restores
    # the file to how it read before klardrop ever touched it.
    perl -0pi -e 's/,?\s*\/\/ BEGIN klardrop\.omarchy.*?\/\/ END klardrop\.omarchy\n?/\n/s' "$MENU_FILE"
  }

  # Mirrors the app-image removal in the plain-JVM uninstall() below (that
  # function isn't defined yet at this point in the script, so this is a small,
  # deliberate duplication of the same handful of `rm -f`/`rm -rf` lines rather
  # than restructuring two otherwise-independent, mutually-exclusive branches).
  # NEVER touches ~/.local/share/klardrop, ~/.config/klardrop, ~/.cache/klardrop,
  # or ~/.klardrop -- those hold the device identity and message history and are
  # shared between the JVM and native builds.
  migrate_from_jvm_install() {
    local jvm_app_dir="$HOME/.local/lib/klardrop"
    local legacy_app_dir="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop"
    local jvm_desktop="$APPS_DIR/klardrop.desktop"
    local jvm_metainfo="${XDG_DATA_HOME:-$HOME/.local/share}/metainfo/com.carlom.Klardrop.metainfo.xml"

    [ -d "$jvm_app_dir" ] || [ -d "$legacy_app_dir/bin" ] || [ -d "$legacy_app_dir/lib" ] || return 0

    if pgrep -f "klardrop.launcher=$jvm_app_dir/bin/klardrop" >/dev/null 2>&1 || \
       pgrep -f "klardrop.launcher=$legacy_app_dir/bin/klardrop" >/dev/null 2>&1; then
      die "The JVM Klardrop app is currently running. Please quit it before migrating to the native build."
    fi

    rm -rf "$jvm_app_dir"
    rm -rf "${legacy_app_dir:?}/bin" "${legacy_app_dir:?}/lib"
    # The JVM install's ~/.local/bin/klardrop is a symlink into $jvm_app_dir,
    # now removed above; drop it explicitly so it can't be left dangling.
    rm -f "$KLARDROP_BIN"
    rm -f "$jvm_desktop" "$jvm_metainfo"
    remove_icons "32 64 128 256 512"

    say "Replaced the JVM Klardrop app with the native build (device identity and history in ~/.local/share/klardrop etc. were kept)."
  }

  clean_omarchy_frontend() {
    [ "$STORED_FLAVOR" = "omarchy" ] || return 0
    if path_exists "$PLUGIN_DEST" && is_package_owned "$PLUGIN_DEST"; then
      die "$PLUGIN_DEST is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$MENU_HELPER" && is_package_owned "$MENU_HELPER"; then
      die "$MENU_HELPER is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$OPEN_HELPER" && is_package_owned "$OPEN_HELPER"; then
      die "$OPEN_HELPER is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$SHARE_PICK_HELPER" && is_package_owned "$SHARE_PICK_HELPER"; then
      die "$SHARE_PICK_HELPER is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$NAUTILUS_EXT_FILE" && is_package_owned "$NAUTILUS_EXT_FILE"; then
      die "$NAUTILUS_EXT_FILE is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$APP_DESKTOP_FILE" && is_package_owned "$APP_DESKTOP_FILE"; then
      die "$APP_DESKTOP_FILE is managed by a system package manager. Refusing to remove."
    fi

    if [ -d "$PLUGIN_DEST" ] && command -v omarchy >/dev/null 2>&1; then
      omarchy plugin disable klardrop.omarchy >/dev/null 2>&1 || true
      omarchy plugin remove klardrop.omarchy --yes >/dev/null 2>&1 || true
    fi
    if [ -d "$PLUGIN_DEST" ]; then
      mv "$PLUGIN_DEST" "$PLUGIN_DEST.bak.$(date -u +%s)"
    fi
    remove_menu_extension
    rm -f "$APP_DESKTOP_FILE" "$NAUTILUS_EXT_FILE" "$MENU_HELPER" "$OPEN_HELPER" "$SHARE_PICK_HELPER"
  }

  clean_qt_frontend() {
    [ "$STORED_FLAVOR" = "qt" ] || return 0
    if path_exists "$KLARDROP_QT_BIN" && is_package_owned "$KLARDROP_QT_BIN"; then
      die "$KLARDROP_QT_BIN is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$KLARDROP_QT_LAUNCHER" && is_package_owned "$KLARDROP_QT_LAUNCHER"; then
      die "$KLARDROP_QT_LAUNCHER is managed by a system package manager. Refusing to remove."
    fi
    if path_exists "$DESKTOP_FILE" && is_package_owned "$DESKTOP_FILE"; then
      die "$DESKTOP_FILE is managed by a system package manager. Refusing to remove."
    fi
    rm -f "$KLARDROP_QT_BIN" "$KLARDROP_QT_LAUNCHER" "$DESKTOP_FILE"
    remove_icons
  }

  omarchy_uninstall() {
    say "Removing Klardrop (Omarchy integration, for the current user)…"

    guard_native_binary_for_remove "$KLARDROP_BIN"
    guard_native_binary_for_remove "$KLARDROP_ENGINE_BIN"
    if path_exists "$SERVICE_FILE"; then
      if is_package_owned "$SERVICE_FILE"; then
        die "$SERVICE_FILE is managed by a system package manager. Refusing to remove."
      fi
      if ! is_our_service_file "$SERVICE_FILE"; then
        die "$SERVICE_FILE exists and is not managed by this installer. Refusing to remove unrelated unit file."
      fi
    fi

    if command -v systemctl >/dev/null 2>&1; then
      if ! systemctl --user disable --now klardrop.service >/dev/null 2>&1; then
        if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
          die "Failed to stop active klardrop.service. Aborting uninstall before deleting files. Stop the service manually: systemctl --user stop klardrop.service"
        fi
      fi
      if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
        die "klardrop.service is still active after disable attempt. Aborting uninstall before deleting files. Stop the service manually: systemctl --user stop klardrop.service"
      fi
      rm -f "$SERVICE_FILE"
      systemctl --user daemon-reload >/dev/null 2>&1 || true
    else
      rm -f "$SERVICE_FILE"
    fi

    if [ -d "$PLUGIN_DEST" ] && command -v omarchy >/dev/null 2>&1; then
      omarchy plugin disable klardrop.omarchy >/dev/null 2>&1 || true
      omarchy plugin remove klardrop.omarchy --yes >/dev/null 2>&1 || true
    fi
    # Fallback in case the plugin CLI/shell wasn't reachable (or was stubbed):
    # keep the same backup-suffix convention Omarchy's own plugin remove uses.
    if [ -d "$PLUGIN_DEST" ]; then
      mv "$PLUGIN_DEST" "$PLUGIN_DEST.bak.$(date -u +%s)"
    fi

    remove_menu_extension

    rm -f "$DESKTOP_FILE" "$APP_DESKTOP_FILE" "$NAUTILUS_EXT_FILE" "$KLARDROP_BIN" "$KLARDROP_ENGINE_BIN" "$MENU_HELPER" "$OPEN_HELPER" "$SHARE_PICK_HELPER" "$MARKER_FILE"
    remove_icons
    if command -v update-desktop-database >/dev/null 2>&1; then
      update-desktop-database "$APPS_DIR" 2>/dev/null || true
    fi

    say "Done. Kept (device identity + history): ~/.local/share/klardrop, ~/.config/klardrop, ~/.cache/klardrop, ~/.klardrop"
    exit 0
  }

  native_uninstall() {
    say "Removing Klardrop native engine (for the current user)…"

    if [ -n "$STORED_FLAVOR" ] && [ "$STORED_FLAVOR" != "native" ] && [ "$STORED_FLAVOR" != "qt" ] && [ "$STORED_FLAVOR" != "omarchy" ]; then
      die "Existing installation marker records '$STORED_FLAVOR', not a native flavor. Refusing to uninstall."
    fi

    guard_native_binary_for_remove "$KLARDROP_BIN"
    guard_native_binary_for_remove "$KLARDROP_ENGINE_BIN"
    if path_exists "$SERVICE_FILE"; then
      if is_package_owned "$SERVICE_FILE"; then
        die "$SERVICE_FILE is managed by a system package manager. Refusing to remove."
      fi
      if ! is_our_service_file "$SERVICE_FILE"; then
        die "$SERVICE_FILE exists and is not managed by this installer. Refusing to remove unrelated unit file."
      fi
    fi

    # If prior install was owned Qt, clean owned Qt frontend files as well
    if [ "$STORED_FLAVOR" = "qt" ]; then
      for f in "$KLARDROP_QT_BIN" "$KLARDROP_QT_LAUNCHER" "$DESKTOP_FILE"; do
        if path_exists "$f" && is_package_owned "$f"; then
          die "$f is managed by a system package manager. Refusing to remove."
        fi
      done
      rm -f "$KLARDROP_QT_BIN" "$KLARDROP_QT_LAUNCHER" "$DESKTOP_FILE"
      remove_icons
    elif [ "$STORED_FLAVOR" = "omarchy" ]; then
      for f in "$PLUGIN_DEST" "$MENU_HELPER" "$OPEN_HELPER" "$SHARE_PICK_HELPER" "$NAUTILUS_EXT_FILE" "$APP_DESKTOP_FILE" "$DESKTOP_FILE"; do
        if path_exists "$f" && is_package_owned "$f"; then
          die "$f is managed by a system package manager. Refusing to remove."
        fi
      done
      clean_omarchy_frontend
      rm -f "$DESKTOP_FILE"
      remove_icons
    fi

    if command -v systemctl >/dev/null 2>&1; then
      if ! systemctl --user disable --now klardrop.service >/dev/null 2>&1; then
        if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
          die "Failed to stop active klardrop.service. Aborting uninstall before deleting files. Stop the service manually: systemctl --user stop klardrop.service"
        fi
      fi
      if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
        die "klardrop.service is still active after disable attempt. Aborting uninstall before deleting files. Stop the service manually: systemctl --user stop klardrop.service"
      fi
      rm -f "$SERVICE_FILE"
      systemctl --user daemon-reload >/dev/null 2>&1 || true
    else
      rm -f "$SERVICE_FILE"
    fi

    rm -f "$KLARDROP_BIN" "$KLARDROP_ENGINE_BIN" "$MARKER_FILE"

    say "Done. Kept (device identity + history): ~/.local/share/klardrop, ~/.config/klardrop, ~/.cache/klardrop, ~/.klardrop"
    exit 0
  }

  qt_uninstall() {
    say "Removing Klardrop (standalone Qt frontend and native engine, for the current user)…"

    # Require valid explicit installer Qt marker
    if [ "$STORED_FLAVOR" != "qt" ]; then
      die "No installer-managed Qt installation detected (valid installer marker recording 'qt' flavor is missing). Refusing to uninstall."
    fi

    # Frontend ownership/package guards BEFORE engine/service mutations
    if path_exists "$KLARDROP_QT_BIN"; then
      if is_package_owned "$KLARDROP_QT_BIN"; then
        die "$KLARDROP_QT_BIN is managed by a system package manager. Refusing to remove."
      fi
    fi
    if path_exists "$KLARDROP_QT_LAUNCHER"; then
      if is_package_owned "$KLARDROP_QT_LAUNCHER"; then
        die "$KLARDROP_QT_LAUNCHER is managed by a system package manager. Refusing to remove."
      fi
    fi
    if path_exists "$DESKTOP_FILE"; then
      if is_package_owned "$DESKTOP_FILE"; then
        die "$DESKTOP_FILE is managed by a system package manager. Refusing to remove."
      fi
    fi
    guard_native_binary_for_remove "$KLARDROP_BIN"
    guard_native_binary_for_remove "$KLARDROP_ENGINE_BIN"
    if path_exists "$SERVICE_FILE"; then
      if is_package_owned "$SERVICE_FILE"; then
        die "$SERVICE_FILE is managed by a system package manager. Refusing to remove."
      fi
      if ! is_our_service_file "$SERVICE_FILE"; then
        die "$SERVICE_FILE exists and is not managed by this installer. Refusing to remove unrelated unit file."
      fi
    fi

    if command -v systemctl >/dev/null 2>&1; then
      if ! systemctl --user disable --now klardrop.service >/dev/null 2>&1; then
        if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
          die "Failed to stop active klardrop.service. Aborting uninstall before deleting files. Stop the service manually: systemctl --user stop klardrop.service"
        fi
      fi
      if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
        die "klardrop.service is still active after disable attempt. Aborting uninstall before deleting files. Stop the service manually: systemctl --user stop klardrop.service"
      fi
      rm -f "$SERVICE_FILE"
      systemctl --user daemon-reload >/dev/null 2>&1 || true
    else
      rm -f "$SERVICE_FILE"
    fi

    rm -f "$KLARDROP_BIN" "$KLARDROP_ENGINE_BIN" "$KLARDROP_QT_BIN" "$KLARDROP_QT_LAUNCHER" "$DESKTOP_FILE" "$MARKER_FILE"
    remove_icons
    if command -v update-desktop-database >/dev/null 2>&1; then
      update-desktop-database "$APPS_DIR" 2>/dev/null || true
    fi

    say "Done. Kept (device identity + history): ~/.local/share/klardrop, ~/.config/klardrop, ~/.cache/klardrop, ~/.klardrop"
    exit 0
  }

  if [ "$ACTION" = "uninstall" ]; then
    if [ "$FORCE_QT" = 1 ] && [ "$STORED_FLAVOR" != "qt" ]; then
      die "No installer-managed Qt installation detected (valid installer marker recording 'qt' flavor is missing). Refusing to uninstall."
    fi
    if [ "$STORED_FLAVOR" = "qt" ]; then
      qt_uninstall
    elif [ "$STORED_FLAVOR" = "omarchy" ]; then
      omarchy_uninstall
    elif [ "$STORED_FLAVOR" = "native" ]; then
      native_uninstall
    elif [ "$FLAVOR" = "omarchy" ]; then
      omarchy_uninstall
    elif [ "$FLAVOR" = "qt" ]; then
      qt_uninstall
    else
      native_uninstall
    fi
  fi

  arch="$(uname -m)"
  case "$arch" in
    x86_64)        NATIVE_ARCH="x64" ;;
    aarch64|arm64) NATIVE_ARCH="arm64" ;;
    *) die "unsupported architecture '$arch' (native engine supports x86_64 and aarch64/arm64)." ;;
  esac
  NATIVE_TARBALL="klardrop-native-linux-$NATIVE_ARCH.tar.gz"

  # Preflight: fail early if executable path contains '=' (unsupported by Desktop Entry specification)
  for exe_path in "$KLARDROP_BIN" "$KLARDROP_ENGINE_BIN" "$KLARDROP_QT_LAUNCHER" "$OPEN_HELPER" "$MENU_HELPER" "$SHARE_PICK_HELPER"; do
    case "$exe_path" in
      *=*)
        die "Executable path '$exe_path' contains '=' which is unsupported by the Desktop Entry specification."
        ;;
    esac
  done

  # Preflight: fail early if running JVM app is detected
  jvm_app_dir="$HOME/.local/lib/klardrop"
  legacy_app_dir="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop"
  if pgrep -f "klardrop.launcher=$jvm_app_dir/bin/klardrop" >/dev/null 2>&1 || \
     pgrep -f "klardrop.launcher=$legacy_app_dir/bin/klardrop" >/dev/null 2>&1; then
    die "The JVM Klardrop app is currently running. Please quit it before migrating to the native build."
  fi

  # Preflight: check package manager and ownership guards on all affected frontend paths
  # BEFORE ANY cleanup, JVM migration, service mutation, or engine installation.
  guard_native_binary_for_write "$KLARDROP_BIN"
  guard_native_binary_for_write "$KLARDROP_ENGINE_BIN"
  if path_exists "$SERVICE_FILE"; then
    guard_not_symlink_or_nonregular "$SERVICE_FILE" "Service unit"
    if is_package_owned "$SERVICE_FILE"; then
      die "$SERVICE_FILE is managed by a system package manager. Refusing to overwrite."
    fi
    if ! is_our_service_file "$SERVICE_FILE"; then
      die "$SERVICE_FILE exists and is not managed by this installer. Refusing to overwrite unrelated unit file."
    fi
  fi
  # Preflight guards for Qt frontend files:
  if [ "$FLAVOR" = "qt" ]; then
    for f in "$KLARDROP_QT_BIN" "$KLARDROP_QT_LAUNCHER"; do
      if path_exists "$f"; then
        if is_package_owned "$f"; then
          die "$f is managed by a system package manager. Refusing to overwrite. Update via your package manager instead."
        fi
        if [ "$STORED_FLAVOR" != "qt" ]; then
          die "$f exists and is not managed by this installer (no installer marker recording 'qt' flavor found). Refusing to overwrite unrelated file."
        fi
      fi
    done
    guard_not_symlink_or_nonregular "$KLARDROP_QT_LAUNCHER" "Qt launcher"
    if path_exists "$DESKTOP_FILE"; then
      guard_not_symlink_or_nonregular "$DESKTOP_FILE" "Desktop entry"
      if is_package_owned "$DESKTOP_FILE"; then
        die "$DESKTOP_FILE is managed by a system package manager. Refusing to overwrite. Update via your package manager instead."
      fi
      if ! is_our_desktop_file "$DESKTOP_FILE"; then
        die "$DESKTOP_FILE exists and is not managed by this installer. Refusing to overwrite unrelated file."
      fi
    fi
  fi
  # If switching flavor away from Qt, verify package ownership before JVM migration or cleanup
  if [ "$STORED_FLAVOR" = "qt" ] && [ "$FLAVOR" != "qt" ]; then
    for f in "$KLARDROP_QT_BIN" "$KLARDROP_QT_LAUNCHER" "$DESKTOP_FILE"; do
      if path_exists "$f" && is_package_owned "$f"; then
        die "$f is managed by a system package manager. Refusing to remove during flavor switch."
      fi
    done
  fi

  # Preflight guards for Omarchy frontend files:
  if [ "$FLAVOR" = "omarchy" ]; then
    if path_exists "$DESKTOP_FILE"; then
      guard_not_symlink_or_nonregular "$DESKTOP_FILE" "Desktop entry"
      if is_package_owned "$DESKTOP_FILE"; then
        die "$DESKTOP_FILE is managed by a system package manager. Refusing to overwrite."
      fi
      if ! is_our_desktop_file "$DESKTOP_FILE"; then
        die "$DESKTOP_FILE exists and is not managed by this installer. Refusing to overwrite unrelated file."
      fi
    fi
    for f in "$APP_DESKTOP_FILE" "$MENU_HELPER" "$OPEN_HELPER" "$SHARE_PICK_HELPER" "$NAUTILUS_EXT_FILE"; do
      if path_exists "$f"; then
        guard_not_symlink_or_nonregular "$f"
        if is_package_owned "$f"; then
          die "$f is managed by a system package manager. Refusing to overwrite."
        fi
      fi
    done
    if path_exists "$PLUGIN_DEST" && is_package_owned "$PLUGIN_DEST"; then
      die "$PLUGIN_DEST is managed by a system package manager. Refusing to overwrite."
    fi
  fi

  # If switching flavor away from Omarchy, verify package ownership before JVM migration or cleanup
  if [ "$STORED_FLAVOR" = "omarchy" ] && [ "$FLAVOR" != "omarchy" ]; then
    for f in "$PLUGIN_DEST" "$MENU_HELPER" "$OPEN_HELPER" "$SHARE_PICK_HELPER" "$NAUTILUS_EXT_FILE" "$APP_DESKTOP_FILE"; do
      if path_exists "$f" && is_package_owned "$f"; then
        die "$f is managed by a system package manager. Refusing to remove during flavor switch."
      fi
    done
  fi

  # Preflight: Perl required for Omarchy menu merge
  if [ "$FLAVOR" = "omarchy" ]; then
    command -v perl >/dev/null 2>&1 || die "perl is required for Omarchy menu integration."
  fi

  command -v tar >/dev/null 2>&1 || die "'tar' is required."
  if command -v curl >/dev/null 2>&1; then dl() { curl -fsSL "$1" -o "$2"; }
  elif command -v wget >/dev/null 2>&1; then dl() { wget -qO "$2" "$1"; }
  else die "need 'curl' or 'wget' to download."; fi

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  if [ -n "${KLARDROP_LOCAL_TARBALL:-}" ]; then
    say "Using local native tarball ${KLARDROP_LOCAL_TARBALL}…"
    cp "$KLARDROP_LOCAL_TARBALL" "$tmp/$NATIVE_TARBALL"
  else
    native_url="${KLARDROP_TARBALL_URL:-$BASE/$NATIVE_TARBALL}"
    say "Downloading Klardrop native ($CHANNEL)…"
    dl "$native_url" "$tmp/$NATIVE_TARBALL" || {
      [ "$CHANNEL" = "stable" ] && die "no stable release published yet — install the nightly instead:
    curl -fsSL https://raw.githubusercontent.com/${REPO}/main/packaging/install.sh | bash -s -- --nightly --$FLAVOR"
      die "download failed: $native_url"
    }
    # An integrity check that can silently be skipped is not a check. Every
    # release publishes a `.sha256` sidecar, and the in-app updater already
    # refuses an asset without one, so the installer must not be the weaker of
    # the two. (A LOCAL tarball override is exempt: the user supplied it.)
    command -v sha256sum >/dev/null 2>&1 || die "sha256sum is not available; refusing to install an unverifiable tarball."
    dl "$native_url.sha256" "$tmp/$NATIVE_TARBALL.sha256" 2>/dev/null \
      || die "checksum sidecar could not be downloaded; refusing to install an unverifiable tarball."
    expected="$(tr -d '[:space:]' < "$tmp/$NATIVE_TARBALL.sha256" | cut -d= -f2 | tail -c 65)"
    actual="$(sha256sum "$tmp/$NATIVE_TARBALL" | awk '{print $1}')"
    [ "$expected" = "$actual" ] || die "checksum mismatch — refusing to install (expected $expected, got $actual)."
    say "Checksum verified."
  fi

  say "Extracting…"
  tar -xzf "$tmp/$NATIVE_TARBALL" -C "$tmp"
  src="$tmp/klardrop-native-linux-$NATIVE_ARCH"
  # One tarball, one install layout, all flavors: the engine, the Rust client and
  # the Qt frontend plus the Omarchy plugin manifest are either all present or
  # the archive is not what this installer knows how to install.
  [ -f "$src/bin/klardrop-engine" ] || die "unexpected tarball layout: missing bin/klardrop-engine."
  [ -f "$src/bin/klardrop" ] || die "unexpected tarball layout: missing bin/klardrop."
  [ -f "$src/bin/klardrop-qt" ] || die "unexpected tarball layout: missing bin/klardrop-qt."
  [ -f "$src/bin/klardrop-qt-launcher" ] || die "unexpected tarball layout: missing bin/klardrop-qt-launcher."
  [ -f "$src/share/klardrop/omarchy-plugin/manifest.json" ] || die "unexpected tarball layout: missing omarchy-plugin/manifest.json."
  if [ "$FLAVOR" = "qt" ]; then
    [ -f "$src/share/klardrop/applications/klardrop.desktop" ] || die "unexpected tarball layout: missing share/klardrop/applications/klardrop.desktop."
  fi
  # `klardrop-fixture-daemon` is a cli-rust test binary. It must never be shipped,
  # and it must never reach a user's PATH; refuse the whole archive rather than
  # installing everything except it.
  if fixture="$(find "$src" -name 'klardrop-fixture-daemon*' -print -quit)" && [ -n "$fixture" ]; then
    die "refusing to install: tarball contains test-only fixture binary '$fixture'."
  fi

  # Preflight: ldd both native binaries (engine and Rust client) for missing
  # shared libraries. Same library hints for both — they ship as one pair and
  # are installed or rejected together.
  check_native_binary_dependencies() {
    local bin="$1"
    local label="$2"
    local ldd_output missing_raw missing_hints
    command -v ldd >/dev/null 2>&1 || die "'ldd' is required to verify native dependencies."
    ldd_output="$(ldd "$bin" 2>&1)" || die "failed to inspect binary dependencies with ldd: $ldd_output"
    if echo "$ldd_output" | grep -q "not found"; then
      missing_raw="$(echo "$ldd_output" | grep "not found" || true)"
      missing_hints=""
      if echo "$ldd_output" | grep -qE "(libavahi-client|libavahi-common).*not found"; then
        missing_hints="${missing_hints}
  - Avahi (libavahi-client):
      Arch/Omarchy:  pacman -S avahi
      Debian/Ubuntu: apt install libavahi-client3
      Fedora:        dnf install avahi-libs
      Runtime service: sudo systemctl enable --now avahi-daemon"
      fi
      if echo "$ldd_output" | grep -qE "libsqlite3.*not found"; then
        missing_hints="${missing_hints}
  - SQLite (libsqlite3):
      Arch/Omarchy:  pacman -S sqlite
      Debian/Ubuntu: apt install libsqlite3-0
      Fedora:        dnf install sqlite-libs"
      fi
      if echo "$ldd_output" | grep -qE "(libssl|libcrypto).*not found"; then
        missing_hints="${missing_hints}
  - OpenSSL 3 (libssl):
      Arch/Omarchy:  pacman -S openssl
      Debian/Ubuntu: apt install libssl3t64 (Ubuntu 24.04+, Debian 13) or libssl3 (older)
      Fedora:        dnf install openssl-libs"
      fi
      if echo "$ldd_output" | grep -qE "libsystemd.*not found"; then
        missing_hints="${missing_hints}
  - libsystemd:
      Arch/Omarchy:  pacman -S systemd-libs
      Debian/Ubuntu: apt install libsystemd0
      Fedora:        dnf install systemd-libs"
      fi
      die "Missing shared library dependencies for Klardrop native binary ($label):
$missing_raw
$missing_hints"
    fi
  }
  check_native_binary_dependencies "$src/bin/klardrop-engine" "engine"
  check_native_binary_dependencies "$src/bin/klardrop" "client"

  if [ "$FLAVOR" = "qt" ]; then
    if ! { [ -f "$src/bin/klardrop-qt" ] && [ -x "$src/bin/klardrop-qt" ]; }; then
      die "extracted klardrop-qt is missing or not executable."
    fi
    ldd_qt_output="$(ldd "$src/bin/klardrop-qt" 2>&1)" || die "failed to inspect Qt binary dependencies with ldd: $ldd_qt_output"
    if echo "$ldd_qt_output" | grep -q "not found"; then
      missing_qt_raw="$(echo "$ldd_qt_output" | grep "not found" || true)"
      missing_qt_hints="
  - Qt 6 libraries:
      Arch/Omarchy:  pacman -S qt6-base qt6-declarative
      Debian/Ubuntu: apt install qt6-base-dev qt6-declarative-dev
      Fedora:        dnf install qt6-qtbase qt6-qtdeclarative"
      die "Missing shared library dependencies for Klardrop Qt binary:
$missing_qt_raw
$missing_qt_hints"
    fi

    say "Verifying Qt QML runtime and imports offscreen…"
    command -v timeout >/dev/null 2>&1 || die "'timeout' (from coreutils) is required to safely bound Qt runtime preflight verification."
    runtime_check_ok=0
    if check_err=$(timeout 10s "$src/bin/klardrop-qt" --check-runtime 2>&1); then
      runtime_check_ok=1
    fi
    if [ "$runtime_check_ok" != 1 ]; then
      die "Qt QML runtime check failed (missing modules or failed import verification):
$check_err

Please install the required Qt6 QML packages:
  Arch/Omarchy:  pacman -S qt6-declarative qt6-wayland
  Debian/Ubuntu: apt install qml6-module-qtquick qml6-module-qtquick-controls qml6-module-qtquick-layouts qml6-module-qtquick-templates qml6-module-qtquick-window qml6-module-qtqml qml6-module-qtqml-models qml6-module-qtqml-workerscript qt6-wayland
  Fedora:        dnf install qt6-qtdeclarative qt6-qtwayland"
    fi
    say "Qt runtime verified."
  fi

  # Diagnostics and guidance (non-fatal before mutation)
  if command -v systemctl >/dev/null 2>&1; then
    if ! systemctl is-active avahi-daemon >/dev/null 2>&1; then
      warn "avahi-daemon is not active. Klardrop requires Avahi for local network discovery. Start it with: sudo systemctl enable --now avahi-daemon"
    fi
  fi

  if [ -n "${WAYLAND_DISPLAY:-}" ] || [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
    if ! command -v wl-copy >/dev/null 2>&1 || ! command -v wl-paste >/dev/null 2>&1; then
      warn "wl-clipboard (wl-copy/wl-paste) not found. Clipboard sharing under Wayland requires wl-clipboard (pacman -S wl-clipboard / apt install wl-clipboard)."
    fi
  fi

  systemd_user_ok=0
  if command -v systemctl >/dev/null 2>&1; then
    if systemctl --user list-units >/dev/null 2>&1; then
      systemd_user_ok=1
    fi
  fi
  if [ "$systemd_user_ok" != 1 ]; then
    warn "systemd --user session is not available or not accessible. The background service cannot be managed automatically via systemd. You can start the engine manually: $KLARDROP_ENGINE_BIN daemon"
  fi

  migrate_from_jvm_install

  if [ "$FLAVOR" = "qt" ]; then
    clean_omarchy_frontend
  elif [ "$FLAVOR" = "omarchy" ]; then
    clean_qt_frontend
  elif [ "$FLAVOR" = "native" ]; then
    clean_omarchy_frontend
    clean_qt_frontend
  fi

  say "Installing to $BIN_DIR (for the current user)…"
  mkdir -p "$BIN_DIR" "$(dirname "$MARKER_FILE")"
  # Stage BOTH binaries before swapping either one in. A failure while copying
  # must not leave a new client talking to an old engine (or the reverse): the
  # pair is installed or neither is.
  tmp_engine_bin="$BIN_DIR/.klardrop-engine.tmp.$$"
  tmp_client_bin="$BIN_DIR/.klardrop.tmp.$$"
  if ! install -m 755 "$src/bin/klardrop-engine" "$tmp_engine_bin"; then
    rm -f "$tmp_engine_bin" "$tmp_client_bin"
    die "failed to install the Klardrop engine binary to $tmp_engine_bin."
  fi
  if ! install -m 755 "$src/bin/klardrop" "$tmp_client_bin"; then
    rm -f "$tmp_engine_bin" "$tmp_client_bin"
    die "failed to install the Klardrop client binary to $tmp_client_bin."
  fi
  # Swap both, then VERIFY both. `mv -f` returns success even when it moved a
  # file INTO a directory instead of replacing it, and the two renames are not
  # one atomic step, so a failure of the second must undo the first rather than
  # leave a new engine beside an old client.
  #
  # "Undo" means restoring the PREVIOUS engine, not deleting the new one. The old
  # rollback removed the engine that had just been installed and reported
  # success, leaving a user who had a working install with no engine at all and
  # no backup: unrecoverable, and caused by the one moment the installer was
  # supposed to be safest. Move the previous file aside first so there is always
  # something to put back.
  engine_backup=""
  if [ -f "$KLARDROP_ENGINE_BIN" ]; then
    engine_backup="$BIN_DIR/.klardrop-engine.bak.$$"
    if mv -f "$KLARDROP_ENGINE_BIN" "$engine_backup"; then
      :
    else
      engine_backup=""
      rm -f "$tmp_engine_bin" "$tmp_client_bin"
      die "failed to set the previous Klardrop engine aside; nothing was changed."
    fi
  fi

  mv -f "$tmp_engine_bin" "$KLARDROP_ENGINE_BIN"
  if ! mv -f "$tmp_client_bin" "$KLARDROP_BIN"; then
    rm -f "$tmp_client_bin"
    if [ -n "$engine_backup" ] && [ -f "$engine_backup" ]; then
      mv -f "$engine_backup" "$KLARDROP_ENGINE_BIN"
      die "failed to move the Klardrop client binary into place. The previous engine was restored; nothing else changed."
    fi
    rm -f "$KLARDROP_ENGINE_BIN"
    die "failed to move the Klardrop client binary into place, and there was no previous engine to restore. No Klardrop engine is now installed; re-run the installer."
  fi
  for swapped in "$KLARDROP_ENGINE_BIN" "$KLARDROP_BIN"; do
    if [ ! -f "$swapped" ]; then
      # A backup still exists at this point on purpose: the previous engine is
      # one rename away, and throwing it away before the install has been
      # verified is what makes a bad install unrecoverable.
      die "'$swapped' is not a regular file after installation. The install is incomplete; run the installer again."
    fi
  done
  rm -f "$engine_backup"
  if [ -L "$MARKER_FILE" ]; then
    die "$MARKER_FILE is a symbolic link. Refusing to write marker."
  fi
  printf '%s\n' "$FLAVOR" > "$MARKER_FILE"

  install_systemd_unit

  was_active=0
  if [ "$systemd_user_ok" = 1 ]; then
    if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
      was_active=1
    fi
  fi

  if [ "$was_active" = 1 ]; then
    if [ "$RESTART_DAEMON" = 1 ]; then
      warn "Restarting klardrop.service (--restart-daemon specified; active transfers will be interrupted)."
      systemctl --user daemon-reload
      systemctl --user restart klardrop.service
    else
      can_restart=0
      control_file="${XDG_RUNTIME_DIR:-${HOME}/.cache}/klardrop/control.json"
      if [ ! -f "$control_file" ] && [ -n "${XDG_RUNTIME_DIR:-}" ]; then
        control_file="$XDG_RUNTIME_DIR/klardrop/control.json"
      fi
      if check_daemon_idle "$control_file"; then
        can_restart=1
      fi

      if [ "$can_restart" = 1 ]; then
        say "Existing daemon is idle — restarting klardrop.service with updated binary…"
        systemctl --user daemon-reload
        systemctl --user restart klardrop.service
      else
        say "The existing Klardrop daemon is still running (active transfers in progress or state could not be inspected)."
        say "The new binaries are installed at $KLARDROP_ENGINE_BIN (engine) and $KLARDROP_BIN (client)."
        say "Restart deferred to preserve transfers. Once transfers complete, run:"
        say "  systemctl --user restart klardrop.service"
      fi
    fi
  else
    if [ "$systemd_user_ok" = 1 ]; then
      systemctl --user daemon-reload
      systemctl --user enable --now klardrop.service
    else
      warn "systemctl not available or accessible — start the daemon manually: $KLARDROP_ENGINE_BIN daemon"
    fi
  fi

  if [ "$FLAVOR" = "omarchy" ]; then
    install_menu_helper
    install_open_helper
    install_share_pick_helper
    install_plugin "$src/share/klardrop/omarchy-plugin"
    install_desktop_entry
    install_app_desktop_entry
    [ -d "$src/share/klardrop/icons" ] && install_icons "$src/share/klardrop/icons"
    install_nautilus_extension
    merge_menu_extension
    say "Klardrop (native, Omarchy) installed: $(cat "$src/VERSION" 2>/dev/null || echo unknown version)"
  elif [ "$FLAVOR" = "qt" ]; then
    tmp_qt_bin="$BIN_DIR/.klardrop-qt.tmp.$$"
    install -m 755 "$src/bin/klardrop-qt" "$tmp_qt_bin"
    mv -f "$tmp_qt_bin" "$KLARDROP_QT_BIN"
    install -m 755 "$src/bin/klardrop-qt-launcher" "$KLARDROP_QT_LAUNCHER"
    mkdir -p "$APPS_DIR"
    # Desktop entry spec: quote and escape backslashes, quotes, dollar, backtick, percent
    desktop_src="$src/share/klardrop/applications/klardrop.desktop"
    escaped_exec="$(escape_desktop_exec_path "$KLARDROP_QT_LAUNCHER")"
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        Exec=*)
          printf 'Exec=/usr/bin/env "%s"\n' "$escaped_exec"
          ;;
        *)
          printf '%s\n' "$line"
          ;;
      esac
    done < "$desktop_src" > "$DESKTOP_FILE"
    chmod 644 "$DESKTOP_FILE"
    if command -v update-desktop-database >/dev/null 2>&1; then
      update-desktop-database "$APPS_DIR" 2>/dev/null || true
    fi
    [ -d "$src/share/klardrop/icons" ] && install_icons "$src/share/klardrop/icons"
    say "Klardrop (native, standalone Qt) installed: $(cat "$src/VERSION" 2>/dev/null || echo unknown version)"
  else
    say "Klardrop (native headless engine) installed: $(cat "$src/VERSION" 2>/dev/null || echo unknown version)"
  fi

  case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) warn "$BIN_DIR is not on your PATH — add it so 'klardrop' resolves for terminal and background usage.";;
  esac
  exit 0
fi

# --- scope -------------------------------------------------------------------
# Root -> system-wide; otherwise per-user. The desktop app's self-updater knows
# both of these app-image roots, so keep them in sync with linuxInstallRoot().
if [ "$(id -u)" -eq 0 ]; then
  APP_DIR="/opt/klardrop"
  BIN_DIR="/usr/local/bin"
  DESKTOP_DIR="/usr/share/applications"
  ICON_DIR="/usr/share/icons/hicolor"
  METAINFO_DIR="/usr/share/metainfo"
  SCOPE="system-wide"
else
  # The app-image lives in .local/lib, NOT .local/share/klardrop — that is where
  # FileKit puts the app's own data (databases/, properties.preferences_pb), and
  # an installer that rm -rf'd its install root would wipe the device identity
  # and message history on every upgrade.
  APP_DIR="$HOME/.local/lib/klardrop"
  LEGACY_APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop"
  BIN_DIR="$HOME/.local/bin"
  DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  ICON_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
  METAINFO_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/metainfo"
  SCOPE="for the current user"
fi

uninstall() {
  say "Removing Klardrop ($SCOPE)…"
  local service_file="$HOME/.config/systemd/user/klardrop.service"
  if [ -e "$BIN_DIR/klardrop" ] || [ -e "$BIN_DIR/klardrop-engine" ]; then
    if command -v pacman >/dev/null 2>&1 && pacman -Qo "$BIN_DIR/klardrop" >/dev/null 2>&1; then
      die "$BIN_DIR/klardrop is managed by pacman. Refusing to remove."
    fi
    if command -v dpkg-query >/dev/null 2>&1 && dpkg-query -S "$BIN_DIR/klardrop" >/dev/null 2>&1; then
      die "$BIN_DIR/klardrop is managed by dpkg. Refusing to remove."
    fi
    if command -v rpm >/dev/null 2>&1 && rpm -qf "$BIN_DIR/klardrop" >/dev/null 2>&1; then
      die "$BIN_DIR/klardrop is managed by rpm. Refusing to remove."
    fi
    if [ "$MARKER_EXISTS" = 1 ] || ([ -f "$service_file" ] && grep -Fx -e 'ExecStart=%h/.local/bin/klardrop daemon' -e 'ExecStart=%h/.local/bin/klardrop-engine daemon' -e "ExecStart=$BIN_DIR/klardrop daemon" -e "ExecStart=$BIN_DIR/klardrop-engine daemon" "$service_file" >/dev/null 2>&1); then
      die "An existing Klardrop native installation was detected ($BIN_DIR/klardrop belongs to an identified Klardrop native installation). Refusing to remove with --jvm --uninstall. Uninstall it first with --native --uninstall (or --omarchy --uninstall, or --qt --uninstall); your device identity and message data will be preserved."
    fi
    if [ -L "$BIN_DIR/klardrop" ]; then
      local link_target link_target_f
      link_target="$(readlink "$BIN_DIR/klardrop" 2>/dev/null || true)"
      link_target_f="$(readlink -f "$BIN_DIR/klardrop" 2>/dev/null || true)"
      if [ "$link_target" != "$APP_DIR/bin/klardrop" ] && [ -n "${LEGACY_APP_DIR:-}" ] && [ "$link_target" != "$LEGACY_APP_DIR/bin/klardrop" ] && \
         [ "$link_target_f" != "$APP_DIR/bin/klardrop" ] && [ -n "${LEGACY_APP_DIR:-}" ] && [ "$link_target_f" != "$LEGACY_APP_DIR/bin/klardrop" ]; then
        die "$BIN_DIR/klardrop points to '$link_target' rather than a JVM Klardrop app. Refusing to remove unrelated file."
      fi
    elif [ ! -d "$APP_DIR" ] && [ ! -d "${LEGACY_APP_DIR:-}/bin" ]; then
      die "$BIN_DIR/klardrop does not appear to be a JVM Klardrop installation. Refusing to remove."
    fi
  fi
  rm -rf "$APP_DIR"
  # Pre-relocation installs put the app-image inside the data dir; drop only the
  # app-image parts so databases/ and preferences survive an uninstall.
  if [ -n "${LEGACY_APP_DIR:-}" ]; then rm -rf "${LEGACY_APP_DIR:?}/bin" "${LEGACY_APP_DIR:?}/lib"; fi
  rm -f "$BIN_DIR/klardrop"
  rm -f "$DESKTOP_DIR/klardrop.desktop"
  rm -f "$METAINFO_DIR/com.carlom.Klardrop.metainfo.xml"
  for s in 32 64 128 256 512; do
    rm -f "$ICON_DIR/${s}x${s}/apps/klardrop.png"
  done
  rm -f "$ICON_DIR/scalable/apps/klardrop.svg"
  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
  fi
  say "Done."
  exit 0
}

[ "$ACTION" = "uninstall" ] && uninstall

# --- preflight ---------------------------------------------------------------
arch="$(uname -m)"
[ "$arch" = "x86_64" ] || die "unsupported architecture '$arch' (only x86_64 is published)."

# Fail early if active native daemon is running
if command -v systemctl >/dev/null 2>&1; then
  if systemctl --user is-active klardrop.service >/dev/null 2>&1; then
    die "An active Klardrop native daemon (klardrop.service) is currently running. Stop it before installing the JVM build: systemctl --user stop klardrop.service"
  fi
fi
if pgrep -f "klardrop.*daemon" >/dev/null 2>&1; then
  die "An active Klardrop native daemon process is currently running. Stop it before installing the JVM build."
fi

# Fail early if an existing native installation is detected (even if daemon is stopped)
local_service_file="$HOME/.config/systemd/user/klardrop.service"
if [ "$MARKER_EXISTS" = 1 ] || ([ -f "$local_service_file" ] && grep -Fx -e 'ExecStart=%h/.local/bin/klardrop daemon' -e 'ExecStart=%h/.local/bin/klardrop-engine daemon' -e "ExecStart=$BIN_DIR/klardrop daemon" -e "ExecStart=$BIN_DIR/klardrop-engine daemon" "$local_service_file" >/dev/null 2>&1); then
  die "An existing Klardrop native installation was detected. Please uninstall it first with --native --uninstall (or --omarchy --uninstall, or --qt --uninstall); your device identity and message data will be preserved. Then rerun the JVM installation."
fi

# Preflight: fail early if executable path contains '=' (unsupported by Desktop Entry specification)
case "$BIN_DIR/klardrop" in
  *=*) die "Executable path '$BIN_DIR/klardrop' contains '=' which is unsupported by the Desktop Entry specification." ;;
esac

# Check package ownership on existing binary
if [ -e "$BIN_DIR/klardrop" ]; then
  if command -v pacman >/dev/null 2>&1 && pacman -Qo "$BIN_DIR/klardrop" >/dev/null 2>&1; then
    die "$BIN_DIR/klardrop is managed by pacman. Refusing to overwrite. Update via your package manager instead."
  fi
  if command -v dpkg-query >/dev/null 2>&1 && dpkg-query -S "$BIN_DIR/klardrop" >/dev/null 2>&1; then
    die "$BIN_DIR/klardrop is managed by dpkg. Refusing to overwrite. Update via your package manager instead."
  fi
  if command -v rpm >/dev/null 2>&1 && rpm -qf "$BIN_DIR/klardrop" >/dev/null 2>&1; then
    die "$BIN_DIR/klardrop is managed by rpm. Refusing to overwrite. Update via your package manager instead."
  fi
fi

require_java() {
  local java_bin="" ver major
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    java_bin="${JAVA_HOME}/bin/java"
  elif command -v java >/dev/null 2>&1; then
    java_bin="$(command -v java)"
  fi
  if [ -n "$java_bin" ]; then
    ver="$("$java_bin" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')" || true
    major="${ver%%.*}"
    if [ "${major:-}" = "1" ]; then
      major="${ver#1.}"; major="${major%%.*}"
    fi
    case "${major:-}" in
      ''|*[!0-9]*) ;;
      *) [ "$major" -ge 21 ] && return 0 ;;
    esac
  fi
  die "Klardrop needs Java 21 or newer on PATH (or JAVA_HOME).
  Arch/Omarchy:   pacman -S jre-openjdk
  Debian/Ubuntu:  apt install openjdk-21-jre
  Fedora:         dnf install java-21-openjdk"
}
require_java

command -v tar >/dev/null 2>&1 || die "'tar' is required."
if command -v curl >/dev/null 2>&1; then dl() { curl -fsSL "$1" -o "$2"; }
elif command -v wget >/dev/null 2>&1; then dl() { wget -qO "$2" "$1"; }
else die "need 'curl' or 'wget' to download."; fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# --- download + verify -------------------------------------------------------
if [ -n "${KLARDROP_LOCAL_TARBALL:-}" ]; then
  say "Using local tarball ${KLARDROP_LOCAL_TARBALL}…"
  cp "$KLARDROP_LOCAL_TARBALL" "$tmp/$TARBALL"
else
  say "Downloading Klardrop ($CHANNEL)…"
  dl "$BASE/$TARBALL" "$tmp/$TARBALL" || {
    [ "$CHANNEL" = "stable" ] && die "no stable release published yet — install the nightly instead:
      curl -fsSL https://raw.githubusercontent.com/${REPO}/main/packaging/install.sh | bash -s -- --nightly"
    die "download failed: $BASE/$TARBALL"
  }

  # Same rule as the native path: a checksum that can silently be skipped is not a check.
  command -v sha256sum >/dev/null 2>&1 || die "sha256sum is not available; refusing to install an unverifiable tarball."
  dl "$BASE/$TARBALL.sha256" "$tmp/$TARBALL.sha256" 2>/dev/null \
    || die "checksum sidecar could not be downloaded; refusing to install an unverifiable tarball."
  expected="$(tr -d '[:space:]' < "$tmp/$TARBALL.sha256" | cut -d= -f2 | tail -c 65)"
  actual="$(sha256sum "$tmp/$TARBALL" | awk '{print $1}')"
  [ "$expected" = "$actual" ] || die "checksum mismatch — refusing to install (expected $expected, got $actual)."
  say "Checksum verified."
fi

say "Extracting…"
tar -xzf "$tmp/$TARBALL" -C "$tmp"
src="$tmp/klardrop-linux-x64"
[ -d "$src/klardrop/bin" ] || die "unexpected tarball layout."
[ -f "$src/klardrop/bin/klardrop" ] || die "unexpected tarball layout."

# --- install -----------------------------------------------------------------
say "Installing to $APP_DIR ($SCOPE)…"
mkdir -p "$BIN_DIR" "$DESKTOP_DIR" "$METAINFO_DIR" "$(dirname "$APP_DIR")"

rm -rf "$APP_DIR"
cp -r "$src/klardrop" "$APP_DIR"
# Sweep the old in-data-dir app-image, leaving the data itself alone.
if [ -n "${LEGACY_APP_DIR:-}" ]; then rm -rf "${LEGACY_APP_DIR:?}/bin" "${LEGACY_APP_DIR:?}/lib"; fi
ln -sf "$APP_DIR/bin/klardrop" "$BIN_DIR/klardrop"

# Desktop entry, with Exec pointed at the absolute launcher.
escaped_jvm_exec="$(escape_desktop_exec_path "$APP_DIR/bin/klardrop")"
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    Exec=*)
      printf 'Exec=/usr/bin/env "%s"\n' "$escaped_jvm_exec"
      ;;
    *)
      printf '%s\n' "$line"
      ;;
  esac
done < "$src/klardrop.desktop" > "$DESKTOP_DIR/klardrop.desktop"
chmod 644 "$DESKTOP_DIR/klardrop.desktop"
cp "$src/com.carlom.Klardrop.metainfo.xml" "$METAINFO_DIR/"

for s in 32 64 128 256 512; do
  if [ -f "$src/icons/${s}x${s}/klardrop.png" ]; then
    mkdir -p "$ICON_DIR/${s}x${s}/apps"
    cp "$src/icons/${s}x${s}/klardrop.png" "$ICON_DIR/${s}x${s}/apps/klardrop.png"
  fi
done
if [ -f "$src/icons/scalable/klardrop.svg" ]; then
  mkdir -p "$ICON_DIR/scalable/apps"
  cp "$src/icons/scalable/klardrop.svg" "$ICON_DIR/scalable/apps/klardrop.svg"
fi

# Refresh caches (best effort).
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -qtf "$ICON_DIR" 2>/dev/null || true
fi

say "Klardrop installed. Launch it from your app menu or run: klardrop"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) warn "$BIN_DIR is not on your PATH — add it, or launch from the app menu.";;
esac
