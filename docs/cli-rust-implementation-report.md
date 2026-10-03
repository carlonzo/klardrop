# Klardrop native Rust CLI/TUI — implementation report

Durable progress record for `docs/cli-daemon-agent-brief.md`. Every line is either a check that
actually ran on this machine, or a gap that is explicitly marked as a gap. Nothing is claimed
from a prior session's claim; prior-session output was re-verified before being recorded.

Host: Linux x64 (Omarchy), Gradle 9.8.0, Rust 1.98.1, JDK in `~/.gradle` (zulu 25 per CI config).
No macOS, Windows or ARM64 runner is available here — those are recorded as unrun gates with the
CI check that now covers them.

---

## Fourth pass (2026-10-02) — the Windows CLI could not be built at all

An audit asked whether the remaining plan acceptance items are implemented or
merely unrun. One was not implemented: **the Windows build did not compile.**
`cli-rust/src/interrupt.rs` defined `extern "C" fn on_sigint(_signal: libc::c_int)`
at module scope while `libc` is a `cfg(unix)`-only dependency, and the
`#[cfg(not(unix))]` arm of `install()` referenced that same ungated symbol.

Reproduced before anything was changed, with a private toolchain carrying both
Windows targets:

```text
$ cargo check --locked --target x86_64-pc-windows-msvc --all-targets
error[E0433]: cannot find module or crate `libc` in this scope
  --> src/interrupt.rs:28:34
```

Every Windows Rust step in both release channels runs
`cargo build --locked --release` and asserts `target/release/klardrop.exe`
exists, so `klardrop-cli-windows-x64.zip` could not have been produced by this
branch. Ctrl-C during `share --wait` was unimplemented there too: the default
disposition applies, so exit 130 came from the shell with no envelope and no
request id — the exact contract violation the plan calls out.

### What changed

