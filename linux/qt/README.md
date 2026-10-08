# Klardrop Portable Qt Quick UI (Linux)

Standalone Qt Quick desktop frontend for Klardrop on Linux, communicating over loopback HTTP with the Klardrop daemon (`ControlPlane`).

## Architecture & Design
- **Presentation (Qt Quick 6)**: Resizable desktop window (minimum 760x480, default 1000x700) using standard Qt Quick Controls 2 (Fusion style) without Quickshell or Omarchy dependencies.
- **Client Bridge (`ClientBridge` C++)**: Control file resolution, Bearer authentication, loopback requests via `QNetworkAccessManager` (`QNetworkProxy::NoProxy`), async `/state` long-polling, action concurrency, URL sanitization, and local desktop services.
- **Security & Connection Rules**:
  - Resolves `$XDG_RUNTIME_DIR/klardrop/control.json` else `$HOME/.cache/klardrop/control.json` (supports `--control-file <path>`).
  - Strict numeric port (1..65535) and token validation (`\A[A-Za-z0-9_\-\.~]+\z`, rejecting trailing LF/CRLF). Rejects non-regular or oversized (>4096 bytes) files.
  - Targets `127.0.0.1`, sets `NoProxy`, disables automatic redirects, and explicitly rejects 3xx responses.
  - Async `/state` long-poll (35s timeout) with bounded backoff (1s..16s) on errors or malformed payloads without busy-spinning.
  - Memory safety: held poll detaches and aborts cleanly across credential rotations and window close without SIGSEGV or shared dangling pointers.
  - Focus & History: `/active-chat` side-effects are omitted in this frontend; window focus strictly gates `/history/read`.

## Packaging, Baseline & Lifecycle
- **Baseline**: Pinned honestly to Ubuntu 24.04 LTS system Qt runtime (Qt 6.4.2, `qt6-base`, `qt6-declarative`, QML modules `QtQml` (`qml6-module-qtqml`), `Models` (`qml6-module-qtqml-models`), `Controls`, `Layouts`, `Templates`, `Window`, `WorkerScript` (`qml6-module-qtqml-workerscript`), and `qt6-wayland`).
- **Distribution Model**: System Qt package runtime (no runtime bundling). Release tarballs stage `bin/klardrop-qt`, `bin/klardrop-qt-launcher`, `.desktop` entry, and hicolor icons, next to the two non-Qt binaries with their own names: `bin/klardrop-engine` (the Kotlin/Native daemon this UI talks to, and what `klardrop-qt-launcher` asks systemd to start) and `bin/klardrop` (the Rust CLI client). The frontend never starts a daemon itself; it only ever reads the control file.
- **Single Instance & Activation**: Uses atomic `QLockFile` in runtime dir per hashed session control file + local domain socket IPC (`QLocalServer` / `QLocalSocket`). Repeated launcher invocations activate and raise existing UI rather than launching duplicate instances.
- **System Tray & Window Lifecycle**: Native `QSystemTrayIcon` with Open and Quit actions. Closing the window hides to the tray if a system tray is available on the system; otherwise exits cleanly so no invisible background GUI remains. Quit action closes the UI only (never stops or restarts the daemon).
- **User-Launch Only**: UI is launched explicitly by the user (`klardrop-qt-launcher` or application menu). Never autostarted on login to respect daemon memory budget.
- **Daemon-Backed Updates**: Integrated with daemon endpoints (`/state.update`, `/update/check`, `/update/apply`). Instructs user to quit and reopen the UI after successful update since running binary process mappings do not change in-place. If an update apply is requested while transfers are active (HTTP 409), the user is notified to wait until active transfers finish and retry manually (no automatic retry or forced transfer abort).
- **Self-Updater Integration**: Native updater pairs engine + Qt frontend updates with rollback on failure to prevent version mismatch. Retains recovery backups when restore fails for actionable manual recovery.

