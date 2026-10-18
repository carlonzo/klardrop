#!/usr/bin/env python3
"""Local Swift typecheck of the swift-export modules + iosApp sources (iOS or macOS).
usage: check.py ios|mac [extra app source dirs...]   (run after bridge.sh)"""
import glob
import json
import os
import re
import shutil
import subprocess
import sys

if len(sys.argv) < 2 or sys.argv[1] not in ("ios", "mac"):
    print("Usage: check.py ios|mac [extra app source dirs...]", file=sys.stderr)
    sys.exit(2)

plat = sys.argv[1]
repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

darwin_bundle = os.environ.get("DARWIN_SDK_BUNDLE", "~/.swiftpm/swift-sdks/darwin.artifactbundle")
B = os.path.expanduser(darwin_bundle)

appdirs = sys.argv[2:] or [f"{repo}/iosApp/iosApp"]

if plat == "ios":
    gen = f"{repo}/presentation/build/SwiftExport/iosArm64/Debug"
    triple = "arm64-apple-ios18.0"
    sdk = f"{B}/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
else:
    gen = f"{repo}/control-plane/build/SwiftExport/macosArm64/Debug"
    triple = "arm64-apple-macosx15.0"
    sdk = f"{B}/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"

res = f"{B}/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift"
out = f"/tmp/klardrop-swift-check/out-{plat}"
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out, exist_ok=True)

if "KONAN_DIST" in os.environ:
    konan_dist = os.path.expanduser(os.environ["KONAN_DIST"])
else:
    dep_toml = os.path.join(repo, "gradle", "dependencies.toml")
    if not os.path.isfile(dep_toml):
        sys.exit(f"Error: {dep_toml} not found")
    kver = None
    with open(dep_toml, "r", encoding="utf-8") as f:
        for line in f:
            m = re.match(r'^\s*kotlin\s*=\s*"([^"]+)"', line)
            if m:
                kver = m.group(1)
                break
    if not kver:
        sys.exit(f"Error: could not find 'kotlin = \"...\"' in {dep_toml}")
    konan_dist = os.path.expanduser(f"~/.konan/kotlin-native-prebuilt-linux-x86_64-{kver}")

krt = os.path.join(konan_dist, "konan", "swift_export", "kotlin_runtime")
if not os.path.isdir(krt):
    sys.exit(f"Error: swift_export runtime not found at {krt}")

module_json_files = glob.glob(f"{gen}/modules/*.json")
if not module_json_files:
    print(f"Error: generated modules json missing under {gen}/modules. Run scripts/swift-check/bridge.sh {plat} first.", file=sys.stderr)
    sys.exit(2)

with open(module_json_files[0], "r", encoding="utf-8") as f:
    mods = json.load(f)["modules"]

byname = {m["name"]: m for m in mods}
inc = ["-I", out, "-I", krt]
base = ["swiftc", "-target", triple, "-sdk", sdk, "-resource-dir", res, "-parse-as-library", "-swift-version", "5", "-package-name", "SwiftExport"]

for m in mods:
    if m.get("bridgeName"):
        d = f"{out}/{m['bridgeName']}"
        os.makedirs(d, exist_ok=True)
        shutil.copy(m["files"]["cHeaderBridges"], d)
        with open(f"{d}/module.modulemap", "w", encoding="utf-8") as f:
            f.write(f'module {m["bridgeName"]} {{\n  header "{os.path.basename(m["files"]["cHeaderBridges"])}"\n  export *\n}}\n')
        inc += ["-I", d]

done = set()
ok = True

def build(n):
    global ok
    if n in done:
        return
    done.add(n)
    m = byname[n]
    for dep in m["dependencies"]:
        build(dep)
    src = m.get("swiftApi") or m["files"]["swiftApi"]
    r = subprocess.run(
        base + inc + ["-emit-module", "-module-name", n, "-emit-module-path", f"{out}/{n}.swiftmodule", src],
        capture_output=True,
        text=True,
    )
    errs = [l for l in r.stderr.splitlines() if ": error:" in l]
    print(f"[module] {n}: {'OK' if r.returncode == 0 else 'FAIL'}")
    if r.returncode:
        ok = False
        print("\n".join(errs[:40]) or r.stderr[-3000:])

for m in mods:
    build(m["name"])

if not appdirs:
    sys.exit(0 if ok else 1)

skip = "MacApp.swift" if plat == "ios" else "iOSApp.swift"
files = [f for d in appdirs for f in glob.glob(f"{d}/**/*.swift", recursive=True) if not f.endswith("/" + skip)] + [f"{repo}/iosApp/Shared/ShareInbox.swift"]
r = subprocess.run(base[:-1] + ["App"] + inc + ["-typecheck", "-module-name", "App"] + files, capture_output=True, text=True)
errs = sorted(set(l for l in r.stderr.splitlines() if ": error:" in l))
print(f"[app] {len(files)} files: {'OK' if r.returncode == 0 else 'FAIL'} ({len(errs)} errors)")
if r.returncode != 0:
    print("\n".join(errs) or r.stderr[-3000:])
sys.exit(0 if ok and r.returncode == 0 else 1)
