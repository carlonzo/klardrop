# BLE Transport — Remaining Work

This file tracks the BLE transport tasks that were **not** finished in the initial implementation landed on branch `claude/add-direct-device-connection-DSkT1`.

## What is already done

Everything below is on the branch, compiles for Android + desktop JVM, and is covered by 30+ tests that run under `./gradlew :klardrop-common:desktopJvmTest`.

- `BleFraming`, `BleReassembler`, `BleRoleSelector` — wire-format chunking and lex-smaller-initiates role selection (`common/src/commonMain/.../ble/`).
- `Connection` is a sealed class with `Tcp` / `Ble` variants; `ConnectionMessenger` treats both identically.
- `BleSession` interface + `BleChannelBridge` that adapts a chunk-oriented GATT session to Ktor `ByteReadChannel` / `ByteWriteChannel`, so the existing `sendMessage` / `readMessage` pipeline is reused verbatim.
- Android GATT **central** + **peripheral** in `BleTransport.android.kt` (`BluetoothGatt` + `BluetoothGattServer`, MTU negotiation, CCCD subscribe, write-ack timeouts).
- `BleServerListener` hosts `serveGatt()` and registers inbound sessions in `ConnectionsPool` after handshake.
- `ClientImpl.establishBleConnection` tries TCP first, then BLE if `BleRoleSelector.shouldInitiate(self, peer)` says so.
- `MessengerImpl` routes BLE-only peers through the same `handleKlardropTransfer` path.
- `ConnectionInfoMessage` + Android `WifiNetworkSuggestion`-backed `ConnectionInfoJoiner`. Fallback joiner copies the password to the clipboard on other platforms.
- UI: "Share Wi-Fi credentials" in `ShareSheet`; "Join Wi-Fi" button on received `ConnectionInfoMessage` cards.
- Android manifest: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, legacy pre-31 fallbacks, `CHANGE_WIFI_STATE`, `uses-feature bluetooth_le required=false`.

**Verified working in code (not yet on real radios): Android ↔ Android pairing and Wi-Fi credential handoff.**

## Open tasks (ordered by leverage)

### 1. Apple BLE (iOS + macOS K/N) — highest priority

Unlocks: iPhone↔Android, iPhone↔iPhone, and macOS-native↔any. One implementation covers both targets because they share the `appleMain` source set.

**File to fill in:** `common/src/appleMain/kotlin/com/carlom/klardrop/common/ble/BleTransport.apple.kt` (currently stubs `connectCentral` / `serveGatt`).

**APIs** (cinterop already available via `platform.CoreBluetooth`):
- Central: `CBCentralManager`, `CBPeripheral`, `CBCentralManagerDelegateProtocol`, `CBPeripheralDelegateProtocol`.
- Peripheral: `CBPeripheralManager`, `CBMutableService`, `CBMutableCharacteristic`, `CBPeripheralManagerDelegateProtocol`.

**Implementation sketch:**

- Hold a single `CBCentralManager` and a single `CBPeripheralManager` per `BleTransport` instance. Both require a delegate; bridge the delegate callbacks to a `Channel<Event>` that coroutines consume.
- `startAdvertising`: build a `CBMutableService` with service UUID `BleConstants.SERVICE_UUID` + TX (write) and RX (notify) mutable characteristics matching `BleConstants.*_CHARACTERISTIC_UUID`. Call `CBPeripheralManager.startAdvertising(...)` with `CBAdvertisementDataServiceUUIDsKey` + `CBAdvertisementDataLocalNameKey = shortDeviceId`.
- `scanForPeers`: `CBCentralManager.scanForPeripheralsWithServices([serviceUuid], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])`. Emit `BlePeerEvent.Found(identifier.UUIDString, shortDeviceId, localName, RSSI)`.
  - **Gotcha**: iOS does not expose service-data AD in the background; get the shortDeviceId from the `CBAdvertisementDataLocalNameKey` or by connecting and reading a well-known characteristic.
- `connectCentral`: `CBCentralManager.connectPeripheral(peripheral)`, then in the delegate: `discoverServices` → `discoverCharacteristics(tx, rx)` → `setNotifyValue(true, rx)` → expose a `BleSession` that:
  - `sendChunk` — `writeValue(chunk, forCharacteristic: tx, type: CBCharacteristicWriteWithResponse)`; await `didWriteValueForCharacteristic`.
  - `receiveChunk` — consume chunks pushed by `didUpdateValueForCharacteristic(rx)`.