| file | change |
|------|--------|
| `cli-rust/src/interrupt.rs` | the `signal(2)` handler moved behind `#[cfg(unix)]`; a `#[cfg(windows)]` handler registers through `SetConsoleCtrlHandler` (`windows-sys` 0.59, already pinned in `Cargo.lock`); `install()` returns `Result` and a failed registration is reported rather than swallowed |
| `cli-rust/src/main.rs` | `interrupt::install()` moved after `Cli::parse()` and its failure reported through `out.fail`, so `--help`, `--version` and usage errors keep working and a `--json` command still emits exactly one JSON value |
| `cli-rust/src/commands/discover.rs` | `discover --wait` now honours the flag (see the reviewer's finding below) |
| `cli-rust/Cargo.toml` | `[target.'cfg(windows)'.dependencies]` `windows-sys` (`Win32_Foundation`, `Win32_System_Console`) plus a matching dev-dependency (`Win32_System_Threading`) for the test |
| `cli-rust/tests/interrupted_wait.rs` | two platform-neutral tests over one platform-specific delivery helper per OS |
| `.github/workflows/build_pr.yml` | a named `Interrupted wait (Windows console event)` step on the Windows matrix leg |
| `cli-rust/README.md` | the "no handler is installed, so Ctrl-C writes no envelope" paragraph was stale and is corrected |

Windows behaviour, deliberately: `CTRL_C_EVENT` and `CTRL_BREAK_EVENT` set the
flag and answer `TRUE` — Windows runs the handler on an OS-created thread, and
the handler does nothing but the atomic store. Every other control event answers
`FALSE`, so a window close, a logoff and a shutdown keep the default disposition
they had before. `CTRL_BREAK_EVENT` is answered as well as `CTRL_C_EVENT`
because a `CREATE_NEW_PROCESS_GROUP` process — what an automated caller gets —
is delivered Ctrl-Break rather than Ctrl-C.

### The reviewer's finding that changed the code

A fresh reviewer, not the task that wrote this, confirmed the handler and the
test and found that **only `share --wait` ever read the flag**. That is harmless
on Unix, where the flag was already installed, but on Windows this change is
precisely what stops Ctrl-C from killing the process — so `discover --wait` (up
to an hour) would have swallowed the interrupt entirely. `discover` now leaves
through the same `cancelled`/130 door, with no request id because it sends
nothing. The same review found the crate README still documenting the old "no
handler is installed" contract; it now describes both handlers and says plainly
that the flag is read at poll boundaries only, because abandoning an in-flight
`POST /share` would lose the request id the interrupt exists to preserve.

A second sweep looked for any other source-level gap of the same shape — no-op
`cfg` fallbacks, `todo!`/`unimplemented!`, ungated unix-only crates,
`allow(dead_code)` hiding unreachable code — across `cli-rust/`, the three
workflows, `cli/README.md` and this report. It found none; its only finding was
the stale paragraph this pass replaced.

### What actually ran here

| check | result |
|-------|--------|
| `cargo check --locked --target x86_64-pc-windows-msvc --all-targets` | clean, no warnings (before the fix: E0433) |
| `cargo check --locked --target x86_64-pc-windows-gnu --all-targets` | clean, no warnings |
| `cargo clippy --locked --all-targets -- -D warnings`, Linux and both Windows triples | clean |
| `cargo fmt --all --check` | clean |
| `cargo test --locked` | **314 passed, 0 failed** — 216 unit, 50 `cli_contract`, 2 `interrupted_wait`, 17 `tui_flows`, 4 `tui_input_edges`, 5 `tui_line_mode`, 4 `tui_motion`, 3 `tui_mouse`, 3 `tui_rendering`, 10 `tui_theme` |
| `cargo build --locked --release` (Linux) | ok |
| `cargo tree -e normal --target x86_64-unknown-linux-gnu` counting `windows-sys` | `0` — the Linux graph did not grow |
| mutation: disable the interrupt check in `discover` | `interrupting_a_discover_wait_stops_instead_of_polling_to_the_window_end` **FAILED** after 30.0 s with "the client ignored the interrupt and polled for the whole 30s window"; restored |
| manual smoke: `share --wait`, SIGINT after 0.5 s | exit **130**, one JSON value with `status:"unknown"`, `error.code:"cancelled"` and the request id; `transfers --id <id>` then exits **0** |

The Windows toolchain used for those `check` runs is private and outside the
repository (`RUSTUP_HOME=/tmp/klardrop-rustup`, toolchain 1.98.1 with the
`x86_64-pc-windows-msvc` and `x86_64-pc-windows-gnu` std added). No system
Rust was replaced and nothing was written outside `/tmp`.

### Still not run — and not a source gap

1. **The Windows binary is compiled here but never linked or executed.** There
   is no Windows linker on this host, so `cargo check` is the strongest local
   check; linking, and the console-event test itself, run only on
   `windows-latest`.
2. **`cli-rust/` is untracked in this branch, `Cargo.lock` included.** Every
   Rust step in every workflow passes `--locked`, and with no lock file cargo
   refuses immediately:

   ```text
   error: cannot create the lock file … because --locked was passed to prevent this
   ```

   Reproduced in a scratch copy. The consequence is concrete: the Rust matrix in
   `build_pr.yml` — and therefore the new Windows console-event gate — cannot go
   green until `cli-rust/` (with `Cargo.lock`) is committed. No Rust CI job has
   ever been green on this branch, which the earlier passes did not record.
3. The macOS `qr-share` capability stays withheld, and the physical Wayland/X11
   checks, the ARM64 matrix and the AUR migration stay exactly where they were.

---

## Third pass (2026-10-02) — verifying the previous pass, and what it had wrong

This pass did not take the report below on trust. Four fresh reviewers — none of
them the task that wrote the code they inspected — audited the client, the shared
control component, the TUI and the packaging, and every finding was checked against
the source before anything was changed. This section records what was **wrong**,
what was fixed, and what actually ran. The phase sections below are unchanged and
still describe the earlier passes.

### A previous "pre-existing, not a regression" verdict was wrong

The two-peer run had reported two engine-side findings as pre-existing defects on
`main`. One of them is not:

| Finding | Earlier verdict | Verified verdict |
| --- | --- | --- |
| A receiver whose download directory does not pre-exist fails at finalize with `ACK_REJECTED` | pre-existing | **introduced by this branch** |
| `/state` can omit a device that `POST /share` will still deliver to | pre-existing | pre-existing (holds) |

`main` has no Linux-native target at all — `git ls-tree -r HEAD` contains nothing under
`common/src/linuxMain`, `common/src/nativeInterop` or `cli-rust`, and `settings.gradle.kts`
lists only `android`, `desktop`, `cli`. Its only Linux engine is the JVM desktop app, whose
`getDownloadStoragePath()` is `FileKit.downloadDir`, and filekit-core-jvm **creates** that
directory before returning it (`javap -c io.github.vinceglb.filekit.FileKit_jvmKt` shows
`platformUserDirectoryOrNull` → `assertExists(Path)` → `File.mkdirs`). The new native engine
resolved the path itself and returned it verbatim, so the directory was never created — and
the pre-existing gap in commonMain `moveToStorage` that would normally be masked was reached
for the first time.

**Reproduced before the fix**, with the two-peer harness no longer pre-creating the
receiver's download directory (which had been hiding it):

```
FAIL: sender exits 0
      exit=1 stdout={"schemaVersion":1,"ok":false,...,"status":"declined",
      "items":[{...,"status":"declined","error":"Recipient declined the transfer (declined)"}],
      "error":{"code":"transfer_failed",...}}
```

**Fix**: `common/src/linuxMain/.../FileUtils.kt` gained `ensureDirectory`, which creates
missing components with mode 0777-masked-by-umask and — unlike `ensureDirectory0700` —
never chmods a directory it did not create, because `~/Downloads` belongs to the user.
`InternalPlatformDependencies.getDownloadStoragePath()` calls it before returning the path.
Pinned by `EnsureDirectoryLinuxTest` (4 tests, including the one that fails if the mode of
an existing directory changes) and by the harness itself, which now asserts
`receiver engine CREATED the download directory it resolved (this script never did)`.

**After the fix**: `bash scripts/klardrop-two-peer-transfer-tests.sh` → **32 run, 32 passed**
(31 before, plus the new assertion), 3,000,000 bytes received byte-for-byte, sender daemon
**VmRSS 34,816 KiB = 35,651,584 bytes < 50,000,000**.

### The TUI panicked on any conversation scroll

`conversation_window()` in `cli-rust/src/tui/render.rs` computed
`tail_last = (end - tail_start).min(tail.len())`. `tail` is empty whenever no transfer is in
flight, so `total == history` and **every scroll ≥ 1** made `end - tail_start` underflow:

```
thread 'main' panicked at src/tui/render.rs:547:21: attempt to subtract with overflow
```

Reachable from `Up`, `PageUp` and the mouse wheel — three interactions the plan requires —
and it killed the client (exit 101) even with `--no-motion --no-mouse --no-color`. Fixed with
`end.saturating_sub(tail_start)`. The review also found that the only test covering scrolling
asserted `assert_ne!(before, after)`, which a blank screen satisfies, and that its own captured
artefact `tests/frames/conversation-scrolled.txt` was **1 byte** against 5,380 for the
un-scrolled frame. That test now asserts the client is still running and that expected message
text is on screen.

### Two GUI callers invoked a terminal-only entry point

`share --pick` needs a real terminal on both stdin and stdout; the desktop entry
(`Terminal=false`) and the Nautilus extension (`Gio.Subprocess`, inherited non-tty stdio) gave
it pipes, so both exited 2 with `share --pick chooses a device on screen…`. File-manager
sharing from Omarchy was dead, and the only assertions about it were `grep -F` over the
generated text.

`install.sh` now installs `~/.local/bin/klardrop-share-pick`, which starts the daemon, finds a
terminal (`$TERMINAL` first, then `foot`, `kgx`, `gnome-terminal`, `alacritty`, `kitty`,
`wezterm`, `xterm`), and execs `klardrop share --pick "$@"` inside it with the right flag per
emulator (`-e` vs `--`). Both GUI callers were repointed at it. The harness **executes** the
helper with stubbed `klardrop`, `systemctl` and terminal, and compares the recorded argv
against an exact expected list; seven mutations (dropping `--pick`, dropping `"@"`, reverting
either caller, using `-e` where `--` is required, ignoring `$TERMINAL`, dropping the helper from
the ownership guard, dropping it from the uninstall list) each fail the suite.

### Windows: the packaged "zip" was a tar

`.github/workflows/release.yml` built `klardrop-cli-windows-x64.zip` with
`tar -a -c -f …`. Under `shell: bash` on `windows-latest`, `tar` is Git Bash's **GNU** tar,
whose `-a` is `--auto-compress`: it picks a compressor from the suffix and can never emit a zip
container. The step exited 0 and published an uncompressed POSIX tar named `.zip`, advertised in
`latest.json` under `windows-cli`. Reproduced locally: `tar -a -c -f t.zip -C s klardrop.exe`
→ `file t.zip` = `POSIX tar archive (GNU)`. The step now runs under `pwsh` with
`Compress-Archive` and asserts the container's `PK` magic bytes; the same staging is mirrored in
a new `windows-release-package` PR job that also checks the archive extracts to `klardrop.exe` at
its root.

Nightly shipped **no Windows job at all** — neither the MSI nor the CLI, while stable shipped
both. `release-nightly.yml` now has a `windows` job (JDK 21, `:desktop:packageReleaseMsi`,
`cargo build --locked --release`, the same pwsh staging, MSI collection) and its `latest.json`
emits the same `windows` and `windows-cli` keys as stable.

The published `windows-cli` key had no consumer: `UpdateChecker` declared `ASSET_MACOS_CLI` but
no `ASSET_WINDOWS_CLI`, and `downloadUrl()` returned `manifest.notes` for Windows. Added the
constant and the `OsType.WINDOWS -> p[ASSET_WINDOWS] ?: p[ASSET_WINDOWS_CLI]` fallback, pinned
by two new tests (`windowsWithoutAnMsiFallsBackToTheNativeCli`,
`windowsPrefersTheInstallerOverTheCli`). `packaging/README.md` now names the collision that
matters to a user — the jpackage GUI launcher is itself `C:\Program Files\Klardrop\app\klardrop.exe`
— and gives a PowerShell install path that avoids it.

### The installer's "rollback" deleted the engine

When the engine rename succeeded and the client rename failed, `install.sh` did
`rm -f "$KLARDROP_ENGINE_BIN"` and reported *"the engine was rolled back."* No backup was taken
anywhere, so a user who had a working install was left with **no engine at all** during the one
moment the installer was supposed to be safest. It now moves the previous engine aside before
the swap and restores it on failure, keeping the backup until both files are verified; the
no-previous-engine case says so explicitly instead of claiming a rollback. The harness assertions
were `[ -e X ] && grep … && fail`, which short-circuit to success when `X` is absent — they
passed on the destructive behaviour. They are now positive (`[ -f … ]` first, then content), and
a mutation restoring the `rm -f` fails with
`FAIL: the engine was deleted by the failed client swap; there is nothing left to restore`.

### Smaller real defects

| Defect | Fix |
| --- | --- |
| `packaging/linux/stage-native-tarball.sh`: `check_elf_arch` had no `else`, so a runner with neither `readelf` nor `file` staged every binary **unverified** and exited 0; an unknown `expected` also fell through both `case` arms | both now `exit 1` — verification that cannot run is not verification |
| `packaging/aur/klardrop-{qt-bin,omarchy}`: `depends=('klardrop-native-bin')` unpinned, so the frontend/plugin could drive a different release's engine | `depends=("klardrop-native-bin=${pkgver}")` |
| `share --pick` started a **second** `Instant::now() + timeout`, so `--timeout 120` could run for 240 s | one budget; the picker and the submission both get what is left of it |
| `klardrop discover` printed device lines to **stderr** (`out.note`) and only the summary to stdout; the Kotlin command printed both to stdout | devices go to stdout, so `klardrop discover > devices.txt` has the list |
| Ctrl-C during `share --wait` killed the client on the default disposition: exit 130 from the shell, **no envelope, no request id** | `cli-rust/src/interrupt.rs` sets a flag from a `signal(2)` handler; the wait loop exits through the existing "delivery unknown" path with `cancelled`, exit 130 and the request id. `tests/interrupted_wait.rs` drives the real binary, sends a real `SIGINT`, and then re-reads the request with `transfers --id`. Removing `interrupt::install()` fails it |
| `TerminalGuard::enable_keyboard_enhancement` was `#[allow(dead_code)]` and never called — dead code documenting a capability the client does not have. Negotiating it needs a capability query, which is exactly the input-leak risk the plan warns about | deleted, with `Undo::KeyboardEnhancement` and its restore arm; the enum comment now says why it is absent |
| `ErrorCode::TerminalRequired` carried `#[allow(dead_code)]` and a "until that phase lands" comment though both call sites exist | attribute and comment removed |
| Neither error-code table test listed `cancelled` / 130 | added to both; 130 is now pinned in the published table, not only in a PTY test |
| `the_app_group_matches_the_repository` proved only that the App Group string *appears* in three files; deleting the line that routes the Kotlin path through it would leave the string in a comment and the test green | also asserts the identifier is a quoted constant, that it is the argument to `containerURLForSecurityApplicationGroupIdentifier`, and that the write still goes through `unixWriteControlFile(resolveControlFilePath()`. Changing the container call to a different group fails it |
| `iosApp.xcodeproj`: the KlardropMac **Debug** config listed `-framework control_plane` *after* `$(inherited)`, while its own comment said "control_plane comes FIRST on purpose" and the Release config had it first. That is the duplicate-symbol ordering the comment warns about | both configs now list it first |
| `cli-rust/scripts/tui-idle-cpu.sh` measured `target/debug` while the report presented the numbers as the shipped client's cost, and parsed `/proc/<pid>/stat` after the *first* `)` while its comment said the last — a legal `comm` containing `)` shifts every field by one, silently | measures `target/release/klardrop` (override with `KLARDROP_BIN`); scans for the last `)` |
| `cli-rust/Cargo.toml` declared `license = "MIT"` while the repository ships one Apache-2.0 `LICENSE` | `license = "Apache-2.0"` |
| Tokyo Night palette attributed only by URL | explicit MIT attribution in `cli-rust/README.md` and the `tui::theme` module doc; no upstream code is vendored |
| `cli-rust/README.md` said bare `klardrop` "print[s] help to stdout, exit 2" while `main.rs` opens the TUI when both ends are terminals — the same file contradicted itself two sections later | corrected |
| `packaging/README.md`: the native-tarball layout showed a `klardrop-engine/` directory above `bin/` that the stager never creates, and its new Windows section claimed the macOS image is notarized while the rest of the file said there is no Developer ID | both corrected against `stage-native-tarball.sh` and `release.yml` |

### Two prior claims that did not hold up

- "`scripts/cli-integration-tests.sh` unchanged" and "the Kotlin `cli` module untouched apart
  from additive shared-control wiring" were both false: that script has 108 changed lines and
  `CliController.kt`, `CliLogging.kt`, `Main.kt`, `DiscoverCommand.kt`, `ListenCommand.kt` and
  `SendCommand.kt` all changed for the native port. The acceptance criterion (old diagnostics
  still work) still holds; the evidence sentence did not.
- The Phase 2 table attributed "ordinary commands never start an engine" to
  `scripts/klardrop-rust-fixture-tests.sh`, which never invokes a submitting command. The
  evidence is the PID census in `klardrop-two-peer-transfer-tests.sh` bracketing a real
  `share --to … --wait --json`.

One reviewer finding did **not** reproduce and is recorded as such: `:cli:linkReleaseExecutable${{ matrix.target }}`
was reported as producing the unresolvable `linkReleaseExecutablelinuxX64`. Gradle resolved it
to `linkReleaseExecutableLinuxX64` in both a real and a `--dry-run` invocation. The matrix now
carries an explicit `linkTarget: LinuxX64` / `LinuxArm64` anyway, because one value driving two
different naming conventions (`linkReleaseExecutableLinuxX64` vs `linuxX64Test`) is a trap
worth removing even when it currently resolves.

### Qt: checked against a real engine, not against route strings

The earlier pass recorded Qt compatibility as "verified by grepping every control-plane path
literal in the repo", with no Qt test run in the final table. Both halves are now real:

- `python3 linux/qt/tests/test_integration.py` → **ALL INTEGRATION TESTS PASSED SUCCESSFULLY**
  (11 suites: control discovery and bearer auth, action routing and the focus/history lifecycle,
  bounded backoff on a malformed 200, reconnect + credential rotation, held-poll rotation
  without SIGSEGV, composer draft retention, `--check-runtime` isolation, single-instance
  activation, update parsing and the 409 no-retry path, screenshot capture, offscreen QML with
  zero binding errors).
- `bash scripts/klardrop-qt-real-engine-tests.sh` (new) → **14 run, 14 passed**, containing
  **36 response-shape checks** against a *real* engine for every field `clientbridge.cpp`
  parses (`/state.version`, `self`, `settings.*`, `devices[].{deviceId,deviceName,trustStatus,
  hasUnread,unreadCount}`, `pairingDialog` present-and-object-or-null, `incoming`,
  `transfers`, `notifications`, `qrShare`, `update`; `/history.messages` + `nextBefore`;
  `/history/read` accepted for an unknown device; a `/settings` round trip visible in `/state`;
  `/send-text` to an unknown device **not** reporting `result: completed`; `/update/check`;
  `/capabilities` agreeing with the control file; `POST /share` rejecting an empty body;
  `GET /transfers`; an unknown route still 404; an unauthenticated `/state` still 401). It
  then runs the **real `klardrop-qt`** through a recording reverse proxy in front of that
  engine, asserting the binary issues authenticated requests, that every one is answered 2xx,
  that it emits no QML errors, never reports a connection failure, and tears down on SIGTERM
  without a crash signal.

Both run in a new `qt-regression` PR job — previously Qt tests ran only in the tagged release
workflows, so a Qt/control-plane regression merged green.

### PTY coverage: what the review found missing, and what is now exercised

The Phase 3 acceptance list names keyboard selection, scrolling, resize, a real send, no
devices, offline state, pairing/incoming decisions, cancellation, EOF and terminal restoration.
Four of those had no executed test, and two more had tests that could not fail. What changed:

| Case | Before | After |
| --- | --- | --- |
| conversation scroll | `assert_ne!(before, after)` — a blank screen satisfies it; the captured frame was 1 byte | asserts the client is still running **and** that an earlier message is on screen and a later one is not. Restoring `(end - tail_start)` fails both tests |
| scroll with no transfer in flight | untested — and this is the exact configuration that panicked | new test: `scrolling_with_no_transfer_in_flight_does_not_kill_the_client`, sending `PAGE_UP`/`UP` and then requiring a clean Ctrl-C exit |
| resize | `screen.len() <= 14` and `chars().count() <= 60`, both guaranteed by the harness's own re-geometried parser | asserts the **pane count** changes: two boxes at 100 columns, one below `TWO_PANE_MIN_WIDTH`, two again after growing back — a client that ignored the resize, or latched into single-pane and never left, fails |
| escape safety, full screen | covered for line mode only; the panes were "verified by inspection" | new test drives the `hostile_names` daemon: no BEL, no NUL, and no `\x1b]0;` — the window-title OSC **executed**. The printable residue `]0;pwned` must still be on screen, because dropping the user's characters entirely would also satisfy every safety assertion |
| no devices | the fixture could produce `[]` but nothing drove it | new test asserts the header says `0 devices` and the pane invites a wait rather than looking broken |
| interrupted wait | no signal handler existed | new `tests/interrupted_wait.rs` (see above) |

The escape test found nothing broken, which is itself the point: the claim had been made by
reading `escape::sanitize` call sites rather than by running one. It is now executed, and the
first draft of the assertion was wrong in an instructive way — it asserted the bare substring
`]0;pwned` was absent, which fails on a client doing exactly the right thing (strip ESC, keep the
characters). The correct property is that the ESC is gone and the residue is inert text.

Rust suite after these changes: **313 tests, 0 failures** — 216 unit, 50 `cli_contract`,
1 `interrupted_wait`, 17 `tui_flows`, 4 `tui_input_edges`, 5 `tui_line_mode`, 4 `tui_motion`,
3 `tui_mouse`, 3 `tui_rendering`, 10 `tui_theme`. `cargo clippy --all-targets -D warnings` and
`cargo fmt --check` clean.

Still **not** covered by an executed PTY test, and recorded as such rather than assumed: a
composed message being submitted with Enter, an `Offline` connection state, and the QR overlay
(the fixture always answers `qrShare.active: false`). Those are gaps in the evidence, not
claims that the features work.

### Checks that ran in this pass

| Check | Command | Outcome |
| --- | --- | --- |
| Qt integration suite | `python3 linux/qt/tests/test_integration.py` | ALL PASSED |
| Qt vs. the real engine | `bash scripts/klardrop-qt-real-engine-tests.sh` | 14/14 (36 shape checks inside) |
| Two-peer transfer, receipt, memory | `bash scripts/klardrop-two-peer-transfer-tests.sh` | 32/32, daemon RSS 35,651,584 B |
| Native Linux unit tests | `./gradlew :klardrop-common:linuxX64Test` | BUILD SUCCESSFUL |
| JVM common tests | `./gradlew :klardrop-common:desktopJvmTest` | BUILD SUCCESSFUL, 632 tests, 0 failures |
| Native engine release link | `./gradlew :cli:linkReleaseExecutableLinuxX64` | BUILD SUCCESSFUL |
| Installer harness | `bash packaging/linux/test-omarchy-install.sh` | ALL INSTALLER TESTS PASSED |
| Rust suite (unit + contract + PTY) | `cargo test --locked` | **313 passed / 0 failed** |
| Rust lint / format | `cargo clippy --locked --all-targets -- -D warnings`, `cargo fmt --all --check` | clean |
| Rust release build | `cargo build --locked --release` | clean |
| Native fixture (client vs. real engine) | `bash scripts/klardrop-rust-fixture-tests.sh` | 18/18 |
| Production JVM-host fixture | `bash scripts/klardrop-jvm-host-fixture-tests.sh` | 22/22 |
| Control-plane JVM tests | `./gradlew :control-plane:desktopJvmTest` | BUILD SUCCESSFUL |
| Workflow YAML | `yaml.safe_load` on all three workflows | parse |
| pbxproj structure | brace/paren balance + link-flag order in both configs | balanced, both first |
| Mutation checks | installer rollback, App Group routing, SIGINT handler, scroll underflow | each mutation fails its test |

Mutation testing was used deliberately for the four fixes whose tests could otherwise pass
vacuously: reverting the installer rollback to `rm -f`, changing the Kotlin container call,
removing `interrupt::install()`, and restoring the `(end - tail_start)` underflow each produce a
named `FAIL`.

---

## Phase 1 — Rust client, production control, and read-only commands

### Superseded Kotlin-client cleanup

The Kotlin client work begun before the user selected Rust was reverted before this phase's work
began. Recorded here because it is part of the first-phase diff:

- Restored from `/tmp/klardrop-omp-kotlin-work/changed-files.json`: the six files that session
  edited were restored to their pre-session state from `/tmp/klardrop-omp-phase1-before/`.
- Deleted the five files that session created and that have no pre-session counterpart.
- The pre-existing Kotlin engine (`cli/.../Main.kt`, `DiscoverCommand`, `ListenCommand`,
  `SendCommand`, `StatusCommand`) and all unrelated branch work (Android/iOS/Qt/updater/installer)
  were retained. The worktree was never reset.
- Protocol observations from that work were used as reference only; there is exactly one
  production CLI client under development (`cli-rust/`).

### What changed

**Shared production control component (`debug-control` → `control-plane`)**

- One implementation, no duplication: `:control-plane` is depended on by `:desktop` (jvmMain),
  `:cli` (commonMain) and `:android` (debug variant only). `:klardrop-common`, `:presentation`
  and `:compose-ui` have no edge to it, so there is no dependency cycle.
- `ControlPlaneService` ships in **every** build. `desktop/src/jvmMain/kotlin/Main.kt` calls
  `ControlPlane.start(klardrop)` unconditionally (`startProductionControlPlane`) and
  `ControlPlane.bind(controller, k)` from `onDiscoveryControllerAvailable`, with
  `ApplicationInfo.controlPort` defaulting to `0` (ephemeral) for non-debug runs.
- Debug-only routes (`/logs`, `/window`, `POST /reset-identity`) remain 403-gated in release
  builds and are absent from the advertised capability list.
- `GET /capabilities` (`apiVersion: 1`) plus the capability array frozen into `control.json`.
- Control-file protection: JVM writes `rw-------`/`rwx------` at creation and re-asserts them,
  and falls back to an explicit owner-only ACL on Windows, throwing when neither view exists;
  the native POSIX actual sets `S_IRUSR|S_IWUSR` on the file and `S_IRWXU` on the directory via
  `chmod`/`fchmod` after `mkdir`/`open`. `ControlPlane.start` fails closed: if the file cannot be
  written or protected it stops the listener, drops the token and rethrows, so a bound listener
  never has an unpublished token.

**Rust client (`cli-rust/`)** — `klardrop`, a single blocking binary, no async runtime:

- `devices`, `status`, and the compatibility `discover` (legacy JSON array shape preserved).
- Loopback-only transport on the literal `127.0.0.1`; no hostname, no redirect, no environment
  or system proxy; one request per connection; `Authorization: Bearer <token>` never logged.
- Bounded control-file read (1..=4096 bytes, JSON object, port 1..=65535, token 1..=256 chars of
  `[A-Za-z0-9_.~-]`), bounded response header line/block and a 4 MiB response body ceiling
  (parity with the Qt client's ceiling), chunked transfer encoding rejected outright.
- Deadline accounting layered three ways: a whole-command `Instant` deadline, per-socket-operation
  caps of 2 s, and a `DeadlineReader` that fails once the absolute deadline has passed, so a peer
  that trickles one byte per interval cannot outlive `--timeout`.
- `--json` emits exactly one JSON value on stdout on both success and failure; all diagnostics,
  progress and `--debug` logging go to stderr; the token never appears in either stream.
- Exit-code map implemented and unit-pinned: 0 / 1 / 2 / 3 / 4, matching the plan's table.

### Fixture coverage and checks that ran here

| Check | Command | Outcome |
| --- | --- | --- |
| Rust unit tests | `cargo test --locked` (lib/bin unit tests) | 31 passed, 0 failed |
| Rust contract tests | `cargo test --locked` (`tests/cli_contract.rs`, real binary vs. fixture daemon) | 23 passed, 0 failed |
| Control-plane module tests | `./gradlew :control-plane:desktopJvmTest --console=plain` | **BUILD SUCCESSFUL**, 71 tests, 0 failed |
| Common JVM tests | `./gradlew :klardrop-common:desktopJvmTest` | BUILD SUCCESSFUL |
| Presentation JVM tests | `./gradlew :presentation:desktopJvmTest` | BUILD SUCCESSFUL |
| Desktop JVM tests | `./gradlew :desktop:jvmTest` | BUILD SUCCESSFUL (combined run above: 2m11s) |
| Fixture script syntax | `bash -n scripts/klardrop-rust-fixture-tests.sh` | OK |
| JVM-host fixture script syntax | `bash -n scripts/klardrop-jvm-host-fixture-tests.sh` | OK |
| CI YAML validity | `python3 -c "import yaml;yaml.safe_load(...)"` for each workflow | all parse; `build_pr.yml` jobs: build-linux, build-macos, rust, desktop-jvm-host-macos, jvm-host-fixture |

Two compile-level defects found and fixed while running the above:

- `DiscoveryController.startPreparedFiles` used a bare `launch { }` after the send loop was
  lifted out of `coroutines.appScope.launch`, which does not compile
  (`'launch' can not be called without the corresponding coroutine scope`). Fixed by naming the
  scope explicitly.
- `ControlPlaneShareTest`'s `@BeforeTest`/`@AfterTest` hooks used expression bodies returning the
  last expression's value, which JUnit 4 rejects (`Method tearDown() should be void`). Fixed by
  pinning the return type to `Unit`.

### Phase 1 acceptance, item by item

| Acceptance criterion | Status | Evidence |
| --- | --- | --- |
| `devices`/`status`/`discover` query one existing fixture engine without starting listeners or discovery | met | `scripts/klardrop-rust-fixture-tests.sh` starts the engine with `--no-klardrop --no-nearby --no-ble`; the client never opens a socket other than its own loopback request |
| Absent / stale / malformed metadata fails promptly | met | contract tests `absent_control_file_reports_not_running`, `malformed_control_files_are_rejected_with_exit_3`, `stale_control_file_fails_fast_without_hanging` (all exit 3, bounded elapsed) |
| JSON stdout parses cleanly, including with debug logging and on failure | met | `json_stdout_stays_parseable_with_debug_and_on_failure`; fixture script asserts one JSON value and zero `\x1b`/`\x07` bytes |
| Old diagnostic workflows and legacy `send` still operate in separate profiles | met | Kotlin `cli` module untouched apart from additive shared-control wiring; `scripts/cli-integration-tests.sh` unchanged |
| No Qt or Omarchy protocol regression | met | no existing route's status or body changed; `:control-plane` adds only `POST /share` and `GET /transfers`. **`scripts/klardrop-ctl` IS modified** — it gains control-file discovery, bearer auth on `http_get`/`http_post`, and ~15 new subcommands — which is additive and route-compatible, but it is not untouched as an earlier draft of this report claimed |
| Production control exercisable without a debug-only build flag | met | `:control-plane:desktopJvmTest` includes `ControlPlaneProductionTest` (release build: `isDebug=false`, `controlPort=0`) asserting the owner-only control file, 401 without a token, and the production capability list |
| Windows/macOS validation recorded separately | **gap, now closed on macOS by implementation and still unrun** | see "Native macOS production control" below and "Remaining release gates" |

### Honest gaps in Phase 1

- **Windows was not executed here.** The Windows ACL branch (`ControlFile.desktopJvm.kt`
  `protect()`) compiles and is covered by the JVM test source set on any platform, but it has
  never run on Windows here.
- **macOS: the earlier claim in this section was wrong, and is corrected below.** It said "there
  is no `:macos` KMP module … so the native macOS engine in the plan preamble does not match this
  repository". That inference was drawn from `settings.gradle.kts` and was **false**: the shipped
  native macOS app is not a Gradle module of its own. It is `presentation`'s `macosArm64`
  framework (`presentation/build.gradle.kts`), driven by the Xcode targets in
  `iosApp/iosApp.xcodeproj` (`KlardropMac`), with `MacApp.swift` as the Swift entry point, and it
  is built and shipped by `build-macos-native` in `.github/workflows/release.yml`. The absence of
  a `:macos` module says nothing about `macosMain` targets. The real gap was that
  `presentation/src/macosMain/.../KlardropBootstrap.kt` constructed `ApplicationInfo()` with the
  default `controlPort = null`, so `ControlPlane.start` returned immediately and the app served
  no local IPC at all. That gap is now closed — see the next section.
- Those two platforms are covered by configured CI jobs rather than by claims: `rust` runs on
  `ubuntu-latest`, `macos-14` and `windows-latest`; `build-macos` compiles and tests the native
  macOS host; `desktop-jvm-host-macos` builds the *separate* JVM desktop app.
- `:desktop:proguardReleaseJars` cannot be verified standalone on this checkout — it fails
  identically before and after this work. Packaged release paths are Phase 4.

---

## Native macOS production control — closing the Phase 1 gap the first run misdiagnosed

This is the correction-and-completion pass. The Phase 1 note above concluded that macOS was a
JVM-only product and that wiring the native app would mean "adding dead targets". Both halves of
that were wrong, and the cost was a shipped macOS app with no local IPC at all: `klardrop
devices`, `klardrop status`, `klardrop share` and the TUI all had nothing to talk to on the
platform whose product *is* the native app.

### What the native macOS app actually is

There is no `:macos` Gradle module, and there does not need to be one — the native app is built
from modules that already exist:

| Piece | Location |
| --- | --- |
| Apple target | `presentation/build.gradle.kts` → `macosArm64 { binaries.framework { baseName = "presentation" } }` |
| Swift entry point | `iosApp/iosApp/App/MacApp.swift` (`@main KlardropMacApp`) |
| Xcode target | `iosApp/iosApp.xcodeproj` → `KlardropMac`, SwiftUI + `presentation.framework` |
| Kotlin host object | `presentation/src/macosMain/.../KlardropBootstrap.kt` |
| Shipped by | `.github/workflows/release.yml` → `build-macos-native` (archive, notarize, DMG) |

The actual defect was one line of behaviour: `KlardropBootstrap` built `ApplicationInfo()` with
its default `controlPort = null`, and `ControlPlane.start` returns immediately when
`controlPort` is null. No server, no `control.json`, no `klardrop` on macOS.

### Why the wiring could not live in `:presentation`

`:control-plane` already depends on `:presentation` (the routes call `DiscoveryController`).
Starting the server from `KlardropBootstrap` would be a dependency cycle. The cycle is broken
the other way round, which is also the shape the JVM host already uses (`desktop/` depends on
`:control-plane`):

- `:control-plane` gains a **`macosArm64`** target producing a second, static framework,
  `control_plane.framework`.
- `project.pbxproj` finds it exactly the way it already finds the synthetic Sentry framework —
  a `FRAMEWORK_SEARCH_PATHS` entry plus `-framework control_plane` in the KlardropMac target's
  `Debug` and `Release` `OTHER_LDFLAGS` — and `build-macos-native` / the nightly macOS job /
  the PR macOS job build it with `:control-plane:link{Release,Debug}FrameworkMacosArm64`
  before `xcodebuild`.
- `MacApp.swift` does `import control_plane` and calls `ControlPlane.shared.start(app:)` then
  `bind(discoveryController:app:)`, passing **`model.controller`** — the same
  `DiscoveryController` the menu bar and window already render from. One engine, one controller.
- No CocoaPods pod was added: the KMP cocoapods plugin exists in `:presentation` only to produce
  a podspec for Sentry's synthetic framework. A plain Gradle-built framework plus a search path
  is the mechanism this project already uses, and it is one fewer moving part than a second pod.

### The control-file location, and why it is not `~/.cache`

The macOS app is **sandboxed** (`com.apple.security.app-sandbox` in `KlardropMac.entitlements`),
so it cannot write outside its own container — and `~/.cache/klardrop/control.json` is not in it.
The one location a sandboxed app and an *unsandboxed* `klardrop` CLI in Terminal can both reach is
the App Group the app already shares with its share extensions:

```
$HOME/Library/Group Containers/D7T5425WSW.group.com.carlom.Klardrop/control.json
```

That identifier already appears in three places (`ShareInbox.appGroupID`,
`KlardropMac.entitlements`, and now the `:control-plane` macOS actual). The Rust client probes
that path first on macOS, and only when it really holds a file, so the separate non-sandboxed
JVM desktop host on the same machine (which still uses `~/.cache`) is still found.

The constant is duplicated in four languages' worth of files, so it is pinned by a test that runs
**here, on Linux, on every `cargo test`**: `control_file::tests::the_app_group_matches_the_repository`
reads the Swift, entitlements and Kotlin files and fails if any of them stops naming the same
group. Drift would otherwise ship as "every macOS command reports the daemon unavailable".

### Two capabilities this host genuinely cannot serve

`qr-share` and video thumbnails both need an external binary (`qrencode`, `ffmpeg`) — the Linux
engine shells out to both. **This app cannot run one.** The rule is narrower than "a sandboxed
process may only execute code inside its own bundle": Apple permits execution from the app
bundle, its sandbox container, its App Group container, and user-selected files when
`com.apple.security.files.user-selected.executable` is granted. What decides it here is that
`KlardropMac.entitlements` grants `files.user-selected.read-write` and nothing broader, so
`/opt/homebrew/bin/qrencode` and `/usr/local/bin/ffmpeg` are outside everything this app may
execute, and `NSTask`/`posix_spawn` of them is refused. qrcode-kotlin, the encoder the JVM host
uses, publishes no `macOS` artifact either (`iosArm64`, `iosSimulatorArm64`, `iosX64`, `tvos*`,
`jvm`, `js`, `wasm`, `android` — no macos), so the Linux approach would be code that can only
fail at run time.

Rather than advertise a route that can only fail, `forBuild()` now subtracts a per-platform
`platformUnavailableCapabilities` set. On macOS it is `{"qr-share"}`:

- `renderQrMatrix` **throws** with the reason, so the route 500s loudly if called anyway;
- `/qr-share` is absent from the `control.json` capability list and from `GET /capabilities`;
- the Rust TUI already gates its QR entry on `has_capability("qr-share")`
  (`tui/app.rs`), so the action disappears from the menu rather than failing when pressed.

Video thumbnails return `false` and are not an advertised capability at all — `/thumbnail` exists
for the Qt and Omarchy frontends, which run against hosts that do extract frames.

This is a real, recorded loss of a feature the JVM macOS app has. The alternative was shipping
CoreImage `CIQRCodeGenerator` bindings that cannot be compiled or exercised on a Linux host; the
honest capability gap is the better trade, and it is listed as an open gate below.

### Fail-closed change to `ControlPlane.start`

`start()` previously computed `genToken = if (resolveControlFilePath() != null) generateToken()
else null` and, on any host with no path, bound a loopback listener that accepted
**unauthenticated** requests against the device's identity and files. With macOS in play that is
now reachable in anger (a signed app whose App Group cannot be resolved). `start()` now refuses
before binding unless the platform declares `controlFileOptional` — true only on Android, whose
harness reaches the port through `adb forward`. On Windows with no `LOCALAPPDATA` this also
changes "serve unauthenticated" into "refuse to start", which is the correct direction.

### Other engine changes this pass

- `ControlFile.linux.kt` moved to a hand-declared **`posixMain`** source set shared verbatim by
  the Linux and macOS engines (`mkdir` → `chmod 0700` → `open` → `fchmod 0600` → write, plus the
  token-scoped delete). The default hierarchy template has no group covering {linux, macos} —
  `unixMain` genuinely does not exist — so the source set is declared explicitly and attached to
  `linuxMain` and `macosMain`. Without that declaration `src/posixMain` would silently not be
  compiled and Gradle would report no error at all.
- `KLARDROP_CONTROL_FILE` pins the exact control file on both POSIX hosts. `XDG_RUNTIME_DIR`
  already relocates the Linux path; macOS has no such ambient variable and a Gradle-run test
  binary has no app-group entitlement, so this is the hook the macOS fixture uses to exercise the
  real write/protect/delete path.
- `KlardropBootstrap` now takes `controlPort` (default: an ephemeral port, overridable with
  `KLARDROP_CONTROL_PORT`, `-1` disables local IPC) and resolves `uiDependencies` **once** via
  `by lazy`. Previously `private val uiDependencies get() = UiDependencies(...)` built a new
  `DiscoveryController` on every access; Swift worked around that with a "never call
  discoveryController() more than once" comment. The control plane needs the same handle, so the
  invariant now lives in Kotlin instead of in a comment.

### Checks that ran here for this pass

| Check | Command | Outcome |
| --- | --- | --- |
| Rust unit + contract + PTY tests | `cargo test --locked` (in `cli-rust`) | **216 unit + 49 contract + 15 + 5 + 4 + 3 + 2 + 10 integration, 0 failed** — includes the two new macOS discovery/consistency tests |
| Rust lint / format | `cargo clippy --locked --all-targets -- -D warnings`, `cargo fmt --all --check` | clean |
| Control-plane JVM tests | `./gradlew :control-plane:desktopJvmTest --rerun-tasks` | **BUILD SUCCESSFUL** |
| Control-plane Linux/Native compile | `./gradlew :control-plane:compileKotlinLinuxX64 --rerun-tasks` | **BUILD SUCCESSFUL** (the shared `posixMain` set compiles for a real native target) |
| Kotlin/Native engine release link | `./gradlew :cli:linkReleaseExecutableLinuxX64` | **BUILD SUCCESSFUL in 1m 45s** |
| Native fixture (Rust client + real engine) | `bash scripts/klardrop-rust-fixture-tests.sh` | **18 run, 18 passed** — control file published, owner-only 0600, no second engine spawned |
| Production JVM-host fixture | `bash scripts/klardrop-jvm-host-fixture-tests.sh` | **22 run, 22 passed** — full production capability list, live `POST /share` + `GET /transfers` |
| Two-peer transfer + memory | `bash scripts/klardrop-two-peer-transfer-tests.sh` | **31 run, 31 passed** — 3,000,000-byte payload received byte-for-byte, sender exits 0 with a `completed` envelope, **sender daemon VmRSS 35,084 KiB = 35,926,016 bytes < 50,000,000** |
| Workflow YAML validity | `python3 -c "import yaml,sys;yaml.safe_load(...)"` on all three workflows | parse |

### What is NOT verified (no macOS runner exists on this machine)

None of the following ran. They are configured as CI gates and are stated as gates, not results:

1. `:control-plane:compileKotlinMacosArm64` / `linkReleaseFrameworkMacosArm64` — the Apple target
   compiles and links. The `macosArm64`/`macosMain`/`posixMain` source sets are wired and
   configured on Linux (Gradle configuration succeeds and the Linux native target compiles), but
   no Apple code has been through a Kotlin/Native compiler on this host.
2. `:control-plane:macosArm64Test` — `ControlPlaneMacosTest`, six tests: control-file write,
   0600 narrowing, token-scoped delete, the App Group directory lookup and the fail-closed
   decision built on it, the withheld `qr-share` capability, the loud QR failure, and that
   `LoopbackHttpServer` binds an ephemeral loopback port on Darwin.
3. The `KlardropMac` Xcode build with `import control_plane`, the `-framework control_plane` link,
   and `MacApp.swift`'s `ControlPlane.shared.start/bind` calls.
4. A macOS two-peer transfer, and `klardrop devices` against the real native app.
5. Notarization/signing of the app now that a second static framework is in the bundle.

If the framework wiring is wrong, **the first failure is a Swift compile error, not a link
error**: the generated `control_plane` header has to be able to name `Klardrop` and
`DiscoveryController`, which is what `export(project(":presentation"))` is for — the same reason
`:presentation` exports `:klardrop-common`. Past that, the link has its own hazard, in the
opposite direction to the one this report originally described: `control_plane.framework` is
static and carries its own copy of `:presentation` and `:klardrop-common`, exactly as
`presentation.framework` carries its own copy of `:klardrop-common`. Two archives defining the
same classes is a duplicate-symbol error — unless only one of them is ever pulled. `ld64` pulls
an archive member only while it resolves a pending undefined symbol, so the framework scanned
first wins and the second is never pulled; that is why `-framework control_plane` sits **before**
`$(inherited)`. Move it back to the end and the link breaks. The
`-Xlinker -undefined -Xlinker dynamic_lookup` already in the target's `OTHER_LDFLAGS` papers over
unresolved *function* symbols; unresolved *data* symbols would not be covered.

---

## Phase 2 — Correlated transfer results and agent commands

### What changed

**Engine side (`common`, `presentation`, `control-plane`)**

- `POST /share` and `GET /transfers[?id=]` are new, purely additive routes. Every pre-existing
  route's status code and response body is unchanged, and `/state`'s `transfers[]` array keeps
  exactly the file transfers it always had.
- `ShareRegistry` (`control-plane/src/commonMain/kotlin/com/carlom/klardrop/control/ShareRegistry.kt`)
  is a bounded, in-memory, lock-free-to-read record of accepted submissions: at most **256**
  requests, least-recently-inserted evicted first, a **2 hour** TTL, pruning on every insert and
  every lookup. It is not a second transfer database; it holds ids and last-known item outcomes
  and forgets everything else.
- A request gets a stable `requestId` and every item a stable `transferId` **at submission**, so a
  multi-file share reports all its ids up front instead of one unobservable send per file.
- Item statuses are `queued → awaiting → transferring → completed | declined | failed`, driven by
  the existing `MessengerSendProgress` / `ReceiveMessageStatus` flows. A terminal outcome is
  retained (previously it was erased from `/state`), and a late event can never regress a terminal
  item.
- Declines are now explicit. `Messenger.kt` tags the Klardrop `TransferRejectedException` with
  `reason = "declined"`, and a new `NearbyTransferRejectedException` gives the Quick Share path the
  same signal, so a peer that refuses the handshake is reported as `declined`, not a generic
  failure.
- `DiscoveryController` gained `prepareOutgoingFiles` / `startPreparedFiles` /
  `startTrackedTextSend` / `sendTextTracked` and an additive `OutgoingTransfer.kind`. `sendFiles`
  is re-expressed on top of them with unchanged observable behaviour (fire-and-forget scope,
  strictly sequential per-file send, same emit order, same progress collection).
- Guard rails: a path that is not a readable regular file is reported as a failed item with a
  `null` transfer id rather than queued for bytes that cannot be sent; at most **64** paths per
  request (400 otherwise); a blank `text` payload is refused rather than sent as an empty message;
  `/share` and `/transfers` answer **503** until the host has bound a `DiscoveryController`;
  `ControlPlane.stop()` clears the registry and drops the engine binding, so a stop/start is a
  fresh daemon for every client that can see the control file.

**Client side (`cli-rust`)**

- `POST` support in the bounded loopback client (same agent policy, same ceilings, a new 64 KiB
  request-body ceiling, `Content-Length`/`Content-Type` set explicitly, body never logged).
- `share --to <id> <paths…> | --text | --clipboard`, with `--wait`, `--timeout`, `--json`.
  Without `--wait` it reports **queued**, never delivered. With `--wait` it polls
  `GET /transfers?id=` and succeeds only when the request *and every item* is `completed`.
- `transfers [--id]` for inspection, and the legacy `send DEVICE_ID --file|--text` compatibility
  command, which now defaults to waiting for terminal delivery so its historical "success means
  delivered" meaning is preserved.
- Device resolution: exact id first, then a unique prefix. No matches → `device_not_found` (exit 2),
  several → `ambiguous_device` (exit 2) listing the candidates. Never a display name, never the
  first candidate.
- Mixed payload kinds are refused before anything is sent, with guidance toward interactive
  selection. Local paths are validated (existence, regular file, UTF-8) → `invalid_path` (exit 2).
- A wait that cannot establish the delivery state emits `status: "unknown"` with the known
  `requestId`, names `klardrop transfers --id <id>`, and exits 4 (deadline) or 3 (forgotten id).
  Nothing is cancelled and nothing is ever retried automatically.

### Independent review and what it changed

Two fresh reviewers (neither of whom wrote the code) reviewed the engine and the client. Every
finding below was a real defect that the implementers' own green test runs had missed. All are
fixed, and each fix is pinned by a test that fails without it.

| Finding | Severity | Fix |
| --- | --- | --- |
| `POST /share` answers `"transferId": null` for an item it could not prepare, but the client typed it as a non-optional `String` — so one unsendable path made `share` report `daemon_unsupported` ("upgrade the daemon") and made the whole `transfers` listing unparseable | BLOCKER | `ShareItem.transfer_id` is `Option<String>`; the fixture daemon can now emit `null`; contract test `an_item_the_daemon_cannot_prepare_keeps_the_rest_of_the_answer_usable` plus unit test `an_item_that_was_never_sent_parses_with_a_null_transfer_id` |
| A directory passed local validation (`fs::metadata` accepts directories), so it reached the daemon and produced the null id above | MAJOR | the client now requires `metadata.is_file()` and answers `invalid_path`; contract test `a_directory_is_refused_before_anything_is_submitted` |
| `ureq` only raises `Err(Status)` for status ≥ 400, so the `300..=399` guard was dead code and a 3xx body was parsed as the daemon's answer — `devices` exited 0 on a stranger's page | MAJOR | `read_body` now rejects any 3xx as `daemon_protocol_error` before touching the body; fixture behaviour `redirect` + contract test `a_redirect_is_a_protocol_error_and_never_the_daemons_answer` |
| `MAX_REQUESTS` bounded records, not items, so one request with 200 000 paths defeated the whole retention policy | MAJOR | 64-path cap, 400 before any per-path work; contract test `a_daemon_refuses_an_oversized_path_list_before_submitting_anything` |
| A Nearby-transport recipient rejection was recorded as `failed`, not `declined` | MAJOR | `NearbyTransferRejectedException` + the same `declined` reason |
| A forgotten request id during `--wait` produced the context-free failure envelope, losing the request id the caller needs | MAJOR | `TransferUnknown` now routes through the same "delivery unknown" reporter |
| An over-long `--id` produced `internal_error` (exit 1) instead of `invalid_argument` (exit 2) | MAJOR | the request-target ceiling is an `InvalidArgument`; contract test `an_over_long_request_id_is_an_invalid_argument_not_a_client_bug` |
| `Chunked transfer encoding is rejected` and the header ceilings were documented but untested | MAJOR | fixture behaviour `chunked` + contract test |
| `rust-version = "1.80"` was below the locked graph's real floor (`icu_properties_data` needs 1.88), producing a confusing toolchain error | MAJOR | `rust-version = "1.88"` and a new `rust-toolchain.toml` pinning channel, `clippy` and `rustfmt` |
| `POST /share` and `GET /transfers` answered an empty 200 (or a 500) in the window between publishing the control file and binding the engine | MINOR | 503 with an explicit "not ready" message; release-build test `theSubmissionRoutesAreGatedOnABoundEngineInAReleaseBuild` |
| A blank `text` payload was sent as an empty message and answered `queued` | MINOR | refused with `text is empty` |
| `ControlPlane.stop()` left the registry populated, contradicting its own documented contract | MINOR | `stop()` clears it (and the engine binding) |
| `ControlPlaneProductionTest` compared the advertised capability list against the very constant it is built from, so it could not detect a missing or spurious capability | MINOR | asserted against a literal list, and the release-build suite now exercises the new routes |
| The retention tests bypassed `POST /share` and never checked that an evicted id disappears from the *listing* | MINOR | the listing assertion was added |
| `stdout_has_no_escapes` used `grep -P`; where PCRE is missing grep exits 2 and `!` turned that into a silent pass | MINOR | replaced with a python escape check |
| The JVM-host fixture asserted a capability set that omitted `share` and `transfers`, and never exercised the submission routes against the real host | MINOR | both added, including a live `POST /share`/`GET /transfers` round trip |

Two compile-level defects found while running the checks are also fixed: a bare `launch` in
`DiscoveryController.startPreparedFiles` (no enclosing scope after the refactor) and JUnit
lifecycle hooks with expression bodies (JUnit 4 requires `void`).

### Checks that ran here, after the fixes

| Check | Command | Outcome |
| --- | --- | --- |
| Rust unit tests | `cargo test --locked` (unit) | 70 passed, 0 failed |
| Rust contract tests | `cargo test --locked --test cli_contract` (real binary vs. fixture daemon) | 49 passed, 0 failed |
| Rust lint / format | `cargo clippy --locked --all-targets -- -D warnings`, `cargo fmt --all --check` | clean |
| Control-plane tests | `./gradlew :control-plane:desktopJvmTest --console=plain` | BUILD SUCCESSFUL, 77 tests |
| Common JVM tests | `./gradlew :klardrop-common:desktopJvmTest` | BUILD SUCCESSFUL |
| Presentation JVM tests | `./gradlew :presentation:desktopJvmTest` | BUILD SUCCESSFUL |
| Desktop JVM tests | `./gradlew :desktop:jvmTest` | BUILD SUCCESSFUL |
| Native Linux engine | `./gradlew :cli:linkReleaseExecutableLinuxX64` | BUILD SUCCESSFUL |
| Fixture script syntax | `bash -n` on both fixture scripts | OK |
| Manual end-to-end | release binary vs. fixture daemon: `devices`, `share --wait` (multi-file, flags after paths), `share --text`, mixed-payload refusal, missing path, ambiguous prefix, `transfers`, `send --file`, `send --text`, `transfers --id <unknown>` | every case produced the documented exit code and envelope; captured in Phase 4's evidence |

### Phase 2 acceptance, item by item

| Acceptance criterion | Status | Evidence |
| --- | --- | --- |
| Stable request/transfer ids for every submitted operation, including multi-file | met | `POST /share` returns `requestId` plus one `transferId` per item up front |
| Results associated with the actual submitted transfers | met | the registry binds `transferId → requestId` at submission and only that binding is updated; contract test `two_concurrent_requests_each_follow_only_their_own_ids` |
| Six outcomes exposed truthfully | met | `queued`/`awaiting`/`transferring`/`completed`/`declined`/`failed`, with `declined` distinguished on both transports |
| `share --wait`, `--json`, validated timeouts, `transfers` inspection | met | commands above |
| Partial multi-file completion is a nonzero result listing each item | met | contract test `a_partial_multi_file_share_is_a_nonzero_result_naming_every_item` |
| Legacy `send` through IPC, preserving delivery waiting | met | contract tests `legacy_send_waits_for_delivery_unless_told_otherwise`, `legacy_send_reports_a_decline_as_a_failure` |
| Ordinary commands never start an engine | met | the client has no engine-starting code path at all; asserted by the PID-set check in `scripts/klardrop-rust-fixture-tests.sh` |
| Bounded retention documented, unknown instead of false completion | met | concrete numbers above; 404 → `transfer_unknown`, exit 3 |
| Local preparation failures and declines visible; no automatic duplicate send | met | `invalid_path` locally, per-item failure server-side, and no retry anywhere in the client |
| Existing Qt/Omarchy endpoints keep working | met | every existing route unchanged; verified by grepping every control-plane path literal in the repo |

Representative output (fixture daemon, `share --wait` with flags after the paths):

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-0000000000000001","deviceId":"11112222","status":"completed","items":[{"transferId":"tx-1-0","path":"/tmp/x/report.pdf","fileName":"report.pdf","totalSize":12,"transferredSize":12,"status":"completed","error":null},{"transferId":"tx-1-1","path":"/tmp/x/notes.txt","fileName":"notes.txt","totalSize":6,"transferredSize":6,"status":"completed","error":null}]}
```

Failure output (ambiguous device prefix):

```json
{"schemaVersion":1,"ok":false,"command":"share","error":{"code":"ambiguous_device","message":"\"3333\" matches 2 devices; use a longer prefix or the full id. Candidates: 3333aaaa, 3333bbbb"}}
```

---


## Phase 4 — Packaging, compatibility, documentation, and launch evidence

### The name split

Today one name, `klardrop`, meant both the engine and the user CLI. The split is now real and
consistent across the build, the tarball, the installer, the updater, the packages and the docs:

| Path | What it is |
| --- | --- |
| `bin/klardrop` | the native Rust client — the user- and agent-facing CLI |
| `bin/klardrop-engine` | the Kotlin/Native engine (`daemon`, `listen`, standalone diagnostics) |
| `bin/klardrop-qt`, `bin/klardrop-qt-launcher` | the Qt frontend (unchanged; the launcher's daemon hints now say `klardrop-engine daemon`) |
| `klardrop-fixture-daemon` | a `cli-rust` **test** binary; the stager and the installer both refuse to package it |

`:cli`'s Kotlin/Native `baseName` is now `klardrop-engine` for both `linuxX64` and `linuxArm64`.
The link task still produces `cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe`.

### What changed

**Staging** (`packaging/linux/stage-native-tarball.sh`): stages both binaries, runs the same ELF
architecture verification on the Rust client as on the engine and the Qt binary, strips both,
fails closed with the exact `cargo build --locked --release` command when the client is missing,
and aborts if a `klardrop-fixture-daemon*` file ever appears in the stage tree.

**Installer** (`packaging/install.sh`): one ownership/symlink guard pair now covers both binaries;
the systemd unit is `ExecStart=%h/.local/bin/klardrop-engine daemon` for every flavour; both
binaries are staged to temporary names and only `mv`-ed into place once both installs succeed, so a
failure can never leave a new client beside an old engine; the three uninstall rm lists and the
preflight guards cover both. The installed callers moved to the explicit picker entry point, which
is required because `share` now demands `--to`:

| Caller | Before | After |
| --- | --- | --- |
| desktop entry | `Exec=klardrop share %F` | `Exec=klardrop share --pick %F` |
| Nautilus extension | `klardrop share <paths>` | `klardrop share --pick <paths>` |
| Omarchy menu helper | `klardrop share --clipboard` / `share <paths>` | `klardrop share --pick --clipboard` / `share --pick <paths>` |

**Self-updater** (`UpdatePlatform.linux.kt`): the tarball channel now owns a **pair** of install
paths (`~/.local/bin/klardrop-engine` and `~/.local/bin/klardrop`). Ownership and symlink refusal
covers both and is checked before anything is downloaded or staged; the expected tar layout requires
both binaries and rejects any `klardrop-fixture-daemon*` entry; staging copies both out of the same
extracted tree; the staged ELF machine is read straight from the header bytes (no `readelf`/`file`
subprocess) and a wrong-architecture client is refused before anything is replaced; all backups are
taken before the first rename, a backup failure unlinks what was already taken, and a failed
restore is preserved and named in the error rather than discarded.

**Packages and CI**: the `klardrop-native-bin` AUR template installs both binaries with
`ExecStart=/usr/bin/klardrop-engine daemon`; the release and nightly workflows gained a Rust
toolchain and `cargo build --locked --release` on the x64 and arm64 native matrices and on the
macOS artifact; both `latest.json` generators publish a new `macos-cli` key for the standalone
client; the updater consumes it as a fallback.

### Checks that ran here

| Check | Command | Outcome |
| --- | --- | --- |
| Native engine relink after the rename | `./gradlew :cli:linkReleaseExecutableLinuxX64` | BUILD SUCCESSFUL; produced `klardrop-engine.kexe` (19,116,840 bytes) |
| Rust client vs. the real native engine | `bash scripts/klardrop-rust-fixture-tests.sh` | **18 run, 18 passed** — one renamed engine, `apiVersion` 1, control file `0600`, one clean JSON envelope per command, no ANSI on stdout, token absent from both streams, PID set unchanged (no second engine), every failure path exit 3/2 as documented, and no file outside the private fixture root created or modified |
| Rust client vs. a **real production JVM host** | `bash scripts/klardrop-jvm-host-fixture-tests.sh` | **22 run, 22 passed** — `./gradlew :cli:jvmRun daemon --port 0 --no-klardrop --no-nearby --no-ble` started without a debug flag, published a `0600` control file, advertised the production capability list with no `logs`/`window`/`reset-identity`, served `POST /share` (200, `req-…`, `queued`) and `GET /transfers?id=` (200) for a real request, refused a mixed and an empty payload with 400, and started no second engine |
| Installer harness | `bash -n packaging/install.sh`, `bash -n packaging/linux/test-omarchy-install.sh` | OK |
| Installer behaviour | `bash packaging/linux/test-omarchy-install.sh` | **ALL INSTALLER TESTS PASSED**, including a new `Rust client / engine split: install, uninstall, guards, tarball layout and ELF preflight` case and the existing systemd-unit `verify`, ownership-refusal and flavour-switch matrices |
| Updater (native) | `./gradlew :klardrop-common:linuxX64Test` | BUILD SUCCESSFUL — includes the new pairing tests: both install paths owned, a package-owned client refused *before the downloader was invoked*, a tarball without the engine refused, a fixture-daemon entry refused, a wrong-architecture client refused before anything was replaced, and a real host ELF pair staging and applying |
| Updater compiles for native | `./gradlew :klardrop-common:compileKotlinLinuxX64 :klardrop-common:compileTestKotlinLinuxX64` | BUILD SUCCESSFUL |
| Workflow YAML | `python3 -c "import yaml;yaml.safe_load(open(f))"` on all five workflows | all parse |

### Memory profile (Linux x64, Kotlin/Native engine, release build)

Measured with the repository's own profiler and with a `/proc/<pid>/status` sampler. The engine's
Linux `pagedAllocator=false` option is unchanged from the branch, so these numbers are comparable
with the pre-existing baseline.

**Idle workload** — `python3 scripts/profile-native-memory.py cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe --seconds 120 --label phase4-idle`
(engine started with `daemon --no-ble --debug` in a private `mktemp` profile; no display, no DBus):

| Metric | Value |
| --- | --- |
| peak RSS over 120 s | 30,272 KiB = **30,998,528 bytes** (KiB × 1024) |
| settled RSS / PSS at 120 s | 30,272 KiB / 24,828 KiB |
| threads | 30 |
| total CPU over 120 s | 0.34 s (≈0.28 % average) |

**Under a client workload** — one daemon, 100 Rust client invocations
(`devices --json`, `status --json`, `transfers --json`, 50 of each) against it, in a private profile:

| Metric | Value |
| --- | --- |
| RSS before the workload | 22,348 KiB |
| RSS after the workload (+3 s settle) | 30,828 KiB (Δ 8,480 KiB) |
| peak RSS (`VmHWM`) for the whole run | 30,828 KiB = **31,567,872 bytes** |

The growth is the daemon finishing its own start-up work (long-poll registration, the unread-count
and transfer collectors) and then flattening; it is bounded, and the whole run stayed **~37 % under
the 50,000,000-byte ceiling**. No retained request records were observed: the Rust client's share
requests in this phase never reached a live peer, so the registry held at most a handful of entries,
and `ShareRegistry` is capped at 256 requests with a 2 hour TTL regardless.

**Client memory, reported separately** — 20 sequential `klardrop devices --json` invocations,
measured with `getrusage(RUSAGE_CHILDREN).ru_maxrss`:

| Metric | Value |
| --- | --- |
| Rust client peak RSS per invocation | 14,448 KiB = 14,794,752 bytes |

This is transient per-invocation memory for a short-lived process, not a background cost, and it is
not counted against the daemon's budget.


---

## Phase 3 — Two-pane interactive TUI

### What was built

`cli-rust/src/tui/` is a Ratatui 0.29 + Crossterm 0.28 full-screen UI with **no async runtime**, so the
crate's blocking-with-deadlines discipline survives into the TUI.

| Module | Responsibility |
| --- | --- |
| `terminal.rs` | `TerminalGuard` — raw mode, alternate screen, cursor, mouse capture, bracketed paste and a reserved keyboard-enhancement slot, acquired in order and restored in **exact reverse** from `Drop`, so an ordinary exit, an EOF, a panic or a handled signal all leave the terminal as they found it |
| `escape.rs` | `sanitize` — C0/C1 controls, zero-width and bidi characters are **dropped** (never replaced by a space, so `evil\x1b[31m` reads as `evil[31m`), whitespace collapsed, length capped |
| `layout.rs` | pure constraint arithmetic: 1-row header, 2-row footer, two panes at ≥ 80 columns (left 40 %, clamped), a single pane with back-navigation below that, and a notice below 20×8 so no widget is ever laid out off-screen |
| `app.rs` | the pure reducer — `handle_key` queues `Action`s, `apply` consumes them; no I/O, no terminal, no threads, so the whole state machine is unit-tested |
| `worker.rs` | two threads off the input loop: a poller that long-polls `GET /state?since=` (~35 s budget) and an executor for actions; each request builds a fresh deadline-bounded `Client` |
| `render.rs` | header, device pane, conversation pane, footer status row and the overlays; every untrusted string goes through `sanitize` first |
| `theme.rs` / `motion.rs` / `qr.rs` | palette and capability ladder; spinner / transfer indicator / success flourish with a 10 fps cap; QR matrix parsing with a quiet-zone and 2-cells-per-module fit check |
| `line.rs` | the line-mode fallback |

### Commands

```
klardrop                                   # opens the TUI only when stdin AND stdout are terminals
klardrop interactive [--theme auto|system|tokyo-night] [--no-motion] [--no-mouse] [--no-color]
                     [--line-mode] [--timeout <SECS>] [--debug] [--control-file <PATH>]
klardrop share --pick [--clipboard] <paths…>   # explicit device picker for callers that need one
klardrop daemon <args…>                        # forwards to the sibling `klardrop-engine`, argv only
```

`share --pick` is the entry point the installed desktop entry, Nautilus extension and Omarchy menu
helper now use, because `share` without `--to` is a usage error. `daemon` is the one way a client
may start an engine, and it is an argument-vector forward — never a shell.

### Two real defects found and fixed while building it

- **Ctrl-C never fired.** The reducer matched it with `is_char(&key, 'c') && modifiers.contains(CONTROL)`,
  but `is_char` deliberately returns `false` for any key carrying CONTROL — so the one interrupt raw
  mode leaves the user was dead code. It now matches on the key code directly.
- **Column padding never reached the screen.** Device rows were assembled and then passed through
  `sanitize`, which collapses runs of whitespace, so the padding the columns are made of was eaten
  before rendering. Rows are now built from already-sanitized parts and are never sanitized twice; the
  device name is what shrinks when a pane is narrow, never the status words or the unread badge.

### What is verified so far

| Check | Command | Outcome |
| --- | --- | --- |
| Rust unit + integration tests | `cargo test --locked` | **256 passed / 0 failed** (185 unit, 49 `cli_contract`, 15 `tui_flows`, 4 `tui_line_mode`, 3 `tui_mouse`) |
| Lint | `cargo clippy --locked --all-targets -- -D warnings` | clean, zero warnings |
| Format | `cargo fmt --all --check` | clean |
| Release build | `cargo build --locked --release` | clean |
| Shipped dependency set | `cargo tree -e normal` | contains no pty/serial dependency — the PTY harness is dev-only |

---

## Phase 4 — launch evidence

### A real two-peer transfer, in isolated profiles

`scripts/klardrop-two-peer-transfer-tests.sh` (new, executable, `bash -n` clean) starts two
Kotlin/Native engines of its own inside one `mktemp -d` root — a receiving node and a sending
daemon — each with its own `--data-dir`, `HOME` and `XDG_RUNTIME_DIR`, discovers the receiver's real
device id from the daemon's `/state`, and drives the **built Rust binary directly** (never through
Gradle, which collapses application exit codes into its own).

I ran it myself; verbatim result:

```
29 tests run, 29 passed, 0 failed
```

| Observation | Value |
| --- | --- |
| transfer wall clock | **275 ms** for 3,000,000 bytes (~11 MB/s over loopback) |
| receiver-side path | `<mktemp>/receiver/downloads/klardrop-two-peer-payload.bin` |
| receiver-side sha256 | matches the sender's payload byte for byte (both `sha256sum` and `cmp`) |
| discovery timings | receiver identity at startup; daemon control file after 524 ms; receiver visible in `/state` after 626 ms |
| sender RSS / receiver RSS at the end | 34,776 KiB / 41,032 KiB |
| PID census | only the two PIDs the script started were ever created, signalled or destroyed |

Sender stdout, exactly one JSON value, exit 0:

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-8a9e8833a2f44a45","deviceId":"3ef0166a","status":"completed","items":[{"transferId":"599793811","path":"/tmp/klardrop-two-peer.5RXwvV/klardrop-two-peer-payload.bin","fileName":"klardrop-two-peer-payload.bin","totalSize":3000000,"transferredSize":3000000,"status":"completed","error":null}]}
```

The deliberately failing case is also covered: sharing to an unknown device id exits 2 with
`device_not_found`, prints one JSON envelope, and the receiver's receipt count and download
directory are unchanged — no partial delivery, no duplicate send.

### Three real findings from that run

1. **The receiver's download directory must already exist.** With `XDG_DOWNLOAD_DIR` pointing at a
   directory that does not exist, the receiver accepts the transfer and then fails at finalize with
   `FileReceivePipeline: Finalize failed …`, answering `ACK_REJECTED`. A send to a fresh profile can
   therefore fail at the far end. Not patched here (it is engine behaviour, outside this plan's
   scope); recorded so it is not a surprise.
2. **Native two-node discovery on a busy host is order-dependent.** Several unrelated Klardrop
   daemons were live on this machine, all announcing from the same address, and
   `VisibleDevicesImpl.addDevice` de-duplicates by address. A receiver whose mDNS record predates
   the daemon's browser was not re-announced within 90 s. The script therefore uses a bounded
   daemon restart (fresh browse) — with a single attempt it fails outright, which proves the recovery
   is load-bearing rather than incidental.
3. **`POST /share` succeeds for a device id that is not in `/state`.** The transfer still delivered
   correctly, so the gap is in `/state` publication, not in the transport.

### Are findings 1 and 3 regressions introduced by this work? — No, both are pre-existing

The first run recorded these as "reported, not patched, outside this plan's scope" without
establishing *whose* bug they were. A read-only investigation (fresh subagent, `git`-based, no
edits) classified them against `HEAD` (`b1fb5e22`) and against clean checkouts of the same tracked
files preserved in this repo's sibling git worktrees:

| Finding | Verdict | Evidence |
| --- | --- | --- |
| **1 — receiver fails at finalize when the download directory does not pre-exist** | **PRE-EXISTING.** Not introduced by this work. | The whole chain is unchanged from `HEAD`: `FileMessageHandler.beginReceive` (`FileMessage.kt:423-489`) stages bytes in the FileKit **cache** dir via `FileManagerImpl.prepareSaveFile` (`FileManagerImpl.kt:18-25`), which is why the header is accepted and the failure can only appear later. At finalize, `FileReceivePipeline.finalizeReceive` (`FileMessage.kt:359-386`) calls `FileTransferImpl.onTransferCompleted` → `PlatformFileSystemImpl.moveToStorage` (`PlatformFileSystem.kt:100-145`), which does `atomicMove` into `platformDependencies.getDownloadStoragePath()` with **no `createDirectories` anywhere on the path**; both `atomicMove` and the copy fallback throw, `getOrThrow` lands in the `Finalize failed …` catch, and the router answers `ACK_REJECTED`. `getDownloadStoragePath` (`linuxMain/InternalPlatformDependencies.kt:24-26`) returns `LinuxPaths.downloadDir` verbatim and never mkdirs it. None of these files is touched by the uncommitted work; the run's own harness (`scripts/klardrop-two-peer-transfer-tests.sh:157-165`) pre-creates the directory and says why, which is evidence the behaviour was observed and deliberately worked around rather than introduced. |
| **3 — `POST /share` accepts a device id that is absent from `/state`** | **PRE-EXISTING behaviour of the control API, surfaced by a new route.** Not a regression. | `/share` performs no device lookup at all: `handleShareRequest` only checks the id is non-empty (`ControlPlane.kt:1037-1072`) before handing it to `startPreparedFiles`/`startTrackedTextSend`. That is exactly what the pre-existing, pre-rename `/send-file` and `/send-text` routes did — the tracked `debug-control/.../DebugControl.kt` at `HEAD` calls `requireDeviceId(body)` with no membership check either. On the `/state` side, `snapshotState` publishes `ctrl.screenStateFlow.value.devices`, fed by `VisibleDevicesImpl`, whose address-level de-duplication is the actual reason a peer can be missing; that file is unchanged from `HEAD`. What the run added is only the `/share` support API on `DiscoveryController` (`startPreparedFiles`, `startTrackedTextSend`, `prepareOutgoingFiles`), not the publication path. The Rust client already refuses the case before it reaches the daemon: `--to` is resolved against the daemon's own device list and an unknown id exits 2 with `device_not_found` (pinned by `share to an unknown device id exits 2` in the two-peer run). |

Neither is patched, because neither is a regression of this work and both are engine behaviour
outside the plan's scope. They remain **open issues on `main`**, listed as such in the remaining
gates below, not as work completed here.

### Phase 3 completion evidence

| Check | Command | Outcome |
| --- | --- | --- |
| Rust unit + PTY/integration tests | `cargo test --locked` | **309 passed / 0 failed** — 216 unit, 50 `cli_contract`, 15 `tui_flows`, 5 `tui_line_mode`, 4 `tui_input_edges`, 4 `tui_motion`, 3 `tui_mouse`, 2 `tui_rendering`, 10 `tui_theme` |
| Lint | `cargo clippy --locked --all-targets -- -D warnings` | clean, zero diagnostics |
| Format | `cargo fmt --all --check` | clean |
| Release build | `cargo build --locked --release` | clean |
| Shipped dependency set | `cargo tree -e normal` | no pty/serial dependency — `portable-pty` and `vt100` are dev-only |
| Idle CPU | `bash cli-rust/scripts/tui-idle-cpu.sh` | **motion-on: 0.060 CPU-s over 10 s = 0.60 % of one core** (draw loop 0.50 %); **motion-off: 0.020 CPU-s = 0.20 %** |

Idle CPU is measured, not assumed: the script pins `TERM=xterm-256color`, sets the window with
`stty rows 40 cols 120`, starts only its own children, and samples `/proc/<pid>/stat` per thread.
It found something worth reporting: the process total is dominated by the `/state` long-poll worker,
not the draw loop — when the fixture answered `/state` instantly the poller spun at 57 % of a core.
That is a fixture artifact, so a `state_long_poll` behaviour was added that holds the request open
and the script uses it. **Motion costs essentially nothing when nothing is moving** (0.50 % vs 0.10 %).

**PTY coverage of the Phase 3 acceptance list, stated honestly.** Keyboard selection, scrolling,
themes, motion, no-motion, `NO_COLOR`, the dumb-terminal fallback, the mouse-capture toggle,
terminal restoration and a real fixture send were already covered before this pass. This pass
added the four that were named in the plan and missing: **cancelling the picker** (exit 130 plus
its envelope), **a mouse click** selecting the row under the pointer, a live **resize**, and
**Ctrl-D** as the raw-mode end-of-input edge. What that coverage does *not* reach, and is still
owed: a true mid-session pty **EOF** (closing the master) — the harness cannot produce one without
also destroying the line discipline it asserts on, so Ctrl-D stands in for the input edge and is
named as such rather than being called EOF — and the **wheel** path, whose only assertion would be
"the fixture's conversation is too short to scroll".

### Theme resolution, exactly as implemented

- **Capability ladder** — `COLORTERM=truecolor|24bit` → truecolor; `TERM` containing `256color` or
  `kitty` → indexed; otherwise monochrome. Monochrome also for `TERM=dumb`, `--no-color` and
  `NO_COLOR`.
- **`--theme tokyo-night`** — the palette verbatim (`#1a1b26` bg, `#c0caf5` fg, `#565f89` muted,
  `#7aa2f7` accent/focus, `#9ece6a` success, `#e0af68` warning, `#f7768e` error), with attribution.
  It never writes a query byte.
- **`--theme system`** — an OSC 10/11 probe bounded by a hard 250 ms deadline, interactive only
  (both stdin and stdout must be terminals; line mode returns before any palette is resolved). The
  terminal is put into raw mode by a `Drop` guard and handed back before the probe returns; any
  keystroke read that was not part of a reply is **replayed into the app** before the input loop's
  first poll, so the probe cannot eat a keypress. A probe needs both fg and bg and `fg != bg`;
  anything else (no answer, half an answer, `fg == bg`, a `?` payload) falls back to Tokyo Night at
  the ladder's depth.
- **`--theme auto`** (the default) — `system`, with that fallback inside it.
- **`NO_COLOR` and `--no-color`** emit **no ANSI at all**: `style()` returns `Style::default()`, so
  no SGR sequence is written — colour and attributes are the same escape. Selection, failure and the
  QR blocks stay legible because they were never colour-only.

### Terminal recovery and the line-mode fallback

`TerminalGuard` acquires raw mode, alternate screen, cursor visibility, mouse capture, bracketed
paste and a keyboard-enhancement slot in order, and restores them in **exact reverse** from `Drop`.
PTY tests assert the restoration behaviourally rather than by claim: a printable-only echo probe
(ECHO is off in raw mode, so a client that forgot to restore cannot pass it) and a check that the set
of raw-mode sequences present after exit does not grow.

The **line-mode fallback** triggers on `--line-mode`, on `TERM` unset/empty/`dumb`, or on a window
below 20×8 — the same predicate the renderer uses. It prints a plain-text summary (self device,
daemon state, protocols, background discovery, the device list with reachability and trust markers,
in-flight transfers, and the reason plus the minimum the interactive view needs), emits **zero**
escape bytes, makes no raw-mode or alt-screen call, and exits 0. `share --pick` refuses with
`terminal_required` rather than falling back, so a picker can never report a false "cancelled".

### Mouse and the selection bypass

Mouse capture is confined to the TUI and is off with `--no-mouse`. Click selects and focuses, the
wheel scrolls. The bypass is one documented key, **`Ctrl-Space`** — a single NUL byte, which every
terminal delivers unambiguously — handled in every mode except the compose line so an overlay can
never swallow it. It writes the real `EnableMouseCapture`/`DisableMouseCapture` commands, and the
undo slot is deliberately untouched by toggling (it records that the client took the mouse at startup,
which is what must be undone).

### Captured terminal frames for human review

`cli-rust/tests/frames/` holds 29 captured frames, including `01-device-list.txt`,
`04-pairing-pending.txt`, `06-incoming-pending.txt`, `11-update-refused-409.txt`,
`12-capabilities-unavailable.txt`, `line-mode-dumb-terminal.txt`, `motion-spinner-turning.txt`,
`motion-transfer-settled.txt`, `mouse-released-help.txt`, `theme-system-from-terminal.txt`,
`theme-system-no-answer.txt` and `theme-no-color.txt`.

A representative frame (`01-device-list.txt`):

```
klardrop | Fixture Host | daemon: connected | 3 devices
┌> Devices  * reachable  + trusted  ! pairing──┐┌  Conversation────────────────────────────────────────────────────────┐
│> *+ Fixture Phone  reachable · trusted (2)   ││Select a device and press Enter                                       │
│   - Fixture Tablet  offline · untrusted      ││                                                                      │
│   * Fixture Laptop  reachable · untrusted    ││                                                                      │
└──────────────────────────────────────────────┘└──────────────────────────────────────────────────────────────────────┘
Tab pane Up/Down select Enter open i text f files y clipboard a actions ? help q quit
Esc quits · ? for keys · mouse released
```

### Phase 4 review: findings and fixes

A fresh reviewer (who had not written any of it) found ten issues. All are fixed, and each fix has
a regression test that fails without it.

| Finding | Severity | Fix |
| --- | --- | --- |
| `guard_not_symlink_or_nonregular` ran only for the engine path, so a **directory** at `~/.local/bin/klardrop` passed every guard and `mv -f` moved the staged client *into* it — the installer exited 0, printed "installed", and left a new engine beside **no client** | MAJOR | the guard is split into `guard_not_symlink` and `guard_not_nonregular`; both binaries now get the NON-REGULAR guard and only the SYMLINK rule stays engine-only. Harness case 23a asserts a directory at the client path is refused, the directory and its contents are untouched, and no engine appears alongside |
| The two `mv`s are not one atomic step: a failure of the second left a new engine beside an old client | MAJOR | a failed second `mv` rolls the first back; afterwards both targets are asserted to be regular files. Harness case covers it |
| A missing `.sha256` sidecar or a missing `sha256sum` only warned, so an unverified tarball installed cleanly while the in-app updater refuses the same situation | MAJOR | the checksum is now **mandatory** on both the native and JVM download paths (a `KLARDROP_LOCAL_TARBALL` override stays exempt). Harness case covers a missing sidecar and a wrong digest |
| `comm` needs lexicographic order but the PID sets came out of `sort -n`, so any before/after pair with a different digit count made the two-peer script **abort** under `set -euo pipefail` instead of reporting | MAJOR | the comparison is now explicit membership, not `comm`. Two self-tests prove it flags an injected foreign PID and reports nothing when only our own PIDs are live |
| `pass "script signals only the PIDs it started"` was unconditional — a script that `pkill`ed the user's daemon would still print PASS | MAJOR | replaced with a real assertion that the two recorded PIDs are numeric, distinct, and the ones captured from `$!` |
| `cli/README.md` claimed the Rust client "has no `daemon` subcommand"; it does, and three other docs say so | MAJOR | corrected |
| The installer harness fabricated its own `klardrop-qt-launcher`, so reverting the stager's `klardrop-engine daemon` hints would have kept every test green | MINOR | the launcher text now lives in one file (`packaging/linux/klardrop-qt-launcher.sh`) that BOTH the stager and the fixtures copy, and the harness asserts the installed launcher names `klardrop-engine daemon` and never the bare `klardrop daemon` |
| Three PKGBUILD headers and `packaging/README.md` said no CI renders or publishes the native packages; `release.yml` does | MINOR | corrected — all four are rendered and pushed, gated only on the AUR repos existing |
| `linux/omarchy/TODO.md`'s live-test runbook still copied `klardrop.kexe` and ran `klardrop daemon` | MINOR | corrected to `klardrop-engine.kexe` / `klardrop-engine daemon`, and the desktop/Nautilus steps now show `share --pick` |
| The installer harness and the native fixture script ran in no workflow, so a packaging regression merged green | MINOR | `build_pr.yml` gained Gradle-free `packaging-tests` and `native-fixture` jobs |
| `detectLinuxInstallChannel` in one native test used the **real** pacman/dpkg/rpm, so it failed on any machine where `klardrop-native-bin` owns `/usr/bin/klardrop` | MINOR | every such call now injects `packageChecker = { null }` |

After the fixes, re-run by me:

| Check | Command | Outcome |
| --- | --- | --- |
| Shell syntax | `bash -n` on `install.sh`, `test-omarchy-install.sh`, `stage-native-tarball.sh`, `klardrop-qt-launcher.sh`, the two-peer script | all clean |
| Installer harness | `bash packaging/linux/test-omarchy-install.sh` | **ALL INSTALLER TESTS PASSED** — 25 `ok:` lines, including the new `pair-install safety: non-regular paths refused, failed swap rolled back, shipped launcher hints, mandatory download checksum` |
| Two-peer transfer | `bash scripts/klardrop-two-peer-transfer-tests.sh` | **31 run, 31 passed, 0 failed** (up from 29 — two new self-tests for the PID-census comparison) |
| Workflow YAML | `python3 -c "import yaml;yaml.safe_load(...)"` | `build_pr.yml` jobs: build-linux, build-macos, rust, desktop-jvm-host-macos, jvm-host-fixture, **packaging-tests**, **native-fixture** |
| Updater (native) | `./gradlew :klardrop-common:linuxX64Test` | BUILD SUCCESSFUL |

### Phase 3 review: findings and fixes

A fresh reviewer reproduced two release-blocking defects end to end. Both are fixed, with PTY
tests that fail without the fix.

| Finding | Severity | Fix |
| --- | --- | --- |
| **Line mode wrote daemon-supplied names straight to stdout with no escaping.** `line::render` was the only stdout writer not passing through `escape::sanitize`, emitting `self.deviceName`, `device.deviceName` and transfer file names verbatim. Device names arrive over mDNS from any machine on the LAN. Reproduced: a device named `evil\x1b]0;PWNED\x07\x1b[2J` made `klardrop interactive --line-mode` exit 0 having written **4 raw ESC bytes** — renaming the user's window and clearing their screen. The full-screen path was already safe, so the funnel had exactly one hole, in the path documented as having none | BLOCKER | every daemon-supplied field in `line.rs` now goes through `escape::sanitize`; the unit test runs against a hostile state carrying ESC/OSC/BEL/NUL in all of them and still asserts the word `evil` survives; PTY test asserts **zero** `0x1b` bytes |
| **The newest messages in a conversation were neither drawn nor scrollable-to.** The window was computed in logical `Line`s while the capacity is terminal ROWS and the paragraph wraps, so any wrapping message pushed its overflow below the pane and it was clipped. Reproduced at 120×40 with 12 messages of 310 characters: the pane showed MSG01..MSG06 and nothing else, and after 10 Down + 5 PageUp + 10 PageDown the screen was byte-identical — scrolling saturated too | BLOCKER | a new `tui::text` module does display-width measurement, hard wrapping, `row_count` and `history_rows`; the conversation window is now a range of **display rows**, and `Scroll`/`PageHistoryUp`/`PageHistoryDown` clamp to display rows rather than to `messages.len()`. PTY test asserts the newest message is on screen and that scrolling changes the screen |
| Device-row widths were measured in **characters**, so a CJK name got twice the room it occupies and clipped `unreachable · untrusted` mid-word — losing exactly the facts the row exists to convey | MAJOR | budgeting and `shrink` now measure display columns (`unicode-width`, already in the tree via ratatui, promoted to a direct dependency). PTY test with a long CJK name asserts the status words are present and not clipped |
| `NO_COLOR` was honoured only in the depth ladder, not at draw time: `NO_COLOR=1` with no flag emitted **153 SGR sequences** including BOLD and DIM, contradicting the documented "not one SGR sequence" contract | MAJOR | `no_color` is computed once as `theme::no_color_requested(!options.color, &env)` and threaded into `render::draw`; `pane_title` was then found to bypass `style()` and was routed through it too (a `--no-color` run went from 148 SGR sequences on the wire to 3, and the remaining ones were shown to be state resets rather than styling). PTY test asserts zero *styling* sequences, with a control test proving the filter is not vacuous |
| The `Ctrl-Space` toast read "mouse released — Ctrl-Space takes it back" even under `--no-mouse`, where the session can never capture a mouse, and the idle footer claimed an engaged bypass that never happened | MAJOR | `TerminalGuard::mouse_available()` (a fixed field, deliberately not derived from the undo stack) drives both the toast and the footer; the no-mouse session says so plainly. PTY test asserts the truthful text and that no capture sequence ever appears |
| `--debug` wrote into the screen the TUI owns: 15 `klardrop: debug:` lines interleaved into the panes and shredded the footer, while a test worked around it by dropping the flag | MAJOR | `client.rs::DebugLog` (`Off`/`Stderr`/`ToFile`) replaces the bare bool; a TUI session sends diagnostics to a **file**, named on stderr in the one moment stderr is still the user's scrollback, before the alternate screen is entered. Non-TUI commands keep `DebugLog::stderr_if`, which is correct there |
| `no_color_writes_no_escape_sequence_at_all` collected only the numeric parameters and asked whether *they* ended in `m` — unmatchable, so it passed unconditionally while `--no-color` was emitting 148 SGR sequences | MINOR | rewritten to match whole sequences, with a control test proving the filter finds styling when colour is allowed |
| `the_settings_overlay_toggles_background_discovery` never opened the settings overlay and never pressed `d` inside it; a PTY test already covers the real behaviour | MINOR | deleted rather than kept misnamed |
| `no_mouse_means_ctrl_space_can_never_start_capturing` pinned the exact string that lies | MINOR | rewritten to wait for a truthful message and to assert nothing on screen still says "mouse released" |
| `a_very_long_history_is_windowed_not_rendered_whole` asserted `rows.len() == 40` (a `TestBackend` property) and never exercised wrapping | MINOR | now asserts the newest message is on screen and the oldest is not, with messages wider than the pane, **and** that the number of rows handed to the paragraph is bounded |

The reviewer also confirmed as clean: the terminal guard's restoration order, the escape funnel
outside line mode, the OSC probe's replay-correctness and boundedness, that every `Action` variant
is dispatched to a consumer, and that the PTY tests assert decoded screen state rather than
"did not hang".

---

## Final verification run

Everything below was executed on this machine (Linux x64) after the last code change — the
native macOS pass included, since it touched shared control-plane code the JVM and Linux hosts
also compile. Re-run in full rather than carried over from the first run's claims.

| Check | Command | Outcome |
| --- | --- | --- |
| Rust suite (unit + contract + PTY) | `cargo test --locked` | **309 passed / 0 failed** — 216 unit, 50 `cli_contract`, 15 `tui_flows`, 5 `tui_line_mode`, 4 `tui_input_edges`, 4 `tui_motion`, 3 `tui_mouse`, 2 `tui_rendering`, 10 `tui_theme` |
| Lint | `cargo clippy --locked --all-targets -- -D warnings` | clean, zero warnings |
| Format | `cargo fmt --all --check` | clean |
| Release build | `cargo build --locked --release` | clean |
| Client vs. real native engine | `bash scripts/klardrop-rust-fixture-tests.sh` | **18 run, 18 passed** |
| Client vs. real production JVM host | `bash scripts/klardrop-jvm-host-fixture-tests.sh` | **22 run, 22 passed** |
| Two-peer transfer + receipt + memory | `bash scripts/klardrop-two-peer-transfer-tests.sh` | **31 run, 31 passed** — 3,000,000 bytes received byte-for-byte; sender daemon VmRSS **35,084 KiB (35,926,016 B) < 50,000,000** |
| Installer harness | `bash packaging/linux/test-omarchy-install.sh` | **ALL INSTALLER TESTS PASSED** |
| TUI idle CPU | `bash cli-rust/scripts/tui-idle-cpu.sh` | motion-on **0.60 %** total / 0.50 % draw loop; motion-off **0.20 %**, over a 10 s window |
| Kotlin control-plane (JVM) | `./gradlew :control-plane:desktopJvmTest --rerun-tasks` | BUILD SUCCESSFUL, **80 tests** |
| Kotlin control-plane (native) | `./gradlew :control-plane:compileKotlinLinuxX64 --rerun-tasks` | BUILD SUCCESSFUL |
| Kotlin common / presentation / desktop | `./gradlew :klardrop-common:desktopJvmTest :presentation:desktopJvmTest :desktop:jvmTest --rerun-tasks` | BUILD SUCCESSFUL (2m 24s) |
| Kotlin engine on the JVM host | `bash scripts/klardrop-jvm-host-fixture-tests.sh` (starts `:cli:jvmRun daemon` in a release-mode JVM host) | **22 run, 22 passed** — this is the check that covers the `cli` JVM target; `:cli:jvmTest` itself runs **zero** tests (the module declares no test source set) and is not listed here as evidence |
| Kotlin native engine | `./gradlew :cli:linkReleaseExecutableLinuxX64` | **BUILD SUCCESSFUL in 1m 45s** — `klardrop-engine.kexe`, **19,127,408 bytes** |
| Kotlin native updater | `./gradlew :klardrop-common:linuxX64Test` | BUILD SUCCESSFUL |
| Workflow YAML | `python3 -c "import yaml;yaml.safe_load(...)"` on all three workflows | all parse |
| Shell syntax | `bash -n` on every script touched | all clean |

Not re-run in this pass because nothing it covers changed: `cargo tree -e normal --depth 1` (the
shipped dependency set is unchanged — this pass added no Rust dependency).

One flake was observed and fixed rather than re-run until green:
`ControlPlaneHistoryTest.historyReadDropsUnreadCountToZeroInState` failed once while a full
`--rerun-tasks` compile was competing for cores. Its collector wait was `50 × 20 ms` — a one
second budget for a coroutine that has to re-query the database and republish a `StateFlow` —
which turns a slow machine into a red build that says nothing about the code. The four bounded
poll loops in that file now share `POLL_ATTEMPTS`/`POLL_INTERVAL_MS` (a five second budget).
Three consecutive `--rerun-tasks` runs are green since.

### Representative JSON

Queued submission (`klardrop share --to <id> report.pdf notes.txt --json`, no `--wait`):

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-0000000000000001","deviceId":"11112222","status":"queued","items":[{"transferId":"tx-1-0","path":"/tmp/x/report.pdf","fileName":"report.pdf","totalSize":12,"transferredSize":0,"status":"queued","error":null}]}
```

Delivered (`--wait`):

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-8a9e8833a2f44a45","deviceId":"3ef0166a","status":"completed","items":[{"transferId":"599793811","path":"/tmp/klardrop-two-peer.5RXwvV/klardrop-two-peer-payload.bin","fileName":"klardrop-two-peer-payload.bin","totalSize":3000000,"transferredSize":3000000,"status":"completed","error":null}]}
```

Refused before anything is sent:

```json
{"schemaVersion":1,"ok":false,"command":"share","error":{"code":"ambiguous_device","message":"\"3333\" matches 2 devices; use a longer prefix or the full id. Candidates: 3333aaaa, 3333bbbb"}}
```

Daemon not bound yet:

```json
{"schemaVersion":1,"ok":false,"command":"share","error":{"code":"daemon_unsupported","message":"the daemon at 127.0.0.1:39117 is not ready yet (HTTP 503 on /share); retry in a moment"}}
```

Missing `--to` — the usage error that now carries the interactive guidance:

```json
{"schemaVersion":1,"ok":false,"command":"share","error":{"code":"invalid_argument","message":"--to <device-id> is required: an imperative share never opens a device picker, so nothing can choose a target for you. To pick one on screen, run `klardrop share --pick` (or `klardrop interactive`) deliberately — from the desktop entry, the file-manager action or the Omarchy menu — and never from an unattended command."}}
```

Cancelled at the picker — its own exit code (130), not a success and not a failure:

```json
{"schemaVersion":1,"ok":false,"command":"share","error":{"code":"cancelled","message":"cancelled: no device was chosen, so nothing was sent"}}
```


---

## Independent review of this pass, and what it changed

Two fresh reviewers — neither of whom wrote any of this code, and neither of whom was the
subagent that investigated the engine findings above — audited the result: one against the
macOS integration line by line, one against the whole plan and against this report. Every
finding below is a real defect the implementer's own green runs had missed. All are fixed.

| Finding | Severity | Fix |
| --- | --- | --- |
| `:control-plane`'s macOS test executable had only the link-time `-F` search path for the dynamic Sentry framework, not the matching `-rpath`, so the new `:control-plane:macosArm64Test` CI step would abort in dyld with "Library not loaded" before a single test ran — `:presentation`'s build file documents this exact requirement for the identical situation | BLOCKER | `macosArm64().binaries.all` now also emits `-rpath` for `TestExecutable`, mirroring `:presentation` |
| `unixWriteControlFile` chmod'd the control file's parent directory to `0700` unconditionally. With `KLARDROP_CONTROL_FILE=/tmp/control.json` — a value the *test* harness uses — that would have narrowed `/tmp` itself | MAJOR | the directory is chmod'd only when this call is what created it (`mkdir` returned 0). The token is protected by the file's own `fchmod 0600` either way; the directory mode only hides the file's existence |
| `control_plane.framework` and `presentation.framework` are both static Kotlin/Native frameworks that each carry their own copy of `:presentation` and `:klardrop-common`. Appending `-framework control_plane` *after* `$(inherited)` puts `presentation` first on the link line, so `ld` would satisfy the Swift references from `presentation` and then pull `control_plane` in and redefine every one of them | MAJOR | `-framework control_plane` now comes **before** `$(inherited)` in both KlardropMac configurations, with a comment in the pbxproj explaining why. `ld64` pulls an archive member only while it resolves a pending undefined symbol, so whichever framework is scanned first supplies the classes and the second is never pulled |
| the `control_plane` framework declared no `export`, so its generated header could not name the `Klardrop`/`DiscoveryController` its own public API takes — a Swift compile error, not a link one | MAJOR | `export(project(":presentation"))`, the same thing `:presentation` does for `:klardrop-common` |
| the plan gives exit **130** to "user cancelled the interactive workflow", but no code path emitted it: `share --pick` printed a human line that `--json` suppressed and exited **0**, so a `--json` caller got *no stdout at all* on success | MAJOR | new `ErrorCode::Cancelled` → `"cancelled"` → exit **130**, raised through the normal error envelope so the one-JSON-value invariant holds. `cli-rust/tests/tui_input_edges.rs::cancelling_the_picker_exits_130_and_says_so_in_json` drives the real picker on a pty and pins both the exit code and the envelope |
| the plan requires a missing `--to` to be "a usage error with guidance to `interactive`", but `required_unless_present = "pick"` let clap reject the invocation first with a message that names the flag and nothing else — making the client's own guidance unreachable dead code | MAJOR | `--to` is no longer clap-required; `share::run` owns the message, which now names `--pick` *and* `interactive` and says an unattended command must never pick. Pinned by `cli_contract::share_without_a_target_names_the_interactive_entry_point` for both the human and the `--json` form |
| the plan's Phase 3 acceptance names mouse selection, cancellation, EOF and resize as PTY checks; mouse click, resize and cancellation had **no** PTY test at all | MAJOR | new `tests/tui_input_edges.rs`: cancellation (above), a real SGR mouse click, a live `TIOCSWINSZ` resize, and Ctrl-D as the raw-mode end-of-input edge. The harness gained `Tui::resize` (which keeps the pty master, the only handle that can make the kernel raise `SIGWINCH`) and a per-test subcommand |
| — and writing the mouse test found a **real product bug**: `click_actions` counted two rows above the first device row, so clicking a device selected the one *above* it and the last device in the list could never be clicked. `Block::bordered()` draws the pane title on the top border row, so it is one row, not two — and the picker draws into `rects.body()`, not `rects.left` | MAJOR | `click_actions` measures against the rectangle the renderer actually used and subtracts one row. Pinned by `a_mouse_click_selects_the_device_under_the_pointer` |
| `theHostRefusesToRunUnauthenticatedWhenItHasNoPrivateControlLocation` asserted `controlFileOptional == false` — a compile-time constant against the literal `false`, which would still pass if the guard in `ControlPlane.start` were deleted outright | MINOR | the decision was extracted into `controlFilePathToPublish()`, and the macOS fixture now drives it for real: with no App Group container (a Gradle-run test binary has no entitlement) it must throw, and with a pinned path it must return that path |
| the fixture claimed to prove "the POSIX control-file actual" while every test pinned `KLARDROP_CONTROL_FILE` and so never called the macOS-only `controlFileDirectory()` | MINOR | a test now calls it with no override and checks the composed path (and that the directory really is the App Group container when it resolves) |
| the fixture asserted the debug capability list on a host that can never build one: `KlardropBootstrap` always constructs `ApplicationInfo(isDebug = false)` on Apple, so `/logs`, `/window` and `/reset-identity` are permanently 403 there | MINOR | those assertions are gone; the desktop JVM production test is where the debug list is covered |
| `thePublishedDocumentCarriesThePortToken…` sat in the macOS fixture but tests `controlFileJson`, a pure commonMain string builder with no platform behaviour | MINOR | moved to `control-plane/src/commonTest/.../ControlFileJsonTest.kt`, so the 4 KiB ceiling the Rust client enforces is now checked on every target |
| this report claimed `scripts/klardrop-ctl` was "untouched" (it is modified), listed `:cli:jvmTest` as verification (it runs zero tests), said `terminal_required` was "reserved", and mis-converted two KiB→byte figures | MINOR | all four corrected in place; the row that covers the `cli` JVM target is now the JVM-host fixture, which actually starts one |

The reviewers also confirmed what was *not* wrong: the `posixMain` source-set wiring really is
attached (the linuxX64 klib on disk contains `ControlFile.posix.kt`), the fail-closed guard runs
before any bind and leaks no token on any of the four existing platforms, capability filtering is
a no-op on android/desktopJvm/linux, both pbxproj edits land in the KlardropMac Debug *and*
Release configurations with the file still structurally valid, and the Rust App Group probe is a
compile-time-constant branch that cannot change non-macOS behaviour.
---

## Remaining release gates — NOT complete, and not claimed to be

1. **ARM64**: no ARM64 runner here. `:cli:linkReleaseExecutableLinuxArm64`, the arm64 native tarball
   and the arm64 AUR digests are configured and referenced but have **not** been built here. The
   `build-linux-native` / `linux-native` matrix arms cover them in CI.
2. **macOS**: no runner here, and this pass added a real macOS integration rather than a claim —
   so all of it is an unrun gate. Specifically **not executed on this machine**:
   `:control-plane:compileKotlinMacosArm64`, `linkReleaseFrameworkMacosArm64`,
   `:control-plane:macosArm64Test`, the `KlardropMac` Xcode build with
   `import control_plane` / `-framework control_plane`, notarization of a bundle that now
   contains a second static framework, and `klardrop devices` against the real running app.
   What *is* verified here: the `posixMain` shared source set compiles for a real native target,
   and the macOS App Group constant is pinned against the Swift/entitlements/Kotlin copies by a
   Rust test that runs on Linux. `desktop-jvm-host-macos` builds the **separate** JVM desktop
   app and says nothing about the native host.
   The macOS `qr-share` capability is deliberately withheld: this app's entitlements do not let
   it execute `qrencode`, and qrcode-kotlin has no macOS artifact — a real feature the JVM macOS
   app still has. Restoring it needs a CoreImage `CIQRCodeGenerator` binding, which must be
   written and run on a machine that can compile Apple code.
3. **Windows**: no runner here. The Rust CLI is **built and packaged** for Windows in both
   channels — the stable `build` job's `windows` arm and the new `release-nightly.yml`
   `windows` job each run `cargo build --locked --release` and stage
   `klardrop-cli-windows-x64.zip` under pwsh with a `PK` magic-byte assertion, published under
   a `windows-cli` key in `latest.json` next to the MSI, and `UpdateChecker` now falls back to
   that asset when a build published no installer. It is deliberately a sibling asset and not a
   file inside the MSI: jpackage signs what it builds, so anything dropped in afterwards
   invalidates the installer's signature. What has still **never run on a Windows machine**:
   the whole job — including the ACL branch in `ControlFile.desktopJvm.kt` `protect()`, which is
   only reachable when `LOCALAPPDATA` is set (without it `ControlPlane.start` refuses rather than
   serving an unauthenticated listener) — and the new `windows-release-package` PR job.
   The Ctrl-C gap this item used to describe is **closed**: the client now installs a
   `SetConsoleCtrlHandler` on Windows as well as a `signal(2)` handler on Unix, both feeding the
   same flag and the same exit-130 envelope. It is compiled for `x86_64-pc-windows-msvc` and
   `x86_64-pc-windows-gnu` on this host, but it has never been linked or executed here, so the
   console-event test remains a `windows-latest` gate. Note also that no Windows job has ever
   been green on this branch: `cli-rust/` is untracked, and every Rust step passes `--locked`.
   See "Fourth pass" above.
4. **Physical Wayland/X11 checks** of the TUI: the PTY tests drive a synthetic terminal. A human
   visual pass on real hardware — especially the two-pane layout at unusual widths and the mouse
   selection bypass against a real terminal's own selection — is still owed. The 29 captured frames
   in `cli-rust/tests/frames/` are the closest substitute and are meant for that review.
5. **The separate AUR migration and the broad Linux release rollout** were explicitly out of scope
   for this plan and were not attempted. `install.sh` was exercised only hermetically, in temp
   directories with stubs — never against a real installation.
6. **Release publication**: nothing was published. No tag, no release, no AUR push, no installer
   run against a live system. The three AUR packages are rendered and pushed by `release.yml` only
   once their repositories exist.
7. **One engine defect, now fixed; one still open.** The earlier classification of "a receiver
   whose download directory does not pre-exist fails at finalize" as pre-existing was **wrong** —
   it was introduced by this branch's new Linux-native path resolution, and it is fixed and
   regression-pinned (see "A previous … verdict was wrong" above). The other one, `/state`
   publication omitting a device that `POST /share` will still deliver to, is genuinely
   pre-existing — the routes it replaces performed no membership check either — and remains open.
8. **macOS debug link order** was wrong (the KlardropMac Debug configuration listed
   `-framework control_plane` after `$(inherited)`, the ordering its own comment says causes
   duplicate symbols) and is now corrected to match Release. It is still an **unrun** gate: no
   macOS runner here, so neither configuration has actually been linked.

---

## Final diff summary

Nothing is committed; the worktree is left dirty on purpose, and every unrelated in-progress
change in this branch was preserved.

- **80 tracked files changed, +4,462 / −963** after this pass, plus new untracked directories.
- The largest new surface is **`cli-rust/`** (the whole native client and TUI) and
  **`control-plane/`** (the shared production control component, renamed from `debug-control`).
- The native macOS pass added: `control-plane/src/macosMain/` (3 actuals),
  `control-plane/src/posixMain/` (the control file shared with Linux),
  `control-plane/src/macosArm64Test/`, `:control-plane`'s `macosArm64` target and framework, the
  `MacApp.swift` wiring, and the Xcode `FRAMEWORK_SEARCH_PATHS` / `OTHER_LDFLAGS` entries that
  link `control_plane.framework` into the shipped `KlardropMac` app.
- New scripts: `scripts/klardrop-rust-fixture-tests.sh`,
  `scripts/klardrop-jvm-host-fixture-tests.sh`, `scripts/klardrop-two-peer-transfer-tests.sh`,
  `cli-rust/scripts/tui-idle-cpu.sh`.
- New packaging surface: `packaging/linux/stage-native-tarball.sh`,
  `packaging/linux/test-omarchy-install.sh`, `packaging/linux/klardrop-qt-launcher.sh` (the single
  source of the shipped Qt launcher text, copied by both the stager and the test fixtures), and the
  three AUR templates under `packaging/aur/`.
- Docs: this report, the rewritten `cli/README.md`, the rewritten `cli-rust/README.md`, corrected
  `packaging/README.md`, `README.md` (the repository-layout table no longer lists a `macos/`
  module or `common-ui/`; it names the real Apple host and `:control-plane`), `site/index.html`,
  `linux/omarchy/TODO.md`, and the plan's own completion checklist.

### This pass's additions on top of the above

- `cli-rust/src/interrupt.rs` + `cli-rust/tests/interrupted_wait.rs` — SIGINT handling and its
  contract test; `cli-rust/Cargo.toml` gained unix-only `libc`.
- `scripts/klardrop-qt-real-engine-tests.sh` plus three Python helpers
  (`klardrop-qt-real-engine-checks.py`, `klardrop-qt-proxy.py`, `klardrop-qt-proxy-report.py`) —
  the real Qt frontend against the real engine.
- `common/src/linuxX64Test/.../EnsureDirectoryLinuxTest.kt` — the download-directory regression.
- `packaging/install.sh` gained `klardrop-share-pick`, the terminal bridge for the two GUI
  callers, plus the recoverable engine swap.
- `.github/workflows/build_pr.yml` gained `qt-regression` and `windows-release-package` jobs;
  `.github/workflows/release-nightly.yml` gained a `windows` job and the matching manifest keys.

### Representative output from this pass

Queued submission (unchanged contract):

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-0000000000000001","deviceId":"11112222","status":"queued","items":[{"transferId":"tx-1-0","path":"/tmp/x/report.pdf","fileName":"report.pdf","totalSize":12,"transferredSize":0,"status":"queued","error":null}]}
```

Delivered after a real two-peer transfer (3,000,000 bytes, receipt verified byte for byte):

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-1437ddc293df87d2","deviceId":"efdacf61","status":"completed","items":[{"transferId":"-1622808908","path":"/tmp/klardrop-two-peer.lzoqu5/klardrop-two-peer-payload.bin","fileName":"klardrop-two-peer-payload.bin","totalSize":3000000,"transferredSize":3000000,"status":"completed","error":null}]}
```

Interrupted wait — exit **130**, one JSON value, delivery explicitly unproven and the request id
named (then confirmed still live via `transfers --id`):

```json
{"schemaVersion":1,"ok":false,"command":"share","requestId":"req-0000000000000001","deviceId":"11112222","status":"unknown","items":[{"transferId":"tx-1-0","path":"/tmp/kd-int.IAJbn1/p.bin","fileName":"p.bin","totalSize":13,"transferredSize":6,"status":"transferring","error":null}],"error":{"code":"cancelled","message":"interrupted while waiting for delivery; the daemon was not told to stop, so this request may still complete; delivery state is unknown — inspect it with `klardrop transfers --id req-0000000000000001`. The transfer was neither cancelled nor retried."}}
```

Refused before anything is sent:

```json
{"schemaVersion":1,"ok":false,"command":"share","error":{"code":"ambiguous_device","message":"\"3333\" matches 2 devices; use a longer prefix or the full id. Candidates: 3333aaaa, 3333bbbb"}}
```

Memory, measured on this host after the fix (Linux x64, Kotlin/Native engine, idle workload
after one 3 MB two-peer transfer and the fixture suite):

```
sender daemon    VmRSS 34816 KiB = 35,651,584 bytes   (budget 50,000,000)
receiver engine  VmRSS 40912 KiB = 41,885,952 bytes   (not part of the budget)
```

### Compatibility changes a user can notice

| Change | Why |
| --- | --- |
| the engine binary is now `klardrop-engine`, not `klardrop` | the name `klardrop` now belongs to the Rust client, so no client invocation can accidentally be an engine |
| `klardrop share` requires `--to <device-id>` | an agent must never silently pick a peer; `share --pick` is the explicit interactive entry point |
| `klardrop send`/`discover`/`status` still exist in the Kotlin engine and still start their own engine | deliberate: those are the isolated-node diagnostics |
| `klardrop daemon <args…>` forwards to `klardrop-engine` | compatibility; argv only, never a shell |
| `klardrop interactive` is the new TUI; bare `klardrop` opens it only when stdin **and** stdout are terminals | automation must never be dropped into a full-screen UI |
| exit codes `0/1/2/3/4/130` with a stable `error.code` on every failure | agents need a machine-readable contract, not a human message |
| the Omarchy desktop entry and the Nautilus "Send with Klardrop" action now run `~/.local/bin/klardrop-share-pick` | `share --pick` needs a real terminal; neither GUI caller had one, so both failed. The helper finds one and runs the picker inside it |
| Ctrl-C during `share --wait` now prints a `cancelled` envelope naming the request id and exits 130, on Unix **and** Windows; `discover --wait` exits 130 too | the operator stops watching; the daemon does not stop sending, so "unknown, here is the id" is the only honest answer. Windows uses `SetConsoleCtrlHandler`, so the client survives the event instead of dying on the default disposition and losing the id |

### A note on the pre-existing processes

PIDs 644850 (`java`) and 1400483 (`klardrop.kexe` — the user's live daemon from the OLD build) were
still running at the end of this work and were never signalled, restarted or otherwise touched.
PID 629635, which was alive when this session began, is no longer present; nothing in this work
signalled it — every fixture script asserts, by PID-set census before and after, that it only ever
creates and signals its own children, and those assertions run on every invocation.

---

## Tracker reconciliation note

The session's task tracker reports 28 of 29 items complete. The single entry it still shows as
open — *"Engine: stable request/transfer IDs for each submitted operation, incl. multi-file"* — is a
**tracker defect, not unfinished work**: `done`, `unblock` and `drop` all return
`Task "…" not found` for the exact string the tracker itself displays. The entry was created during
an earlier reconciliation pass that passed both a phase list and a flat item list to `init`.

The capability it tracks is implemented and was re-proved directly after the tracker refused to
close it. Three files submitted in one `share` produce one `requestId` and one distinct
`transferId` per item, all reaching a terminal state, exit 0:

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-0000000000000001","deviceId":"11112222","status":"completed","items":[
 {"transferId":"tx-1-0","path":"/tmp/…/one.bin","fileName":"one.bin","totalSize":1,"transferredSize":1,"status":"completed","error":null},
 {"transferId":"tx-1-1","path":"/tmp/…/two.bin","fileName":"two.bin","totalSize":2,"transferredSize":2,"status":"completed","error":null},
 {"transferId":"tx-1-2","path":"/tmp/…/three.bin","fileName":"three.bin","totalSize":3,"transferredSize":3,"status":"completed","error":null}]}
```

The same property is pinned by `ControlPlaneShareTest.shareAnswersWithTheDocumentedShape` and by
the two-peer run, whose real engine returned `transferId` `599793811` for a 3,000,000-byte transfer
whose receipt the receiver verified byte for byte.
