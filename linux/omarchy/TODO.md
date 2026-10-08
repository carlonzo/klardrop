# Klardrop for Omarchy: Implementation Checklist

Omarchy shell integration for Klardrop: a QML shell plugin talks over a loopback, token-authenticated HTTP API to a headless `klardrop daemon` (running on JVM now, Kotlin/Native `linuxX64` later).

## Phase 1: control API + headless daemon (JVM)
- [x] 1. Promote DebugControl into the production control API:
  - [x] Require a random token (Bearer header) on every request.
  - [x] Write `{port, token}` to `$XDG_RUNTIME_DIR/klardrop/control.json` with mode 0600.
  - [x] Bind loopback only (127.0.0.1).
  - [x] Gate debug-only endpoints (`/reset-identity`, `/logs`, `/window`) to debug builds.
- [x] 2. Add endpoints needed by the UI:
  - [x] `GET /history?device=`, backed by DeviceChatViewModel / DB.
  - [x] `POST /rename-device` → `saveCustomDeviceName`.
  - [x] `POST /settings` (background discovery, etc.).
  - [x] `POST /send-clipboard`.
  - [x] `GET /state?since=<version>`: long-poll returning when state changes.
- [x] 3. Add `klardrop daemon` to `:cli` (starts Klardrop + DiscoveryController + control API, no Compose/tray).
- [x] 4. Make `klardrop share <paths…> [--to <id>] [--text …] [--clipboard]` send through the running daemon (without `--to`, pick paired device via `omarchy-menu-select`).
- [x] 5. Verify incoming pair and transfer requests use desktop notifications with Accept/Reject actions (current `Notifier` path).

## Phase 1b: Omarchy shell plugin (QML)
- [x] 1. Create `linux/omarchy/plugin/manifest.json` (id `klardrop.omarchy`, author `Klardrop`, kinds `["bar-widget"]`, entryPoints.barWidget `Panel.qml`; daemon is source of truth for settings).
- [x] 2. Create `linux/omarchy/plugin/Main.qml` (read `control.json`, `XMLHttpRequest`, `/state` long-poll loop, fallback `systemctl --user start klardrop`).
- [x] 3. Create `linux/omarchy/plugin/Panel.qml` (devices, pair/unpair, confirm dialog, send file/text/clipboard, incoming requests, transfer progress, per-device history, rename device, styled with `Commons/Color` and `Style` tokens).
- [x] send-side progress in /state transfers (hook MessengerSendProgress from DiscoveryController.sendFiles).

## Phase 2: native engine (Kotlin/Native `linuxX64`)
- [x] 0. Spike: Add `linuxX64()` to `:protos`, `:klardrop-common`, `:presentation`, `:debug-control`, and `:cli`, compile to identify and resolve blockers.
- [ ] 1. Add `common/src/linuxMain/` actuals for ~23 expects:
  - [x] Step 1a: mDNS via avahi-client (AvahiThreadedPoll), `getifaddrs` polling (`LanAddressSelector` & `NetworkLifecycleMonitor`), `AdvertisedPortProbe` POSIX actual.
  - [x] Step 1b: Subprocess-backed actuals via `posix_spawnp`: clipboard (`wl-copy`/`wl-paste`), notifier (`notify-send --action`), secrets (`secret-tool` / AES-GCM encrypted file fallback with 0600 files, 0700 dirs).
  - [x] Step 1b: Real XDG paths mirroring desktop JVM (`LinuxPaths.kt`), sqldelight native driver with basePath, stderr logger, unhandled exception guard.
  - [ ] Updates: self-update installer via `curl` / `tar` (deferred).
  - [ ] Stubs: BLE (LAN-only native v1), permissions, connectivity restrictions, foreground state, LanTlsListener.
- [x] 2. Port daemon/share commands off java.* for linuxX64 (native `daemon`/`share` in `cli/src/linuxMain`; `discover`/`listen`/`send`/`status` stayed in `commonMain`, now multiplatform too — `ListenCommand`/`SendCommand` no longer touch `java.*`). `DaemonCommand`/`ShareCommand` were later unified into a single `cli/src/commonMain` implementation (JVM and native shared one HTTP client, one menu-selection flow, and one termination-handling loop behind small expect/actual seams in `CliProcess.kt`); the unrequested rofi/dmenu fallback in `ShareCommand` was dropped in favor of `omarchy-menu-select` or `--to`.
- [x] 3. Build `klardrop` as `linuxX64` executable from `:cli` (`:cli:linkReleaseExecutableLinuxX64`); verify `commonTest` passes on `linuxX64Test` (no `integrationCommonTest` source set exists in this repo).
- [x] 4. Linux release workflow publishing `klardrop-native-linux-x64.tar.gz` (native `:cli` build, next to the existing JVM `klardrop-linux-x64.tar.gz`): `build-linux-native` / `linux-native` jobs in `.github/workflows/release.yml` / `release-nightly.yml` run `:cli:linkReleaseExecutableLinuxX64` + `:klardrop-common:linuxX64Test` on `ubuntu-latest` (`libavahi-client-dev`, `libsqlite3-dev`), stage via `packaging/linux/stage-native-tarball.sh` (`bin/klardrop` stripped, `share/klardrop/omarchy-plugin/`, `VERSION`), and upload for the existing `publish` job to pick up (checksummed + attached the same way as every other `dist/*` asset). No installer/self-update wiring yet (Phase 3). Max required glibc on the built binary: **2.17** (`objdump -T … | grep GLIBC_ | sort -uV | tail -1`) — well under any glibc Omarchy (Arch, rolling) ships.

## Memory & idle CPU
Measured with `KLARDROP_HOME=$T/home XDG_RUNTIME_DIR=$T <bin> daemon` in isolated tmp dirs. RSS via `ps -o rss=`; idle CPU via `utime+stime` from `/proc/$PID/stat` (clock ticks / `CLK_TCK`), sampled at 10/30/60(/70)s and expressed as % of one core over each interval. SIGTERM after.

