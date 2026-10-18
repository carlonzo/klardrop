#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 1 ] || { [ "$1" != "ios" ] && [ "$1" != "mac" ]; }; then
  echo "Usage: $0 ios|mac" >&2
  exit 2
fi

plat="$1"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
GRADLEW_IOS="${GRADLEW_IOS:-$HOME/kn-ios/gradlew-ios}"

ulimit -n 65536
cd "$repo"

case "$plat" in
  ios)
    sdk=iphoneos
    out_dir="/tmp/swx-out-ios"
    tasks=(:presentation:compileSwiftExportMainKotlinIosArm64)
    ;;
  mac)
    sdk=macosx
    out_dir="/tmp/swx-out-mac"
    tasks=(:presentation:compileSwiftExportMainKotlinMacosArm64 :control-plane:compileSwiftExportMainKotlinMacosArm64 --continue)
    ;;
esac

mkdir -p "$out_dir"
export SDK_NAME="$sdk" \
       PLATFORM_NAME="$sdk" \
       ARCHS=arm64 \
       CONFIGURATION=Debug \
       TARGET_BUILD_DIR="$out_dir" \
       BUILT_PRODUCTS_DIR="$out_dir" \
       FRAMEWORKS_FOLDER_PATH=Frameworks \
       ENABLE_BITCODE=NO \
       EXPANDED_CODE_SIGN_IDENTITY=- \
       SRCROOT="$repo/iosApp" \
       CONFIGURATION_BUILD_DIR="$out_dir"
"$GRADLEW_IOS" "${tasks[@]}" --console=plain
