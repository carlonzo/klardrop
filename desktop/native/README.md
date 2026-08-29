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

## macOS (helper process)

macOS uses the out-of-process Swift helper (`klardrop-ble-helper`) because the
JVM has no path to CoreBluetooth — see [`macos/README.md`](macos/README.md). Its
NDJSON stdin/stdout protocol is intentionally OS-agnostic and remains the
documented alternative for a future Windows (WinRT) helper; the in-process
dbus-java approach above is preferred wherever the JVM can reach the Bluetooth
stack directly.