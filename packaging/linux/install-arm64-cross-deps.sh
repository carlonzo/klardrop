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
# and exit 100 on the 404.
#
# arm64 packages are taken from snapshot.ubuntu.com at one timestamp, not from
# the live ports.ubuntu.com mirror. ports is a separate mirror and lags the
# amd64 archive: apt then co-installs Multi-Arch: same packages (libssl-dev,
# libc6, gcc-16-base, ...) only by downgrading the host copies, and Ubuntu
# 26.04's apt exits 100 because -y was given without --allow-downgrades.
# Downgrading binutils, libstdc++6, and libssl-dev under the Kotlin/Native
# link is the wrong fix. A snapshot publishes amd64 and arm64 together, so the
# arm64 versions match the host (or are newer, which apt may upgrade to).
# This script never passes --allow-downgrades. If the host image is newer
# than every published arm64 build, the arm64 debs are unpacked into the
# multiarch directories and the host dpkg database is left alone.
#
# GitHub's Ubuntu images do not name archive.ubuntu.com in the source
# entry itself: deb822 URIs are `mirror+file:/etc/apt/apt-mirrors.txt`, and
# apt walks azure.archive.ubuntu.com, then archive.ubuntu.com, then
# security.ubuntu.com. Pin every Ubuntu archive source — legacy one-line lists
# and deb822, any mirror hostname — to amd64, and add an arm64 snapshot source.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

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

# Host packages whose installed version must not be pulled backwards when the
# arm64 counterpart is co-installed. These are the Multi-Arch: same packages
# the nightly job tried to downgrade (the -dev packages plus the toolchain
# they version-lock). binutils itself is not a sentinel: we do not install
# binutils:arm64, and a lone older binutils index entry should not force the
# unpack path. The simulate step below still refuses a real downgrade.
sync_packages=(
  gcc-16-base
  libc6
  libc6-dev
  libavahi-client-dev
  libavahi-client3
  libgcc-s1
  libsqlite3-dev
  libssl-dev
  libssl3t64
  libstdc++6
  libsystemd-dev
  libsystemd0
)
arm_packages=(
  libc6-dev:arm64
  libavahi-client-dev:arm64
  libsqlite3-dev:arm64
  libssl-dev:arm64
  libsystemd-dev:arm64
)

# stdout: snapshot id. exit 0 = arm64 versions are >= installed (safe to
# apt-install). exit 2 = newest snapshot is older than the host (do not
# apt-install; unpack instead).
set +e
snapshot_id="$(python3 - "$codename" "${sync_packages[@]}" <<'PY'
import gzip
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

codename = sys.argv[1]
packages = sys.argv[2:]


def installed(pkg: str):
    result = subprocess.run(
        ["dpkg-query", "-W", "-f", "${Version}", pkg],
        capture_output=True,
        text=True,
    )
    version = result.stdout.strip()
    if result.returncode != 0 or not version:
        return None
    return version


def version_cmp(left: str, op: str, right: str) -> bool:
    return subprocess.run(
        ["dpkg", "--compare-versions", left, op, right]
    ).returncode == 0


installed_versions = {}
for pkg in packages:
    version = installed(pkg)
    if version is not None:
        installed_versions[pkg] = version
        print(f"installed {pkg} {version}", file=sys.stderr)


def fetch(snapshot: str) -> dict[str, str]:
    versions: dict[str, str] = {}
    pockets = (codename, f"{codename}-updates", f"{codename}-security")
    for pocket in pockets:
        for component in ("main", "universe"):
            url = (
                f"https://snapshot.ubuntu.com/ubuntu/{snapshot}"
                f"/dists/{pocket}/{component}/binary-arm64/Packages.gz"
            )
            request = urllib.request.Request(
                url, headers={"User-Agent": "klardrop-arm64-cross-deps"}
            )
            try:
                with urllib.request.urlopen(request, timeout=90) as response:
                    payload = gzip.decompress(response.read()).decode(
                        "utf-8", "replace"
                    )
            except Exception as exc:
                print(f"note: {url} ({exc})", file=sys.stderr)
                continue
            name = None
            for line in payload.splitlines():
                if line.startswith("Package: "):
                    name = line[len("Package: ") :].strip()
                elif line.startswith("Version: ") and name:
                    version = line[len("Version: ") :].strip()
                    previous = versions.get(name)
                    if previous is None or version_cmp(version, "gt", previous):
                        versions[name] = version
                    name = None
                elif line == "":
                    name = None
    return versions


