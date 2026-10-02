# klardrop — native CLI client

A native command line client for a **running** Klardrop daemon.

The client is a shell: it never starts, spawns or embeds an engine. It reads the
daemon's control file, then speaks authenticated loopback HTTP (`127.0.0.1`
only) to that daemon. Sharing goes through the daemon too: the client submits a
request, the daemon owns the transfer, and the client reports exactly what the
daemon knows about it.

## Build

```sh
cargo build --locked              # debug binary at target/debug/klardrop
cargo build --locked --release    # release binary at target/release/klardrop
cargo test --locked               # unit + contract tests
cargo clippy --locked --all-targets -- -D warnings
cargo fmt --check
```


The toolchain is pinned by `rust-toolchain.toml` (Rust 1.88 with `clippy` and
`rustfmt`), so a build on a developer machine and a build on a CI runner use the
same compiler. `rust-version` in `Cargo.toml` matches that floor, which is set
by the locked dependency graph rather than guessed.

`Cargo.lock` is committed: the dependency set is narrow and pinned
(`clap` without its `color` feature, `serde`, `serde_json`, and `ureq` with
`default-features = false`, i.e. plain HTTP with no TLS and no compression —
the daemon is loopback-only). There is no async runtime; every call is blocking
with an explicit deadline.

A second binary, `klardrop-fixture-daemon`, exists **only** to back
`tests/cli_contract.rs`. It is not a user-facing tool.

## Commands

```
klardrop                       # no arguments: open the TUI when stdin AND stdout
                               # are terminals; otherwise print help, exit 2
klardrop devices [--json] [--timeout <secs>]
klardrop status  [--json] [--timeout <secs>]
klardrop discover [--json] [--wait <secs>] [--timeout <secs>]
klardrop share --to <device-id> <path>… [--json]
klardrop share --to <device-id> <path>… --wait [--timeout <secs>] [--json]
klardrop share --to <device-id> --text <text> --wait --json
klardrop share --to <device-id> --clipboard --wait --json
klardrop transfers [--json]
klardrop transfers --id <request-id> [--json]
klardrop send <device-id> [--file <path>]… [--text <text>] [<content>] [--no-wait] [--json]
klardrop --version
```

Global flags (accepted before or after the subcommand):

| flag | meaning |
|------|---------|
| `--control-file <path>` | use this control file instead of the search path |
| `--debug` | diagnostics on **stderr** only; a TUI session writes them to a **file** instead, and names that file on stderr before it takes the screen — a line printed to stderr mid-session would repaint the TUI, and the path is printed too late to be seen |

| command | what it does |
|---------|--------------|
| `devices` | one `GET /state`; lists devices with pairing and reachability |
| `status` | `GET /capabilities` (optional) then `GET /state`: daemon version, self device, protocols, settings, counts, update status |
| `discover` | polls `GET /state` every 250 ms until at least one device is visible or the wait window expires. Compatibility command kept from the previous CLI; it never sends anything |
| `share` | `POST /share` with exactly one payload kind (paths, `--text` or `--clipboard`), then optionally `--wait` |
| `transfers` | `GET /transfers`, or `GET /transfers?id=…` for one request |
| `send` | the compatibility spelling of `share`; **waits for delivery by default** |

`discover` stops as soon as a device is visible, so it returns immediately when
the daemon already sees devices.

## Sharing

`share` never guesses and never prompts:

* `--to` is required. It is matched against device **ids** — an exact id
  first, then an unambiguous prefix. A display name is never a match, an
  unknown id lists the visible ids (`device_not_found`), and an ambiguous
  prefix lists its candidates (`ambiguous_device`). The first candidate is
  never chosen for you.
* files, `--text` and `--clipboard` are alternatives. A mixture is
  `invalid_argument` (exit 2) **before** the daemon is contacted, and the
  message names both offenders and points at `klardrop interactive`.
* every path is checked locally first; an unreadable path is `invalid_path`
  (exit 2) naming the path, and nothing is sent. Paths are sent in absolute
  form. Spaces and Unicode are fine.
* a file name that starts with `-` needs a `--` before it
  (`klardrop share --to ID -- -weird.pdf`). Every other flag may still follow
  the paths.
