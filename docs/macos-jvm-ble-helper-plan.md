# macOS JVM Bluetooth Helper Plan

## Summary
Implement foreground BLE communication between the macOS JVM desktop app and Android/iOS Klardrop apps by adding a Swift CoreBluetooth helper process for macOS JVM and completing the shared Apple BLE transport for iOS. The desktop JVM app will use the helper over newline-delimited JSON IPC, while Android and iOS use native platform BLE implementations behind the existing `BleTransport` / `BleSession` contracts.

## Key Changes
- Add a Swift helper binary for macOS JVM:
  - Uses `CoreBluetooth` central + peripheral roles.
  - Advertises/scans Klardrop peers using `BleConstants.SERVICE_UUID`.
  - Hosts the GATT service with existing TX/RX characteristics.
  - Sends/receives BLE chunks and reports sessions/events over stdin/stdout JSON.
- Replace the macOS branch of `BleTransport.desktopJvm.kt`:
  - Launch helper via `ProcessBuilder`.
  - Implement `isSupported`, `startAdvertising`, `stopAdvertising`, `scanForPeers`, `connectCentral`, and `serveGatt`.
  - Wrap helper sessions as `BleSession` objects consumed by existing `BleChannelBridge`, `Client`, and `BleServerListener`.
- Implement `BleTransport.apple.kt` for iOS foreground support:
  - Use `CBCentralManager` for scan/connect/discover/notify.
  - Use `CBPeripheralManager` for advertise/GATT server/write/notify.
  - Match the same Klardrop service/characteristic UUIDs and chunk semantics as Android/helper.
- Normalize advertisement compatibility:
  - Android advertises service data with short device id.
  - Apple/macOS helper scans broadly enough in foreground to detect Android service-data advertisements and Apple service UUID advertisements.
  - Peer identity remains `shortDeviceId`; address remains platform-specific (`BluetoothDevice` MAC on Android, `CBPeripheral.identifier` UUID on Apple/macOS helper).

## Public Interfaces / Protocol
- Keep existing Kotlin public contracts unchanged:
  - `BleTransport`
  - `BleSession`
  - `BlePeerEvent`
  - `BleConstants`
- Add private JVM-helper IPC protocol:
  - Commands: `scan_start`, `scan_stop`, `advertise_start`, `advertise_stop`, `connect`, `send_chunk`, `close_session`, `shutdown`.
  - Events: `ready`, `peer_found`, `peer_lost`, `session_opened`, `chunk`, `session_closed`, `error`.
  - Binary BLE chunks encoded as Base64.
  - Every request has a `requestId`; one-shot operations return `ok` or `error` with that id.
- Helper lifecycle:
  - One helper process per `BleTransport` instance.
  - Restart once on unexpected helper exit; after second failure, report BLE unsupported and log the error.
  - Close helper process when transport is closed or app exits.

## Build / Packaging
- Add Swift source under the desktop module, for example `desktop/src/main/swift/klardrop-ble-helper.swift`.
- Add Gradle tasks:
  - `compileMacBleHelperDebug` for local `:desktop:run`.
  - `compileMacBleHelperRelease` for packaged desktop distributions.
  - Only run these tasks on macOS hosts.
- Bundle helper into desktop resources and resolve it in this order:
  - App bundle resource path for packaged app.
  - Gradle build output path for `:desktop:run`.
- Add macOS bundle Bluetooth usage text for packaged desktop builds:
  - `NSBluetoothAlwaysUsageDescription`.
- Do not implement Linux/Windows BLE in this plan; keep those as unsupported JVM desktop platforms.

## Test Plan
- Unit tests:
  - Kotlin IPC parser/dispatcher handles request-response, events, helper exit, malformed JSON, and session close.
  - `BleSession` wrapper enforces MTU, closed-session behavior, chunk routing, and write acknowledgements.
  - Apple/helper advertisement parsing accepts Android service-data advertisements and Apple service UUID/local-name advertisements.
- Integration tests:
  - Fake helper process test for desktop JVM `BleTransport`: scan emits `BlePeerEvent.Found`; connect returns usable `BleSession`; chunks round-trip.
  - Existing `BleChannelBridge` tests continue passing.
  - Existing `:klardrop-common:desktopJvmTest` continues passing.
- Device acceptance:
  - Android app open + macOS JVM desktop app open: both advertise/scan; Android log shows desktop BLE peer, desktop log shows Android BLE peer.
  - Android to macOS JVM: connect, handshake, send text message over BLE when TCP is unavailable.
  - macOS JVM to Android: connect, handshake, send text message over BLE when TCP is unavailable.
  - iOS app open + macOS JVM desktop app open: same discovery and text-message transfer both directions.
  - Android/iOS/macOS JVM all open: no duplicate connection churn; `BleRoleSelector` prevents both sides initiating the same BLE connection.

## Assumptions
- v1 supports foreground BLE only; background iOS/macOS behavior is out of scope.
- End-to-end success requires implementing both macOS JVM helper support and iOS `BleTransport.apple.kt`; Android already has the baseline implementation but may need small compatibility fixes.
- The Bluetooth payload remains the existing Klardrop length-prefixed message stream over BLE chunks; no new app-level message protocol is introduced.
- macOS JVM is the desktop target for this work; the native `:macos` app remains separate and does not need to become feature-complete in this plan.
