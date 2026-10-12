# `klardrop-engine` — the Kotlin/Native engine

This Gradle module (`:cli`) builds **`klardrop-engine`**. It is **not** the
user-facing CLI. The user-facing CLI is a separate Rust program, `klardrop`,
built from [`../cli-rust`](../cli-rust) and documented in full in
[`../cli-rust/README.md`](../cli-rust/README.md).

## If you only read one section

| binary | built from | role | how you run it |
|--------|-----------|------|----------------|
| `klardrop` | `cli-rust/` (Rust) | the user- and agent-facing CLI and TUI. A pure client: it reads the running engine's control file and speaks authenticated loopback HTTP to it. It **never** starts an engine. | `klardrop devices`, `klardrop share --to <id> <path>…`, `klardrop interactive` |
| `klardrop-engine` | this module, `:cli` (Kotlin/Native `linuxX64` + `linuxArm64`, plus a JVM target) | the engine: the `daemon` service, the local control plane, and the deliberately standalone diagnostics `discover`, `listen`, `send`, `status`. | `klardrop-engine daemon` (as a service), `klardrop-engine listen`, … |

The two are installed as distinct files by the native flavors of
[`packaging/install.sh`](../packaging/install.sh): `~/.local/bin/klardrop` and
`~/.local/bin/klardrop-engine`. The systemd `--user` unit runs
`ExecStart=%h/.local/bin/klardrop-engine daemon`. The name split is the point:
a stray `klardrop` is either the client (which contacts the running engine) or
an explicit `klardrop-engine daemon`, so no client invocation can start a
second engine by accident. See [`../packaging/README.md`](../packaging/README.md)
("The native Linux tarball") for the tarball layout.

Users and agents driving the command line want
[`../cli-rust/README.md`](../cli-rust/README.md). This file is the reference for
the engine only. For the running evidence log see
[`../docs/cli-rust-implementation-report.md`](../docs/cli-rust-implementation-report.md).

## Building

Engine (Kotlin/Native, the shipped artifact):

```sh
./gradlew :cli:linkReleaseExecutableLinuxX64    # -> cli/build/bin/linuxX64/releaseExecutable/klardrop-engine.kexe
./gradlew :cli:linkReleaseExecutableLinuxArm64   # -> cli/build/bin/linuxArm64/releaseExecutable/klardrop-engine.kexe
```

JVM target, for development (`mainRun` main class is
`com.carlom.klardrop.cli.MainKt`):

```sh
./gradlew :cli:jvmRun --args="status"
```

