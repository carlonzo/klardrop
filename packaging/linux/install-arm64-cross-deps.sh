#!/usr/bin/env bash
#
# aarch64 multiarch headers and libraries so an x64 Linux host can cross-compile
# the Kotlin/Native linuxArm64 engine.
#
# Kotlin/Native 2.4 has no compiler for a linux-aarch64 host, so the release
# jobs cannot build this target on ubuntu-24.04-arm (the link task is skipped
# and Gradle still exits 0). They cross-compile it from ubuntu-24.04 instead.
# The cinterop .def files look in /usr/include/aarch64-linux-gnu and
# /usr/lib/aarch64-linux-gnu for the linux_arm64 target. Those paths are the
# multiarch layout (:arm64 packages), not the gcc-aarch64-linux-gnu sysroot
# under /usr/aarch64-linux-gnu, so this script does not install the cross
# compiler.
#
# archive.ubuntu.com and security.ubuntu.com publish amd64 only. Leaving them
# unrestricted after `dpkg --add-architecture arm64` makes `apt-get update`
# 404 on binary-arm64. arm64 packages come from ports.ubuntu.com.
set -euo pipefail

if [ "$(dpkg --print-architecture)" != "amd64" ]; then
  echo "error: this script cross-installs arm64 libraries onto an amd64 host" >&2
  exit 1
fi

sudo dpkg --add-architecture arm64

# Pin the existing Ubuntu sources to amd64 so apt stops looking for arm64
# indexes on archive.ubuntu.com. Idempotent.
sudo python3 - <<'PY'
from pathlib import Path

def pin_deb822(path: Path) -> None:
    if not path.exists():
        return
    text = path.read_text()
    stanzas = text.split("\n\n")
    changed = False
    out = []
    for stanza in stanzas:
        lines = stanza.splitlines()
        body = [l for l in lines if l.strip() and not l.strip().startswith("#")]
        is_deb = any(l.startswith("Types:") and "deb" in l.split(":", 1)[1] for l in body)
        has_arch = any(l.startswith("Architectures:") for l in body)
        uris = " ".join(l for l in body if l.startswith("URIs:"))
        is_ubuntu = "archive.ubuntu.com" in uris or "security.ubuntu.com" in uris
        if is_deb and is_ubuntu and not has_arch:
            new_lines = []
            inserted = False
            for line in lines:
                new_lines.append(line)
                if not inserted and line.startswith("Types:"):
                    new_lines.append("Architectures: amd64")
                    inserted = True
            if not inserted:
                new_lines.append("Architectures: amd64")
            stanza = "\n".join(new_lines)
            changed = True
        out.append(stanza)
    if changed:
        path.write_text("\n\n".join(out))
        if not path.read_text().endswith("\n"):
            path.write_text(path.read_text() + "\n")

for path in Path("/etc/apt/sources.list.d").glob("*.sources"):
    pin_deb822(path)
PY

if [ -f /etc/apt/sources.list ]; then
  sudo sed -i -E \
    '/^deb \[arch=/{b}; s#^deb (https?://(archive|security)\.ubuntu\.com)#deb [arch=amd64] \1#' \
    /etc/apt/sources.list
fi

codename="$(. /etc/os-release && echo "$VERSION_CODENAME")"
sudo tee /etc/apt/sources.list.d/arm64-ports.sources >/dev/null <<EOF
Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports/
Suites: ${codename} ${codename}-updates ${codename}-security
Components: main universe
Architectures: arm64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF

sudo apt-get update
sudo apt-get install -y \
  binutils \
  libc6-dev:arm64 \
  libavahi-client-dev:arm64 \
  libsqlite3-dev:arm64 \
  libssl-dev:arm64 \
  libsystemd-dev:arm64
