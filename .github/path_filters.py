#!/usr/bin/env python3
"""Decide which pull-request jobs run.

The workflow always starts, so the aggregator check exists even when every
build job is skipped. Job conditions read the booleans this script prints.
A change under a shared Kotlin module, protos, the Gradle catalog, or the
workflows runs the full matrix — that is what Renovate catalog bumps hit.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Trailing slash so "cli/" does not swallow "cli-rust/".
FULL_PREFIXES = (
    "android/",
    "brand/",
    "cli/",
    "common/",
    "compose-ui/",
    "control-plane/",
    "desktop/",
    "gradle/",
    "presentation/",
    "protos/",
    ".github/workflows/",
)
FULL_FILES = {
    ".github/path_filters.py",
    "build.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "settings.gradle.kts",
}
LINUX_PREFIXES = (
    "cli-rust/",
    "linux/",
    "packaging/",
    "scripts/",
)
MACOS_PREFIXES = ("iosApp/",)
RUST_MACOS_PREFIXES = ("cli-rust/",)

OUTPUT_KEYS = ("full", "linux", "macos", "rust_macos", "desktop_jvm")


def _under(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def classify(paths: list[str]) -> dict[str, bool]:
    full = any(path in FULL_FILES or _under(path, FULL_PREFIXES) for path in paths)
    return {
        "full": full,
        "linux": full or any(_under(path, LINUX_PREFIXES) for path in paths),
        "macos": full or any(_under(path, MACOS_PREFIXES) for path in paths),
        "rust_macos": full or any(_under(path, RUST_MACOS_PREFIXES) for path in paths),
        "desktop_jvm": full,
    }


def _parse_paths(raw: str) -> list[str]:
    return [line.strip() for line in raw.splitlines() if line.strip()]


def _expect(paths: list[str], **expected: bool) -> None:
    got = classify(paths)
    missing = [key for key in OUTPUT_KEYS if key not in expected]
    if missing:
        raise AssertionError(f"self-test omitted {missing}")
    if got != expected:
        raise AssertionError(f"{paths}: expected {expected}, got {got}")


def self_test() -> None:
    off = dict(full=False, linux=False, macos=False, rust_macos=False, desktop_jvm=False)
    on = dict(full=True, linux=True, macos=True, rust_macos=True, desktop_jvm=True)

    _expect(["iosApp/iosApp/App/Foo.swift"], **{**off, "macos": True})
    _expect(
        ["cli-rust/src/main.rs", "cli-rust/Cargo.lock"],
        **{**off, "linux": True, "rust_macos": True},
    )
    _expect(["gradle/dependencies.toml"], **on)
    _expect(["common/src/commonMain/kotlin/Foo.kt"], **on)
    _expect(["protos/src/main/proto/wire.proto"], **on)
    _expect(["presentation/src/commonMain/kotlin/Bar.kt"], **on)
    _expect([".github/workflows/build_pr.yml"], **on)
    _expect([".github/path_filters.py"], **on)
    _expect(["build.gradle.kts"], **on)
    _expect(["desktop/build.gradle.kts"], **on)
    _expect(["cli/src/linuxMain/kotlin/Main.kt"], **on)
    _expect(["site/index.html"], **off)
    _expect(["README.md", "docs/guide.md"], **off)
    _expect(["packaging/linux/test-omarchy-install.sh"], **{**off, "linux": True})
    _expect(["linux/qt/tests/test_integration.py"], **{**off, "linux": True})
    _expect(["scripts/klardrop-jvm-host-fixture-tests.sh"], **{**off, "linux": True})
    # "cli/" must not treat the Rust crate as a shared Kotlin change.
    rust_only = classify(["cli-rust/src/main.rs"])
    if rust_only["full"] or rust_only["macos"] or rust_only["desktop_jvm"]:
        raise AssertionError(f"cli-rust was classified as shared Kotlin: {rust_only}")


def _write_outputs(path: Path, result: dict[str, bool]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        for key in OUTPUT_KEYS:
            handle.write(f"{key}={'true' if result[key] else 'false'}\n")


def _write_summary(path: Path, paths: list[str], result: dict[str, bool]) -> None:
    lines = ["### Path filters", ""]
    for key in OUTPUT_KEYS:
        lines.append(f"- `{key}`: {'run' if result[key] else 'skip'}")
    lines.append("")
    lines.append("<details><summary>Changed paths</summary>")
    lines.append("")
    if paths:
        lines.extend(f"- `{path}`" for path in paths)
    else:
        lines.append("- (none)")
    lines.append("")
    lines.append("</details>")
    lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--files-from", type=Path)
    args = parser.parse_args(argv)

    if args.self_test:
        self_test()
        print("path filter self-test passed")
        if args.github_output is None:
            return 0

    if args.files_from is None or args.github_output is None:
        parser.error("--files-from and --github-output are required unless only --self-test is set")

    paths = _parse_paths(args.files_from.read_text(encoding="utf-8"))
    result = classify(paths)
    _write_outputs(args.github_output, result)
    if args.summary is not None:
        _write_summary(args.summary, paths, result)
    for key in OUTPUT_KEYS:
        print(f"{key}={'true' if result[key] else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