### Historical pre-allocator profiles
| Build | RSS 10s | RSS 30s | RSS 60s | RSS 120s | Threads | Idle CPU (10–60s) |
|---|---|---|---|---|---|---|
| Original baseline (default GC scheduler, unbounded `Dispatchers.IO`) | ~154–189 MB | ~75–116 MB | ~75–76 MB | ~75–76 MB | 36–43 | not measured (pre-fix) |
| `gcSchedulerType=aggressive` + dispatcher fix (first pass, later reverted) | ~50–58 MB | ~50–60 MB | ~52–66 MB | ~50–66 MB | 23–25 | **~1.2–1.8%**, continuous |
| **Kept: default GC scheduler + dispatcher fix only** | ~135–147 MB | ~66–73 MB | ~66–70 MB | ~67–69 MB | 28–34 | **~0.25–0.35%** |
| Default scheduler + explicit `GC.autotune=false`/`targetHeapBytes`/`regularGCInterval` tuning (tried 24 MB/10s, 24 MB/2s, 16 MB/5s) | ~134–146 MB | ~67–73 MB | ~68–72 MB | ~68–70 MB | 28–34 | ~0.20–0.40% |
| **Final M4 profile 2026-09-28** (release kexe rebuilt with all M4 in: sleep-inhibit, BLE default-off, punch-through bound dial, TLS QR share; busy office LAN, Android peer pinging) | ~119 MB | ~75 MB | ~75 MB | — | 31 | **~0.22%** |
| Same binary, isolated netns (no LAN peers) | ~82 MB | ~62 MB | ~63 MB | — | 24 | **~0.16%** |

`gcSchedulerType=aggressive` (a build-time binary option) is meant for testing: it runs the GC on a tight schedule to chase a small heap, which is why it gave the best RSS but cost ~1.2–1.8% of a CPU core *continuously* on an always-idle daemon — unacceptable for a laptop background process. It was reverted.

Explicit native-runtime GC tuning (`@OptIn(NativeRuntimeApi::class)`, `kotlin.native.runtime.GC.autotune`/`targetHeapBytes`/`regularGCInterval`) was tried as a middle ground — disable autotune, aim for a 16–24 MB heap, force a periodic collection every 2–10s even without allocation pressure. It made no measurable difference versus doing nothing (same ~67–70 MB steady RSS, same ~0.2–0.4% CPU): explicit periodic collections did not lower measured steady RSS (earlier theories that the floor was strictly non-GC memory were speculative without live heap stats, and subsequent allocator observations show substantial memory sensitivity to runtime allocation strategy). Since it added `@OptIn` surface and three tuned constants for no measured benefit, it was dropped (ponytail: no unjustified complexity).

Also checked: lengthening the two 5s `getifaddrs` pollers (`NetworkLifecycleMonitor`, `LanAddressSelector`) to 60s changed idle CPU by less than measurement noise (~0.28% vs ~0.30%) — polling is not a meaningful contributor and was left at 5s.

**Kept**: `gcSchedulerType=aggressive` was reverted (continuous CPU cost unacceptable); kept the shared bounded native IO dispatcher fix (`sharedNativeIoDispatcher`, `limitedParallelism(8)`) plus executable-only `binaryOption("pagedAllocator", "false")` on `linuxX64` and `linuxArm64` in `cli/build.gradle.kts`. This avoids aggressive GC overhead while dropping steady-state daemon RSS well below the 50 MB threshold without custom allocators, third-party libraries, or speculative arena limits.

Change kept (Linux native scope in common `linuxMain` and executable targets; performance measured on x86_64 only, ARM64 remains unmeasured, no effect on Android/iOS/desktop JVM):
- `common/src/linuxMain/.../CoroutinesImpl.kt`: `ioDispatcher`/`mainDispatcher` were computed getters returning a fresh `Dispatchers.IO.limitedParallelism(n)` view per call site — this created a new bounded window every access instead of sharing one, so the bound was not real. Fixed to a single shared `sharedNativeIoDispatcher` (`by lazy`, `limitedParallelism(8)`), and pointed the four other linuxMain actuals that called `Dispatchers.IO` directly (`Notifier`, `LanAddressSelector`, `NetworkLifecycleMonitor`, `LinuxTrustStorage`) at the same shared instance.
- `cli/build.gradle.kts`: executable-only `binaryOption("pagedAllocator", "false")` for `linuxX64` and `linuxArm64`.

### 2026-09-30 Baseline vs Per-Object Allocator (`pagedAllocator=false`)
Measured with isolated stdlib runner `scripts/profile-native-memory.py` (runnable usage: `python3 scripts/profile-native-memory.py <binary> --seconds 120 [--label <name>]`, suite self-test via `python3 scripts/profile-native-memory.py --self-test`).

- **Test Condition**: Normal real LAN with native Klardrop and Nearby TCP servers active (`daemon --no-ble --debug`). BLE native stub/default off. Headless isolated temporary identity (`KLARDROP_HOME`, `XDG_CONFIG_HOME`, `XDG_RUNTIME_DIR`, `XDG_DOWNLOAD_DIR`). Stripped child environment (`DBUS_SESSION_BUS_ADDRESS`, `DISPLAY`, `WAYLAND_DISPLAY` removed to force AES-GCM file secret storage fallback and isolate keyring/clipboard). Corrected-source release binary stripped (`/tmp/klardrop-memory-corrected-baseline-stripped.kexe`, SHA `6db4458e4325778b53f3e78ee0629f5a3a9279bec769f72f6f1d9d321dfa3985`) vs candidate stripped (`/tmp/klardrop-memory-paged-off-stripped.kexe`, SHA `2387d177d5cfda4a5aec09e19aefc71c421d0f16a5a6e339e64cfafb3915581e`). Authoritative run data in `/tmp/klardrop-memory-comparison-summary.json`. Performance measured strictly on x86_64; ARM64 remains unmeasured.
- **Serial 120s Daemon Runs (2 paired trials)**:
  - **Settled RSS**: baseline 70.26–80.36 MiB (median 75.31 MiB) → candidate 28.12–28.34 MiB (median 28.23 MiB) — **~62.5% reduction**, meeting the strict 50,000,000 byte (47.68 MiB) target for the measured daemon.
  - **Settled PSS**: baseline median 69.49 MiB → candidate median 22.48 MiB.
  - **Startup Peaks (VmHWM / smaps max)**: baseline 105.55–122.28 MiB → candidate 28.12–28.34 MiB (candidate peak remains flat at steady-state).
  - **Idle CPU (60–120s)**: baseline 0.217–0.25% vs candidate 0.183–0.217% of one core.
  - **Threads**: 28–34 threads across both builds.
