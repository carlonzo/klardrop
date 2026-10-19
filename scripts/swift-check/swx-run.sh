#!/usr/bin/env bash
# Run presentation swift export on Linux; prints PASS/OVERFLOW/OTHER
repo="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo" || exit 2
GRADLEW_IOS="${GRADLEW_IOS:-$HOME/kn-ios/gradlew-ios}"
ulimit -n 65536
out_dir="/tmp/swx-out"
mkdir -p "$out_dir"
export SDK_NAME=iphoneos PLATFORM_NAME=iphoneos ARCHS=arm64 CONFIGURATION=Debug TARGET_BUILD_DIR="$out_dir" BUILT_PRODUCTS_DIR="$out_dir" FRAMEWORKS_FOLDER_PATH=Frameworks ENABLE_BITCODE=NO EXPANDED_CODE_SIGN_IDENTITY=- SRCROOT="$repo/iosApp" CONFIGURATION_BUILD_DIR="$out_dir"
"$GRADLEW_IOS" :presentation:iosArm64DebugSwiftExport --stacktrace --console=plain > /tmp/swx-run.log 2>&1
code=$?
if grep -q StackOverflowError /tmp/swx-run.log; then
  echo OVERFLOW
  exit 1
elif [ $code -eq 0 ]; then
  echo PASS
else
  echo "OTHER($code)"
  grep -A3 "What went wrong" /tmp/swx-run.log | head -5
  exit 1
fi