- `serveGatt`: `CBPeripheralManagerDelegate.didReceiveWriteRequests` → push chunk to per-central session; `updateValue(chunk, forCharacteristic: rx, onSubscribedCentrals: [central])` for outbound; on first `didSubscribeToCharacteristic(rx)` emit a new `BleSession` to the flow.

**iOS entitlements / Info.plist** — already in `iosApp/iosApp/Info.plist` from the scaffold commit: `NSBluetoothAlwaysUsageDescription`, `UIBackgroundModes = bluetooth-central, bluetooth-peripheral`. Verify after implementation.

**Bridging callback-heavy Obj-C delegates to coroutines** is the main challenge. Use `suspendCancellableCoroutine` for one-shot completions (connect, write ack, service discovery) and `Channel<ByteArray>` for streaming notifications, same pattern as `BleTransport.android.kt`.

**Estimate**: ~400 lines, one focused session on a Mac with Xcode.

---

### 2. iOS `NEHotspotConfiguration` for Wi-Fi join

**File:** new `common/src/iosMain/kotlin/com/carlom/klardrop/common/features/IosConnectionInfoJoiner.kt`, wire into `InternalPlatformDependencies.ios.kt` replacing the `FallbackClipboardConnectionInfoJoiner`.

**API:** `NEHotspotConfiguration(ssid:passphrase:isWEP:)` → `NEHotspotConfigurationManager.sharedManager.applyConfiguration(config, completionHandler:)`.

**Entitlement:** add `com.apple.developer.networking.HotspotConfiguration = true` to `iosApp/iosApp/iosApp.entitlements`.

**Estimate**: ~60 lines, 30 minutes.

---

### 3. Mac-JVM BLE via Swift helper binary + stdio IPC

Unlocks: Klardrop desktop (`compose.desktop`) running on macOS can use BLE.

**Helper binary (new)** — `desktop/src/main/swift/klardrop-ble-helper.swift` (needs Swift 5.5+, CoreBluetooth). Protocol: line-delimited JSON over stdin/stdout.

Request messages from JVM → helper:
```json
{"cmd": "scan_start"}
{"cmd": "scan_stop"}
{"cmd": "advertise_start", "shortDeviceId": "abc12345"}
{"cmd": "advertise_stop"}
{"cmd": "connect", "peerId": "uuid", "shortDeviceId": "..."}
{"cmd": "send_chunk", "sessionId": "...", "bytes": "base64..."}
{"cmd": "close_session", "sessionId": "..."}
```

Events from helper → JVM:
```json
{"event": "peer_found", "id": "uuid", "shortDeviceId": "...", "rssi": -55}
{"event": "peer_lost", "id": "uuid"}
{"event": "session_opened", "sessionId": "...", "mtu": 185}
{"event": "chunk", "sessionId": "...", "bytes": "base64..."}
{"event": "session_closed", "sessionId": "..."}
{"event": "error", "message": "..."}
```

**JVM side:** replace the stub in `common/src/desktopJvmMain/kotlin/com/carlom/klardrop/common/ble/BleTransport.desktopJvm.kt`. On `System.getProperty("os.name").startsWith("Mac")`, `ProcessBuilder` the packaged helper from the app bundle (`Contents/Resources/klardrop-ble-helper`). Wrap a `BleSession` around each `sessionId`.

**Build integration:**
- Build `klardrop-ble-helper` via `swiftc` as a separate Gradle task that runs only on macOS build hosts. Produce a universal binary: `swiftc -target arm64-apple-macos11 ...` then `lipo -create`.
- Ship it inside the Mac distribution only — see task #5 below for the packaging hook.

**Estimate**: ~200 lines Swift + 150 lines Kotlin IPC client + build glue. 1 full day on a Mac.

---

### 4. Linux-JVM BLE via BlueZ D-Bus

Unlocks: Klardrop desktop on Linux can use BLE.

**Library:** add to `desktop/build.gradle.kts`, guarded by `OperatingSystem.current().isLinux`:
```kotlin
dependencies {
  if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
    implementation("com.github.hypfvieh:dbus-java-core:5.1.0")
    implementation("com.github.hypfvieh:dbus-java-transport-jnr-unixsocket:5.1.0")
  }
}
```

**BlueZ interfaces to implement:**
- Register the app as a GATT application via `org.bluez.GattManager1.RegisterApplication`.
- Register an advertisement via `org.bluez.LEAdvertisingManager1.RegisterAdvertisement` with service UUID and local name.
- Expose `org.bluez.GattService1` + `org.bluez.GattCharacteristic1` D-Bus objects for TX/RX.
- Scan via `org.bluez.Adapter1.StartDiscovery` + listen to `InterfacesAdded` signals for `org.bluez.Device1` objects matching the service UUID.

