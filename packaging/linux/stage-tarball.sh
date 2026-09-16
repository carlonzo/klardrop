#!/usr/bin/env bash
#
# Stage the Linux universal tarball from a Compose Desktop app-image.
# Copies ProGuard'd jars + Skiko + the system-JRE wrapper; never lib/runtime.
#
# Expects :desktop:createReleaseDistributable to have already run.
# Writes dist/klardrop-linux-x64.tar.gz (cwd = repo root).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

APPDIR=desktop/build/compose/binaries/main-release/app
if [ -d "$APPDIR/klardrop" ]; then
  APP_IMAGE="$APPDIR/klardrop"
elif [ -d "$APPDIR/klardrop-debug" ]; then
  APP_IMAGE="$APPDIR/klardrop-debug"
else
  echo "error: no app-image under $APPDIR" >&2
  exit 1
fi

APP_LIB="$APP_IMAGE/lib/app"
[ -d "$APP_LIB" ] || { echo "error: missing $APP_LIB" >&2; exit 1; }
shopt -s nullglob
jars=( "$APP_LIB"/*.jar )
skiko=( "$APP_LIB"/libskiko-linux-*.so )
[ ${#jars[@]} -gt 0 ] || { echo "error: no jars in $APP_LIB" >&2; exit 1; }
[ ${#skiko[@]} -gt 0 ] || { echo "error: no libskiko-linux-*.so in $APP_LIB" >&2; exit 1; }

STAGE=stage/klardrop-linux-x64
rm -rf stage
mkdir -p "$STAGE/icons" "$STAGE/klardrop/bin" "$STAGE/klardrop/lib"

# Jars + native libs (Skiko). Do not copy lib/runtime (the ~89MB jlink image).
cp -a "$APP_LIB" "$STAGE/klardrop/lib/app"
install -m 755 packaging/linux/klardrop "$STAGE/klardrop/bin/klardrop"

if [ -e "$STAGE/klardrop/lib/runtime" ]; then
  echo "error: lib/runtime leaked into the staged tarball" >&2
  exit 1
fi
bash -n "$STAGE/klardrop/bin/klardrop"

cp packaging/linux/klardrop.desktop "$STAGE/"
cp packaging/linux/com.carlom.Klardrop.metainfo.xml "$STAGE/"
for s in 32 64 128 256 512; do
  mkdir -p "$STAGE/icons/${s}x${s}"
  cp "brand/klardrop-icon-${s}.png" "$STAGE/icons/${s}x${s}/klardrop.png"
done
mkdir -p "$STAGE/icons/scalable"
cp brand/klardrop-icon.svg "$STAGE/icons/scalable/klardrop.svg"

mkdir -p dist
tar -czf dist/klardrop-linux-x64.tar.gz -C stage klardrop-linux-x64
rm -rf stage
ls -la dist/