* without `--wait` the command reports the request's real status, normally
  `queued` — never "delivered".
* with `--wait` it polls `GET /transfers?id=<requestId>` every 250 ms until
  every item is terminal. Only "every item completed" is exit 0; a decline, a
  failure, or a partial multi-file result is exit 1 with the per-item outcome.
* a `--timeout` that expires mid-wait leaves the delivery state **unknown**:
  exit 4, `"status":"unknown"`, and the message says so and names
  `klardrop transfers --id <requestId>`. The daemon's transfer is neither
  cancelled nor retried, and the request is never resubmitted automatically.

### Legacy `send`

`send` runs on exactly the same implementation and the same daemon routes, so
there is no second code path to keep honest. Only the promise differs:

| legacy syntax | today |
|---------------|-------|
| `send ID --file P` (`-f`, repeatable) | waits for delivery, exit 0 only if every item completed |
| `send ID --text T` (`-t`) | same |
| `send ID CONTENT` | a positional value containing `/` or `\` is a file, anything else is text — the previous CLI's rule, unchanged |
| `send ID … --no-wait` | reports `queued` and exits 0 |
| mixtures of the above | `invalid_argument`, exit 2 |

Its JSON envelope is the versioned one, with `"command":"send"`.

## Interactive TUI

```
klardrop interactive [--theme auto|system|tokyo-night] [--no-motion] [--no-mouse] [--no-color] [--line-mode] [--timeout <secs>]
klardrop share --pick …      # same TUI, narrowed to choosing one device
klardrop daemon [args…]      # forwards to the sibling klardrop-engine binary
```

`interactive` needs a real terminal on **both** stdin and stdout. It is
checked before the control file is read, so a piped invocation is told exactly
why it cannot work instead of waiting for a keypress that will never come. No
subcommand opens the same TUI, and only when, both ends are terminals —
otherwise the bare `klardrop` prints help and exits 2.

The TUI never starts an engine: it observes a daemon that is already running.
Every daemon call happens on worker threads, so the keyboard never waits for a
`GET /state?since=` long poll, and a slow send never delays the next state
change. The daemon owns every transfer; quitting the TUI cancels nothing.

### Line mode

Not every terminal can host a full-screen client. When it cannot, `interactive`
prints the same daemon state as lines and exits 0: no raw mode, no alternate
screen, no mouse capture, and **not one escape byte** on stdout. There is
nothing to restore afterwards, so a crash cannot leave a shell with no echo.

The decision is made once, at startup, in this order:

| trigger | why |
|---------|-----|
| `--line-mode` | the user asked for it, and an explicit request is not second-guessed |
| `TERM` unset, empty, or `dumb` | the terminal says it cannot address the screen; everything drawn would be a smear of literal escapes |
| a window below 20×8 | that is `layout::MIN_WIDTH` × `layout::MIN_HEIGHT`, the same predicate the renderer uses — below it the layout has nothing to lay out |

A window that *becomes* too small while the TUI is running does not switch modes
mid-session: the TUI keeps its state and draws its "too small" notice, because
tearing the screen down to print a summary would destroy the conversation the
user is in the middle of.

The summary names the self device, the daemon connection and protocols, the
device list with reachability and the daemon's own trust word, any transfer in
flight, and the reason the interactive view was not used. A captured frame is in
`tests/frames/line-mode-dumb-terminal.txt`.

`share --pick` never falls back: a picker has one answer to give and no way to
give it in lines, so a terminal that cannot host a screen is a refusal with a
way forward (`--to <device-id>`), never a silent "cancelled".

### `--theme`

`--theme` picks the palette, and never picks whether colour is used at all —
that is `--no-color` and `NO_COLOR`. The ladder from truecolor to indexed to
monochrome loses saturation, never information: selection is a marker glyph,
failure is a word and a marker, and a QR code differs by glyph as well as by
colour.

| value | meaning |
|-------|---------|
| `auto` (default) | ask the terminal for its own foreground and background (`system`), and fall back to Tokyo Night |
| `system` | ask the terminal, and fall back to Tokyo Night |
| `tokyo-night` | the shipped Tokyo Night palette, at whatever depth the terminal supports; never asks the terminal anything |

The **depth ladder** is read from the environment and costs nothing:

| depth | when |
|-------|------|
| truecolor | `COLORTERM=truecolor` or `24bit` |
| indexed | a `TERM` advertising 256 colours or kitty |
| monochrome | anything else, `TERM=dumb`, `--no-color`, `NO_COLOR` |

#### What `system` asks, and when it refuses to

`system` is the only choice that costs a round trip on the input stream: it
writes `ESC ] 10 ; ? BEL` and `ESC ] 11 ; ? BEL` and reads the replies. Four
rules follow, and each of them is a bug if it is broken:

* **Interactive only.** Both stdin and stdout have to be terminals. A
  redirected stdout has no screen to colour, and a redirected stdin has nothing
  to read a reply from without eating an automation's input. Line mode answers
  before a palette is ever resolved, so it never asks anything at all — a line
  session writes no escape byte whatsoever.
* **Bounded.** The whole exchange has a hard deadline of 250 ms
  (`palette::PROBE_TIMEOUT`). A terminal that ignores OSC 10 and OSC 11 — a
  bare pty, `screen`, anything that is not an emulator — costs exactly one
  timeout and then falls back. It cannot hang the client.
* **Non-destructive.** The terminal is handed back before the probe returns,
  from `Drop`, so an early return or an error still restores it. Keystrokes the
  probe reads that are *not* part of a reply are handed back too, and folded
  into the app before the input loop polls again, so a character typed during
  the probe still reaches the compose box.
* **All or nothing.** A palette needs both colours. One reply, a reply that
  never arrives, a reply that only half arrives, and a foreground equal to its
  own background are all the same thing, which is Tokyo Night at the depth the
  ladder picked. `--debug` names which of the two happened:
  `theme = System, terminal fg #e0c0a0 bg #101418`, or
  `theme = System, no answer from the terminal, using tokyo-night`.

