#!/usr/bin/env bash
# Build the macOS BLE helper as a universal arm64+x86_64 binary, ad-hoc codesign,
# and copy into desktop resources. Run on macOS only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG_DIR="$ROOT/desktop/native/macos/klardrop-ble-helper"
OUT_DIR="$ROOT/desktop/src/jvmMain/resources/native/macos"
OUT_PATH="$OUT_DIR/klardrop-ble-helper"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "build-mac-ble-helper.sh must be run on macOS (got $(uname -s))" >&2
  exit 1
fi

if ! command -v swift >/dev/null 2>&1; then
  echo "swift toolchain not found on PATH; install Xcode command line tools first" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

cd "$PKG_DIR"
swift build -c release \
  --arch arm64 \
  --arch x86_64

BUILT_BIN="$PKG_DIR/.build/apple/Products/Release/KlardropBleHelper"
if [[ ! -f "$BUILT_BIN" ]]; then
  echo "Expected built binary at $BUILT_BIN, not found" >&2
  exit 1
fi

cp "$BUILT_BIN" "$OUT_PATH"
chmod +x "$OUT_PATH"

# Ad-hoc codesign so Gatekeeper does not block execution from the JVM tmpdir.
codesign --force --sign - "$OUT_PATH"

echo "Wrote $(file "$OUT_PATH" | head -1)"
echo "Size: $(du -h "$OUT_PATH" | cut -f1)"
