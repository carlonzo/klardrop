#!/usr/bin/env bash
#
# aarch64 multiarch headers and libraries so an x64 Linux host can cross-compile
# the Kotlin/Native linuxArm64 engine.
#
# Kotlin/Native 2.4 has no compiler for a linux-aarch64 host, so the release
# jobs cannot build this target on ubuntu-26.04-arm (the link task is skipped
# and Gradle still exits 0). They cross-compile it from ubuntu-26.04 instead.
# The cinterop .def files look in /usr/include/aarch64-linux-gnu and
# /usr/lib/aarch64-linux-gnu for the linux_arm64 target. Those paths are the
# multiarch layout (:arm64 packages), not the gcc-aarch64-linux-gnu sysroot
# under /usr/aarch64-linux-gnu, so this script does not install the cross
# compiler.
#
# Ubuntu's amd64 archives do not publish binary-arm64. That includes
# archive.ubuntu.com, security.ubuntu.com, and every mirror of them (the Azure
# mirror GitHub-hosted runners prefer, country mirrors, and the failover list
# in /etc/apt/apt-mirrors.txt). Leaving those sources unrestricted after
# `dpkg --add-architecture arm64` makes `apt-get update` request binary-arm64
# and exit 100 on the 404. arm64 packages come from ports.ubuntu.com.
#
# GitHub's Ubuntu images do not name archive.ubuntu.com in the source
# entry itself: deb822 URIs are `mirror+file:/etc/apt/apt-mirrors.txt`, and
# apt walks azure.archive.ubuntu.com, then archive.ubuntu.com, then
# security.ubuntu.com. Pin every Ubuntu archive source — legacy one-line lists
# and deb822, any mirror hostname — to amd64, and add an arm64 ports source.
set -euo pipefail

if [ "$(dpkg --print-architecture)" != "amd64" ]; then
  echo "error: this script cross-installs arm64 libraries onto an amd64 host" >&2
  exit 1
fi

sudo dpkg --add-architecture arm64

# Restrict existing Ubuntu sources before apt-get update. Idempotent: a stanza
# that already names an architecture is left alone, including the ports source
# written below.
sudo python3 - /etc/apt <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlparse

apt_root = Path(sys.argv[1])


def mirror_file_path(uri: str):
    """Return the local file behind mirror+file:, or None if uri is not one."""
    if not uri.startswith("mirror+"):
        return None
    target = uri[len("mirror+") :]
    if not target.startswith("file:"):
        return None
    rest = target[len("file:") :].split("?", 1)[0]
    if rest.startswith("///"):
        return Path(rest[2:])
    if rest.startswith("//"):
        rest = rest[2:]
        slash = rest.find("/")
        if slash < 0:
            return None
        return Path(rest[slash:])
    if rest.startswith("/"):
        return Path(rest)
    return None


