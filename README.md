<p align="center">
  <img src="brand/klardrop-icon-256.png" alt="Klardrop" width="160" />
</p>

<h1 align="center">Klardrop</h1>

<p align="center">
  Cross-platform, peer-to-peer nearby sharing for Android, iOS, macOS, and desktop.
</p>

<p align="center">
  <a href="https://github.com/carlonzo/klardrop/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/carlonzo/klardrop?include_prereleases&label=release"></a>
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"></a>
  <img alt="Status: public beta" src="https://img.shields.io/badge/status-public%20beta-orange.svg">
</p>

---

## What is Klardrop?

Klardrop is an open-source nearby-sharing app — think AirDrop, but it works between
every device you own regardless of the operating system. Pick a file or a snippet of
text on one device, choose a nearby device, and it lands on the other side a moment
later. There is no account, no cloud, no upload step: bytes go directly from one
device to the other over the local network (or Bluetooth when Wi-Fi can't reach).

It's built with [Kotlin Multiplatform](https://kotlinlang.org/lp/multiplatform/) and
[Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/), so the
discovery, transfer, and UI code is shared across every target.

### Supported platforms

| Platform | Status | Notes |
|---|---|---|
| **Android** | ✅ | Full app with a system share-sheet extension |
| **iOS** | ✅ | Full app with a share extension |
| **macOS** | ✅ | Native app with a menubar presence |
| **Desktop (JVM)** | ✅ | Windows / Linux / macOS via the JVM build |

### What you can send

- Files of any type and size (streamed in chunks, with progress)
- Text snippets and links
- Clipboard contents (between paired devices)

## How it works

Klardrop has three jobs: **find** nearby devices, **trust** them once, and **move**
bytes between them.

### 1. Discovery — finding devices nearby

Klardrop simultaneously advertises itself and scans for other Klardrop instances on
every transport it has available:

- **mDNS over Wi-Fi** for devices on the same local network (service type
  `_klardrop._tcp.`). It can also discover devices speaking Google's Nearby Share
  protocol (`_FC9F5ED42C8A._tcp.`) for cross-app interop.
- **Bluetooth Low Energy** as a fallback medium when Wi-Fi isn't reachable. Both
  devices broadcast a well-known service UUID and exchange short device IDs in the
  scan-response so they can recognise each other without connecting first.

Every discovered device shows up in a live list with its name, OS, and device type.
The friendly name and metadata are only revealed *after* an authenticated handshake,
never over open airwaves.

### 2. Pairing — establishing trust

The first time two devices talk to each other, they go through a one-time pairing
flow that you confirm with a tap on both ends. Under the hood:

- Each side generates a fresh **ECDH (P-256)** key pair for the session and exchanges
  the public halves.
- Each side derives the same **shared secret** locally via ECDH — the secret itself
  never crosses the wire.
- Each side also presents its long-lived **ECDSA (P-256)** identity public key so
  future messages can be verified as coming from the same device.
- A short verification code derived from both public keys can be compared visually,
  protecting the pairing step against a man-in-the-middle attacker on the LAN.

Once paired, the shared secret and the peer's identity key are stored locally on
both sides and the devices recognise each other automatically forever after.

### 3. Transfer — moving the bytes

Klardrop uses its own compact wire format: a 4-byte length prefix, a 1-byte message
type, and a Protocol Buffer payload. The same format flows over every transport
(Wi-Fi TCP, Nearby Share TCP, BLE GATT), so the rest of the system doesn't have to
care which medium the bytes travelled over.

For files, the sender announces the file metadata, the receiver acknowledges
(`ACK_READY`), and the payload is streamed in 32 KB chunks with live progress on
both ends. Every chunk carries an authentication tag (see below), and the whole
transfer is acknowledged when complete (`ACK_RECEIVED`). If a connection drops,
the sender retries with exponential back-off and rebuilds the connection from
scratch.

The wire format, message types, and ACK state machine are documented in detail in
[`KLARDROP_PROTOCOL.md`](KLARDROP_PROTOCOL.md).

## Security

Klardrop is built to feel like dropping a file across the room — and the security
model is designed around that mental image: bytes only move between your devices
after **you've explicitly approved that they know each other**, and they only ever
travel **directly between those two devices**.

- **Peer-to-peer, no server in the middle.** Klardrop has no backend. Transfers go
  directly from one device to the other over your local Wi-Fi or a direct BLE link.
  Your files never touch our infrastructure — there is no infrastructure.
- **Explicit pairing with confirmed identity.** Two devices can't talk to each other
  in earnest until you've paired them via an on-device prompt on both ends. Pairing
  performs an ECDH (P-256) key exchange that gives both sides a shared secret derived
  locally — the secret itself is never transmitted — and binds it to each device's
  ECDSA (P-256) identity key. From that point on, only your paired devices can
  impersonate or be impersonated by each other.
- **End-to-end message authentication.** Every message exchanged between paired
  devices is wrapped in a `TrustedMessage` envelope carrying an ECDSA signature, a
  timestamp, and a random nonce. The receiver verifies the signature against the
  pinned identity key, rejects stale timestamps, and rejects nonces it has already
  seen — so tampered, replayed, or spoofed messages are dropped before they reach
  the app.
- **Per-chunk file integrity.** Large file transfers are protected chunk-by-chunk
  with HMAC-SHA256 tags keyed by a per-pair key derived from the ECDH shared secret
  via HKDF-SHA256. The MAC binds each chunk to its position in the transfer, so an
  attacker can't reorder, splice, or replay chunks between transfers — any mismatch
  fails the entire transfer immediately.
- **Identity keys live in the platform-secure store.** On every platform, your
  device's long-lived private signing key is held by the OS keystore — Android
  Keystore, iOS / macOS Keychain, and the OS-secure store on desktop — so it never
  leaves the device and never appears in app storage in plaintext.
- **Minimal exposure on the wire before pairing.** BLE advertisements carry only a
  random 8-character device id and the service UUID — your device name, OS, and
  device type are only revealed after the authenticated Klardrop handshake.

> **A note on transport encryption.** Klardrop's threat model is "trusted devices on
> a network you control" and the current focus is authenticated integrity rather
> than confidential transport: messages between paired devices are signed and file
> chunks are MAC'd, but payloads themselves are not yet symmetrically encrypted on
> the wire. Adding an authenticated-encryption layer (AES-GCM / ChaCha20-Poly1305)
> keyed from the same ECDH secret is on the roadmap; until then, prefer running
> Klardrop on networks you trust (your home / work Wi-Fi, your hotspot, or BLE),
> rather than on shared open Wi-Fi.

If you find a security issue, please follow the private reporting instructions in
[`SECURITY.md`](SECURITY.md) rather than posting a public proof-of-concept.

## Install

Binaries are published to [GitHub Releases](https://github.com/carlonzo/klardrop/releases/latest)
on every tagged version.

| Platform | Format | Notes |
|---|---|---|
| macOS    | `.dmg`     | Unsigned during beta — run `xattr -dr com.apple.quarantine /Applications/Klardrop.app` after first launch if Gatekeeper blocks it |
| Windows  | `.msi`     | Unsigned during beta — SmartScreen will warn on first run |
| Linux    | `.deb`     | Debian / Ubuntu / Mint; AppImage planned |
| Android  | `.apk`     | Sideload; Play Store internal-testing track planned |
| iOS      | TestFlight | Public invite link forthcoming |

The desktop app checks for updates on launch via the `latest.json` manifest published
alongside each release.

## Repository layout

| Module | Purpose |
|---|---|
| `common/` | Shared business logic: discovery, transport, file management, trust, DI |
| `common-ui/` | Shared Compose Multiplatform UI |
| `protos/` | Protocol Buffer definitions for the wire format and Nearby Share interop |
| `android/` | Android app + share extension |
| `iosApp/` | iOS app + share extension |
| `macos/` | Native macOS app |
| `desktop/` | JVM desktop app (Windows / Linux / macOS) |
| `cli/` | Command-line client (see [`cli/README.md`](cli/README.md)) |

## Building and running

Requires **JDK 21**. For mobile / native targets you also need the platform toolchain
(Xcode for iOS and macOS, the Android SDK for Android). Everything else — Kotlin,
Compose, Ktor, jmDNS, the cryptography library, the protobuf generator — is pulled in
by Gradle, so there's no separate setup step.

```bash
# Desktop (JVM) on the current host
./gradlew :desktop:run
# or, equivalently:
./run-desktop.command

# Android (install on a connected device or emulator)
./gradlew :android:installDebug

# Command-line client
./gradlew :cli:run --args="--help"

# macOS / iOS — open in Xcode
open iosApp/iosApp.xcworkspace

# Tests
./gradlew test
```

## Contributing

Klardrop is a personal project: the codebase is being tidied
in the open. Issues and pull requests are welcome, especially:

- Bug reports with reproducible steps
- Platform-specific fixes (BLE quirks, mDNS edge cases, share-extension oddities)
- Security review of the trust / pairing flow
- UI polish on the shared Compose screens

## Acknowledgements

Huge thanks to [@grishka](https://github.com/grishka) and his project
[**NearDrop**](https://github.com/grishka/NearDrop). Klardrop's Nearby Share /
Quick Share support — and the ability to send to and receive from Android — is
only possible because grishka reverse-engineered Google's protocol and wrote it
up in [`PROTOCOL.md`](https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md).
The Kotlin port that lives in this repo started as a translation of his Swift
implementation; the wire format, the UKEY2 handshake glue, the paired-key dance,
and the payload-layer framing all follow his lead. Thank you for the work and
for sharing it openly.

## License

[Apache License 2.0](LICENSE) © 2022–2026 Carlo Marinangeli.
