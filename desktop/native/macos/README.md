# macOS BLE helper (klardrop-ble-helper)

A small Swift CLI that wraps Apple `CoreBluetooth` and exposes a newline-delimited
JSON protocol over stdin/stdout. Spawned by the desktop JVM at runtime so the
desktop app can take part in Klardrop BLE discovery + transfers on macOS, where
the JVM has no direct path to `CoreBluetooth`.

## Layout

```
desktop/native/macos/
├── README.md                                          (this file)
└── klardrop-ble-helper/
    ├── Package.swift                                  Swift Package manifest
    └── Sources/KlardropBleHelper/
        ├── main.swift                                 stdin loop + dispatcher
        ├── Protocol.swift                             JSON encoder/decoder
        ├── CentralController.swift                    CBCentralManager wrapper
        └── PeripheralController.swift                 CBPeripheralManager wrapper

desktop/src/jvmMain/resources/native/macos/
└── klardrop-ble-helper                                committed universal Mach-O
                                                      (loaded at runtime from
                                                      the classpath)
```

## Requirements

- macOS host (Apple Silicon or Intel — the script produces a universal binary).
- Xcode Command Line Tools installed:
  ```sh
  xcode-select --install
  ```
- Swift toolchain on `PATH`. Verify with `swift --version`.

You can develop the Swift sources from any text editor; opening
`Package.swift` in Xcode (`xed klardrop-ble-helper`) gives you SPM-aware
auto-complete and CoreBluetooth API lookup.

## Build

The single command that builds, signs, and installs the binary into the
desktop module's resources:

```sh
./scripts/build-mac-ble-helper.sh
```

What it does, in order:

1. `cd desktop/native/macos/klardrop-ble-helper`
2. `swift build -c release --arch arm64 --arch x86_64`
   — produces a universal release binary at
   `.build/apple/Products/Release/KlardropBleHelper`.
3. Copies the binary to
   `desktop/src/jvmMain/resources/native/macos/klardrop-ble-helper`.
4. `chmod +x` and ad-hoc codesigns (`codesign --force --sign -`) so macOS
   Gatekeeper allows the JVM to spawn it from the temp dir.

The script is idempotent — re-running it just overwrites the resource binary.

## Commit workflow

After modifying any file under `klardrop-ble-helper/Sources/`:

1. Run `./scripts/build-mac-ble-helper.sh`.
2. Commit BOTH the Swift source change AND the regenerated binary at
   `desktop/src/jvmMain/resources/native/macos/klardrop-ble-helper`. They
   travel together — the JVM ships the binary as a classpath resource.

The `desktop/native/macos/klardrop-ble-helper/.build/` directory is ignored
via the local `.gitignore`; only the Swift sources and the final installed
binary are tracked in git.

## Packaging into the desktop .dmg

The Swift binary is bundled automatically as a regular Compose-Desktop
classpath resource — there's no extra packaging step. When `:desktop:run`
or `:desktop:packageDmg` runs, Gradle picks up everything under
`desktop/src/jvmMain/resources/`, which includes
`native/macos/klardrop-ble-helper`.

At runtime, `HelperBinaryResolver` (in the desktop JVM module) extracts the
binary from the classpath to `${java.io.tmpdir}/klardrop-ble-helper-<sha256>`
on first BLE use and execs it via `ProcessBuilder`. The SHA-suffixed filename
means the temp file is content-addressed — multiple desktop installs and
helper upgrades coexist without stomping each other.

The desktop module's `compose.desktop.application.nativeDistributions.macOS`
block injects `NSBluetoothAlwaysUsageDescription` into the `.dmg`'s
`Info.plist`, which is required for `CoreBluetooth` to work without a
permission prompt failure.

## Running standalone (for debugging)

You can drive the helper by hand to test the JSON protocol:

```sh
./desktop/src/jvmMain/resources/native/macos/klardrop-ble-helper
> {"id":"1","cmd":"init"}
< {"ok":true,"id":"1"}
< {"event":"state","state":"poweredOn"}
> {"id":"2","cmd":"scan_start"}
< {"ok":true,"id":"2"}
< {"event":"peer_found","peerId":"<UUID>","shortDeviceId":"...","rssi":-65}
...
> {"id":"99","cmd":"shutdown"}
< {"ok":true,"id":"99"}
```

When run outside an app bundle, macOS may not deliver Bluetooth-state
callbacks until the user explicitly grants Bluetooth permission to the
controlling Terminal in System Settings → Privacy & Security → Bluetooth.

## Protocol reference

Synchronized with `MacBleHelperProtocol.kt` in the desktop JVM module.

- **Commands** (JVM → helper) carry `id` + `cmd`. Helper replies with
  `{"id":"<same>","ok":true,...}` or `{"id":"<same>","ok":false,"error":"<code>","message":"..."}`.
- **Events** (helper → JVM) carry no `id`; identified by `event` field:
  `state`, `peer_found`, `peer_lost`, `session_opened`, `chunk`,
  `session_closed`, `log`.
- All binary chunks are base64-encoded under `data`.

The protocol is intentionally OS-agnostic — a future Linux helper (BlueZ) or
Windows helper (WinRT) can speak the same wire format and slot in behind the
same JVM-side `MacBleHelperProcess` code.

## Lifecycle

- One helper process per `BleTransport` instance (one per app launch).
- JVM restarts the helper with exponential backoff (1s → 30s) on unexpected
  exit; after 5 crashes in 60 seconds the helper is parked and BLE reports
  unsupported for the remainder of the session.
- Each crash is reported through the project's logger, which feeds Bugsnag.
- `shutdown` command, JVM-side close, or stdin EOF (parent process death)
  terminates the helper cleanly.