def classify(versions: dict[str, str]) -> str:
    if not installed_versions:
        return "eq"
    any_newer = False
    for pkg, have in installed_versions.items():
        arm = versions.get(pkg)
        if arm is None:
            print(f"snapshot missing {pkg}", file=sys.stderr)
            return "missing"
        if version_cmp(arm, "lt", have):
            print(f"{pkg}: arm64 {arm} < installed {have}", file=sys.stderr)
            return "older"
        if version_cmp(arm, "gt", have):
            any_newer = True
            print(f"{pkg}: arm64 {arm} > installed {have}", file=sys.stderr)
        else:
            print(f"{pkg}: arm64 {arm} == installed", file=sys.stderr)
    return "ge" if any_newer else "eq"


now = datetime.now(timezone.utc)
# A few minutes back so the snapshot object exists; then a short walk if that
# timestamp was not published. Older than the first readable snapshot cannot
# be newer than the host, so the walk stops on "older".
candidates = []
for minutes in (20, 60, 180, 720, 1440):
    candidates.append((now - timedelta(minutes=minutes)).strftime("%Y%m%dT%H%M%SZ"))

for snapshot in candidates:
    print(f"checking snapshot {snapshot}", file=sys.stderr)
    versions = fetch(snapshot)
    if not versions:
        continue
    kind = classify(versions)
    if kind == "missing":
        continue
    if kind == "older":
        print(snapshot)
        sys.exit(2)
    print(snapshot)
    sys.exit(0)

print("no readable arm64 snapshot", file=sys.stderr)
sys.exit(1)
PY
)"
snapshot_rc=$?
set -e
printf '%s\n' "$snapshot_id" >&2
if [ "$snapshot_rc" -ne 0 ] && [ "$snapshot_rc" -ne 2 ]; then
  echo "error: could not choose an arm64 snapshot" >&2
  exit 1