- **Third Independent Confirmation Run (root 120s)**: Same candidate SHA `2387d177d5cfda4a5aec09e19aefc71c421d0f16a5a6e339e64cfafb3915581e`, artifact `/tmp/klardrop-profile-memory-q8az7eqm`: settled + observed peak 29,464 KiB = 28.7734 MiB = 30.171136 decimal MB; PSS 23,474 KiB = 22.9238 MiB; 30 threads; idle CPU 60–120s 0.2167% of one core; clean exit. (Reported separately; two-trial median 28.23 MiB kept unchanged).
- **Native QR TLS 16 MiB Transfer Check** (single same-host transfers, SHA256 verified):
  - **Throughput**: baseline 0.038s (425.7 MiB/s) vs candidate 0.033s (478.1 MiB/s) (single-shot figures; no statistically meaningful speedup claimed).
  - **Memory Impact**: baseline pre-transfer RSS 125.35 MiB / post-120s settle 79.27 MiB (peak VmHWM 127.24 MiB) vs candidate pre-transfer RSS 27.47 MiB / observed peak (VmHWM / samples before and after) 33.23 MiB (no continuous peak sampling; no universal peak guarantee) / post-120s settle 30.52 MiB.
- **Scope & Limitations**:
  - The strict < 50 MB threshold is achieved only for the measured headless background daemon and QR sender under real LAN listening conditions.
  - Ordinary paired-device receive and clipboard paths were not validated because both bounded same-host control-target visibility discovery attempts timed out amid same-IP dedup (raw native socket and HTTP connections established cleanly); no discovery semantics or keyring configs were altered.
  - Does not claim exact memory root cause is definitively proven, nor does it claim universal footprint for multi-hour sessions, active paired exchanges, the graphical QML shell, or ARM64 targets. Long-running real paired, clipboard, graphical, and ARM64 profiles remain open follow-up items.

