# Klardrop native Rust CLI/TUI: execution plan for OMP

## Objective

Make a native Rust CLI/TUI useful to people and automation without starting another
Klardrop engine. Both interactive and imperative commands use authenticated local
IPC to the existing engine, identity, discovery, trust storage, and transfers.
The engine remains Kotlin Multiplatform compiled for each supported platform;
Rust owns only the CLI/TUI shell. The user's latest
interactive requirements are a minimalist two-pane TUI with mouse support, playful
restrained animation, and system colors with Tokyo Night fallback. Use Ratatui with
Crossterm for the TUI and a small conventional Rust command parser. Do not embed
Kotlin/JVM through FFI/JNI or rewrite discovery, pairing, crypto, or transfer logic.

The user authorized OMP to implement the entire plan, with independent review
between phases. Continue through all four phases after each review; do not wait for
the user to notice that a phase ended. All implementation is delegated to OMP;
The user subsequently requested planning-only Codex work because of approaching
usage limits: OMP now owns implementation, fresh independent reviewer tasks,
validation, fixes, and progression through every phase without Codex intervention.

## Architecture and platform decisions

- `cli-rust/`: native Rust client binary named `klardrop`, built per OS/architecture.
  Commit Cargo.lock, use release builds for profiling, and keep dependency features
  narrow. Prefer a maintained small HTTP client over a new handwritten HTTP parser;
  the current transport is authenticated loopback HTTP, so no TLS stack is needed
  for local-only traffic. Network work must not block terminal input.
- Linux: existing Kotlin/Native executable is the engine, shipped separately as
  `klardrop-engine`. The service executes `klardrop-engine daemon`. Ordinary Rust
  commands never create another engine. Explicit `klardrop daemon ...` may forward
  to the sibling engine with an argument vector for compatibility; no shell eval.
- macOS: the existing native macOS KMP engine/app serves the same local control
  contract. It is compiled for macOS, not an iOS framework loaded into macOS.
- Windows: Rust is native; initially it communicates with the existing KMP JVM
  engine/app. This retains the engine's JVM cost but avoids another JVM for each
  CLI invocation. A Windows Kotlin/Native engine port is separate future work;
  do not claim a native engine exists there or attempt that port in this plan.
- Keep Android/iOS native shells and their shared engine code working. No mobile
  Rust TUI or mobile IPC service is required by this task.
- Production control availability must be real: today Linux has a production
  daemon API, but desktop JVM control is debug-gated and the native macOS bootstrap
  does not expose it. Reuse/extract the existing control implementation into an
  appropriate shared production component without creating module dependency
  cycles. Enable required authenticated user-facing control actions in production
  desktop hosts; retain test-only controls behind their debug boundary. Do not
  duplicate the core/API implementation independently for each platform.
- Preserve existing Qt/Omarchy/native macOS shells. A new CLI shell must not own
  their engine lifecycle. Use platform-appropriate private control-file locations,
  loopback-only binding, token protection, and capability/version negotiation.
  Protect Windows metadata with per-user ACLs rather than treating POSIX mode bits
  as effective Windows protection. The existing JVM ControlFile implementation
  unconditionally uses POSIX attributes; make platform-specific private storage
  agree with Rust metadata discovery, and fail closed if protection cannot be applied.

## Superseded Kotlin-client work

A prior OMP session began Kotlin client phase 1 before the user selected Rust.
That session was stopped. Its changes are preserved at
`/tmp/klardrop-omp-kotlin-work/changed-files.json`, with the edited files beside it.
Pre-session originals are under `/tmp/klardrop-omp-phase1-before/`.

Inspect those exact files and restore only the superseded OMP client changes;
retain the pre-existing Kotlin engine, diagnostic commands, and all unrelated branch
work. Never reset the entire worktree. Do not restore this plan from the old snapshot.
Record this cleanup in the first phase diff. Use useful protocol observations as
reference, not a reason to retain two production CLI clients.

This is an implementation handoff, not permission to publish a release, migrate a
live installation, restart the user's daemon, or send files to real nearby devices.

## Execution protocol

