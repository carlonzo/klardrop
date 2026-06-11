#!/usr/bin/env bash
# Sync the Linux test box (10.79.71.140) to the EXACT commit checked out on this Mac.
# Transport: GitHub origin (git@github.com:carlonzo/klardrop.git). Run before any live test
# round so the Linux desktop/CLI + adb-driven Android run the same code state as here.
#
# Usage:  .reliability/sync-linux.sh
#   Optional: LINUX_REPO=/path/to/klardrop .reliability/sync-linux.sh   (skip auto-discovery)
set -euo pipefail

LINUX="carlo@10.79.71.140"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
LOCAL_SHA="$(git rev-parse --short HEAD)"

echo ">> Local: ${BRANCH} @ ${LOCAL_SHA}"
echo ">> Pushing to origin..."
git push origin "${BRANCH}"

REPO="${LINUX_REPO:-}"
if [ -z "${REPO}" ]; then
  echo ">> Discovering klardrop repo on ${LINUX}..."
  REPO="$(ssh "${LINUX}" 'for d in ~/klardrop ~/Projects/klardrop ~/dev/klardrop ~/code/klardrop /home/carlo/klardrop; do if [ -d "$d/.git" ]; then echo "$d"; break; fi; done')"
fi
if [ -z "${REPO}" ]; then
  echo "ERROR: klardrop repo not found on ${LINUX}. Set LINUX_REPO=/abs/path and retry (or clone it there)." >&2
  exit 1
fi
echo ">> Linux repo: ${REPO}"

REMOTE_SHA="$(ssh "${LINUX}" "cd '${REPO}' \
  && git fetch origin --quiet \
  && (git checkout '${BRANCH}' --quiet 2>/dev/null || git checkout -b '${BRANCH}' 'origin/${BRANCH}' --quiet) \
  && git reset --hard 'origin/${BRANCH}' --quiet \
  && git rev-parse --short HEAD")"

if [ "${REMOTE_SHA}" = "${LOCAL_SHA}" ]; then
  echo ">> OK: Linux synced to ${BRANCH} @ ${REMOTE_SHA} (matches Mac)."
else
  echo "WARN: Linux at ${REMOTE_SHA} but Mac at ${LOCAL_SHA} — mismatch (did a new commit land mid-sync?)." >&2
  exit 2
fi
