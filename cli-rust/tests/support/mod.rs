//! A pseudo-terminal harness for driving the real `klardrop` TUI.
//!
//! A terminal program's contract is what it puts on the screen, and there is
//! no way to assert on that without a terminal to put it on. So these helpers
//! do exactly what a person does: start the binary on a pty of a known size,
//! type at it, and look at the screen.
//!
//! Three pieces:
//!
//! * [`Fixture`] — the same fake daemon `cli_contract.rs` uses, started as a
//!   child of this test and killed with it. It is the only process a test here
//!   ever starts besides the client itself.
//! * [`Tui`] — the client on a pty, with the output fed to a VT parser so
//!   "the screen" is a grid of characters rather than a stream of escapes.
//! * [`Tui::dump`] — writes the current screen into `tests/frames/`, so a
//!   failing assertion has a human-readable artefact next to it.
//!
//! The pty crates are dev-dependencies only: `cargo tree -e normal` must never
//! list them, because the shipped client opens no terminal device itself — it
//! borrows the one the user already has.

#![allow(dead_code)]

use portable_pty::{native_pty_system, Child as PtyChild, CommandBuilder, PtySize};
use portable_pty::{MasterPty, SlavePty};
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The real client. There is no stub: a TUI test that ran against a fake
/// renderer would prove nothing about the renderer.
pub const CLI: &str = env!("CARGO_BIN_EXE_klardrop");
/// The fixture daemon binary, built alongside the client.
pub const FIXTURE_DAEMON: &str = env!("CARGO_BIN_EXE_klardrop-fixture-daemon");
/// The bearer token the fixture accepts. Written into the control file and
/// read by the client; never printed by a test.
pub const FIXTURE_TOKEN: &str = "fixture-token-0123456789abcdef";

/// Default pty size: wide enough for the two panes, tall enough for the
/// actions overlay to have room.
pub const COLS: u16 = 120;
pub const ROWS: u16 = 40;

/// Upper bound on any wait. Generous, so a slow machine does not fail a
/// "should appear" assertion, but finite: a test that hangs is a failure, not a
/// wait.
pub const WAIT: Duration = Duration::from_secs(20);

// ------------------------------------------------------------------- keys

pub const UP: &str = "\x1b[A";
pub const DOWN: &str = "\x1b[B";
pub const ENTER: &str = "\r";
pub const ESC: &str = "\x1b";
pub const CTRL_C: &str = "\x03";
pub const PAGE_UP: &str = "\x1b[5~";

// ------------------------------------------------------------- directories

static COUNTER: AtomicUsize = AtomicUsize::new(0);

/// A directory that removes itself. Never inside the repository, so a failed
/// run cannot leave state behind in the worktree.
pub struct TempDir {
    path: PathBuf,
}

