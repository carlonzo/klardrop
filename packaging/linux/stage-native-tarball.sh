#!/usr/bin/env bash
#
# Stage the native (JVM-free) Linux tarball from TWO binaries:
#   bin/klardrop        — the Rust client, from cli-rust/target/release/klardrop
#   bin/klardrop-engine — the Kotlin/Native engine (:cli), from klardrop-engine.kexe
# The names are deliberately distinct: the Rust client owns the user-facing
# `klardrop` name, the engine is a separate binary so no client can accidentally
# start a second engine.
# Published ALONGSIDE klardrop-linux-x64.tar.gz (the JVM app), not replacing it —
# see linux/omarchy/TODO.md Phase 2/3. No installer logic here; that's Phase 3.
#
# Expects :cli:linkReleaseExecutableLinuxX64 (x64) or :cli:linkReleaseExecutableLinuxArm64
# (arm64) to have already run, plus `cargo build --locked --release` in cli-rust/
# for the matching host arch.
# Writes dist/klardrop-native-linux-<arch>.tar.gz (cwd = repo root).
#
# Version: pass as $1, or set KLARDROP_VERSION; defaults to "dev".
# Arch: pass as $2, or set KLARDROP_ARCH; "x64" (default) or "arm64".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

VERSION="${1:-${KLARDROP_VERSION:-dev}}"
ARCH="${2:-${KLARDROP_ARCH:-x64}}"

case "$ARCH" in
  x64) KMP_TARGET=linuxX64; LINK_TASK=linkReleaseExecutableLinuxX64; TARBALL_ARCH=x64 ;;
  arm64) KMP_TARGET=linuxArm64; LINK_TASK=linkReleaseExecutableLinuxArm64; TARBALL_ARCH=arm64 ;;
  *) echo "error: unknown arch '$ARCH' (want x64|arm64)" >&2; exit 1 ;;
esac

# Engine (Kotlin/Native, :cli) and client (Rust, cli-rust/) have different names on
# purpose; see the header.
ENGINE_SRC=cli/build/bin/$KMP_TARGET/releaseExecutable/klardrop-engine.kexe
[ -f "$ENGINE_SRC" ] || { echo "error: missing $ENGINE_SRC (run :cli:$LINK_TASK)" >&2; exit 1; }

RUST_SRC="${KLARDROP_RUST_BIN:-cli-rust/target/release/klardrop}"
if [ ! -f "$RUST_SRC" ]; then
  echo "error: missing $RUST_SRC" >&2
  echo "       build it with: (cd cli-rust && cargo build --locked --release)" >&2
  echo "       (override the path with KLARDROP_RUST_BIN; stager fails closed when the Rust client is missing)" >&2
  exit 1
fi

PLUGIN_SRC=linux/omarchy/plugin
[ -d "$PLUGIN_SRC" ] || { echo "error: missing $PLUGIN_SRC" >&2; exit 1; }

QT_BIN="${KLARDROP_QT_BIN:-linux/qt/klardrop-qt}"
if [ ! -f "$QT_BIN" ] || [ ! -x "$QT_BIN" ]; then
  echo "error: missing Qt frontend binary at '$QT_BIN' (set KLARDROP_QT_BIN or build linux/qt/klardrop-qt; stager fails closed when Qt missing)" >&2
  exit 1
fi

# The Qt launcher's daemon hints live in ONE file, shared with
# packaging/linux/test-omarchy-install.sh, so the install tests assert the text
# this stager actually ships rather than a copy that can drift.
LAUNCHER_SRC=packaging/linux/klardrop-qt-launcher.sh
[ -f "$LAUNCHER_SRC" ] || { echo "error: missing $LAUNCHER_SRC" >&2; exit 1; }