## Build Instructions (Out-of-Tree)
```bash
mkdir -p /tmp/build-klardrop-qt && cd /tmp/build-klardrop-qt
qmake6 /path/to/klardrop/linux/qt/klardrop-qt.pro
make -j$(nproc)
# Binary produced: /tmp/build-klardrop-qt/klardrop-qt
```

## Running & Testing
- **Run with live daemon**: `/tmp/build-klardrop-qt/klardrop-qt`
- **Run with isolated control file**: `/tmp/build-klardrop-qt/klardrop-qt --control-file /path/to/control.json`
- **Run preflight check**: `/tmp/build-klardrop-qt/klardrop-qt --check-runtime` (runs offscreen QML import validation with disk cache disabled, bypassing IPC, control file, and locks)
- **Run integration test suite** (builds out-of-tree in `/tmp`, runs offscreen harness & captures screenshot):
  ```bash
  python3 linux/qt/tests/test_integration.py
  ```

## Implemented User Flows
- Device selection, chat history display, and keyset paging.
- PlainText rendering across dynamic peer names, messages, filenames, and notifications (no HTML injection).
- Sending text, clipboard, and files via native file picker and drag-and-drop (`DropArea`).
- Pairing workflow: nearby pairing, incoming pair confirmation dialog, error dismissal.
- Unpair confirmation dialog before forgetting a trusted peer.
- Incoming transfers: status-based display (`Completed` -> Open, `PendingAuthorization` -> Accept/Decline, Dismiss).
- Peer revoked trust notifications with "Pair Again" and "Dismiss" actions.
- QR code file sharing: regular file selection, integer-cell Canvas matrix rendering with >=4-cell quiet zone, countdown timer, daemon active sync.
- Settings: background discovery toggle (honoring `supportsBackgroundDiscovery`), device renaming.
- Update notifications: update availability, status, error reporting, and safe action execution.

## Automated Verified Flows (Host Qt 6.11.2 Offscreen)
- *Scope Notice*: Tested strictly on host Qt 6.11.2 with `-platform offscreen`; no untested claims of universal Linux display server portability or older unvalidated distros.
- Authentic control discovery, port/token validation (including LF/CRLF rejection), Bearer auth, and credential rotation.
- In-flight held `/state` poll credential rotation without SIGSEGV; asserts client reconnected and used rotated token.
- State long-polling, trusted and nearby device lists, reachability status, and unread badges.
- Window focus / active lifecycle: gates `/history/read`, suppresses read while inactive, verifies subsequent unread read-marks, no `/active-chat`.
- Local path validation: rejects remote hosts and unencoded `#` in file URIs, preserves `%23`, spaces, and UTF-8.
- Offscreen preview screenshot captured with message filter catching all QML warnings (0 component/binding errors).
- Composer draft retention & send regression: verifies draft retained on failure, cleared on successful same-draft ack, newer edits & whitespace preserved, and duplicate sends blocked.
- Single-instance activation and offscreen `--check-runtime` mode isolation.

## Deliberate Ceilings & Limits
- **Action Concurrency**: Capped at 8 concurrent in-flight requests.
- **Response Size**: Capped at 4 MB stream reading buffer for actions and state polls.
- **History Retention**: Loaded chat history grows with requested pages (not pruned on page fetch); bounded window ceiling deferred until memory pressure is observed.
- **Mutating Actions**: Never retried automatically.
- **Binary Mappings After Update**: UI does not restart itself; user must quit and reopen.

## Deferred Parity Gates
1. **Host Installs & Docker Matrix**: Automated testing across multiple physical distros/containers (host testing only; Docker permission unavailable).
2. **Native Automatic X11 Clipboard / BLE Parity**: Remotely automated clipboard sync and BLE background discovery parity remain future gates.
3. **Published Package Swap**: The installer default has switched to native (Omarchy / Qt); published package repositories (AUR `klardrop-bin` still JVM, native templates unpublished) remain deferred until separate package migration.
4. **Video Thumbnails & Problem Reporting**: Thumbnail generation (`/thumbnail`) and problem reporting (`/report-problem`).