#### How the roles are derived

Every semantic role is derived from the terminal's own two colours, so the
client reads as part of the user's theme instead of fighting it:

| role | derived as |
|------|-----------|
| body text, toasts | the terminal's foreground, unchanged |
| muted, unfocused | the midpoint of the terminal's two colours |
| accent, selection | the foreground moved 60% towards Tokyo Night's accent |
| success, warning, error | the foreground moved 60% towards Tokyo Night's matching role |
| QR modules | the terminal's background and foreground, the two values a scanner can tell apart |

Mixing towards Tokyo Night keeps the *identity* of each role — an accent is
blue, an error is red — while sitting on the user's own foreground hue.

#### `--no-color` and `NO_COLOR`

These mean **no styling at all**, not "less colour": every style the renderer
draws with becomes the terminal's own default, so nothing on the wire sets a
colour or an attribute. A `BOLD` pane title is a `SGR` sequence exactly as much
as a tinted row is, and selection and failure are glyphs, so nothing is lost.

A full-screen client still has to move the cursor and take the screen, so it is
not byte-free. The one `SGR` group it cannot avoid is ratatui's crossterm
backend ending every frame with `ESC[39m ESC[49m ESC[0m` — resets to the
terminal's own defaults, carrying no styling — so the contract a test can hold
this client to is "no `SGR` sequence *sets* anything". In line mode it is
byte-free outright, and a test asserts zero `0x1b` bytes on the wire.

### Motion and idle CPU

`--no-motion` turns off every animation and changes no word on screen. Motion
exists for three states the *daemon* is in — connecting, discovering, and a
transfer in flight — plus a one-off acknowledgement after a send completes. The
frame rate is capped at 10 fps, and the animation clock is only advanced while
something is actually animating, so an idle client asks the clock nothing and
redraws nothing.

Measured here, driving the real client on a pseudo terminal against the fixture
daemon and sampling `utime`+`stime` from `/proc/<pid>/stat`:

```sh
scripts/tui-idle-cpu.sh 10
```

```
idle CPU, 10s window, 100 Hz clock, fixture holding /state open
motion-on   total  0.030 CPU-s ( 0.30%)   draw loop  0.020 CPU-s ( 0.20%)   over 10s
motion-off  total  0.030 CPU-s ( 0.30%)   draw loop  0.020 CPU-s ( 0.20%)   over 10s
```