check_elf_arch() {
  local file="$1"
  local expected="$2"

  # An unknown expectation is a bug in the caller, not a licence to skip: without
  # this the `case` arms below fall through and the binary is staged unverified.
  case "$expected" in
    x64|arm64) ;;
    *)
      echo "error: unknown expected architecture '$expected' (want x64 or arm64)" >&2
      exit 1
      ;;
  esac

  if command -v readelf >/dev/null 2>&1; then
    local machine
    machine="$(readelf -h "$file" 2>/dev/null | awk -F: '/Machine:/ {print $2}' || true)"
    case "$expected" in
      x64)
        if ! echo "$machine" | grep -qiE 'X86[-_]64|AMD64'; then
          echo "error: $file ELF machine ($machine) does not match expected x64 arch" >&2
          exit 1
        fi
        ;;
      arm64)
        if ! echo "$machine" | grep -qiE 'AArch64|ARM64'; then
          echo "error: $file ELF machine ($machine) does not match expected arm64 arch" >&2
          exit 1
        fi
        ;;
    esac
  elif command -v file >/dev/null 2>&1; then
    local desc
    desc="$(file -b "$file" 2>/dev/null || true)"
    case "$expected" in
      x64)
        if ! echo "$desc" | grep -qiE 'x86[-_]64'; then
          echo "error: $file file type ($desc) does not match expected x64 arch" >&2
          exit 1
        fi
        ;;
      arm64)
        if ! echo "$desc" | grep -qiE 'aarch64|arm64'; then
          echo "error: $file file type ($desc) does not match expected arm64 arch" >&2
          exit 1
        fi
        ;;
    esac
  else
    # Failing OPEN here is how a runner without binutils would publish an arm64
    # tarball full of x64 binaries: the arch check silently became a no-op and
    # every other guard in this script still passed. Verification is the whole
    # point of this function, so a build that cannot verify must not build.
    echo "error: neither readelf nor file is available; cannot verify that $file is $expected" >&2
    echo "       install binutils (readelf) or file, or stage the tarball by hand" >&2
    exit 1
  fi
}

# A wrong-arch client must fail staging, not ship: same check for every binary.
check_elf_arch "$ENGINE_SRC" "$ARCH"
check_elf_arch "$RUST_SRC" "$ARCH"
check_elf_arch "$QT_BIN" "$ARCH"

STAGE=stage/klardrop-native-linux-$TARBALL_ARCH
rm -rf stage
mkdir -p "$STAGE/bin" "$STAGE/share/klardrop/omarchy-plugin" "$STAGE/share/klardrop/applications"

# Engine first, under its own name; the Rust client takes the user-facing `klardrop`.
install -m 755 "$ENGINE_SRC" "$STAGE/bin/klardrop-engine"
strip "$STAGE/bin/klardrop-engine"

install -m 755 "$RUST_SRC" "$STAGE/bin/klardrop"
strip "$STAGE/bin/klardrop"

install -m 755 "$QT_BIN" "$STAGE/bin/klardrop-qt"
strip "$STAGE/bin/klardrop-qt"

cp "$LAUNCHER_SRC" "$STAGE/bin/klardrop-qt-launcher"
chmod 755 "$STAGE/bin/klardrop-qt-launcher"

cat > "$STAGE/share/klardrop/applications/klardrop.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Klardrop
GenericName=Nearby file sharing
Comment=Share files and clipboard with nearby devices
Exec=klardrop-qt-launcher
Icon=klardrop
Terminal=false
Categories=Network;FileTransfer;
EOF

cp -a "$PLUGIN_SRC/." "$STAGE/share/klardrop/omarchy-plugin/"

# App icon for the Omarchy .desktop/menu entries (install.sh puts these under
# the user's hicolor theme). Just the two sizes install.sh actually installs —
# no need to ship every size the JVM tarball does.
for s in 128 256; do
  mkdir -p "$STAGE/share/klardrop/icons/${s}x${s}"
  cp "brand/klardrop-icon-${s}.png" "$STAGE/share/klardrop/icons/${s}x${s}/klardrop.png"
done

echo "$VERSION" > "$STAGE/VERSION"

# `klardrop-fixture-daemon` is a cli-rust test binary and must never ship. It cannot
# reach here from the sources copied above, but the guard makes that structural rather
# than assumed: if a future copy step ever drags it in, staging fails instead.
if fixture="$(find "$STAGE" -name 'klardrop-fixture-daemon*' -print -quit)" && [ -n "$fixture" ]; then
  echo "error: refusing to package test-only fixture binary: ${fixture#$STAGE/}" >&2
  exit 1
fi

mkdir -p dist
tar -czf dist/klardrop-native-linux-$TARBALL_ARCH.tar.gz -C stage klardrop-native-linux-$TARBALL_ARCH
rm -rf stage
ls -la dist/