fi
if [[ ! "$snapshot_id" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]; then
  echo "error: bad snapshot id '$snapshot_id'" >&2
  exit 1
fi

# Drop a previous live-ports source. Leaving it enabled lets apt prefer the
# lagged arm64 publish over the snapshot and bring the downgrade back.
sudo rm -f /etc/apt/sources.list.d/arm64-ports.sources
sudo tee /etc/apt/sources.list.d/arm64-cross.sources >/dev/null <<EOF
Types: deb
URIs: https://snapshot.ubuntu.com/ubuntu/${snapshot_id}
Suites: ${codename} ${codename}-updates ${codename}-security
Components: main universe
Architectures: arm64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
Snapshot: no
EOF

sudo apt-get update

install_from_apt() {
  sudo apt-get install -y \
    binutils \
    "${arm_packages[@]}"
}

simulate_install() {
  local log rc
  set +e
  log="$(sudo apt-get install -s -y binutils "${arm_packages[@]}" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "$log"
  if [ "$rc" -eq 0 ]; then
    return 0
  fi
  if printf '%s\n' "$log" | grep -q 'without --allow-downgrades\|will be DOWNGRADED'; then
    return 2
  fi
  return 1
}

verify_arm64_libs() {
  local missing=0 path
  for path in \
    /usr/lib/aarch64-linux-gnu/libssl.so \
    /usr/lib/aarch64-linux-gnu/libcrypto.so \
    /usr/lib/aarch64-linux-gnu/libsqlite3.so \
    /usr/lib/aarch64-linux-gnu/libavahi-client.so \
    /usr/lib/aarch64-linux-gnu/libsystemd.so \
    /usr/include/aarch64-linux-gnu/openssl/opensslconf.h
  do
    if [ ! -e "$path" ]; then
      echo "error: missing $path" >&2
      missing=1
    fi
  done
  if ! command -v readelf >/dev/null 2>&1; then
    echo "error: readelf is not installed" >&2
    missing=1
  fi
  [ "$missing" -eq 0 ]
}

unpack_arm64_debs() {
  local work root
  work="$(mktemp -d)"
  root="$work/root"
  mkdir -p "$root"
  python3 - "$codename" "$snapshot_id" "$root" "${arm_packages[@]}" <<'PY'
import gzip
import os
import subprocess
import sys
import urllib.request

codename, snapshot, root = sys.argv[1], sys.argv[2], sys.argv[3]
wanted = [pkg.split(":", 1)[0] for pkg in sys.argv[4:]]
base = f"https://snapshot.ubuntu.com/ubuntu/{snapshot}"
index = {}


def version_cmp(left: str, op: str, right: str) -> bool:
    return subprocess.run(
        ["dpkg", "--compare-versions", left, op, right]
    ).returncode == 0


for pocket in (codename, f"{codename}-updates", f"{codename}-security"):
    for component in ("main", "universe"):
        url = f"{base}/dists/{pocket}/{component}/binary-arm64/Packages.gz"
        request = urllib.request.Request(
            url, headers={"User-Agent": "klardrop-arm64-cross-deps"}
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = gzip.decompress(response.read()).decode("utf-8", "replace")
        except Exception as exc:
            print(f"note: {url} ({exc})", file=sys.stderr)
            continue
        record = {}
        last_key = None

        def store(record: dict) -> None:
            name = record.get("Package")
            version = record.get("Version")
            filename = record.get("Filename")
            if not name or not version or not filename:
                return
            previous = index.get(name)
            if previous is None or version_cmp(version, "gt", previous["Version"]):
                index[name] = record

        for line in payload.splitlines():
            if line == "":
                store(record)
                record = {}
                last_key = None
                continue
            if line[:1].isspace():
                if last_key is not None and line.strip():
                    record[last_key] = record.get(last_key, "") + " " + line.strip()
                continue
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            last_key = key
            record[key] = value.strip()
        store(record)


def depend_options(field: str) -> list[list[str]]:
    groups = []
    for part in field.split(","):
        options = []
        for alt in part.split("|"):
            token = alt.strip().split(" ", 1)[0]
            if token:
                options.append(token.split(":", 1)[0])
        if options:
            groups.append(options)
    return groups


selected = []
seen = set()


def walk(name: str) -> None:
    if name in seen:
        return
    record = index.get(name)
    if record is None:
        print(f"unresolved arm64 dependency {name}", file=sys.stderr)
        return
    seen.add(name)
    selected.append(record)
    for options in depend_options(record.get("Depends", "")):
        for option in options:
            if option in index:
                walk(option)
                break


for name in wanted:
    walk(name)

os.makedirs(root, exist_ok=True)
for record in selected:
    filename = record["Filename"]
    if not filename.endswith("_arm64.deb"):
        continue
    dest = os.path.join(root, os.path.basename(filename))
    url = f"{base}/{filename}"
    print(f"fetch {url}", file=sys.stderr)
    request = urllib.request.Request(
        url, headers={"User-Agent": "klardrop-arm64-cross-deps"}
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        with open(dest, "wb") as handle:
            handle.write(response.read())
    subprocess.run(["dpkg-deb", "-x", dest, root], check=True)
print(f"unpacked {len(selected)} arm64 packages", file=sys.stderr)
PY

  copy_tree() {
    local src="$1" dest="$2"
    if [ -d "$src" ]; then
      sudo mkdir -p "$dest"
      sudo cp -a "$src"/. "$dest"/
    fi
  }
  copy_tree "$root/usr/lib/aarch64-linux-gnu" /usr/lib/aarch64-linux-gnu
  copy_tree "$root/usr/include/aarch64-linux-gnu" /usr/include/aarch64-linux-gnu
  if [ -d "$root/lib/aarch64-linux-gnu" ] && [ ! -L "$root/lib" ]; then
    copy_tree "$root/lib/aarch64-linux-gnu" /lib/aarch64-linux-gnu
  fi
  # Fill host include paths the -dev packages would have created, without
  # replacing headers the amd64 packages already installed.
  if [ -d "$root/usr/include" ]; then
    while IFS= read -r -d '' path; do
      relpath="${path#"$root"/}"
      case "$relpath" in
        usr/include/aarch64-linux-gnu|usr/include/aarch64-linux-gnu/*) continue ;;
      esac
      dest="/$relpath"
      if [ -d "$path" ]; then
        sudo mkdir -p "$dest"
      elif [ ! -e "$dest" ]; then
        sudo mkdir -p "$(dirname "$dest")"
        sudo cp -a "$path" "$dest"
      fi
    done < <(find "$root/usr/include" -print0)
  fi
  if ! command -v readelf >/dev/null 2>&1; then
    sudo apt-get install -y binutils
  fi
  rm -rf "$work"
}

if [ "$snapshot_rc" -eq 0 ]; then
  set +e
  simulate_log="$(simulate_install)"
  simulate_rc=$?
  set -e
  printf '%s\n' "$simulate_log"
  if [ "$simulate_rc" -eq 0 ]; then
    install_from_apt
    verify_arm64_libs
    exit 0
  fi
  if [ "$simulate_rc" -ne 2 ]; then
    echo "error: arm64 cross library install failed" >&2
    exit 1
  fi
  echo "snapshot solver still wants a host downgrade; unpacking arm64 debs" >&2
else
  echo "newest arm64 snapshot is older than the host; unpacking arm64 debs" >&2
fi

unpack_arm64_debs
verify_arm64_libs