Over a 30 s window the same script reports **0.080 CPU-seconds (0.27% of one
core) with motion on and 0.080 CPU-seconds (0.27%) with `--no-motion`**: motion
costs nothing when nothing is moving, which is the claim the design makes.
Repeated 10 s runs land between 0.02 and 0.03 CPU-seconds — the 10 ms clock tick
is the granularity, which is why the longer window is the number to quote.

`total` is the whole process; `draw loop` is the main thread alone, which is
the only thread motion can change. The client's other thread long-polls the
daemon, so what it costs is a property of the daemon rather than of the client:
the fixture is therefore started with `state_long_poll`, which holds the
request open the way the real control plane does, instead of answering
instantly and turning an idle client into a hot loop.

The script starts only its own children — the fixture daemon and the client —
and signals nothing else.

### Keyboard map

| key | what it does |
|-----|--------------|
| `Tab` / `Shift-Tab` | move between the device and conversation panes |
| `Up` / `Down` | select a device; scroll the conversation |
| `PageUp` / `PageDown` | page through older messages |
| `Enter` | open the selected device; start a message in a conversation |
| `i` | write a message |
| `f` | send files by absolute path |
| `y` | send the clipboard |
| `n` | rename this device |
| `a` | the actions overlay: pairing and incoming decisions first, then QR share, rename, settings and the update controls |
| `?` | the key map |
| `r` | refresh now |
| `Esc` | close the overlay, the compose line, or the conversation |
| `Ctrl-Space` | release the mouse to the terminal, or take it back |
| `q` / `Ctrl-C` | quit |

Inside the actions overlay a digit picks the entry whose number is drawn next
to it; `Esc` goes back. Nothing is offered that the daemon did not advertise —
a route the daemon does not list is named as unavailable rather than offered as
a button that would answer 404. A pairing request or an unanswered incoming
transfer is a *decision*, and the overlay says so out loud when there is none.

Inside the settings overlay `d` toggles background discovery. The QR overlay
draws the daemon's module matrix with half-block cells whenever a four-module
quiet zone and two columns per module fit the window; when it does not, the
overlay shows the URL and says how much room the code wants.

#### Selecting and copying out of the TUI

While the TUI holds the mouse, the terminal cannot start a selection of its
own: every click and drag is reported to the application instead. `Ctrl-Space`
is the bypass. It is one key, it works from every mode except the compose line,
and it is a toggle rather than a mode — the same key takes the mouse back when
the copy is done.

- **What it does:** writes the real `DisableMouseCapture` / `EnableMouseCapture`
  sequences, so the terminal, not the client, decides where a drag begins.
- **Where it shows:** a toast says whether the mouse was released or taken
  back, and the footer reads `mouse released` for as long as it is released —
  the moment a user needs the key is the moment the key is on screen.
- **It cannot start a capture that was never taken.** With `--no-mouse`,
  `Ctrl-Space` writes nothing at all: a session told not to capture must not
  begin capturing behind the user's back. Because such a session never *held*
  a mouse either, the footer reads `no mouse · Ctrl-Space stays off` and the
  key answers `this session captures no mouse — Ctrl-Space will not take one`,
  rather than describing a release that did not happen and offering a key that
  cannot work.
- **Whatever it leaves is still the guard's to undo.** On quit, a capture that
  is still held is released, exactly as at startup.

`Ctrl-Space` arrives as a single NUL byte, which every terminal delivers
unambiguously and crossterm decodes as Ctrl-Space. `tests/tui_mouse.rs` asserts
each of those claims against the bytes the client actually wrote.

### `share --pick`

`klardrop share --pick …` opens the same TUI in a picker mode with one question:
which device. It accepts only the keys that answer it (move, `Enter`, `r`,
`Esc`/`q`), so a caller that only wanted a device cannot send anything by
accident, and the chosen device id is handed back to the caller.

### `daemon`

`klardrop daemon [args…]` forwards its arguments to the sibling
`klardrop-engine` binary, which owns the terminal and reports for itself; the
client adds no envelope and no exit-code translation of its own. When that
binary cannot be found the command says so rather than silently doing nothing.

## Control file search order

1. `--control-file <path>` (an explicit override; used by fixtures and by users
   running isolated daemons);