**Requires:** BlueZ 5.41+ (advertising) and 5.50+ (LE-only mode). Most desktop distros since ~2018.

**Estimate**: ~500 lines, 1-2 days.

---

### 5. Per-OS Compose Desktop packaging

**File:** `desktop/build.gradle.kts` — `compose.desktop.application.nativeDistributions` block.

Intent: each installer bundles only the runtime deps needed for that OS, plus (on Mac) the Swift helper binary.

Sketch:
```kotlin
compose.desktop {
  application {
    nativeDistributions {
      macOS {
        bundleID = "com.carlom.klardrop"
        appResourcesRootDir.set(project.layout.buildDirectory.dir("native/mac"))
        // klardrop-ble-helper is built into build/native/mac/ by the swiftc task
      }
      linux {
        // No BLE helper; BlueZ JARs are included via conditional dependencies.
      }
      windows {
        // No BLE; mDNS-only build.
      }
    }
  }
}
```

Conditional Gradle dependencies (same file) route BLE-library JARs to the relevant OS only:
```kotlin
dependencies {
  val os = org.gradle.internal.os.OperatingSystem.current()
  if (os.isLinux) implementation(deps.dbus.java.core)
  // macOS deps are the Swift helper binary, not JARs.
  // Windows gets nothing.
}
```

Tasks to add:
1. `buildBleHelperMac` — invokes `swiftc` / `xcrun swift build`, produces universal binary into `build/native/mac/`.
2. `packageDmg` depends on `buildBleHelperMac` so the helper is in place before packaging.
3. Skip BLE helper on non-Mac build hosts.

**Estimate**: 1 day, mostly debugging the Gradle + Compose DSL interaction.

---

### 6. Windows WinRT BLE — **deferred (user approved)**

Windows desktop falls back to mDNS. If we later want BLE there:
- WinRT APIs: `BluetoothLEAdvertisementPublisher`, `GattServiceProvider`, `BluetoothLEAdvertisementWatcher`.
- Access from JVM: JNA with COM bridging, or Java 22+ `jextract` from the WinRT headers.
- Non-trivial — easily 1000+ lines. Revisit only if Windows users ask.

---

### 7. Real-device testing matrix

Once the platform implementations above land, run the manual matrix from the plan's verification section:

1. Android phone ↔ Android phone — advertise, discover, role-select, connect, handshake, send text, send `ConnectionInfoMessage`, tap "Join Wi-Fi", verify `WifiNetworkSuggestion` prompt.
2. Android ↔ iPhone.
3. Android ↔ native macOS.
4. Linux laptop (JVM) ↔ Android.
5. Mac laptop (JVM via Swift helper) ↔ Android.
6. With Wi-Fi disabled on one device to force the BLE path (otherwise TCP wins by default).

**Debugging tools:**
- **nRF Connect** (Android/iOS app) — inspects advertisements and GATT services from a third-party central.
- `bluetoothctl` / `hcitool lescan` on Linux.
- Android `logcat -s BluetoothGatt,BluetoothLeScanner,BleTransport.android,BleServerListener`.

---

## Known rough edges worth addressing when touching the code

- `MessengerImpl.send` does not currently show BLE-specific UI state (e.g. "connecting via Bluetooth…"). If BLE is slow on first connect, users see a generic spinner. Consider a progress hint.
- BLE reconnect on transient drop works by `ConnectionMessenger.isClosed` → `client.connectTo` chain, but we don't back off between retries. Add exponential backoff if users hit reconnect storms.
- `BleServerListener` accepts any handshake device id. The trust layer still gates `CONNECTION_INFO` / `CLIPBOARD_SYNC` messages, but untrusted peers can still open a session. Consider rejecting during handshake.
- MTU of 20 is used on the peripheral side if `onMtuChanged` fires after `onDescriptorWriteRequest`. Rare in practice but possible. Could buffer session emission until both events fire.

---

## Commits reference

| SHA | Summary |
|---|---|
| `b294cd8` | `BleFraming` + `BleRoleSelector` + tests |
| `305686b` | `Connection` sealed, `BleSession`, `BleChannelBridge` + 5 tests |
| `cc8f2bd` | Android GATT central + peripheral, Client/Server BLE wiring |
| `1439dcc` | UI + `ConnectionInfoJoiner` + Android `WifiNetworkSuggestion` |
| `ad172eb` | Write-ack timeouts, dead-code cleanup |
