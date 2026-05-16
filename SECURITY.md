# Security Policy

Klardrop transfers files between devices on the local network, so we take
network-facing bugs seriously. If you find a vulnerability, please report it
privately — don't open a public issue.

## How to report

- **Preferred:** open a private report via
  [GitHub Security Advisories](https://github.com/carlonzo/klardrop/security/advisories/new).
- **Email:** `carlo.marinangeli@gmail.com` with `[klardrop-security]` in the
  subject. Include reproduction steps, affected platforms, and the build/commit
  you tested against.

You should expect an acknowledgement within a few days. Klardrop is currently
maintained by one person, so timelines are best-effort.

## Scope

In scope:

- Remote code execution, memory-safety issues, or sandbox escapes triggered by
  a peer on the local network.
- Bugs that let a peer read or write files outside the intended share location.
- Issues in the Klardrop or Nearby Share protocol implementations
  (`UnifiedServer`, `ConnectionMessenger`, message handlers).
- Cryptographic mistakes in the pairing / trust / secret-store code.

Out of scope:

- Issues that require physical access to an unlocked device.
- Denial of service caused by a peer flooding the local network.
- Findings in third-party dependencies that aren't reachable from Klardrop's
  code paths — please report those upstream.

## Disclosure

Once a fix is available, we'll publish a GitHub Security Advisory with a CVE
where appropriate, credit the reporter (unless they prefer anonymity), and ship
the fix in the next release.