2. on **macOS**, `$HOME/Library/Group Containers/D7T5425WSW.group.com.carlom.Klardrop/control.json`
   — tried first, and only when it really holds a file. That is where the shipped
   `Klardrop.app` publishes: it is sandboxed, so it cannot write to `$HOME/.cache`,
   and the App Group container is the one location it and this unsandboxed CLI can
   both reach;
3. `$XDG_RUNTIME_DIR/klardrop/control.json` — unix, only when the variable is
   set and non-empty;
4. `$HOME/.cache/klardrop/control.json` — unix. This is what the non-sandboxed JVM
   desktop host on macOS still uses, which is why the App Group probe above only
   wins when a file is actually there.

On Windows only `%LOCALAPPDATA%\Klardrop\control.json` is used; there is no
`XDG_RUNTIME_DIR`/`$HOME` fallback there. The resolved path is identical to the
one the daemon writes and to the one the Qt client reads.

The file looks like:

```json
{"port":8765,"token":"<32 lowercase hex>","apiVersion":1,"capabilities":["state", ...]}
```

Before use it is validated exactly like the Qt client does:

* it must be a **regular file** (not a directory, fifo or socket);
* size must be **1..=4096 bytes** — the read never takes more than 4097 bytes,
  so a file that grows between `stat` and `read` is still rejected without an
  unbounded allocation;
* it must be a JSON **object**;
* `port` must be an **integral** number in `1..=65535` (`8080.5` and `"8080"`
  are rejected);
* `token` must be non-empty, at most 256 characters, and consist only of
  `[A-Za-z0-9_.~-]` — which is also what makes it safe to place in an
  `Authorization` header.

Anything else is `daemon_control_invalid` (exit 3). A missing file is
`daemon_not_running` (exit 3), and the message says to start the daemon.

## Transport

* the peer is always the literal `127.0.0.1:<port>` from the control file — no
  hostname, no user-supplied host, no DNS;
* one request per connection (`Connection: close`, no keep-alive reuse, hence
  no silent retry of an aborted request);
* `Authorization: Bearer <token>` on every request; the token is never printed
  or logged;
* redirects are never followed — a 3xx is a protocol error;
* the environment/system proxy is never consulted (`try_proxy_from_env(false)`),
  so `HTTP_PROXY`/`ALL_PROXY` cannot capture the bearer token.

Ceilings, all enforced before any unbounded read:

| limit | value |
|-------|-------|
| request target | 2048 B |
| response header line | 8192 B |
| response header block | 64 KiB |
| response body | 4 MiB |
| request body | 64 KiB |

Chunked transfer encoding is rejected outright with `daemon_protocol_error`, and
a `Content-Length` above the body ceiling is refused before a single body byte
is read.

## Timeouts

`--timeout` is the deadline for the **whole command**: control-file resolution,
connect, every request, and every read. Socket connect/read/write timeouts are
derived from the time remaining at the moment of each request.