## Phase 3: Omarchy install flavor and system integrations
- [x] 1. Binary install to `~/.local/bin/klardrop` and systemd user service `~/.config/systemd/user/klardrop.service` enabled. `packaging/install.sh` auto-detects Omarchy (`command -v omarchy`, or `--omarchy`; `--jvm` forces the old JVM path) and installs `klardrop-native-linux-x64.tar.gz` instead, per-user only (refuses to run as root — the plugin and user unit need a real session). If a JVM install is already present (`~/.local/lib/klardrop`, or the pre-relocation `~/.local/share/klardrop/{bin,lib}`), it's replaced first — app-image, `.desktop`, AppStream metadata and icons removed, data dirs untouched, a `pgrep -f` on the running JVM's `-Dklardrop.launcher=` argument produces a warn-don't-kill message if it's still running. The unit's hardening is deliberately light for a *user* unit: `NoNewPrivileges` plus the `Protect*`/`Restrict*`/`LockPersonality` directives that don't need mount namespacing (`ProtectClock`, `ProtectKernelLogs`, `ProtectKernelModules`, `ProtectKernelTunables`, `ProtectControlGroups`, `RestrictRealtime`, `RestrictSUIDSGID`, `LockPersonality`). `ProtectSystem`/`ProtectHome`/`ReadWritePaths` were tried and dropped: they need a private mount namespace, and `ReadWritePaths` pointing at a not-yet-created dir (`~/.cache/klardrop`, `~/.config/klardrop` on a fresh install) makes the unit fail to start; `ProtectSystem=strict` also makes `/tmp` read-only, and `ProtectHome` can't know a localized `XDG_DOWNLOAD_DIR`. Verified with `systemd-analyze --user verify` (clean, no warnings) on the generated unit.
- [x] 2. Plugin install to `~/.config/omarchy/plugins/klardrop.omarchy` and enabled via `omarchy plugin enable klardrop.omarchy` (hand-installed, so `omarchy-shell shell rescanPlugins` runs first; `enable` itself never prompts, so no `--yes` is needed, and it already places a bar-widget plugin into the bar — no separate "add to bar" step exists).
- [x] 3. Menu entries merged into `~/.config/omarchy/extensions/omarchy-menu.jsonc` (`trigger.share.klardrop-file`, `-folder`, `-clipboard`), via a marker-delimited (`// BEGIN klardrop.omarchy` … `// END klardrop.omarchy`) block inserted before the file's final `}` — idempotent, backed up (`.bak.<epoch>`) before every edit, and leaves the user's own entries/comments untouched (jq is deliberately not used for the edit itself, since it would reprint the file and drop every comment). The entries call a small installed helper, `~/.local/bin/klardrop-omarchy-share`, that mirrors `omarchy-menu-share`'s pick-with-`omarchy-file-select`-then-run shape. There is deliberately no `trigger.share.klardrop` panel-summon entry: the plugin is bar-widget-only (toggles its own panel internally in `Panel.qml`, no `panel`/`overlay` kind declared, so no shell IPC exists to summon it from a menu action), and a menu item that only prints a `notify-send` hint is worse than not having one. **Open item:** summoning the panel from the menu would need the plugin to declare a `panel`/`overlay` kind with its own shell IPC target — not done here.
- [x] 4. `klardrop.desktop` with MIME type associations and `Exec=klardrop share --pick %F` (the Rust client requires an explicit target device for `share`, and a file manager cannot supply one, so the entry points at the client's device-picker entry point), `NoDisplay=true` so it only appears in "Open With…", not the app launcher. `Icon=klardrop` resolves via two hicolor sizes (128/256, from `brand/klardrop-icon-*.png`) that `stage-native-tarball.sh` now ships under `share/klardrop/icons/` and `install.sh` installs/removes under `~/.local/share/icons/hicolor/`.
- [x] 5. Nautilus python extension ("Send with Klardrop") in `~/.local/share/nautilus-python/extensions/` executing `klardrop share --pick <paths>` via `Gio.Subprocess` with an argv list (no shell), mirroring the `localsend.py` idiom Omarchy already ships. Uses `os.path.expanduser` (not `shutil.os.path`).
- [x] 6. Optional Hyprland bind in `~/.config/hypr/bindings.conf`. (Left open — no default binding decided; see Open Questions.) Decided 2026-09-28: no default binding (it would clobber user binds). `packaging/README.md` documents a one-line `bindd` calling `omarchy-shell klardrop.omarchy toggle`.
- [x] 7. `--uninstall` (combined with `--omarchy`, or auto-detected) reverses every step above: stops/disables/removes the systemd unit, calls `omarchy plugin disable`/`omarchy plugin remove --yes` (falling back to a `.bak.<epoch>`-suffixed move of the plugin folder if that CLI/shell isn't reachable), removes the menu block (byte-for-byte restoring the file when it was only that block that changed), removes the `.desktop`, icons and Nautilus extension, and never deletes `~/.local/share/klardrop`, `~/.config/klardrop`, `~/.cache/klardrop`, or `~/.klardrop` (device identity + history) — the uninstall message says so explicitly.

Tested by `packaging/linux/test-omarchy-install.sh`: install, idempotent
re-install, uninstall, and migrating over a pre-seeded fake JVM install (app-image
+ symlink + `.desktop`/metainfo/icons removed, `~/.local/share/klardrop/databases/`
contents byte-for-byte unchanged), all inside a temp `HOME` with `omarchy`,
`omarchy-shell`, `systemctl`, `update-desktop-database`, `xdg-mime`,
`notify-send`, `omarchy-file-select`, and `gtk-update-icon-cache` stubbed to
argv-logging no-ops; `systemd-analyze --user verify` runs against the generated
unit when that binary is available. Uses a locally staged fake tarball via
`KLARDROP_LOCAL_TARBALL` (or `KLARDROP_TARBALL_URL` to point at a different URL
instead of downloading).

## Deferred Items
- BLE transport on native (port `ble/linux/*` from dbus-java to sd-bus cinterop).
- `LanTlsListener` / QR browser share on native (needs OpenSSL TLS server cinterop).
- `linuxArm64` target.
- Netlink instead of polling in `NetworkLifecycleMonitor`.
- Crash reporting on native (sentry-native or equivalent).
- Retiring the JVM desktop build on Omarchy once native reaches parity.

## Spike Findings
### Dependency Status on `linuxX64`
- **Wire** (`com.squareup.wire:wire-runtime`): Compiles out of the box on `linuxX64` in `:protos`.
- **Sentry KMP** (`io.sentry:sentry-kotlin-multiplatform:0.27.0`): Already publishes `linuxx64` artifact (`sentry-kotlin-multiplatform-linuxx64-0.27.0.klib`). Resolves cleanly; no source-set movement required.
- **FileKit** (`io.github.vinceglb:filekit-core:0.16.0`): Publishes `filekit-core-linuxx64-0.16.0`.
- **Cryptography** (`dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0`): Publishes `cryptography-provider-optimal-linuxx64`.
- **DataStore** (`androidx.datastore:datastore-core-okio:1.2.1`): Publishes `linuxx64`.
- **SQLDelight** (`app.cash.sqldelight:native-driver:2.4.0`): Available on `linuxx64`, wired in `common/build.gradle.kts`.
- **UKEY2 KMP** (`com.carlonzo.ukey2:ukey2-kmp`): **RESOLVED** — `carlonzo/ukey2-kmp` published a `linuxX64` artifact as `1.1.0` on Maven Central. `gradle/dependencies.toml` now pins `ukey2 = "1.1.0"` and the `mavenLocal()` repository (previously needed to consume the pre-release `1.1` build) was removed from the root `build.gradle.kts`. The `:ukey2-stub` substitution mentioned below was already gone from this branch before this artifact landed.
- **`:cli` on `linuxX64`**: Existing `commonMain` code (`CliController.kt`, `CliLogging.kt`, `DiscoverCommand.kt`, `ListenCommand.kt`, `SendCommand.kt`) has legacy dependencies on `java.io.File`, `java.net.InetSocketAddress`, `System.getenv`, and `Runtime.getRuntime().addShutdownHook`. Kept on JVM for step 0 spike; porting to multiplatform/native deferred to Phase 2 step 2.

### Stubbed Actuals Created (`common/src/linuxMain/` & `debug-control/src/linuxMain/`)
Each stub actual is isolated in its own file named matching its `desktopJvmMain` counterpart, with a `// TODO(linux-native): ...` roadmap comment:
- `com/klardrop/common/CrashReporter.linux.kt` (no-op stub)
- `com/carlom/klardrop/common/InternalPlatformDependencies.linux.kt` & `.kt` (common stubs)
- `com/carlom/klardrop/common/ble/BleTransport.linux.kt` (empty stub for deferred BLE)
- `com/carlom/klardrop/common/communication/PunchThroughDial.linux.kt` (stub)
- `com/carlom/klardrop/common/communication/PlatformTransferAnchor.linux.kt` (stub)
- `com/carlom/klardrop/common/connectivity/ConnectivityRestrictionMonitor.linux.kt` (unrestricted stub)
- `com/carlom/klardrop/common/database/DriverFactory.kt` (configured with `NativeSqliteDriver`)
- `com/carlom/klardrop/common/discovery/AdvertisedPortProbe.linux.kt` (stub)
- `com/carlom/klardrop/common/features/ClipboardReaderWriter.kt` (stub)
- `com/carlom/klardrop/common/mdns/ServiceDiscoveryMdns.linux.kt` (stub)
- `com/carlom/klardrop/common/network/NetworkLifecycleMonitor.linux.kt` (stub)
- `com/carlom/klardrop/common/notifications/ForegroundState.linux.kt` (stub)
- `com/carlom/klardrop/common/notifications/Notifier.linux.kt` (stub)
- `com/carlom/klardrop/common/permissions/PermissionsMonitor.linux.kt` (granted stub)
- `com/carlom/klardrop/common/qrshare/LanAddressSelector.linux.kt` (stub)
- `com/carlom/klardrop/common/qrshare/LanTlsListener.linux.kt` (stub)
- `com/carlom/klardrop/common/update/UpdatePlatform.linux.kt` (stub)
- `com/carlom/klardrop/common/utils/CoroutinesImpl.kt` (`Dispatchers.Default` / `IO`)
- `com/carlom/klardrop/common/utils/PlatformFileSystem.linux.kt` (stub using `/tmp/klardrop`)
- `com/carlom/klardrop/common/utils/UnhandledExceptionGuard.linux.kt` (no-op stub)
- `com/carlom/klardrop/common/utils/logger.linux.kt` (stderr printing logger)
- `debug-control/src/linuxMain/kotlin/com/carlom/klardrop/debug/ControlFile.linux.kt` (POSIX `open`, `0600`, `mkdir`, `unlink` - functional implementation)

### Non-working Items Left for Step 1
1. **mDNS**: COMPLETED in Step 1a via `avahi-client` cinterop (`AvahiThreadedPoll`, `ServiceDiscoveryMdns.linux.kt`).
2. **Network interface enumeration**: COMPLETED in Step 1a via `getifaddrs` POSIX calls in `LinuxNetworkInterfaces.kt`, `LanAddressSelector`, and `NetworkLifecycleMonitor`.
3. **AdvertisedPortProbe**: COMPLETED in Step 1a via POSIX socket connect.
4. **Subprocess actuals**:
   - `ClipboardReaderWriter`: COMPLETED in Step 1b via `posix_spawnp` (`wl-copy` and `wl-paste`).
   - `Notifier`: COMPLETED in Step 1b via `posix_spawnp` (`notify-send --action`).
   - `TrustStorage`: COMPLETED in Step 1b via `SecretToolSecretStore` and `EncryptedFileSecretStore` fallback (0600 files, 0700 dirs).
   - `UpdatePlatform`: `curl` / `tar` unpack (deferred).
5. **XDG directories**: COMPLETED in Step 1b via `LinuxPaths.kt` mirroring desktop JVM layout (`XDG_DATA_HOME`, `XDG_CONFIG_HOME`, `XDG_CACHE_HOME`, `user-dirs.dirs`, `KLARDROP_HOME`).
6. **Upstream `ukey2-kmp` release**: COMPLETED — `linuxX64` target published as `1.1.0` on Maven Central; repo now depends on it directly.

### Effort Estimates
- **Phase 2 Step 1** (Native actuals: Avahi cinterop, `posix_spawn` subprocesses, `getifaddrs`, XDG dirs, native driver): **3.5 - 4 days**
  - Avahi cinterop + event loop: ~1.5 - 2 days
  - Subprocess actuals (`wl-copy`/`wl-paste`, `notify-send`, `secret-tool`, `curl`): ~1 day
  - `getifaddrs` & XDG path resolution: ~0.5 day
  - `ukey2-kmp` linuxX64 upstream publication & integration: ~0.5 day
- **Phase 2 Step 2** (Port CLI commands off `java.*` for `linuxX64`): **1 day**
- **Phase 2 Step 3** (Build `linuxX64` binary & run native tests): **1 day**
- **Phase 2 Step 4** (Linux release packaging workflow): **0.5 day**
- **Phase 3** (Omarchy installer & system integrations): **1.5 - 2 days**

## Open Questions
- Default Hyprland bind (if any).
- Whether the plugin should also be published as its own git repo so `omarchy plugin add` works.

## Live Test Steps (current)
Trial setup (keeps the JVM install; data dirs are shared, so the identity and trusted devices carry over):
1. Daemon: `./gradlew :cli:linkReleaseExecutableLinuxX64`, `cp cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe ~/.cache/klardrop-try/klardrop-engine`, then `systemctl --user restart klardrop-try` (the first time: `systemd-run --user --unit=klardrop-try ~/.cache/klardrop-try/klardrop-engine daemon`). Logs: `journalctl --user -u klardrop-try -f`. Only the engine lives there: `daemon`/`listen` belong to `klardrop-engine`, and the user-facing CLI is the separate Rust client (`cargo build --locked --release` in `cli-rust/` → `cli-rust/target/release/klardrop`), which talks to that engine over the control file.
2. Plugin: `cp -r linux/omarchy/plugin/. ~/.config/omarchy/plugins/klardrop.omarchy/`, then **`omarchy-restart-shell`** (a rescan does NOT reload changed QML; never use `omarchy-refresh-shell`, which resets shell.json). First time only: `omarchy plugin enable klardrop.omarchy`.
3. Open: main window `omarchy-shell klardrop.omarchy openWindow`; quick panel: the bar icon or `omarchy-shell klardrop.omarchy toggle`; share from the terminal (Rust client): `cli-rust/target/release/klardrop share --to <device-id> <file>`, or `klardrop share --pick <file>` to pick the device interactively.
4. Check: pair/send both ways with a phone, theme switch, `journalctl --user -t omarchy-shell` free of klardrop warnings.
Full install instead (replaces the JVM app, keeps data): `bash packaging/linux/stage-native-tarball.sh dev && KLARDROP_LOCAL_TARBALL=dist/klardrop-native-linux-x64.tar.gz bash packaging/install.sh --omarchy`; undo with `bash packaging/install.sh --uninstall`.

## Status & next steps (updated 2026-09-27)
- **Done and reviewed:** Phases 1-3, Stage 2 M1 (engine gaps), M2 (standalone window, launcher, bar-icon fix; live-checked in the real shell). ukey2-kmp 1.1.0 released.
- **Nothing is committed yet** (about 77 files on `feat/omarchy-native`). Proposed commits: (1) Phases 1-3; (2) native engine + ukey2 1.1.0 + M1; (3) packaging + M2. Keep M3a's partial, unreviewed files out until they're reviewed.
- **M3 is split:** **M3a** = control API/engine (Kotlin), **M3b** = UI parity in the window (QML, after M3a). M3a was **interrupted by a session limit mid-work**: its lead must first inspect the partial changes in debug-control/presentation/common (git diff), then continue from its parity matrix.
- **Process:** each milestone has a lead (Opus); implementers are Sonnet subagents (agy is out of quota until about 2026-10-02); every task gets a fresh reviewer. Leads follow the brief rules: prompts via file, no visible test windows, no QML harnesses (GPU budget), don't touch the user's config except the trial plugin/daemon.
- **Open decisions (user):** sentry-native for signal crashes (currently: the lightweight curl sender only); first stable release (the update check 404s until one exists); when to replace the JVM install with the Omarchy installer.
- **Environment note:** the GPU has 2 GB of VRAM and is nearly full in normal use. On 2026-09-27 every shell popup failed with EGL_BAD_ALLOC (eglError 0x3003) until VRAM was freed and `omarchy-restart-shell` was run. Not a Klardrop bug, but the reason leads must not run extra QML/Quickshell instances.

## Stage 2: feature parity with the JVM desktop app

Goal: the Omarchy flavor (native `klardrop daemon` + shell plugin) can fully replace the JVM app on Linux.
Order = milestones; ship M1 + M2 before telling Omarchy users to switch.

### M0: live validation (blocker for everything below)
- [ ] Live test on the real desktop (bar widget, panel, pair/send both ways with Android, menu, Nautilus, "Open with", theme switch).
- [ ] Confirm the systemd unit's remaining Protect*/Restrict* lines don't break avahi / D-Bus / notify-send in a user unit (they can imply PrivateUsers).
- [ ] Run the `klardrop-connection-tests` matrix (discovery, pair, unpair-offline, identity reset) against the native daemon via `scripts/klardrop-ctl`.

### M1: engine gaps (native daemon)
- [x] **Self-update** (mandatory per distribution policy): `UpdatePlatform.linux.kt` returns null. Manifest fetch plus native-tarball install into `~/.local/bin`, then `systemctl --user restart klardrop`. Also needs a `latest.json` entry for the native tarball in the release workflows (today it only points at the JVM tarball).
- [x] **Clipboard sync cost**: `ClipboardManager` polls `readForSync()` every 500 ms, which on Linux spawns `wl-paste` twice a second whenever a trusted device is visible. Switch the Linux path to event-driven `wl-paste --watch`.
- [x] **Crash reporting**: sentry-kmp's linuxX64 artifact turned out to be a no-op stub (built from commonStub), so the daemon reports through a small curl-based Sentry envelope sender (`SentryLinuxSender.linux.kt`) and skips `Sentry.init` on Linux. It covers `CrashReporter.notify` and uncaught Kotlin exceptions (sent synchronously before abort), is capped at 20 events per process with dedupe, and is tagged `device.platform=linux`. Gating is unchanged: an empty compile-time DSN or `--debug` means nothing is sent. The native release/nightly jobs now pass `klardropSentryDsn`. Idle cost is nil: 60 s idle RSS was 75.7 MB with the DSN vs 76.5 MB without, and CPU was 0.28% for both.
- [ ] Crash reporting follow-ups: native signal crashes (SIGSEGV/SIGABRT from C interop) aren't captured (would need sentry-native/crashpad; tracked in Deferred Items); user problem reports now go through `/report-problem` (done in M3a). Done: uncaught Kotlin exceptions are now sent as `fatal`. `UnhandledExceptionGuard.linux.kt` calls `reportUncaughtException(..., fatal = true)`, which passes through `log(..., fatal)` to `CrashReporter.notify(throwable, fatal)` (linux sender level `fatal`; the Sentry SDK path sets `SentryLevel.FATAL`). The coroutine handler and plain `log` calls stay `error`. Covered by `SentryLinuxSenderTest.notifyFatalUsesFatalLevel`.
- [x] **Port-in-use clean failure**: a busy control port now exits 3 with `control port N already in use`, and any other startup failure (e.g. `--port 1`) exits 1 with `daemon failed: ...`. No abort or core dump on native, no hang on JVM. The root cause was an uncaught exception escaping `main()`. The control socket now binds with `SO_REUSEADDR`, so a restart during TIME_WAIT works. `stop()` only deletes a control.json whose token matches its own. The debug-control tests use a temp `XDG_RUNTIME_DIR`: before this they deleted the live daemon's control.json. The Klardrop/Nearby server was already ephemeral-port with a bind fallback (`CommunicationComponent.kt:208`, `Server.kt:131`), the same on JVM and native. Covered by `LoopbackHttpServerTest` and a tier0 case.
- [x] **Idle wait**: the `delay(100)` poll is gone. Native uses a self-pipe (a C `sigaction` handler writes one byte; a dedicated thread blocks in `read()`), JVM awaits the shutdown-hook latch. Fresh release daemon (`--no-ble`, isolated, 30 s warmup then 60 s idle): 66.5 MB RSS, 0.23% CPU, 31 threads, about 258 context switches/s. With no LAN peers (isolated netns) it's 62 MB and 0.17%.
- [ ] Remaining idle wakeups (about 250/s) come from the kotlinx-coroutines K/N runtime, not app code: `ThreadLocalKeepAlive.keepAlive` in worker `runBlocking` loops (about 56/s) and `WorkerDispatcher` delay re-arm (about 29/s), measured with gdb on `Worker#executeAfter`. App `delay`s are about 0.2/s. Reducing it would mean fewer dispatcher threads or a custom dispatcher (risky). RSS rises to about 95 MB on a busy LAN (peers pinging); worth profiling the heap growth.
  - Findings 2026-09-27 (bounded look, no code change): a release daemon on the real LAN (6 h uptime, Android peer connecting about once a minute) sat at 82-84 MB RSS (63 MB anon, 19.5 MB file) with a 117 MB high-water mark. RSS comes back down after peaks, so this looks like K/N GC heap sizing, not a leak. App-side allocation isn't hot: about 25 log lines/min (mostly `VisibleDevices` snapshots and inbound-connection open/close), so `LogBuffer`'s copy-on-append 2000-line list is negligible. The per-minute inbound connection logs a full `kotlinx.io.EOFException: Channel is already closed` stack trace when it ends (`[Server]: Connection ... ended`). That's log noise, not memory. It could be demoted to `logLocal` without a trace if it ever matters. No cheap win found. The next step would be a real heap profile (K/N GC stats via `-Xruntime-logs=gc=info`, or massif). Leave this unchecked.
- [x] **Send-side progress** in `/state` transfers (hook MessengerSendProgress from `DiscoveryController.sendFiles`). Done: `DiscoveryController` emits `OutgoingTransfer`, `DebugControl.trackOutgoingSend` puts it into `/state` (tested in `DebugControlUpdateTest`).
- [x] **Update manifest 404** (live test 2026-09-26): expected, not a bug. The trial binary is a local build, so `UPDATE_CHANNEL` is `stable` and it fetches `https://github.com/carlonzo/klardrop/releases/latest/download/latest.json`. The repo has no stable release yet (only the `nightly` prerelease, which `/releases/latest` excludes), so GitHub returns 404; the nightly URL `.../releases/download/nightly/latest.json` returns 200. Resolves itself with the first stable release; CI nightlies are built with `-Pklardrop.updateChannel=nightly`.

### M2: standalone app window (main entry point)
The bar widget is a **companion** for quick access. Opening "Klardrop" from the launcher must open a real app window with the JVM app's layout.
- **Tech:** a Quickshell `FloatingWindow` (a regular toplevel that Hyprland tiles and floats like any app) owned by the klardrop.omarchy plugin, running inside the existing omarchy-shell process. No extra runtime and no JVM; it uses the same `qs.Ui` components and `Color`/`Style` tokens, so it follows the theme. Precedent: Omarchy's own `plugins/dev-gallery/GalleryPanel.qml` opens a `FloatingWindow` from a plugin.
- [x] Window shell: IPC `omarchy-shell klardrop.omarchy openWindow` (plus the existing toggle for the panel); a single instance (re-focus if already open); remember the size; closing the window never stops the daemon. Implemented (`StandaloneWindow.qml`, `openWindow`/`closeWindow` IPC, lazy Loader, refocus via `hyprctl dispatch hl.dsp.focus({ window = "title:^Klardrop$" })`, size saved via `bar.shell.updateEntryInline`); offscreen open/close x40 showed flat RSS. Pending: real-shell live check (see below).
- [x] Layout parity with the JVM app (compose-ui `WideLayout` / `Sidebar` / `discovery_screen` / `DeviceChatScreen`): left pane with this device header, **Nearby** and **Trusted** sections (status dot, reachability, unread badge, pair / forget); right pane with the selected device's chat/history and the message input; an empty state when nothing is selected; incoming banner stack; pairing / trust dialogs; settings (device name, background discovery, update channel) and the update banner. Implemented in `StandaloneWindow.qml` (no unread badge or update settings yet: API missing, see M3). Pending the real-shell live check. Done 2026-09-28 in code (reviewed): unread badges, update banner/settings, notification cards, and drop targets are now in. The real-shell visual check belongs to M0.
- [x] Share logic between the window and the companion panel: move the device list, transfer rows and dialogs into shared QML components in the plugin so both surfaces use one implementation (Main.qml stays the single API/state client, so there's still one long-poll). Done: `DeviceRow`, `TransferRow`, `IncomingRequestRow`, `PromptOverlay`, `HistoryMessage`; shared helpers in `Main.qml`.
- [x] Launcher entry: a visible `Klardrop` .desktop entry (Exec opens the window through IPC; if omarchy-shell or the daemon isn't running, start the daemon via systemd and show a clear error) with the icon. Keep the separate NoDisplay "Open with…" share entry. Done: `klardrop-app.desktop` -> `~/.local/bin/klardrop-omarchy-open` (starts `klardrop.service`, calls `openWindow`, notify-send with the real error on failure).
- [x] Installer and uninstall updated; a Hyprland window rule (float + size) only if the default tiling looks wrong. Done (installer test covers install, helper failure cases, uninstall, JVM migration). No window rule: tiling is the Omarchy default for apps.
- [x] Companion panel trimmed to quick actions: device list with send file / clipboard, pending requests, transfers, and "Open Klardrop" to the window.
- [x] Live-test fixes found on 2026-09-26: the bar icon is 0x0 in a vertical bar (the root needs implicitWidth/Height, and the BarIconButton inside still sizes to 0 for a third-party plugin); phones show the desktop glyph (the daemon reports `MOBILE`, the plugin checks `phone`); the QML warnings `Main.qml:197` "Value is null" and `pollState` in an invalid context after a reload. (The update manifest 404 moved to M1, resolved as expected.) Fixed in code: root `implicitWidth/Height` from the button (the `qs.Ui` Panel is a plain Item), `MOBILE`/`DESKTOP` glyphs, null-safe destroyed-context guards in `Main.qml`.
- [x] Real-shell live check of the M2 plugin: `omarchy-shell shell rescanPlugins` does NOT reload a changed plugin in the running shell (the old compiled type stays cached; `openWindow` answers "Function not found."), so it needs one `omarchy-shell` restart. Then: `debugBarGeometry` (klardrop slot non-zero), `omarchy-shell klardrop.omarchy openWindow` twice (one window, refocused), screenshots of window + bar, journal free of klardrop warnings, 20x open/close with omarchy-shell RSS flat. **Done 2026-09-27:** after `omarchy-restart-shell` (NOT `omarchy-refresh-shell`, which resets shell.json), the bar slot is 28x27 and visible, openWindow x2 gives 1 window, phone glyphs are correct, no klardrop warnings, and 20x close/open grew the shell RSS by +1.4 MB (flat).
- [x] Window right pane is see-through: content of windows behind shows through (the window/background color is translucent under Hyprland opacity). Give the window an opaque surface color (Color.popups.background or the theme background).
- [x] Background-discovery toggle in the window header has no label/tooltip. Fixed 2026-09-28: the window surface is forced opaque (`Color.popups.background` at alpha 1 plus a backstop Rectangle); the toggle has a label and a `PanelToolTip`.

### M3: control API + UI feature parity (M3a = API/engine, M3b = UI in the window)
API (DebugControl) endpoints the panel still needs:
- [x] `/state` fields for the window: a per-device unread count (sidebar badge), and file name/count on `incoming` entries (the banner can only say "wants to send files").
- [x] retry failed transfer (`DeviceChatViewModel.retryFileTransfer`), history paging (`/history` is capped at 100) and mark-read.
- [x] notification cards: dismiss, pair-from-notification, received-card click (`OnDeviceActionListener` / `ReceiveNotificationsCallbacks`).
- [x] peer-revoked-trust and pairing-error state in `/state` (`DiscoveryScreenState.PeerRevokedTrust`).
- [x] update status/actions (`UpdateBannerController`: recheck, restart, channel stable/nightly).
- [x] report a problem (Sentry user feedback, `ReportProblem*`).
- M3a done 2026-09-28 (reviewed, tests green): `/state` `devices[].unreadCount`, `incoming[].fileCount/fileNames/totalSize/text`, `notifications[]` with PeerRevokedTrust device info, `update{}` status/version/channel/action; `GET /history?limit=&before=` keyset paging (`nextBefore`), `POST /history/read`, `/retry`, `/clipboard`, `/notification/dismiss|pair`, `/incoming/dismiss|open`, `/pairing-dialog/dismiss`, `/report-problem` (Linux: curl Sentry feedback envelope), `/update/check|apply`. Release channel is build-time (same as the JVM app), so it is exposed read-only. Tests: `DebugControlNotificationsTest`, `DebugControlHistoryTest`, `SentryLinuxSenderTest`.
UI (window first; the companion panel only where it makes sense for quick actions):
- [x] chat view parity: bubbles with quick actions (copy, open, reveal in folder, retry), URL open, date chips, send status, long-text viewer.
- [x] media: image/video thumbnails + recent media rail (thumbnails generated daemon-side, served via the API). Done 2026-09-28 (reviewed): bubbles with date chips, delivery status, http(s)-only link opening (peer text HTML-escaped), a >600-char viewer, Copy/Open/Reveal/Retry, keyset paging with scroll preservation, and mark-read only while the window is active. Images load directly (`sourceSize` capped at 320 px); videos use `GET /thumbnail?id=` (ffmpeg first frame cached under the app temp dir, `null` without ffmpeg, the same as the JVM app). The recent-media rail is Android-only in the JVM app too (`RecentMediaRail.android.kt`), so it doesn't apply to desktop.
- [x] update banner + update settings; report-problem form; trust/forget confirmations.
- [x] drag & drop files onto the panel (QML DropArea).
- [x] **Summon the panel from the menu / a keybind**: the plugin can own an `IpcHandler` (the shell's bluetooth and agents panels do). Then re-add `trigger.share.klardrop` and decide the optional Hyprland bind.
- [x] per-peer quick send from the bar (tray-menu equivalent: "send to <peer>"). Done 2026-09-28 (reviewed): new `UpdateBanner.qml` (available/downloading/ready/failed, restart with a 409 force-confirm, command copy, http(s)-only URL/notes opening) and a SETTINGS section (version, channel, "Check for updates", report-a-problem form with the JVM outcome copy). New `NotificationRow.qml` covers PeerRevokedTrust (dismiss / pair again) and pairing errors; received cards get Open/Dismiss. Pair and forget both confirm. `DropArea` on trusted device rows (window + panel) and on the chat pane (`file://` only, percent-decoded). Per-peer send file/clipboard already existed in the panel. The panel `IpcHandler` (`toggle`) already existed, so `trigger.share.klardrop` was re-added to the installer menu block (installer test asserts it). Peer-supplied names and file names render as plain text.

### M4: larger / deferred
- [ ] Sleep inhibition during transfers (`PlatformTransferAnchor` via `systemd-inhibit`). An improvement over the JVM app, which has none (`TransferAnchor.None`), so not needed for parity.
- [ ] Flaky: `scripts/cli-integration-tests.sh` tier4 (peer restart / mDNS timing) fails intermittently under load; pre-existing, unrelated to the native work. Stabilize.
- [ ] Punch-through bound dial on linuxX64 (`punchThroughSupported=false`). The burst logic is shared engine code; only the platform bound-socket primitive is missing, and Android/Apple stub it too, so it only matters for two firewalled desktops. Kotlin/Native needs a ktor Socket around a pre-bound fd (no reflection).
- [x] QR browser share: `LanTlsListener` needs a TLS server (OpenSSL cinterop) on native. Done 2026-09-28 (reviewed twice): `LanTlsListener.linux.kt` runs an OpenSSL 3 TLS server over raw POSIX sockets (`openssl.def`; CI installs `libssl-dev`). The listen socket exists only while a share is active; the accept loop solely owns the listen fd. Client sockets have 1 s timeouts plus a 30 s idle deadline, so idle phones can't pin the 8-thread IO pool. SIGPIPE is ignored. Fixed during development: a read/write-loop use-after-free, a lock deadlock, SIGPIPE deaths, and an accept() thread leak. API: `POST /qr-share {paths}` (regular files only) returns `{url, expiresAt, qr}`, where `qr` is a module matrix (`qrencode` on Linux, qrcode-kotlin on JVM); `POST /qr-share/stop`; `/state.qrShare`. The standalone `QrShareOverlay.qml` draws the matrix. Verified with a live curl download (checksum match) and `LanTlsListenerNativeTest` (handshake, round trip, 10 idle clients then recovery).
- [ ] BLE transport: port `ble/linux/*` from dbus-java to sd-bus cinterop.
- [ ] `linuxArm64` target + tarball.
- [x] Memory: daemon under 50 MB target achieved for headless native daemon and QR sender (2026-09-30). Tested via `pagedAllocator=false` on corrected release: steady RSS dropped from 75.31 MiB median to 28.23 MiB median (~62.5% reduction; candidate settled 28.12–28.34 MiB, well below 50,000,000 bytes) with idle CPU 0.18–0.22% and verified 16 MiB QR transfer performance (478 MiB/s, 30.52 MiB post-settle). Limitation: scoped strictly to measured headless background daemon and QR sender with isolated credentials; long-running active paired-device receive, clipboard sync, graphical shell, and ARM64 profiles remain unmeasured open follow-up items.
- [ ] Retire the JVM Linux build for Omarchy users once M1-M3 ship (the installer already migrates).