Run OMP with **`space-bunny-alpha`**, as explicitly requested by the user. Pin the
main, planning, fast, and review model roles to it; do not silently switch models
or fall back if it is unavailable. Report the blocker instead. OMP subagents used
for this handoff must also use `space-bunny-alpha`.

Start a fresh session from the repository root, with this plan attached:

```sh
omp --model space-bunny-alpha \
  --smol space-bunny-alpha --slow space-bunny-alpha --plan space-bunny-alpha \
  --no-prewalk \
  @docs/cli-daemon-agent-brief.md \
  "Execute the full plan. Review and validate each phase in fresh reviewer tasks, fix findings, then proceed autonomously through Phase 4."
```

The options and `@file` syntax were checked against the installed OMP v18.4.9
help. Model availability/authentication must be verified when execution starts.
The full-plan OMP lead starts each subsequent phase after a fresh reviewer has
approved the preceding phase. Do not wait for Codex or the user between phases.
Do not use an unqualified resume/continue command that could select another
repository's session.

Read the applicable AGENTS.md instructions and this entire plan before editing.
The user's newer explicit OMP model selection takes precedence over older role
model selections in those instructions for this handoff.
Execute one phase at a time. Have a fresh reviewer inspect the diff and independently
verify the relevant checks, fix findings, and record evidence before proceeding.
If delegating to agy, follow the session/model rules in AGENTS.md. Do not invent
unavailable model names or start concurrent agy sessions.

Use temporary profiles, private runtime directories, fixture peers, and owned child
processes. Never kill or restart existing Klardrop processes. Preserve unrelated
changes in this branch. Do not claim a check passed unless it actually ran.

At each phase, report changed files, runnable checks and results, compatibility
changes, and remaining gaps. Update the checklist below when the phase is approved
by a fresh OMP reviewer. Keep durable progress and final evidence in
`docs/cli-rust-implementation-report.md`, distinguishing real checks from gaps.
All implementer and reviewer tasks use space-bunny-alpha; reviewers must not be
the task that implemented the code they inspect.
Do not implement the separate AUR migration or broad Linux release rollout here.

## Current behavior and starting points

- `cli/.../Main.kt`: existing Kotlin engine/diagnostic command registration. Retain
  its daemon role; the new production user CLI lives in `cli-rust/`.
- `cli/.../commands/ShareCommand.kt`: talks to the daemon's authenticated loopback
  API, but has its own HTTP parser, no bounded overall request deadline, no JSON
  result mode, and Omarchy-only interactive device selection.
- `SendCommand.kt`, `DiscoverCommand.kt`, and `StatusCommand.kt`: initialize a
  separate engine through `CliController`. Status therefore describes that new
  engine rather than querying the installed service.
- `SendCommand.kt`: existing success means completed delivery. Preserve that
  meaning when converting its syntax to a daemon-backed compatibility command.
- `debug-control/.../DebugControl.kt`: `/state`, `/send-file`, `/send-text`, and
  `/send-clipboard` already exist. `/send-file` calls an asynchronous action and
  returns HTTP 200 before transfer completion. Text routes await a result but can
  return HTTP 200 with an error result. HTTP 200 alone is not delivery confirmation.
- `presentation/.../DiscoveryController.kt`: `sendFiles`, outgoing transfer IDs,
  progress flows, and `debugSendTextAndWait` are existing building blocks. Reuse the
  actual send path and its progress collection; do not send or collect a cold
  transfer flow twice when adding observation.
- `scripts/klardrop-ctl`: existing daemon API tooling and fixture patterns.
- `scripts/cli-integration-tests.sh`: existing standalone JVM diagnostic tests.
  Preserve their deliberate separate-node testing behavior explicitly.
- `cli/README.md`: currently describes a JVM-only CLI; update it to native Linux
  and daemon-backed usage as part of this work.

Paths abbreviated above are under
`cli/src/commonMain/kotlin/com/carlom/klardrop/cli/`,
`debug-control/src/commonMain/kotlin/com/carlom/klardrop/debug/`, and
`presentation/src/commonMain/kotlin/com/carlom/klardrop/`.

## User-facing contract

Target commands, to be implemented and documented:

```sh
klardrop                                      # interactive only when stdin AND stdout are terminals
klardrop interactive                          # explicit two-pane terminal UI
klardrop interactive --theme tokyo-night --no-motion
klardrop devices --json
klardrop status --json
klardrop share --to <device-id> report.pdf
klardrop share --to <device-id> report.pdf notes.txt --wait --timeout 120 --json
klardrop share --to <device-id> --text "hello" --wait --json
klardrop share --to <device-id> --clipboard --wait --json
klardrop transfers --json
klardrop transfers --id <request-id> --json
```

- Explicit commands never open a menu. Missing `--to` in imperative sharing is a
  usage error with guidance to `interactive`; no Omarchy GUI picker is launched.
  Preserve Omarchy file-manager/menu sharing by updating its known callers to use
  an explicit interactive option/command when they need device selection.
- No arguments without a terminal print help and exit promptly. Explicit
  `interactive` without a terminal fails promptly with usage code 2. Never read
  from redirected stdin merely because a required argument is missing.
- `devices` queries the daemon's discovered devices, identifying pairing and
  reachability. Resolve an exact ID or unique ID prefix. Reject ambiguous prefixes;
  never silently choose the first match or target by an ambiguous display name.
- Reject mixed payload types before submission: files, text, and clipboard are
  alternatives. Support multiple paths, spaces, Unicode, and `--` for dash-prefixed
  filenames. Preserve existing file/directory support where it actually works;
  rejected/unsupported paths must not be silently skipped and reported successful.
- `share` without `--wait` reports **queued**, not delivered. With `--wait`, success
  requires confirmed terminal completion for every submitted item.
- The `send DEVICE_ID --file ...` and `send DEVICE_ID --text ...` syntaxes remain
  compatibility commands using the daemon, defaulting to completion waiting.
  Retain documented positional-content behavior without expanding its heuristics.
- Preserve `discover` as a daemon-backed compatibility command, including bounded
  discovery waiting. Preserve its existing JSON array and status JSON fields unless
  an explicit migration is documented; new canonical commands can use envelopes.
- Normal Rust client commands never initialize `CliController` or spawn an
  engine. Missing/stale daemon information gives a clear start command and exit 3;
  there is no silent standalone engine or automatic service-start fallback.
- Keep `daemon` and deliberately standalone `listen` diagnostics in the Kotlin
  engine. Preserve isolated-node testing through explicit engine invocation or
  `--standalone` compatibility forwarding where needed; `--data-dir` by itself
  does not authorize a client to start another engine. Keep non-Linux behavior
  working through the platform control contract described above.

### Output and exit codes

For new `--json` commands, emit one valid JSON value on stdout on both success and
failure. Put progress, diagnostic logs, and debug output on stderr. No ANSI styling,
prompts, banners, tokens, or unrelated engine logs may contaminate JSON stdout.