def expand_uris(uri: str) -> list[str]:
    """Concrete archive URIs for a source URI.

    `mirror+file:` entries (GitHub's ubuntu.sources) expand to the URLs in
    that list. Anything else is returned unchanged.
    """
    path = mirror_file_path(uri)
    if path is None:
        return [uri]
    if not path.is_file():
        return [uri]
    found = []
    for line in path.read_text(errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        found.append(line.split()[0])
    return found or [uri]


def host_and_path(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    return (parsed.hostname or "").lower(), parsed.path or ""


def is_ubuntu_archive_host(host: str) -> bool:
    host = host.lower().rstrip(".")
    return host == "ubuntu.com" or host.endswith(".ubuntu.com")


def classify_concrete(uri: str) -> str:
    """'ports', 'archive', or 'other' for one concrete URI."""
    host, path = host_and_path(uri)
    if host == "ports.ubuntu.com" or "ubuntu-ports" in path:
        return "ports"
    if is_ubuntu_archive_host(host):
        return "archive"
    return "other"


def signed_by_ubuntu(signed_by: str) -> bool:
    return "ubuntu-archive-keyring" in signed_by


def classify_source(uris: list[str], signed_by: str) -> str:
    """Architecture to pin, or '' to leave the source alone.

    Ubuntu amd64 archives (any mirror hostname, including a mirror+ list that
    names them) are pinned to amd64. ports.ubuntu.com is pinned to arm64.
    An explicit Architectures / arch= on the source is handled by the caller.
    """
    flat: list[str] = []
    for uri in uris:
        flat.extend(expand_uris(uri))
    kinds = [classify_concrete(uri) for uri in flat]
    if any(kind == "archive" for kind in kinds):
        return "amd64"
    if kinds and all(kind == "ports" for kind in kinds):
        return "arm64"
    # Country mirror or other hostname still signed by the Ubuntu archive key.
    if signed_by_ubuntu(signed_by):
        if any("ubuntu-ports" in host_and_path(uri)[1] for uri in flat):
            return "arm64"
        return "amd64"
    return ""


def parse_deb822_fields(stanza: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    current = None
    values: list[str] = []

    def commit() -> None:
        nonlocal current, values
        if current is not None:
            fields[current] = " ".join(values).strip()
        current = None
        values = []

    for raw in stanza.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw[:1].isspace() and current is not None:
            values.append(raw.strip())
            continue
        commit()
        if ":" not in raw:
            continue
        key, value = raw.split(":", 1)
        current = key.strip().lower()
        values = [value.strip()] if value.strip() else []
    commit()
    return fields


def is_deb_types(types: str) -> bool:
    return any(token in ("deb", "deb-src") for token in types.split())


def insert_architectures(stanza: str, arch: str) -> str:
    lines = stanza.splitlines(keepends=True)
    insert_at = None
    index = 0
    while index < len(lines):
        raw = lines[index]
        if not raw.strip() or raw.lstrip().startswith("#"):
            index += 1
            continue
        if raw.lower().startswith("types:"):
            insert_at = index + 1
            index += 1
            while (
                index < len(lines)
                and lines[index][:1].isspace()
                and lines[index].strip()
                and not lines[index].lstrip().startswith("#")
            ):
                insert_at = index + 1
                index += 1
            break
        index += 1
    addition = f"Architectures: {arch}\n"
    if insert_at is None:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] = lines[-1] + "\n"
        lines.append(addition)
    else:
        lines.insert(insert_at, addition)
    return "".join(lines)


def pin_deb822(text: str) -> tuple[str, bool]:
    lines = text.splitlines(keepends=True)
    blocks: list[tuple[bool, str]] = []
    current: list[str] = []
    current_is_stanza = None
    for line in lines:
        is_stanza = line.strip() != ""
        if current and is_stanza != current_is_stanza:
            blocks.append((current_is_stanza, "".join(current)))
            current = []
        current.append(line)
        current_is_stanza = is_stanza
    if current:
        blocks.append((bool(current_is_stanza), "".join(current)))

    changed = False
    rebuilt: list[str] = []
    for is_stanza, content in blocks:
        if not is_stanza:
            rebuilt.append(content)
            continue
        fields = parse_deb822_fields(content)
        if (
            not is_deb_types(fields.get("types", ""))
            or "architectures" in fields
        ):
            rebuilt.append(content)
            continue
        uris = fields.get("uris", "").split()
        arch = classify_source(uris, fields.get("signed-by", ""))
        if not arch:
            rebuilt.append(content)
            continue
        rebuilt.append(insert_architectures(content, arch))
        changed = True
    return "".join(rebuilt), changed


def options_have_arch(opts: str) -> bool:
    for token in opts.split():
        if token.split("=", 1)[0] == "arch":
            return True
    return False


def pin_oneline_line(line: str) -> tuple[str, bool]:
    newline = ""
    body = line
    if body.endswith("\n"):
        newline = "\n"
        body = body[:-1]
    if body.endswith("\r"):
        newline = "\r" + newline
        body = body[:-1]
    stripped = body.lstrip()
    if not stripped or stripped.startswith("#"):
        return line, False
    indent = body[: len(body) - len(stripped)]
    if stripped.startswith("deb-src") and (
        len(stripped) == len("deb-src") or stripped[len("deb-src")].isspace()
    ):
        kind = "deb-src"
        rest = stripped[len("deb-src") :].strip()
    elif stripped.startswith("deb") and (
        len(stripped) == len("deb") or stripped[len("deb")].isspace()
    ):
        kind = "deb"
        rest = stripped[len("deb") :].strip()
    else:
        return line, False
    opts = None
    if rest.startswith("["):
        end = rest.find("]")
        if end < 0:
            return line, False
        opts = rest[1:end]
        rest = rest[end + 1 :].strip()
    if not rest:
        return line, False
    if opts is not None and options_have_arch(opts):
        return line, False
    uri, sep, tail = rest.partition(" ")
    arch = classify_source([uri], opts or "")
    if not arch:
        return line, False
    if opts is None:
        pinned = f"[arch={arch}] {uri}"
    else:
        pinned = f"[{opts.strip()} arch={arch}] {uri}"
    if sep:
        pinned += sep + tail
    return f"{indent}{kind} {pinned}{newline}", True


def pin_oneline(text: str) -> tuple[str, bool]:
    changed = False
    out: list[str] = []
    for line in text.splitlines(keepends=True):
        pinned, did = pin_oneline_line(line)
        out.append(pinned)
        changed = changed or did
    return "".join(out), changed


def write_if_changed(path: Path, updated: str, changed: bool) -> None:
    if not changed:
        return
    if updated and not updated.endswith("\n"):
        updated += "\n"
    path.write_text(updated)
    print(f"restricted Ubuntu sources in {path}")


def main() -> None:
    sources = []
    sources_list = apt_root / "sources.list"
    if sources_list.is_file():
        sources.append(sources_list)
    list_dir = apt_root / "sources.list.d"
    if list_dir.is_dir():
        sources.extend(sorted(list_dir.glob("*.list")))
        sources.extend(sorted(list_dir.glob("*.sources")))
    for path in sources:
        if not path.is_file():
            continue
        original = path.read_text(errors="replace")
        if path.suffix == ".sources":
            updated, changed = pin_deb822(original)
        else:
            updated, changed = pin_oneline(original)
        write_if_changed(path, updated, changed)


if __name__ == "__main__":
    main()
PY

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
