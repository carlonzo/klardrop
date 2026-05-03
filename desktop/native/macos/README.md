# macOS BLE helper (klardrop-ble-helper)

A small Swift CLI that wraps Apple `CoreBluetooth` and exposes a newline-delimited
JSON protocol over stdin/stdout. Spawned by the desktop JVM at runtime so the
desktop app can take part in Klardrop BLE discovery + transfers on macOS, where
the JVM has no direct path to `CoreBluetooth`.

## Layout

- `klardrop-ble-helper/Package.swift` — Swift Package Manager manifest.
- `klardrop-ble-helper/Sources/KlardropBleHelper/` — Swift sources.
- `../../src/jvmMain/resources/native/macos/klardrop-ble-helper` — committed
  prebuilt universal binary loaded at runtime from the classpath.

## Building

```sh
./scripts/build-mac-ble-helper.sh
```

Builds a universal `arm64`+`x86_64` Mach-O executable, ad-hoc codesigns it, and
overwrites the resource binary. Requires macOS + Xcode command line tools.

After regenerating, commit the updated binary alongside the Swift source change.

## Protocol

Synchronized with `MacBleHelperProtocol.kt` in the desktop JVM module.

- Commands carry `id` + `cmd`; helper replies with `{"id", "ok": true|false, ...}`.
- Events have no `id` and carry `event` + payload. They include
  `state`, `peer_found`, `peer_lost`, `session_opened`, `chunk`,
  `session_closed`, `log`.
- All binary chunks are base64-encoded under `data`.

The protocol is intentionally OS-agnostic — a future Linux helper (BlueZ) or
Windows helper (WinRT) can speak the same wire format.

## Lifecycle

- One helper process per `BleTransport` instance (one per app launch).
- The JVM restarts the helper with exponential backoff on unexpected exit.
- Crashes are logged through `BugsnagWrapper.notify` from the JVM side.
- `shutdown` command, JVM-side close, or stdin EOF terminates the helper cleanly.