The Rust client is built with cargo; its build, test, clippy and fmt commands
are documented in [`../cli-rust/README.md`](../cli-rust/README.md#build).
`packaging/linux/stage-native-tarball.sh` expects both artifacts before it
stages a tarball.

## Commands

Registered in `Main.kt` (four legacy commands) plus `platformSubcommands()`,
which contributes `daemon` and `share`:

| subcommand | talks to a running daemon? | lifecycle |
|------------|---------------------------|-----------|
| `daemon` | n/a — **it is** the daemon | current |
| `listen` | no — starts its own engine | current |
| `discover` | no — starts its own engine | frozen / compatibility |
| `status` | no — starts its own engine | frozen / compatibility |
| `send` | no — starts its own engine | frozen / compatibility |
| `share` | **yes** — reads the control file | superseded by the Rust client |

> The Clikt root command is still *named* `klardrop` (`Main.kt`), so
> `klardrop-engine --help` prints a `Usage: klardrop …` line. The binary is
> `klardrop-engine`; the help text's name is historical.

### `daemon` — the service

The one command you run as a service. It starts the full engine
(`Klardrop.init()`), binds the control plane (`ControlPlane.bind`), publishes
`control.json`, prints
`Klardrop daemon running on port <port> (token auth enabled)`, and blocks in
`awaitTerminationRequest()` until SIGINT/SIGTERM, then calls `ControlPlane.stop()`.

| flag | type | default |
|------|------|---------|
| `--port`, `-p` | int | `0` (ephemeral; the bound port is still published to `control.json`) |
| `--debug` | flag | off |
| `--no-klardrop` | flag | Klardrop TCP server on |
| `--no-nearby` | flag | Nearby Share server on |
| `--no-ble` | flag | BLE transport on |
| `--data-dir` | path (env `KLARDROP_HOME`) | platform default |

Exit codes: `0` on clean shutdown, `3` with `control port N already in use`,
`1` with `daemon failed: …` for any other startup failure.

### `share` — daemon-backed (superseded)

The only subcommand in this module that talks to a running daemon. Superseded
by `klardrop share` in the Rust client; kept because the desktop entry, the
Nautilus action and the Omarchy share helper call it.

| argument / flag | meaning |
|-----------------|---------|
| `paths` (positional, multiple) | files or directories to share |
| `-t`, `--to` | target device id (short or full) |
| `--text` | share a text message instead |
| `--clipboard` | share current clipboard content instead |

Exactly one payload kind, decided in that order: `--clipboard` →
`/send-clipboard`, `--text` → `/send-text`, otherwise `paths` → `/send-file`.
With none of them it exits `2` before contacting anything. Every path is
existence-checked and absolutized locally first; a missing path exits `2` and
sends nothing.

Requests go to the literal `127.0.0.1:<port>` from the control file, with
`Authorization: Bearer <token>` and `Connection: close`. A `200` prints
`Shared successfully`; any other status exits `1`. A missing control file
exits `3`.

Caveats worth knowing before you use it:

* Device selection without `--to` is **Omarchy-only**: it queries `GET /state`,
  filters to `paired == true`, and shells out to `omarchy-menu-select`
  (`/usr/share/omarchy/bin/omarchy-menu-select`, else `PATH`), 120 s timeout.
  Cancelling exits `130`. Without that binary, `--to` is required.
* There is no `--json`, no `--wait`, no `--timeout`, and no way to query a
  request afterwards. The Rust client's `share` / `transfers` cover all of that.
* Its "daemon is not running" message suggests `klardrop daemon`. Both spellings
  work: the Rust client has a `daemon` subcommand that forwards to the sibling
  `klardrop-engine` binary with an argument vector, and `klardrop-engine daemon`
  always works directly.

### `listen` — standalone receiver

Starts its own engine through `CliController`, then collects
`messenger.receive()`. Incoming `PendingAuthorization` transfers are
**auto-accepted**. With `--json` it emits one JSONL record per completed
transfer; text content is escaped (spaces → `_`, truncated at 120 chars).

| flag | type | default |
|------|------|---------|
| `--timeout` | long seconds | `600` (`0` = run until Ctrl-C) |
| `--json` | flag | off (JSONL on stdout) |
| `--debug` | flag | off |
| `--no-klardrop` | flag | Klardrop TCP server on |
| `--no-nearby` | flag | Nearby Share server on |
| `--data-dir` | path (env `KLARDROP_HOME`) | platform default |

Exit `0` when the window elapses, `3` if the engine fails to initialize.

### `discover`, `status`, `send` — frozen, standalone diagnostics

These three are superseded by the Rust client but kept as isolated-node
diagnostics. Each one **initializes its own engine** via `CliController` — it
does not read the control file, does not observe a running daemon, and dials
peers directly. That is deliberate: with `--data-dir` (or `KLARDROP_HOME`)
pointing at a private path, a process gets its own identity, trust store,
databases and FileKit dirs, so two nodes can run on one host. The cost is
that a second engine on the LAN is a *second identity*, not a view of the
daemon's.

| | `discover` | `status` | `send` |
|-|------------|----------|--------|
| arguments | – | – | `DEVICE_ID`, optional `CONTENT` |
| `--json` | device array on stdout | `StatusJson` (`running`, `debug`, `device_count`, `devices`) on stdout | – |
| `--debug` | ✅ | ✅ | ✅ |
| `--timeout` | seconds, default `5` | – | – |
| `--settle-timeout` | – | – | seconds, default `10` |
| `--no-klardrop` / `--no-nearby` | – | – | ✅ |
| `--data-dir` (`KLARDROP_HOME`) | ✅ | ✅ | ✅ |
| exit codes | `0`, `3` init failure | `0`, `3` init failure | `0` delivered, `1` send failure, `2` usage, `3` init failure |

Behaviour notes:

* `discover` runs the **full** window and does not stop early; it prints a `.`
  per second (unless `--json`/`--debug`) and reports each device the first time
  it is seen. It has no `--no-klardrop` / `--no-nearby` flags — it initializes
  with defaults.
* `status` takes a single snapshot of visible devices. Its empty-state hint
  says `Run 'klardrop discover'`; as an engine command that is
  `klardrop-engine discover` (or, better, `klardrop devices`).
* `send`: `--file`/`-f` wins over `--text`/`-t`, which wins over the positional
  `CONTENT`; a `CONTENT` containing `/` or `\` is treated as a file path and
  anything else as text. The target is polled every 500 ms until
  `--settle-timeout`; if it never appears, the visible ids are listed and the
  exit is `2`.

## Exit codes used by this module

| code | meaning |
|------|---------|
| `0` | success (delivery confirmed, for `send`) |
| `1` | `daemon`: startup failure · `share`: non-200 or transport error · `send`: send failure or decline |
| `2` | usage error (`share` with no payload or a missing path; `send` with an unknown device or no content) |
| `3` | engine init failure, control port in use, missing/invalid control file, or no paired device to pick |
| `130` | `share`: device selection cancelled |

## How the engine and the client find each other

1. `daemon` calls `ControlPlane.bind`, which ends in `start()` and writes
   `control.json` via `writeControlFile`. The write **fails closed**: if the
   file cannot be created or protected, the listener is torn down rather than
   left bound with no way to authenticate. The body is hand-encoded so the JVM
   and native writers are byte-identical:
   `{"port":…,"token":…,"apiVersion":…,"capabilities":[…]}`.
2. The token is a fresh 32-lowercase-hex string per run — safe to place in an
   `Authorization` header — and it is regenerated on every daemon start.
3. **Search order on Linux** (identical for the engine and the client):
   `$XDG_RUNTIME_DIR/klardrop/control.json` when that variable is set and
   non-empty, otherwise `$HOME/.cache/klardrop/control.json`. The Rust client
   additionally honours an explicit `--control-file <path>` override ahead of
   both; this module has no such flag, so `klardrop-engine share` always uses
   the search path.
4. **On macOS the native app publishes elsewhere.** `Klardrop.app` is sandboxed and
   cannot write outside its container, so the `:control-plane` macOS actual publishes
   into the App Group the app already shares with its share extensions:
   `$HOME/Library/Group Containers/D7T5425WSW.group.com.carlom.Klardrop/control.json`.
   The Rust client probes that first. This JVM host, which is not sandboxed, keeps
   using `$HOME/.cache/klardrop`, and the engine honours
   `KLARDROP_CONTROL_FILE=<path>` to pin the file explicitly (used by the macOS
   fixture; unset in production).
5. Both sides pin the peer to the literal `127.0.0.1:<port>` and send
   `Authorization: Bearer <token>` on every request.

The client never starts an engine. Within this module only `share` reads the
control file; `daemon` writes it; `discover`, `status`, `send` and `listen`
ignore it and each own a separate engine instance.

## Module layout

| path | role |
|------|------|
| `Main.kt` | entry point (`main`), Clikt root command, subcommand registration |
| `PlatformSubcommands.kt` | the extra subcommand list the root command adds (`daemon` + `share`); one common implementation, no per-platform actual |
| `commands/` | the six `CliktCommand` implementations |
| `CliController.kt` | the bridge that initializes/shuts down an engine for the standalone commands |
| `CliProcess.kt` | `expect`/`actual` seams: subprocess capture, termination-signal handling, shutdown completion |

Architecture: Kotlin Multiplatform (Clikt for parsing, FileKit for paths,
Ktor for sockets) on top of `:klardrop-common`, `:presentation` and
`:control-plane`.