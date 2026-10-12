#!/usr/bin/env bash
# Klardrop Qt desktop launcher.
# Starts the existing systemd user service if present, then launches the Qt UI.
#
# Single source of truth for the SHIPPED launcher text: both
# packaging/linux/stage-native-tarball.sh (which puts it in the native tarball)
# and packaging/linux/test-omarchy-install.sh (which puts it in the fixture
# tarball) copy this file verbatim. Keeping one copy is what lets the install
# tests assert the shipped daemon hints — the engine name, never the pre-split
# form where the client binary owned the daemon — against the real payload.
set -u

warn_daemon() {
  local msg="$1"
  if command -v notify-send >/dev/null 2>&1; then
    notify-send "Klardrop" "$msg" >/dev/null 2>&1 || true
  fi
  echo "$msg" >&2
}

if command -v systemctl >/dev/null 2>&1; then
  unit_load_state="$(systemctl --user show -p LoadState --value klardrop.service 2>/dev/null || true)"
  if [ "$unit_load_state" = "loaded" ]; then
    if ! systemctl --user is-active klardrop.service >/dev/null 2>&1; then
      if ! systemctl --user start klardrop.service >/dev/null 2>&1; then
        warn_daemon "Klardrop background service (klardrop.service) failed to start. Check service logs: journalctl --user -u klardrop.service (or run 'klardrop-engine daemon' manually)"
      fi
    fi
  else
    warn_daemon "Klardrop systemd user service unit (klardrop.service) is not loaded or missing. Start daemon manually with: klardrop-engine daemon"
  fi
else
  warn_daemon "systemctl is not available. Start Klardrop daemon manually with: klardrop-engine daemon"
fi

BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$BIN_DIR/klardrop-qt" ]; then
  exec "$BIN_DIR/klardrop-qt" "$@"
else
  exec klardrop-qt "$@"
fi
