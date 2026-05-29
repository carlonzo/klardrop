#!/usr/bin/env bash
#
# Klardrop Linux installer.
#
#   curl -fsSL https://raw.githubusercontent.com/carlonzo/klardrop/main/packaging/install.sh | bash
#
# Downloads the latest universal tarball (bundled JRE — no Java needed), verifies
# its checksum, and installs the app-image plus a launcher, menu entry and icons.
#
# Scope is auto-detected: run as your user it installs under ~/.local (no sudo);
# run as root (sudo) it installs system-wide under /opt. Re-running upgrades in
# place. Uninstall with:  curl -fsSL <url> | bash -s -- --uninstall
set -euo pipefail

REPO="carlonzo/klardrop"
TARBALL="klardrop-linux-x64.tar.gz"
BASE="https://github.com/${REPO}/releases/latest/download"

say()  { printf '\033[1;34m::\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m::\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

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
  APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/klardrop"
  BIN_DIR="$HOME/.local/bin"
  DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  ICON_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
  METAINFO_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/metainfo"
  SCOPE="for the current user"
fi

uninstall() {
  say "Removing Klardrop ($SCOPE)…"
  rm -rf "$APP_DIR"
  rm -f "$BIN_DIR/klardrop"
  rm -f "$DESKTOP_DIR/klardrop.desktop"
  rm -f "$METAINFO_DIR/com.carlom.Klardrop.metainfo.xml"
  for s in 32 64 128 256 512; do
    rm -f "$ICON_DIR/${s}x${s}/apps/klardrop.png"
  done
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
  say "Done."
  exit 0
}

[ "${1:-}" = "--uninstall" ] && uninstall

# --- preflight ---------------------------------------------------------------
arch="$(uname -m)"
[ "$arch" = "x86_64" ] || die "unsupported architecture '$arch' (only x86_64 is published)."
command -v tar >/dev/null 2>&1 || die "'tar' is required."
if command -v curl >/dev/null 2>&1; then dl() { curl -fsSL "$1" -o "$2"; }
elif command -v wget >/dev/null 2>&1; then dl() { wget -qO "$2" "$1"; }
else die "need 'curl' or 'wget' to download."; fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# --- download + verify -------------------------------------------------------
say "Downloading latest Klardrop…"
dl "$BASE/$TARBALL" "$tmp/$TARBALL"

if dl "$BASE/$TARBALL.sha256" "$tmp/$TARBALL.sha256" 2>/dev/null && command -v sha256sum >/dev/null 2>&1; then
  expected="$(tr -d '[:space:]' < "$tmp/$TARBALL.sha256" | cut -d= -f2 | tail -c 65)"
  actual="$(sha256sum "$tmp/$TARBALL" | awk '{print $1}')"
  [ "$expected" = "$actual" ] || die "checksum mismatch — refusing to install (expected $expected, got $actual)."
  say "Checksum verified."
else
  warn "Could not verify checksum (no sidecar or sha256sum) — continuing."
fi

say "Extracting…"
tar -xzf "$tmp/$TARBALL" -C "$tmp"
src="$tmp/klardrop-linux-x64"
[ -d "$src/klardrop/bin" ] || die "unexpected tarball layout."

# --- install -----------------------------------------------------------------
say "Installing to $APP_DIR ($SCOPE)…"
mkdir -p "$BIN_DIR" "$DESKTOP_DIR" "$METAINFO_DIR" "$(dirname "$APP_DIR")"

rm -rf "$APP_DIR"
cp -r "$src/klardrop" "$APP_DIR"
ln -sf "$APP_DIR/bin/klardrop" "$BIN_DIR/klardrop"

# Desktop entry, with Exec pointed at the absolute launcher.
sed "s|^Exec=klardrop|Exec=$APP_DIR/bin/klardrop|" "$src/klardrop.desktop" > "$DESKTOP_DIR/klardrop.desktop"
cp "$src/com.carlom.Klardrop.metainfo.xml" "$METAINFO_DIR/"

for s in 32 64 128 256 512; do
  if [ -f "$src/icons/${s}x${s}/klardrop.png" ]; then
    mkdir -p "$ICON_DIR/${s}x${s}/apps"
    cp "$src/icons/${s}x${s}/klardrop.png" "$ICON_DIR/${s}x${s}/apps/klardrop.png"
  fi
done

# Refresh caches (best effort).
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
command -v gtk-update-icon-cache  >/dev/null 2>&1 && gtk-update-icon-cache -qtf "$ICON_DIR" 2>/dev/null || true

say "Klardrop installed. Launch it from your app menu or run: klardrop"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) warn "$BIN_DIR is not on your PATH — add it, or launch from the app menu.";;
esac