Use a small versioned envelope, for example:

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"...","status":"queued","deviceId":"...","items":[{"transferId":"...","path":"...","status":"queued"}]}
```

For failures include a stable machine-readable `error.code` and human-readable
`error.message`; do not expose local authentication tokens. Preserve compatibility
command JSON formats as specified above. Do not infer API compatibility solely
from matching CLI/daemon version strings.

| Exit | Meaning |
| --- | --- |
| 0 | Command succeeded; queued for ordinary share, all items completed for `--wait` |
| 1 | Operational failure, recipient decline, or transfer failure |
| 2 | Invalid arguments, ambiguous target, invalid path, or terminal required |
| 3 | Daemon unavailable, invalid control metadata, or unsupported daemon capability |
| 4 | Discovery/request/completion deadline exceeded |
| 130 | User cancelled the interactive workflow or interrupted a wait |

`--timeout` is a positive finite overall command budget in seconds, including
connection, discovery, submission, and waiting. Use 120 seconds as the share/wait
default; preserve bounded existing discovery defaults. Reject invalid values before
any send. A completion timeout or interrupted wait does not prove failure or cancel
the daemon's transfer. Report the known request ID and whether delivery remains
unknown; users can inspect it with `transfers --id`. Never retry a mutating request
automatically after an uncertain response.

## Phase 1 — Rust client, production control, and read-only commands

1. Establish fixture coverage for current command syntax and standalone diagnostics.
2. Create the smallest reusable Rust daemon client using the existing control
   contract as reference. Use a bounded maintained HTTP library and Rust deadline
   accounting, with serde JSON and a conventional parser such as clap. Avoid an
   async runtime unless it simplifies the real workload; std threads/channels can
   keep blocking local requests out of the TUI. Pin compatible dependency versions.
3. Bound and validate control-file reads, port, token, response headers/body lengths,
   and overall I/O time. Follow the Qt client's existing security/size expectations
   (including its 4 MiB response ceiling) where applicable. Connect only to loopback;
   disable environment/system HTTP proxies and all redirects so the token never
   leaves loopback. Do not permit header injection or use unbounded peer-controlled
   allocation.
4. Implement Rust devices/status and compatibility discover. Do not switch shipped
   binaries/callers in this intermediate phase. Add the shared production control
   component and desktop host hooks described above, including private per-platform
   metadata discovery and capability reporting. Keep the existing debug test API
   and user shells working. Establish a production JVM-host fixture in addition to
   the native Linux fixture; compile macOS host code on a supported runner.
5. Defer Rust legacy file `send` until phase 2 provides completion correlation;
   never substitute queued success for its delivery success. Keep legacy Kotlin
   diagnostic behavior and the existing Omarchy picker unchanged until their
   replacements and packaging are ready.
6. Introduce the JSON/error/exit contract and clarify unsupported older daemon
   behavior. An old daemon must not yield a false delivery-success result.

Acceptance: Rust devices/status/discover query one existing fixture engine without starting
listeners/discovery; absent/stale/malformed metadata fails promptly; JSON stdout
parses cleanly, including with debug logging and errors; old diagnostic workflows
and legacy send still operate in separate profiles. No Qt or Omarchy protocol
regression. Production control can be exercised without a debug-only build flag.
Record actual Windows/macOS validation separately from native Linux evidence;
phase 1 does not claim packaging switched or all commands are implemented.

## Phase 2 — Correlated transfer results and agent commands

1. Return stable request/transfer IDs for each submitted operation, including
   multi-file operations. Associate results with the actual submitted transfers,
   not filename matches, global "latest history", or unrelated concurrent activity.
2. Observe the existing transfer completion/ACK path. Expose queued, pending
   recipient approval, transferring, completed, declined, and failed outcomes.
   Reuse existing history/state if it can provide exact correlation. Add only a
   focused status endpoint if necessary; keep existing routes compatible with Qt,
   Omarchy, and scripts. Track text/clipboard using the same truthful result contract.
3. Implement `share --wait`, `--json`, validated timeouts, and `transfers` inspection.
   Partial multi-file completion is a nonzero result listing each item's outcome.
   Now implement Rust legacy `send` through IPC, preserving delivery
   waiting and terminal exit semantics. Verify that ordinary client commands never
   start an engine; retain separate-engine diagnostics only explicitly.
4. Bound any added in-memory result retention and document lookup after eviction
   or daemon restart. Return a clear not-found/unknown result instead of false
   completion. Do not add a second transfer database or indefinite result cache.
5. Ensure local preparation failures and remote declines produce visible errors;
   submission timeout/connection loss must never cause an automatic duplicate send.

Acceptance: fixture tests cover queued vs completed, delayed ACK, decline, failure,
timeout with unknown delivery, missing file, mixed payload refusal, ambiguous IDs,
multi-file partial failure, and two concurrent requests to the same device. Waiting
must follow the correct IDs and never report success from another operation. Existing
Qt/Omarchy endpoints continue to work.

## Phase 3 — Two-pane interactive TUI, mouse, motion, and themes

This supersedes the original numbered-menu scope following the user's explicit
request. Implement it in reviewed increments within this phase: terminal/input
foundation, main flows, then themes/motion and terminal recovery checks. Retain the
one-phase-at-a-time execution protocol and the imperative contract from phases 1–2.

### Layout and interactions

1. Use reliable Rust terminal detection on Linux, macOS, and Windows.
   No-argument terminal invocation and `interactive` open the TUI. Use an alternate
   screen with a left device pane and right conversation/transfer pane, a compact
   header, and discoverable keyboard shortcuts in the footer. Devices show names,
   trust, reachability, selection, and unread state. Right-pane tabs or a small
   actions menu can host secondary features without adding more persistent panes.
2. Offer keyboard navigation (Tab/Shift-Tab focus, arrows, Enter, Escape, help, quit)
   and mouse selection/focus, button clicks, and wheel scrolling. No feature may
   require a mouse. Mouse capture is confined to the TUI; offer `--no-mouse` and
   document the terminal's selection bypass. Use Ratatui/Crossterm input and width
   handling rather than inventing a raw escape-sequence parser; the previous
   Kotlin TUI selection is superseded.
3. Handle resize, scrolling, Unicode/wide characters, wrapped long names, empty
   states, and small terminals. Use a compact single-pane/back-navigation layout
   where two panes cannot fit; do not clip controls outside the screen. Keep a
   simple line-mode fallback for unsupported/dumb terminals. Avoid huge history
   renders: page existing daemon history and retain a bounded visible window.
4. Match the current UI's main flows through existing daemon actions: device
   selection, chat/history, text, files, clipboard, outgoing progress/results,
   pairing requests and incoming-transfer approval, QR sharing, device rename,
   supported discovery settings, and update status/apply. Reuse existing trust and
   receiver decisions; no automatic pairing, acceptance, or new trust policy.
   Represent QR with terminal cells only if it can retain a readable quiet zone;
   give a clear small-terminal alternative rather than an unreadable code.
   Honor daemon capability flags; label unavailable features rather than exposing
   controls that silently do nothing. No thumbnails or new BLE/X11 implementation.
5. History read marking must follow the actually focused, visible conversation,
   preserving the Qt UI's lifecycle behavior. Update application remains non-force:
   409 means wait/retry explicitly, with no automatic action replay. Leaving the
   TUI never stops the daemon or cancels a transfer merely by closing its observer.
6. Support an explicit interactive sharing entry point for already supplied paths
   or clipboard content. Keep the Omarchy shell picker as an explicit adapter if
   needed; normal TUI use must not depend on Omarchy, a display server, or `fzf`.
   Prepare the explicit Omarchy adapter and updated caller fixtures in this phase;
   switch installed helper/context-menu callers together with the binary packaging
   in phase 4. Rust imperative sharing requires `--to`; explicitly requested
   picker interaction is distinct and never invoked by an agent's ordinary command.

### Colors and tasteful animation

7. Provide `--theme auto|system|tokyo-night`, default `auto`. Use reliably available
   system/terminal colors first, including the current Omarchy palette when present
   (read-only). Fall back to Tokyo Night when no usable system palette is detected.
   Validate bounded palette input; never execute shell/theme configuration. Terminal
   color queries, if needed, must be supported, interactive-only, bounded, and must
   not consume keyboard input accidentally. An unsupported terminal must not hang.
8. Use semantic colors for foreground/background, muted text, accent/focus, success,
   warning, and error. Reuse the actual Tokyo Night palette, with appropriate color
   capability fallback (truecolor, indexed colors, monochrome). Respect `NO_COLOR`
   and the CLI's explicit no-color control; ensure readable selected/disabled rows.
   Status remains understandable through labels and glyphs without color. Preserve
   readable contrast for both light and dark system themes.
9. Add a little fun: a subtle connection/discovery spinner or moving transfer
   indicator and a short success flourish. Keep progress truthful; animation never
   substitutes for a daemon result. Provide `--no-motion`; avoid blinking, flashing,
   infinite idle decoration, and animations on redirected/JSON output. Coalesce
   redraws, cap active animation around 10 frames/second, and stop animation timers
   when idle or hidden. Measure idle CPU rather than assuming rendering is cheap.

### Terminal correctness and acceptance

10. Raw input must handle Ctrl-C explicitly. Restore raw/cooked mode, cursor,
    alternate screen, mouse capture, and any enabled keyboard modes on ordinary
    exit, EOF, exception, and handled termination. Keep network work off the input
    loop so waiting for a peer does not freeze navigation. Escape remote text,
    device names, and filenames before drawing; never render untrusted terminal
    control sequences. Bracketed pasted text must not trigger shortcuts or submit
    a send just because it contains newlines.

Acceptance: PTY-based checks exercise keyboard and mouse selection, scrolling,
resize/small terminal behavior, a real fixture send, no devices, offline state,
pairing/incoming decisions, cancellation, EOF, and terminal restoration. Check
Tokyo Night fallback, a fixture system palette, malformed/missing palette input,
NO_COLOR, no-motion, and a dumb terminal. Pipe/non-TTY/JSON checks prove automation
never hangs, queries terminal capabilities, emits UI escapes, or spawns a picker.
Capture representative terminal frames for human visual review. Exercise secondary
UI actions against fixture daemon responses and retain focused-history/update
semantics. Run without Omarchy commands or a display environment.

### Current primary references (checked 2026-10-01)

- [Ratatui backends](https://ratatui.rs/concepts/backends/): raw mode, alternate
  screen, mouse input, test rendering, and matching Crossterm versions. Use one
  compatible Crossterm version; duplicate event queues/raw-mode ownership are bugs.
- [ureq documentation](https://docs.rs/ureq/latest/ureq/): candidate small blocking
  HTTP client for local IPC. Disable unused TLS/proxy/compression behavior and
  validate redirects, body limits, and end-to-end deadlines for the selected version.
- [Bubble Tea releases](https://github.com/charmbracelet/bubbletea/releases): current
  implementation lessons include input-disabled capability-query leakage, terminal
  restoration, and Unicode rendering. Use these lessons, not a Go runtime rewrite.
- [Kitty keyboard protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/):
  optional enhanced input is negotiated and restored; keep legacy keyboard support.
- [Tokyo Night palette](https://github.com/folke/tokyonight.nvim/tree/main/lua/tokyonight/colors):
  palette reference for the requested default; use its license/attribution correctly.

## Phase 4 — Packaging, compatibility, documentation, and launch evidence

1. Package Rust `klardrop` and KMP `klardrop-engine` together for Linux x64/ARM64;
   include the Rust CLI in native desktop release artifacts for macOS and Windows.
   Stage only freshly built correct-architecture binaries. Update stable/nightly
   CI, installer, user service paths, archive metadata, and native package templates.
   No JVM is installed by native Linux routes. Ordinary commands require an already
   running host, while explicit daemon/standalone forwarding preserves documented
   legacy launch/diagnostic entry points. Engine launch must not recursively invoke
   the frontend or depend on a guessed working directory/PATH.
2. Extend existing installer/self-updater ownership and rollback checks to the new
   Rust binary. Pair engine, Rust shell, and applicable Qt artifacts when updating;
   do not leave mixed versions or overwrite package-owned/unrelated files. Preserve
   recoverable backups on rollback failure and legacy install migration behavior.
   Keep updater/API/Omarchy compatibility tests meaningful for the new payload.
3. Rewrite `cli/README.md` and add focused Rust build instructions with human/agent
   examples, exact output/exit semantics, standalone diagnostics, and timeout limits.
4. Switch actual Omarchy helper/context-menu callers to the prepared explicit picker
   entry point alongside packaging, and complete integration docs. Record changes
   such as imperative share requiring `--to`. Verify existing desktop actions.
5. Run focused Rust command/API/render/PTY tests (`cargo test --locked`, format,
   lint, release build), then required native/JVM builds
   and affected regression checks. Identify the real Gradle tasks first; native
   release tasks are `:cli:linkReleaseExecutableLinuxX64` and
   `:cli:linkReleaseExecutableLinuxArm64`. Run ARM64 checks on a supported runner;
   do not claim cross-platform validation from an x64-only build.
6. Verify one actual two-peer transfer in isolated profiles with receiver-side
   receipt/content checks, including agent JSON output and completion exit status.
   Invoke the built binary directly for exit-code assertions; Gradle can collapse
   application failures into its own exit code. Do not scrape the last JSON-looking
   line to conceal polluted stdout.
7. Profile a separate native daemon before/after repeated Rust client commands and after
   clients exit. Background daemon RSS must remain below 50,000,000 bytes in the
   documented idle workload; report transient CLI memory separately. Preserve
   Linux `pagedAllocator=false`. Check for retained request records/resources.
8. Record remaining release gates separately: physical Wayland/X11 checks, native
   x64/ARM64 release matrix, and published AUR migration. CLI completion does not
   mean those gates or publication are complete.

## Completion checklist
- [x] Phase 1 reviewed: daemon client and read-only command routing are verified.
- [x] Phase 2 reviewed: send routing and exactly correlated agent outcomes are verified.
- [x] Phase 3 reviewed: terminal workflow is portable and automation never prompts.
- [x] Phase 4 reviewed: paired packaging, docs, regression evidence, peer receipt, and memory evidence recorded.

**Addendum (2026-10-02).** Phase 1 was re-audited against the repository after the first full run.
That run concluded that macOS was served only by the JVM desktop host because there is no
`:macos` Gradle module, and declined to wire the native app. That conclusion was wrong — the
native macOS app is built from `presentation`'s `macosArm64` framework plus the Xcode `KlardropMac`
target, and the absence of a Gradle module says nothing about `macosMain`. The native macOS host
has since been wired to the shared control plane for real (`:control-plane` `macosArm64` target and
`control_plane.framework`, `MacApp.swift` start/bind, App Group control-file metadata, a macOS
native fixture, and CI that builds and tests the shipped host). The four boxes above still stand
as phase approvals; the macOS work is a **completion pass on Phase 1's production-control
requirement**, and what is *not* verified (no macOS runner on the development host) is enumerated
in `docs/cli-rust-implementation-report.md` under "Remaining release gates". Two engine-side
findings from the two-peer run were verified to be **pre-existing, not regressions** of this work,
and remain open. **Correction (third pass):** that classification was half wrong. The
receiver-finalize `ACK_REJECTED` defect was *not* pre-existing — it was reachable only through
the Linux-native download-path provider this work adds, and is now fixed and pinned. Only the
`/state`-omits-a-device finding is genuinely pre-existing.

A second, independent review pass then found and fixed gaps that spanned every phase, not just
macOS: the Rust CLI is now built and packaged for Windows as well (a `windows-cli` release
asset), exit **130** is actually emitted for a cancelled interactive workflow with a proper
`cancelled` envelope, the missing-`--to` usage error now carries the guidance to `interactive` as
the contract requires, and the PTY suite gained the mouse-click / resize / cancellation /
end-of-input coverage the Phase 3 acceptance list named. Writing the mouse test found a real
product bug — clicking a device row selected the row *above* it — which is fixed and pinned. The
full findings table is in the report under "Independent review of this pass".

A third pass then checked the second pass instead of extending it. The four boxes above still
mean "this phase was reviewed", not "nothing further was found": that pass found real defects in
all four, including a Linux engine regression (a receiver's download directory was resolved but
never created), a TUI panic on any conversation scroll, two Omarchy GUI callers invoking a
terminal-only entry point, a Windows release asset that was a tar file named `.zip`, and an
installer "rollback" that deleted the engine instead of restoring the previous one. Qt
compatibility is now proven against a real engine rather than by grepping route strings. The
findings, the fixes and the checks are in the report under "Third pass (2026-10-02)"; the still-
open cross-platform gates are under "Remaining release gates".

A fourth pass then audited the remaining acceptance items for *implementation*, not for
runner time, and found that one was neither implemented nor merely unrun: **the Windows Rust
build did not compile.** `src/interrupt.rs` named `libc::c_int` at module scope while `libc` is
a `cfg(unix)`-only dependency, so every `cargo build --locked --release` step in both release
channels could not have produced `klardrop.exe`, and Ctrl-C during `share --wait` kept the
default disposition there. The client now installs `SetConsoleCtrlHandler` on Windows as well
as `signal(2)` on Unix — both feeding the same flag, the same exit 130 and the same
`cancelled` envelope — the interrupt is read by `discover --wait` too, and a Windows PTY-free
console test aims a real control event at an owned child in its own process group. Two Windows
triples now compile clean here; the linked binary and the console event itself remain a
`windows-latest` gate. The report's "Fourth pass" records the checks, the independent review,
and the one concrete blocker to any Rust CI job going green: `cli-rust/`, `Cargo.lock`
included, is still untracked while every step passes `--locked`.

Deliver a final diff summary, exact tests/builds and outcomes, representative JSON
success/failure output, memory measurements with workload/architecture, and remaining
launch gaps. Leave release publication and live installations untouched.