impl TempDir {
    pub fn new(tag: &str) -> Self {
        let index = COUNTER.fetch_add(1, Ordering::SeqCst);
        let path =
            std::env::temp_dir().join(format!("klardrop-tui-{tag}-{}-{index}", std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).expect("create temp dir");
        Self { path }
    }

    pub fn join(&self, name: &str) -> PathBuf {
        self.path.join(name)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

// ---------------------------------------------------------------- fixture

/// A running fixture daemon, its control file and its isolated directories.
///
/// The environment is scrubbed: an explicit `--control-file` is passed to the
/// client, and `HOME`/`XDG_RUNTIME_DIR` point into a temp directory anyway, so
/// the control-file search order can never resolve to the developer's real
/// daemon.
pub struct Fixture {
    dir: TempDir,
    child: Child,
    control: PathBuf,
}

impl Fixture {
    /// Starts a daemon with `behaviors`, and returns once its control file
    /// exists.
    pub fn start(tag: &str, behaviors: &[&str]) -> Self {
        let dir = TempDir::new(tag);
        let control = dir.join("control.json");
        let child = Command::new(FIXTURE_DAEMON)
            .env("KLARDROP_FIXTURE_CONTROL_FILE", &control)
            .env("KLARDROP_FIXTURE_TOKEN", FIXTURE_TOKEN)
            .env("KLARDROP_FIXTURE_BEHAVIORS", behaviors.join(","))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn fixture daemon");

        let deadline = Instant::now() + Duration::from_secs(15);
        while Instant::now() < deadline && !control.exists() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            control.exists(),
            "fixture daemon never wrote its control file"
        );
        Self {
            dir,
            child,
            control,
        }
    }

    pub fn control(&self) -> &Path {
        &self.control
    }

    pub fn dir(&self) -> &Path {
        self.dir.path()
    }

    /// The port the fixture actually bound.
    pub fn port(&self) -> u16 {
        let raw = fs::read_to_string(&self.control).expect("read fixture control file");
        let value: serde_json::Value = serde_json::from_str(&raw).expect("fixture control JSON");
        value["port"]
            .as_u64()
            .expect("fixture port")
            .try_into()
            .expect("fixture port fits u16")
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

// -------------------------------------------------------------------- TUI

/// How a test wants the client started.
///
/// The default is the one every existing flow wants: a capable terminal with
/// motion, mouse capture and colour *off*, so the screen is a pure function of
/// the daemon's state. A test that is about one of those switches turns the
/// one it needs back on and says so.
pub struct LaunchOptions {
    pub motion: bool,
    pub mouse: bool,
    pub color: bool,
    /// `TERM` for the child. `dumb` is the line-mode trigger.
    pub term: String,
    /// Extra arguments, placed after the fixed ones.
    pub extra: Vec<String>,
    /// Hold the pty slave open after the child exits.
    ///
    /// Without this the pty disappears the moment the client's last descriptor
    /// closes, and there is no longer anything to type at — so
    /// [`Tui::echoes_input`] cannot be asked whether the terminal came back.
    /// Holding it also means the reader never sees `EIO`, so a test that sets
    /// this waits on [`Tui::wait_for_exit`] rather than on the screen.
    pub keep_slave: bool,
    /// Environment variables for the child, set after the scrub below.
    ///
    /// For the switches a *variable* selects and a flag does not: `NO_COLOR`
    /// is the contract `--no-color` is documented to match, and testing only
    /// the flag would leave the variable's half of it unproven.
    pub env: Vec<(String, String)>,
    /// The subcommand and its own arguments, replacing the default `interactive`.
    ///
    /// The picker is only reachable through `share --pick`, so the exit-code and
    /// envelope contract for *cancelling* an interactive workflow cannot be
    /// observed at all without this.
    pub subcommand: Vec<String>,
    /// Append the display flags (`--no-motion`, `--no-mouse`, `--no-color`).
    ///
    /// Off for a subcommand that does not accept them: clap rejects unknown
    /// arguments, so `share --pick --no-motion` would fail before the picker
    /// ever opened and the test would be asserting the wrong thing.
    pub display_flags: bool,
    /// Append `--timeout 30`, so a fixture that never binds fails the test
    /// instead of stalling it. Generous on purpose: this bounds a hang, it is
    /// not a latency budget, and these binaries run several pty-driving
    /// clients concurrently on a shared runner.
    pub timeout: bool,
}

impl Default for LaunchOptions {
    fn default() -> Self {
        Self {
            motion: false,
            mouse: false,
            color: false,
            term: "xterm-256color".to_string(),
            extra: Vec::new(),
            subcommand: vec!["interactive".to_string()],
            display_flags: true,
            timeout: true,
            keep_slave: false,
            env: Vec::new(),
        }
    }
}

/// A run of printable bytes that appears nowhere else in the client's output,
/// so finding it in the pty's own stream can only mean the terminal typed it
/// back. Printable throughout on purpose: a cooked pty translates control
/// characters on the way out (`ECHOCTL` turns `BEL` into `^G`), and a probe
/// that came back spelled differently would be testing the translation rather
/// than the echo.
const ECHO_PROBE: &[u8] = b"klardrop-echo-probe-7f3a";

/// The escape sequences a terminal program writes while it holds the keyboard
/// in raw mode. None of them is a drawing instruction, so their presence in
/// the stream is unambiguous.
pub const RAW_MODE_FURNITURE: [&str; 3] = [
    "\x1b[?1049h", // alternate screen
    "\x1b[?2004h", // bracketed paste
    "\x1b[?1002h", // mouse reporting
];

/// Every `SGR` sequence in `raw`, whole: `ESC [`, then digits and `;`, then
/// `m`.
///
/// `SGR` is the only escape a colour *or an attribute* needs, so "this
/// terminal program emitted no styling at all" is exactly "this stream
/// contains no `SGR`". The whole sequence has to be matched, terminator
/// included: scanning for the `m` alone matches the `m` of `\x1b[38;5;9m` and
/// the `m` of any unrelated sequence ending in it, and asking whether the
/// *parameters* end in `m` matches nothing at all — which is how a guard on
/// this contract ends up passing while the client styles every cell.
///
/// Hand-rolled rather than a regex crate: the match is a `find` and a
/// `starts_with`, and the dev-dependency set is already two pty crates deep.
pub fn sgr_sequences(raw: &str) -> Vec<&str> {
    let mut found: Vec<&str> = Vec::new();
    let mut from = 0;
    while let Some(offset) = raw[from..].find("\x1b[") {
        let start = from + offset;
        let params = &raw[start + 2..];
        let end = params
            .find(|c: char| !c.is_ascii_digit() && c != ';')
            .unwrap_or(params.len());
        if params[end..].starts_with('m') {
            let past = start + 2 + end + 1;
            found.push(&raw[start..past]);
            from = past;
        } else {
            from = start + 2;
        }
    }
    found
}

/// The `SGR` sequences in `raw` that *set* something: a colour, or an
/// attribute such as `BOLD` or `DIM`.
///
/// The sequences left out are the ones that put the terminal back to its own
/// defaults — `CSI 0 m`, `CSI 39 m`, `CSI 49 m`, and `CSI m`, which is `0` with
/// the parameter omitted. Those carry no styling information at all, and there
/// is exactly one unavoidable group of them: ratatui's crossterm backend ends
/// *every* `draw` by queueing `SetForegroundColor(Reset)`,
/// `SetBackgroundColor(Reset)` and `SetAttribute(Reset)`, whatever the cells
/// said. A client that drew a blank screen through that backend still puts
/// them on the wire, so "no SGR sequence at all" is not a contract any
/// full-screen draw can keep.
///
/// What the client *does* control is that nothing is ever styled, and that is
/// what this filters for: with colour suppressed, a `BOLD` pane title or a
/// tinted row shows up here even though a bare "is the list empty" check on
/// resets would not have noticed.
pub fn styling_sequences(raw: &str) -> Vec<&str> {
    sgr_sequences(raw)
        .into_iter()
        .filter(|sequence| {
            let params = &sequence[2..sequence.len() - 1];
            !params
                .split(';')
                .all(|param| matches!(param, "" | "0" | "39" | "49"))
        })
        .collect()
}
/// `bytes` with every escape sequence removed: CSI, OSC and the two-character
/// escapes, the way a terminal would consume them.
///
/// Needed because on Windows `raw` is ConPTY's rendering of the client's output,
/// not the output itself, and that rendering interleaves cursor moves through the
/// text. In a 10-column window ConPTY emits `self: Fixt`, then `ESC[3;10H`, then
/// `ture Host`, so a phrase the client printed contiguously is not contiguous in
/// the stream and a `contains` on the raw bytes cannot see it. Removing the
/// sequences reassembles the logical text on both platforms, and is a no-op on
/// unix, where a line-mode client emits no escapes to begin with.
pub fn strip_escapes(bytes: &[u8]) -> String {
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != 0x1b {
            out.push(bytes[i]);
            i += 1;
            continue;
        }
        i += 1; // the ESC itself
        match bytes.get(i) {
            Some(b'[') => {
                i += 1; // CSI introducer
                while i < bytes.len() && (0x20..0x30).contains(&bytes[i]) {
                    i += 1;
                }
                while i < bytes.len() && !(0x40..=0x7e).contains(&bytes[i]) {
                    i += 1;
                }
                i += 1; // the final byte
            }
            Some(b']') => {
                i += 1; // OSC introducer
                        // Terminated by BEL, or by ESC \.
                while i < bytes.len() {
                    if bytes[i] == 0x07 {
                        i += 1;
                        break;
                    }
                    if bytes[i] == 0x1b && bytes.get(i + 1) == Some(&b'\\') {
                        i += 2;
                        break;
                    }
                    i += 1;
                }
            }
            Some(_) => i += 1, // any other two-character escape
            None => {}
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}
/// The printed text as one continuous run: [`strip_escapes`], then the terminal's
/// own line breaks removed.
///
/// The second half matters wherever the window is narrow. ConPTY renders what the
/// client printed into the columns it was given, so a 10-column window wraps
/// mid-word — `self: Fixt`, newline, `ture Host (` — and the phrase the client
/// printed contiguously is split across rows. Taking the newlines out undoes that
/// wrapping and restores the logical text.
///
/// On unix this is the identity in practice: a line-mode client emits its own
/// newlines and no escapes, so the result is the same text it printed.
pub fn printed_text(bytes: &[u8]) -> String {
    strip_escapes(bytes)
        .chars()
        .filter(|c| !matches!(c, '\n' | '\r'))
        .collect()
}

/// The client, running on a pty, with its screen decoded.
pub struct Tui {
    /// The pty's master end, kept so a test can resize the window.
    ///
    /// Only the master can issue `TIOCSWINSZ`, and that ioctl is what makes the
    /// tty driver raise `SIGWINCH` in the child — the only way crossterm learns
    /// about a resize. The handle therefore has to outlive the writer and the
    /// reader, which is why the launcher clones those instead of taking them.
    master: Box<dyn MasterPty + Send>,
    child: Box<dyn PtyChild + Send + Sync>,
    writer: Box<dyn Write + Send>,
    parser: Arc<Mutex<vt100::Parser>>,
    exited: Arc<Mutex<Option<std::io::Error>>>,
    /// Every byte the client has ever written to the pty, in order.
    ///
    /// The decoded screen cannot answer "did this write a single escape byte",
    /// and that is a question the fallback path has to be able to answer.
    raw: Arc<Mutex<Vec<u8>>>,
    /// The pty's own slave end, kept only when a test asked for it: see
    /// [`LaunchOptions::keep_slave`]. It is the only handle on the line
    /// discipline that survives the child exiting.
    _slave: Option<Box<dyn SlavePty + Send>>,
}

impl Tui {
    /// Starts `klardrop interactive` against `fixture` on a pty of the default
    /// size, with motion, mouse capture and colour off so the screen is a pure
    /// function of the state.
    pub fn launch(fixture: &Fixture) -> Self {
        Self::launch_sized(fixture, COLS, ROWS)
    }

    /// As [`Tui::launch`], at an explicit size — for tests that need a terminal
    /// smaller or larger than the default. `Tui::resize` is the other half: it
    /// changes the geometry of an already-running session, which is what a user
    /// dragging the window edge does, and what the resize tests use.
    pub fn launch_sized(fixture: &Fixture, cols: u16, rows: u16) -> Self {
        Self::launch_sized_with(fixture, cols, rows, &LaunchOptions::default())
    }

    /// [`Tui::launch`], with the switches a specific test cares about.
    pub fn launch_with(fixture: &Fixture, options: &LaunchOptions) -> Self {
        Self::launch_sized_with(fixture, COLS, ROWS, options)
    }

    /// The real launcher. Everything the tests vary goes through
    /// [`LaunchOptions`], so there is exactly one place that decides what the
    /// child is started with.
    pub fn launch_sized_with(
        fixture: &Fixture,
        cols: u16,
        rows: u16,
        options: &LaunchOptions,
    ) -> Self {
        let pty = native_pty_system();
        let pair = pty
            .openpty(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .expect("open pty");

        // Establish the geometry on the pty itself, not only in `openpty`'s argument.
        // On unix portable-pty applies `PtySize` as it opens the pair, so this repeats
        // what already holds. On Windows the pseudoconsole is not reliably sized by that
        // argument, and a client that starts inside a one-row window clamps every
        // absolute cursor position it is given: the frame collapses onto one line, and
        // every assertion that reads a ROW — the selection marker, the panes — fails
        // for a reason that has nothing to do with the client. Asserting the size here
        // makes the window the client draws into the one the tests are written against.
        pair.master
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .expect("size the pty");

        // `CommandBuilder` rather than `std::process::Command`: portable-pty
        // spawns through its own `fork`/`exec`, so the child is the pty slave.
        // Motion, mouse capture and colour follow the options, and the theme
        // variables are cleared so the palette cannot depend on the
        // developer's shell.
        let mut command = CommandBuilder::new(CLI);
        command.args([
            "--control-file",
            fixture.control().to_string_lossy().as_ref(),
        ]);
        command.args(&options.subcommand);
        if options.display_flags {
            if !options.motion {
                command.arg("--no-motion");
            }
            if !options.mouse {
                command.arg("--no-mouse");
            }
            if !options.color {
                command.arg("--no-color");
            }
        }
        // A connection deadline, so a fixture that never binds fails the test
        // instead of stalling it. Generous on purpose: this bounds a hang, it
        // is not a latency budget, and several pty-driving clients run
        // concurrently on a shared runner.
        if options.timeout {
            command.args(["--timeout", "30"]);
        }
        command.args(&options.extra);

        // The palette is resolved from the environment, so the variables that
        // choose it are cleared: the screen must not depend on the
        // developer's shell.
        command.env("HOME", fixture.dir());
        command.env("XDG_RUNTIME_DIR", fixture.dir());
        command.env("TERM", &options.term);
        command.env_remove("COLORTERM");
        command.env_remove("NO_COLOR");
        command.env_remove("KLARDROP_THEME");
        // Applied last, so a test can put back exactly the variable the scrub
        // just removed — which is the point: `NO_COLOR` has to be testable as
        // the environment contract it is, not only as a flag.
        for (key, value) in &options.env {
            command.env(key, value);
        }

        let child = pair
            .slave
            .spawn_command(command)
            .expect("spawn klardrop on the pty");

        let slave = if options.keep_slave {
            Some(pair.slave)
        } else {
            drop(pair.slave);
            None
        };

        // The master is kept because only it can issue TIOCSWINSZ, and that ioctl
        // is what makes the tty driver raise SIGWINCH in the child. Both handles
        // are borrowed from it rather than taken, so it survives the launch.
        let master = pair.master;
        let mut reader = master.try_clone_reader().expect("clone pty reader");
        let writer = master.take_writer().expect("take pty writer");

        let parser = Arc::new(Mutex::new(vt100::Parser::new(rows, cols, 0)));
        let exited = Arc::new(Mutex::new(None));
        let raw = Arc::new(Mutex::new(Vec::new()));
        {
            let parser = Arc::clone(&parser);
            let exited = Arc::clone(&exited);
            let raw = Arc::clone(&raw);
            std::thread::spawn(move || {
                let mut buffer = [0u8; 8192];
                loop {
                    match reader.read(&mut buffer) {
                        Ok(0) => break,
                        Ok(n) => {
                            raw.lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner())
                                .extend_from_slice(&buffer[..n]);
                            parser
                                .lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner())
                                .process(&buffer[..n]);
                        }
                        // The slave is gone: EIO is how a pty says "the other
                        // end closed", and it is the ordinary end of this loop.
                        Err(e) => {
                            *exited
                                .lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(e);
                            break;
                        }
                    }
                }
            });
        }

        Self {
            child,
            master,
            writer: Box::new(writer),
            parser,
            exited,
            raw,
            _slave: slave,
        }
    }

    /// Resizes the window to `cols`x`rows`, the way a user dragging its edge does.
    ///
    /// Sending bytes cannot fake this: crossterm only hears about a resize when
    /// the kernel raises `SIGWINCH`, and the kernel only raises it for a real
    /// `TIOCSWINSZ`. The local parser is re-geometried too, so later assertions
    /// read the same window the client drew into.
    pub fn resize(&mut self, cols: u16, rows: u16) {
        self.master
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .expect("resize pty");
        self.parser
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .set_size(rows, cols);
    }

    /// The screen as text: one string per row, trailing blank rows removed.
    /// Trailing spaces are already gone — that is the VT grid, not a guess.
    pub fn screen(&self) -> Vec<String> {
        let parser = self
            .parser
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let contents = parser.screen().contents();
        let mut rows: Vec<String> = contents.lines().map(str::to_string).collect();
        while rows.last().is_some_and(|row| row.trim().is_empty()) {
            rows.pop();
        }
        rows
    }

    /// The whole screen as one string, so a test can ask "does it contain".
    pub fn text(&self) -> String {
        self.screen().join("\n")
    }

    /// Types `keys` and gives the client a moment to answer. A fixed pause
    /// rather than a lock-step handshake: the TUI redraws on its own clock,
    /// and every assertion here waits on the screen afterwards anyway.
    pub fn send(&mut self, keys: &str) {
        self.writer
            .write_all(keys.as_bytes())
            .expect("write to the pty");
        self.writer.flush().expect("flush the pty");
        std::thread::sleep(Duration::from_millis(120));
    }

    /// Writes `bytes` at the client with no pause, for a test that is probing
    /// the pty rather than the client.
    pub fn send_raw(&mut self, bytes: &[u8]) {
        self.writer.write_all(bytes).expect("write to the pty");
        self.writer.flush().expect("flush the pty");
    }

    /// Waits until `needle` is on the screen, and returns the screen.
    pub fn wait_for(&self, needle: &str) -> Vec<String> {
        let deadline = Instant::now() + WAIT;
        loop {
            let rows = self.screen();
            if rows.iter().any(|row| row.contains(needle)) {
                return rows;
            }
            assert!(
                !self.has_exited(),
                "the client exited before {needle:?} appeared; screen was:\n{}",
                rows.join("\n")
            );
            assert!(
                Instant::now() < deadline,
                "{needle:?} never appeared on screen; last screen was:\n{}",
                rows.join("\n")
            );
            std::thread::sleep(Duration::from_millis(25));
        }
    }

    /// Waits until `needle` is gone from the screen. Used for effects: a
    /// decision is only real once the thing it decided about is no longer
    /// drawn.
    pub fn wait_until_gone(&self, needle: &str) -> Vec<String> {
        let deadline = Instant::now() + WAIT;
        loop {
            let rows = self.screen();
            if !rows.iter().any(|row| row.contains(needle)) {
                return rows;
            }
            assert!(
                !self.has_exited(),
                "the client exited while waiting for {needle:?} to disappear"
            );
            assert!(
                Instant::now() < deadline,
                "{needle:?} was still on screen when the wait ran out; screen was:\n{}",
                rows.join("\n")
            );
            std::thread::sleep(Duration::from_millis(25));
        }
    }

    /// Waits for the client to exit and returns its raw exit code. `portable-pty`
    /// reports the code as a `u32`; the value is still the process's own.
    pub fn wait_for_exit(&mut self) -> u32 {
        let deadline = Instant::now() + WAIT;
        loop {
            match self.child.try_wait() {
                Ok(Some(status)) => return status.exit_code(),
                Ok(None) => {}
                Err(e) => panic!("cannot wait for the client: {e}"),
            }
            assert!(
                Instant::now() < deadline,
                "the client did not exit; screen was:\n{}",
                self.text()
            );
            std::thread::sleep(Duration::from_millis(25));
        }
    }

    /// `true` once the pty master has seen the slave close, which is how a pty
    /// reports that the other end is gone. Read through a flag rather than by
    /// polling the child, so waiting on the screen never needs `&mut`.
    pub fn has_exited(&self) -> bool {
        self.exited
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .is_some()
    }

    /// Writes the current screen to `tests/frames/<name>.txt`.
    ///
    /// Returned so a failing assertion can quote the frame rather than only
    /// describing it.
    pub fn dump(&self, name: &str) -> String {
        let text = self.text();
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/frames");
        fs::create_dir_all(&dir).expect("create tests/frames");
        let path = dir.join(format!("{name}.txt"));
        fs::write(&path, format!("{text}\n")).expect("write the captured frame");
        path.display().to_string()
    }
}

impl Tui {
    /// Every byte the client has written to the pty, in order.
    pub fn raw(&self) -> Vec<u8> {
        self.raw
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// Whether the pty types back what is written to it, or `None` when the pty is
    /// too far gone to ask.
    ///
    /// This is the check a person makes after a full-screen program exits: type
    /// something and see whether your own shell still says it back. `ECHO` is
    /// off in raw mode, so a client that forgot to restore the terminal cannot
    /// pass this — and, unlike reading the flags out of `termios`, it needs no
    /// knowledge of the platform's `termios` layout.
    ///
    /// Needs [`LaunchOptions::keep_slave`]: once the client's descriptors are
    /// gone the pty is gone with them, and there is nothing left to type at.
    ///
    /// `Some(false)` — the probe ran and the pty did not echo — is the failure.
    /// `None` means the platform cannot report it (macOS refuses the write, Windows
    /// does not echo), which says nothing about the client; see the body.
    pub fn echoes_input(&mut self) -> Option<bool> {
        // ConPTY does not echo input written to the master back to it, so on Windows
        // the probe cannot report what the line discipline is doing. That is the same
        // situation as macOS below and for the same reason — a fact about the platform,
        // not about the client — so it is reported as "cannot probe" rather than
        // dressed up as "did not restore". Callers assert the restore with the
        // escape-sequence comparison, which works on every platform.
        if cfg!(windows) {
            return None;
        }
        let before = self.raw().len();
        // macOS revokes the tty when the session leader that owned it exits, so the
        // slave refuses writes with EIO even though `keep_slave` still holds a live
        // descriptor. There is nothing left to type at there — a fact about the
        // platform, not about the client — so it is reported as "cannot probe" rather
        // than dressed up as "did not restore". Callers assert the restore with the
        // escape-sequence comparison, which works on every platform.
        if self.writer.write_all(ECHO_PROBE).is_err() {
            return None;
        }
        self.writer.flush().expect("flush the echo probe");
        let deadline = Instant::now() + Duration::from_millis(1000);
        while Instant::now() < deadline {
            let raw = self.raw();
            if raw.len() >= before + ECHO_PROBE.len()
                && raw[before..]
                    .windows(ECHO_PROBE.len())
                    .any(|window| window == ECHO_PROBE)
            {
                return Some(true);
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        Some(false)
    }

    /// Which raw-mode furniture is still in everything the client wrote.
    ///
    /// Each of these is switched *on* by a program that takes the keyboard and
    /// never switched off again, so a set that stops growing once the client
    /// has exited is a client that gave the terminal back.
    pub fn raw_mode_furniture(&self) -> Vec<&'static str> {
        let raw = String::from_utf8_lossy(&self.raw()).into_owned();
        RAW_MODE_FURNITURE
            .into_iter()
            .filter(|sequence| raw.contains(sequence))
            .collect()
    }

    /// The client's pid, for sampling `/proc`.
    pub fn pid(&self) -> Option<u32> {
        self.child.process_id()
    }

    /// Waits until the accumulated output contains `needle`.
    ///
    /// For output the VT grid cannot show — a `--debug` line on stderr, an
    /// escape sequence the fallback path must not have written at all.
    pub fn wait_for_raw(&self, needle: &str) -> Vec<u8> {
        let deadline = Instant::now() + WAIT;
        loop {
            let raw = self.raw();
            if String::from_utf8_lossy(&raw).contains(needle) {
                return raw;
            }
            assert!(
                !self.has_exited(),
                "the client exited before {needle:?} was written; output was:\n{}",
                String::from_utf8_lossy(&raw)
            );
            assert!(
                Instant::now() < deadline,
                "{needle:?} was never written; output was:\n{}",
                String::from_utf8_lossy(&raw)
            );
            std::thread::sleep(Duration::from_millis(25));
        }
    }
}

impl Drop for Tui {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Captures `rows` as a frame named after `name`, and returns the text so a
/// caller can assert on it as well.
pub fn capture(tui: &Tui, name: &str) -> String {
    let text = tui.text();
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/frames");
    fs::create_dir_all(&dir).expect("create tests/frames");
    fs::write(dir.join(format!("{name}.txt")), format!("{text}\n")).expect("write frame");
    text
}
