#!/usr/bin/env bash
#
# publish.sh - add a Klardrop .deb to the self-hosted, GPG-signed APT repo.
#
# Run by CI after the desktop .deb is built. It:
#   1. imports the signing key from APT_GPG_PRIVATE_KEY (ASCII-armored) into an
#      isolated, ephemeral GNUPGHOME, configured for non-interactive (loopback)
#      passphrase entry so reprepro can sign metadata without a tty/pinentry;
#   2. runs `reprepro includedeb stable <deb>` against the apt tree, removing any
#      previously-registered build of the same package first so re-running a
#      release (or re-tagging) does not hard-fail with "already registered";
#   3. exports the public key (binary .gpg keyring + ASCII .asc) to the apt tree
#      root so users can fetch it for `signed-by=`.
#
# Usage:
#   packaging/apt/publish.sh <deb-path> <apt-tree-dir>
# or via env (args take precedence):
#   DEB_PATH=... APT_TREE=... packaging/apt/publish.sh
#
# Required env (provided by CI secrets):
#   APT_GPG_PRIVATE_KEY  - ASCII-armored private key (the whole armored block)
#   APT_GPG_PASSPHRASE   - passphrase for that key
#
# The apt tree (<apt-tree-dir>) must already contain conf/distributions; in this
# repo that is packaging/apt/conf/distributions, which CI copies into the
# gh-pages `apt/` dir before invoking this script. reprepro creates db/, dists/
# and pool/ underneath it.

set -euo pipefail

# ---------------------------------------------------------------------------
# Resolve inputs (positional args override env).
# ---------------------------------------------------------------------------
DEB_PATH="${1:-${DEB_PATH:-}}"
APT_TREE="${2:-${APT_TREE:-}}"

if [ -z "${DEB_PATH}" ] || [ -z "${APT_TREE}" ]; then
  echo "usage: $0 <deb-path> <apt-tree-dir>" >&2
  echo "   (or set DEB_PATH and APT_TREE in the environment)" >&2
  exit 2
fi

if [ ! -f "${DEB_PATH}" ]; then
  echo "error: .deb not found: ${DEB_PATH}" >&2
  exit 1
fi

if [ ! -f "${APT_TREE}/conf/distributions" ]; then
  echo "error: ${APT_TREE}/conf/distributions missing - copy packaging/apt/conf there first" >&2
  exit 1
fi

if [ -z "${APT_GPG_PRIVATE_KEY:-}" ] || [ -z "${APT_GPG_PASSPHRASE:-}" ]; then
  echo "error: APT_GPG_PRIVATE_KEY and APT_GPG_PASSPHRASE must be set" >&2
  exit 1
fi

# Resolve to absolute paths: reprepro is invoked with -b <basedir> and we want
# the .deb path to stay valid regardless of cwd.
DEB_PATH="$(readlink -f "${DEB_PATH}")"
APT_TREE="$(readlink -f "${APT_TREE}")"

# Suite/codename and the binary package name must match conf/distributions and
# the .deb's Package field respectively.
SUITE="stable"
PKG="klardrop"

# ---------------------------------------------------------------------------
# Isolated GNUPGHOME so we never touch the runner's real keyring, and so the
# only secret key present is ours (lets reprepro's `SignWith: yes` pick it
# unambiguously). Cleaned up on exit.
# ---------------------------------------------------------------------------
GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
chmod 700 "${GNUPGHOME}"

cleanup() {
  # Best-effort: stop the agent and wipe the ephemeral keyring (it holds the
  # private key) so it never lingers on the runner.
  gpgconf --kill gpg-agent >/dev/null 2>&1 || true
  rm -rf "${GNUPGHOME}"
}
trap cleanup EXIT

# Configure gpg + gpg-agent for headless signing:
#   - pinentry-mode loopback     : gpg takes the passphrase from --passphrase
#                                  instead of launching a pinentry dialog.
#   - allow-loopback-pinentry    : the agent permits the above.
#   - long cache TTLs            : keep the primed passphrase available for the
#                                  whole run, since reprepro shells out to gpg
#                                  itself (without our --passphrase flag) and
#                                  relies on the agent's cached passphrase.
# These must be written BEFORE the agent starts, so we (re)start it afterwards.
cat > "${GNUPGHOME}/gpg.conf" <<'EOF'
pinentry-mode loopback
EOF
cat > "${GNUPGHOME}/gpg-agent.conf" <<'EOF'
allow-loopback-pinentry
default-cache-ttl 3600
max-cache-ttl 3600
EOF
gpgconf --kill gpg-agent >/dev/null 2>&1 || true

# Import the private key non-interactively.
printf '%s' "${APT_GPG_PRIVATE_KEY}" | gpg --batch --import

# Discover the imported key's fingerprint (used to select the key for signing
# and to export the public key).
KEY_FPR="$(gpg --list-secret-keys --with-colons \
  | awk -F: '/^fpr:/ { print $10; exit }')"
if [ -z "${KEY_FPR}" ]; then
  echo "error: no secret key found after import" >&2
  exit 1
fi
echo "Imported signing key: ${KEY_FPR}"

# Prime the gpg-agent passphrase cache with one throwaway signature. After this,
# the agent has the (loopback) passphrase cached for `default-cache-ttl`, so the
# reprepro-driven `gpg` invocations sign Release/InRelease without prompting.
# This also validates the passphrase up front for a clear error message.
echo "klardrop-apt-publish" \
  | gpg --batch --yes --pinentry-mode loopback \
        --passphrase "${APT_GPG_PASSPHRASE}" \
        --local-user "${KEY_FPR}" \
        --detach-sign -o /dev/null - \
  || { echo "error: test-sign failed - bad passphrase or key?" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Publish the .deb.
# ---------------------------------------------------------------------------
# Idempotency: a re-run of the same release tag would otherwise fail because the
# exact version is "already registered". Remove the package from the suite first
# (no-op the very first time / when absent), then include the new build. `remove`
# exits non-zero when the package is not present, which is fine on a fresh repo,
# so we swallow that with `|| true`.
echo "Removing any existing '${PKG}' from suite '${SUITE}' (idempotent re-run guard)..."
reprepro -b "${APT_TREE}" remove "${SUITE}" "${PKG}" || true

echo "Adding ${DEB_PATH} to suite '${SUITE}'..."
# --ignore=undefinedtarget keeps us resilient if the .deb declares a Section
# reprepro doesn't otherwise know; the package still lands in main/<arch>.
reprepro -b "${APT_TREE}" --ignore=undefinedtarget includedeb "${SUITE}" "${DEB_PATH}"

# ---------------------------------------------------------------------------
# Export the public key to the apt tree root so users can fetch it.
#   - klardrop-archive-keyring.gpg : binary OpenPGP keyring (apt's preferred form
#     for signed-by=).
#   - klardrop-archive-keyring.asc : ASCII-armored, convenient for curl|gpg or
#     manual inspection.
# ---------------------------------------------------------------------------
echo "Exporting public key to apt tree root..."
gpg --batch --yes --export "${KEY_FPR}" \
  > "${APT_TREE}/klardrop-archive-keyring.gpg"
gpg --batch --yes --armor --export "${KEY_FPR}" \
  > "${APT_TREE}/klardrop-archive-keyring.asc"

echo "Done. Repository updated at ${APT_TREE}"
echo "  dists/${SUITE}/  pool/  klardrop-archive-keyring.{gpg,asc}"