| flag | default | meaning |
|------|---------|---------|
| `--timeout` | 15 s (`devices`, `status`, `transfers`), 20 s (`discover`), 120 s (`share`, `send`) | whole-command deadline: resolution, submission and waiting |
| `--wait` | 5 s (`discover`, matching the previous CLI's default) | discovery window; never outlives `--timeout` |

Values are seconds and may be fractional. Non-numeric, zero, negative,
non-finite (`inf`, `NaN`) and above 3600 values are rejected with
`invalid_argument` (exit 2) **before** the control file is even read, so a bad
flag never causes a request.

## Output discipline

* With `--json`, stdout carries **exactly one** JSON value and nothing else —
  this holds on success and on failure, and with `--debug` enabled.
* Without `--json`, human text goes to stdout; progress notes, `--debug`
  diagnostics and error messages go to stderr.
* Transfer progress always goes to stderr, `--json` or not, so a script that
  captures stdout still sees a waiting command move.
* No ANSI escapes are ever emitted (clap's `color` feature is not compiled in).
* The client never prompts and never reads stdin.

### Versioned envelope (`devices --json`, `status --json`)

```json
{"schemaVersion":1,"ok":true,"command":"devices","daemon":{"apiVersion":1,"version":"1.2.3"},"devices":[{"deviceId":"...","deviceName":"...","deviceType":"ANDROID","paired":true,"reachable":true,"trustStatus":"trusted","reachability":"reachable","connectionTypes":["KLARDROP"],"hasUnread":false,"unreadCount":0}]}
```

```json
{"schemaVersion":1,"ok":true,"command":"status","daemon":{"apiVersion":1,"version":"1.2.3"},"self":{"deviceId":"...","deviceName":"...","deviceType":"...","osType":"..."},"protocols":{"klardrop":true,"nearby":true,"ble":false},"settings":{"backgroundDiscoveryEnabled":true,"supportsBackgroundDiscovery":true},"deviceCount":1,"pairedCount":1,"reachableCount":1,"activeTransfers":0,"update":{"status":"up_to_date","supported":true}}
```

`paired` is derived from the daemon's trusted-device list; `reachable` is
`reachability == "reachable"`.

Against a daemon that does not implement `/capabilities` (404), the command
still succeeds and `daemon` reports `{"apiVersion":null,"version":null}` — unknown,
not an error.

### Transfer envelope (`share --json`, `send --json`, `transfers --json`)

```json
{"schemaVersion":1,"ok":true,"command":"share","requestId":"req-1f2e…","deviceId":"11112222","status":"queued","items":[{"transferId":"42","path":"/home/me/report.pdf","fileName":"report.pdf","totalSize":12,"transferredSize":0,"status":"queued","error":null}]}
```

```json
{"schemaVersion":1,"ok":true,"command":"transfers","requests":[{"requestId":"req-1f2e…","deviceId":"11112222","kind":"files","status":"declined","createdAt":1750000000000,"updatedAt":1750000005000,"items":[{"transferId":"42","path":"/home/me/report.pdf","fileName":"report.pdf","totalSize":12,"transferredSize":0,"status":"declined","error":"recipient declined the transfer"}]}]}
```

With `--id`, `transfers` answers the same record under `"request"`. Item keys
always appear in the order above, and a text or clipboard item carries explicit
`null` for `path`, `fileName` and `error` with both sizes `0`.

An item is `queued`, `awaiting`, `transferring`, `completed`, `declined` or
`failed`. A request's `status` is derived from its items: any failure, else any
decline, else all completed, else `queued`.

### Failure envelope with operation context

When a share fails in a way it can describe — a decline, a partial failure, an
undecided delivery state — the failure envelope carries what the command
actually knows, in this exact key order:

```json
{"schemaVersion":1,"ok":false,"command":"share","requestId":"req-1f2e…","deviceId":"11112222","status":"declined","items":[{"transferId":"42","path":"/home/me/report.pdf","fileName":"report.pdf","totalSize":12,"transferredSize":0,"status":"declined","error":"recipient declined the transfer"}],"error":{"code":"transfer_failed","message":"request req-1f2e… ended as declined: report.pdf: recipient declined the transfer"}}
```

An expired deadline uses the same shape with `"status":"unknown"` and
`daemon_timeout`:

```json
{"schemaVersion":1,"ok":false,"command":"share","requestId":"req-1f2e…","deviceId":"11112222","status":"unknown","items":[…],"error":{"code":"daemon_timeout","message":"… delivery state is unknown — inspect it with `klardrop transfers --id req-1f2e…`. The transfer was neither cancelled nor retried."}}
```

## Wire contract the client speaks

`POST /share` carries exactly one payload kind:

```json
{"deviceId":"11112222","paths":["/abs/a","/abs/b"]}
{"deviceId":"11112222","text":"hello"}
{"deviceId":"11112222","clipboard":true}
```

and answers `200` with the minted request id, the request's real status and one
item per submitted thing. `GET /transfers` lists the daemon's recent requests
newest first; `GET /transfers?id=…` answers one record or `404`.

The registry is bounded on purpose, with concrete numbers you can rely on:

- at most **256** requests are retained; the least recently inserted one is
  evicted first,
- a request becomes unqueryable **2 hours** after it was accepted,
- pruning runs on every insert and on every lookup, so an idle request really
  does expire even if nothing else happens,
- one `POST /share` may carry at most **64** paths; more is `400` from the
  daemon (`invalid_argument`, exit 2), refused before anything is submitted.

Because pruning also runs on lookup, a `share --wait` that idles longer than
the retention window can find its own request gone; that surfaces as
`transfer_unknown` with `status: "unknown"` and exit 3, never as a delivery
result. From the client's side a lookup for a forgotten id means the daemon no
longer knows it — evicted, expired, or restarted — and explicitly **not** that
the transfer failed. The client never turns a forgotten id into a success.

A daemon that does not implement these routes at all (404) is
`daemon_unsupported` (exit 3), never a delivery result. A daemon whose host is
up but has not bound its engine yet answers `503`, which is also
`daemon_unsupported` (exit 3) with a "not ready yet" message — retryable, and
still not a delivery result.



### Failure envelope

```json
{"schemaVersion":1,"ok":false,"command":"status","error":{"code":"daemon_not_running","message":"..."}}
```

## Exit codes

| code | exit | when |
|------|------|------|
| `invalid_argument` | 2 | bad flag value, a mixed or missing payload, an empty target |
| `invalid_path` | 2 | a path that does not exist or cannot be read |
| `ambiguous_device` | 2 | `--to` matches more than one device id prefix |
| `device_not_found` | 2 | `--to` matches no visible device |
| `terminal_required` | 2 | the command needs a real terminal on both stdin and stdout, and this invocation has at least one redirected |
| `daemon_not_running` | 3 | no control file |
| `daemon_control_invalid` | 3 | control file unreadable/invalid, or the daemon rejected the token (401/403) |
| `daemon_unreachable` | 3 | nothing listening, or the control file is stale |
| `daemon_unsupported` | 3 | the daemon answered, but without the fields or routes this client needs |
| `transfer_unknown` | 3 | the daemon no longer knows the request id (evicted, or restarted) — **not** a delivery failure |
| `daemon_timeout` | 4 | the command deadline expired; with `--wait` the delivery state is unknown |
| `daemon_protocol_error` | 1 | malformed status/header line, chunked encoding, oversized header or body, a 3xx |
| `daemon_response_invalid` | 1 | the body was not JSON, or was not a JSON object |
| `daemon_http_error` | 1 | any other non-2xx status |
| `transfer_failed` | 1 | a decline, a failed item, or a partial multi-file result |
| `internal_error` | 1 | a client bug or a violated invariant |
| `cancelled` | 130 | a person backed out of an interactive workflow (`share --pick`) before anything was sent, or interrupted a wait (`share --wait`, `discover --wait`) |

Exit 0 means the command's promise was kept — `queued` for an ordinary share,
every item confirmed delivered when waiting was requested. Exit 1 is an
operational failure (a decline, a transfer failure, a protocol or response
error). Exit 2 is a mistake in the invocation. Exit 3 means the daemon could not
be used. Exit 4 means a deadline expired. Exit 130 is a **cancelled
workflow**, reported through the normal envelope rather than as a bare exit.

Quitting the full TUI (`klardrop interactive`, `q` or `Ctrl-C`) deliberately
does *not* answer 130: leaving the client cancels nothing, the daemon keeps any
transfer, and that is an ordinary exit 0.

`Ctrl-C` *during a wait* is a different thing and does answer 130. The client
installs a console handler — `signal(2)` for `SIGINT` on Unix,
`SetConsoleCtrlHandler` for `CTRL_C_EVENT`/`CTRL_BREAK_EVENT` on Windows — whose
only job is to set a flag; the poll loop notices it and leaves through the same
door an expired deadline takes. So `share --wait` prints one JSON value naming
the request id with `status: "unknown"` and `error.code: "cancelled"`, and
`discover --wait` prints a plain `cancelled` value because it sends nothing.
**The daemon is never told to stop and nothing is ever retried**, so the request
can still be inspected with `klardrop transfers --id <request-id>`; a client that
had died on the signal would have taken that id with it.

The flag is only read at poll boundaries. One request already in flight runs to
its own budget — `--timeout` seconds, 15 by default for `devices`, `status` and
`transfers`, 120 for `share` — because abandoning a `POST /share` mid-flight
would lose the very request id the interrupt exists to preserve.

A daemon too old to answer is never reported as "no devices" or as a delivered
transfer: a 200 `/state` whose schema this client cannot use, or a missing
`POST /share` route, is `daemon_unsupported` (exit 3).

## Legacy `discover --json` migration

The previous (Kotlin) CLI was an engine of its own; it could reach a peer's
socket directly and therefore had more to report than the daemon's control API
exposes. `discover --json` therefore keeps the legacy **array** shape — a bare
JSON array, not the versioned envelope — but two of its five fields are gone:

```json
[{"device_id":"11112222","name":"Fixture Phone","device_type":"ANDROID"}]
```

| legacy field | status | why |
|--------------|--------|-----|
| `device_id` | kept | present in `/state` |
| `name` | kept | present in `/state` (`deviceName`) |
| `device_type` | kept | present in `/state` |
| `os_type` | **removed** | `/state` reports an operating system type only for the *local* device (`self.osType`). A peer's OS is never published by the control API, and guessing it would be worse than omitting it. Use `devices --json` instead: it carries the full typed device list and is the versioned contract. |
| `connections[{type,address,port}]` | **removed** | `/state` publishes only connection *type names* per device (`connectionTypes`, e.g. `["KLARDROP"]`). It deliberately does not publish a peer's listening address or port; a client cannot dial a peer from the control plane, and doing so is a send operation, out of scope for this read-only phase. Connection type names are available in `devices --json`. |

Consumers that need pairing state, reachability, unread counts or connection
types should move to `devices --json`, whose envelope is versioned by
`schemaVersion`. Consumers that only need "what devices exist" can keep parsing
`discover --json` unchanged apart from the two removed fields.

## Tests

`tests/cli_contract.rs` spawns `klardrop-fixture-daemon` over loopback and
covers the transport: single-JSON-value stdout without ANSI,
absent/malformed/empty/oversized control files, out-of-range ports and bad token
characters, a stale control file failing fast, an old daemon without
`/capabilities`, a `/state` without `self` reported as `daemon_unsupported`, an
oversized response body rejected as a protocol error, a wedged daemon hitting
the deadline, a slow-dripping daemon that answers one byte at a time but still
must not outlive `--timeout`, invalid timeouts rejected before any request,
`--json` stdout parsing with `--debug` and on failure, HTTP 500, an invalid JSON
body, a rejected token, the legacy `discover --json` shape, the control-file
search order, and that an ambient `HTTP_PROXY` is ignored.

It also covers sharing, driving the real binary against the fixture:

* unknown flags on every command, and mixed payloads refused before the daemon
  is contacted at all (a control file that does not exist would otherwise
  answer `daemon_not_running`);
* queued without `--wait` versus confirmed with `--wait`, and a delayed
  acknowledgement that the wait must actually wait for;
* a decline (exit 1, `transfer_failed`, per-item outcome) and a plain failure;
* a wait that times out: exit 4, `"status":"unknown"`, an elapsed bound, and a
  follow-up `transfers --id` proving the request still exists;
* a missing file (`invalid_path`) with proof that no request was created;
* an ambiguous id prefix (`ambiguous_device`) and an unknown id
  (`device_not_found`), neither of which sends anything;
* a partial multi-file share: exit 1 naming both items, one completed and one
  failed;
* two concurrent requests to the same device, each following only its own ids;
* the `transfers` round trip, the newest-first registry, and an unknown id
  (`transfer_unknown`);
* an old daemon without `POST /share`: exit 3 `daemon_unsupported`, never a
  delivery claim;
* text and clipboard shares with explicit `null` file fields, Unicode and
  spaced paths, and legacy `send` with and without `--no-wait`.

Unit tests cover control-file parsing/validation/search order, envelope
serialization (including the byte-exact failure envelope and the error-code
table), `/state`, `/capabilities` and transfer-response parsing, device
selection, duration-flag validation, payload validation and the path checks.

## Licence and attribution

This crate is part of the Klardrop repository and is covered by the root
[Apache License 2.0](../LICENSE), like every other component in it.

The `--theme tokyo-night` palette is the colour set from
[folke/tokyonight.nvim](https://github.com/folke/tokyonight.nvim), which is
distributed under the MIT licence, © 2021–2026 Follin Brun. The seven values are
copied verbatim; no tokyonight.nvim code is vendored.