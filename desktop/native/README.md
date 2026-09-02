# Desktop native BLE backends

Platform-specific BLE plumbing for the desktop JVM app. The shared `BleTransport`
picks the backend for the current OS at runtime.

## Linux (BlueZ over D-Bus, in-process)

The Linux BLE transport talks to `org.bluez` directly from the JVM process via
[dbus-java](https://github.com/hypfvieh/dbus-java) (pinned at 5.2.0 in
`gradle/dependencies.toml`; 6.x is not released yet). No helper process and no
native library: the D-Bus system-bus socket is opened in-process over dbus-java's
native Unix-socket transport, and dbus-java's built-in ObjectManager support is
reused for BlueZ's object tree. The library ships JDK 17+ bytecode, which runs
fine on the repo's JDK 21 toolchain.

Requirements on the Linux host:

- **BlueZ ≥ 5.48** — the `LEAdvertisingManager1` D-Bus API is stable from 5.48;
  the GATT D-Bus API is stable since 5.42, so older BlueZ may serve one role but
  not both.
- **Kernel ≥ 3.18** — LE advertising support in the Bluetooth stack.
- **Unprivileged D-Bus system-bus access** — the app connects to the system bus
  as the logged-in user; the default `org.bluez` D-Bus policy allows this without
  root.
- **Adapter powered by the desktop session** — Klardrop does not toggle
  `Adapter1.Powered` itself. If the adapter is off or BlueZ is unreachable, the
  capability probe fails early with a clear message; switch Bluetooth on from
  your desktop environment first.

Implementation lives in
`common/src/desktopJvmMain/kotlin/com/carlom/klardrop/common/ble/linux/`
(`LinuxBlueZTransport`, `BlueZConnection`, `BlueZCentralFacade`,
`BlueZPeripheralFacade`).

### dbus-java traps

Four API shapes here fail silently rather than loudly, and none of them shows up
in a fake-facade test — `BlueZDBusContractTest` pins each one:

- **`addSigHandler(Class, String, handler)`** — that `String` is the *sender's
  unique bus name* (validated against `^:[0-9]*\.[0-9]*$`), not an object path.
  Subscribe by object path with a `DBusMatchRuleBuilder` rule instead, and never
  pin the sender: BlueZ's signals arrive under its unique name, so a rule reading
  `sender='org.bluez'` passes the daemon and is then dropped by dbus-java's own
  client-side re-check.
- **`ObjectManager.InterfacesAdded/Removed`** — `objectPath` is the *emitting*
  path, which is `/` for every device since BlueZ emits from its root
  ObjectManager. The added/removed device path is the signal's first argument,
  `signalSource`.
- **`Properties.Get`** — its return type is a type variable, and dbus-java
  unwraps the wire `Variant` for those, so the value arrives bare. Casting the
  result to `Variant` yields null every time.
- **`@DBusProperty`** — introspection metadata only. An exported object answers
  `Get`/`GetAll` only if it implements `Properties` (what the exported GATT and
  advertisement objects do) or annotates getters `@DBusBoundProperty`. This is
  load-bearing for `LEAdvertisement1`, whose entire payload BlueZ reads via
  `GetAll` during `RegisterAdvertisement`.

## macOS (helper process)

macOS uses the out-of-process Swift helper (`klardrop-ble-helper`) because the
JVM has no path to CoreBluetooth — see [`macos/README.md`](macos/README.md). Its
NDJSON stdin/stdout protocol is intentionally OS-agnostic and remains the
documented alternative for a future Windows (WinRT) helper; the in-process
dbus-java approach above is preferred wherever the JVM can reach the Bluetooth
stack directly.