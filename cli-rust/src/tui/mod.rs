//! The interactive TUI.
//!
//! The module split is the whole design:
//!
//! | module      | job |
//! |-------------|-----|
//! | [`terminal`] | owns and restores the terminal; refuses to run without one |
//! | [`escape`]   | the single funnel every drawn string passes through |
//! | [`action`]   | intents: what the keyboard asks for |
//! | [`text`]    | terminal display width, and the rows a message occupies |
//! | [`app`]      | state, and the pure reducer that changes it |
//! | [`layout`]   | constraint arithmetic, including every degenerate size |
//! | [`theme`]    | presentation, so a palette can be swapped without touching drawing |
//! | [`palette`]  | reads the terminal's own foreground and background, or says it can't |
//! | [`render`]   | draws the frame |
//! | [`worker`]   | every daemon call, on threads that are not the keyboard's |
//!
//! This module is the only place they meet: it owns the input loop, and that
//! loop does exactly three things — wait briefly for a key, fold whatever the
//! daemon said into the app, and draw.

pub mod action;
pub mod app;
pub mod escape;
pub mod layout;
pub mod line;
pub mod motion;
pub mod palette;
pub mod qr;
pub mod render;
pub mod terminal;
pub mod text;
pub mod theme;
pub mod worker;

use std::sync::mpsc::{self, TryRecvError};
use std::time::{Duration, Instant};

use crossterm::event::{Event, KeyEventKind, MouseButton, MouseEvent, MouseEventKind};
use ratatui::backend::CrosstermBackend;
use ratatui::Terminal;

use crate::control_file::ControlFile;
use crate::envelope::{CliError, CliResult, ErrorCode};
use crate::state::RequestStatus;
use action::{Action, Pane};
use app::{App, ConnectionState};
use ratatui::layout::Position;
use terminal::TerminalGuard;
use theme::Theme;
use worker::{DaemonEvent, SharePayload, Worker, WorkerCommand};

/// How long the input loop waits for a key before looking at the clock and
/// redrawing. Short enough that the tick and toast expiry stay timely, long
/// enough that an idle TUI does not spin the CPU.
pub const EVENT_POLL: Duration = Duration::from_millis(50);

/// Options for one TUI session, resolved from the command line.
#[derive(Debug, Clone)]
pub struct Options {
    pub theme: theme::ThemeChoice,
    /// `false` (`--no-motion`) turns every animation off. Motion is opt-out,
    /// and off still draws a truthful static row — it only stops the frame
    /// from changing.
    pub motion: bool,
    /// `--line-mode`: skip the full-screen client entirely and print the
    /// daemon state as lines. It is also what happens implicitly when the
    /// terminal cannot host a screen; see [`line`].
    pub line_mode: bool,
    /// Whether the TUI takes the mouse at startup. `Ctrl-Space` toggles it
    /// afterwards; see [`terminal::TerminalGuard::toggle_mouse_capture`].
    pub mouse: bool,
    pub color: bool,
    /// Bounds the initial connection only; every later request carries its own
    /// deadline inside the worker.
    pub connect_timeout: Duration,
    /// Write `--debug` diagnostics to stderr. Never to the screen, and never
    /// the bearer token: the client's own logging already redacts it.
    pub debug: bool,
    /// `Some(summary)` turns this session into the device picker for an
    /// already-supplied payload; `None` is the ordinary two-pane client.
    pub picker: Option<String>,
}

/// Opens the TUI against an already-validated control file, and returns the
/// device the user settled on when this was a picker (`None` in the ordinary
/// client, and when the picker was cancelled).
///
/// Order matters here. The terminal is checked before anything reads stdin —
/// redirected input is not a keyboard, and reading it would block forever on
/// an automation that expects a command to return. The daemon is probed before
/// the screen is taken, so a connection failure is a clean message rather than
/// a flash of alternate screen.
pub fn run(control: ControlFile, options: Options) -> CliResult<Option<String>> {
    if !terminal::is_interactive_terminal() {
        return Err(CliError::new(
            ErrorCode::TerminalRequired,
            "klardrop interactive needs a real terminal on both stdin and stdout; \
             this invocation has at least one of them redirected",
        ));
    }

    // A terminal that cannot host a screen gets the same daemon state as
    // lines, and never a single escape byte: this branch runs before anything
    // takes the terminal, so there is nothing to restore afterwards.
    if let Some(reason) = line::fallback_reason(&options) {
        // A picker has one answer to give and no way to give it in lines, so
        // this is a refusal with a way forward, never a silent "cancelled".
        if options.picker.is_some() {
            return Err(CliError::new(
                ErrorCode::TerminalRequired,
                format!(
                    "the device picker needs a screen and this terminal cannot \
                     host one ({reason}); pass --to <device-id> instead"
                ),
            ));
        }
        line::run(&control, &options, reason)?;
        return Ok(None);
    }

    let deadline = Instant::now() + options.connect_timeout;
    // This one request happens before the screen is taken, so its diagnostics
    // go to stderr like any other command's. Everything from the worker
    // onwards happens *after*, and is routed to a file below.
    crate::client::Client::new(
        control.port,
        &control.token,
        deadline,
        crate::client::DebugLog::stderr_if(options.debug),
    )?
    .get_json(crate::commands::STATE_PATH)?;

    // Past this point the client owns a screen: line mode has already answered
    // and returned above, and both ends were checked as terminals at the top.
    // That is what makes the palette probe below safe to run at all.
    let env = theme::ProcessEnv;
    // Asked once, and the same answer goes to the depth ladder *and* to the
    // renderer. `NO_COLOR` used to reach only the ladder, so a terminal with
    // `NO_COLOR=1` set and no `--no-color` flag was handed a monochrome
    // palette full of BOLD/DIM/REVERSED and drew every one of them — the exact
    // "no ANSI at all" the README and `theme.rs` both promise.
    let no_color = theme::no_color_requested(!options.color, &env);
    let depth = theme::detect_depth(&env, no_color);
    let probe =
        match theme::wants_terminal_palette(options.theme) && depth != theme::Depth::Monochrome {
            true => palette::query(),
            // Tokyo Night is an answer in itself, and with no colour allowed there
            // is nothing to derive: in both cases the terminal is never touched.
            false => palette::Probe::none(),
        };
    let theme = theme::resolve(options.theme, probe.palette(), depth);
    if options.debug {
        eprintln!("klardrop: debug: theme depth = {depth:?}");
        eprintln!(
            "klardrop: debug: theme = {}",
            describe(options.theme, probe.palette())
        );
    }

    // From here on stderr *is* the screen, so `--debug` cannot write to it:
    // one line per daemon request, straight into the middle of the panes and
    // the footer. The session's diagnostics go to a file instead, named here
    // while stderr is still the user's scrollback rather than the alternate
    // screen.
    let debug_log = if options.debug {
        let path = std::env::temp_dir().join(format!("klardrop-debug-{}.log", std::process::id()));
        eprintln!(
            "klardrop: debug: writing this session's log to {}",
            path.display()
        );
        crate::client::DebugLog::to_file(&path)
    } else {
        crate::client::DebugLog::Off
    };

    let mut guard = TerminalGuard::setup(options.mouse)?;
    let mut screen = Terminal::new(CrosstermBackend::new(std::io::stdout())).map_err(|e| {
        CliError::new(
            ErrorCode::InternalError,
            format!("cannot open the terminal ({e})"),
        )
    })?;

    let outcome = session(
        &mut screen,
        &mut guard,
        control,
        &options,
        Look {
            theme: &theme,
            no_color,
        },
        debug_log,
        probe.replay,
    );

    // Give the terminal back before anything else can fail, so a later error
    // still reaches a usable shell.
    drop(screen);
    guard.restore();
    outcome
}

/// How the palette was decided, for `--debug`.
///
/// Names the outcome rather than the colours themselves, so a log says *where*
/// the palette came from — and, when the terminal did not answer, that it did
/// not — without becoming a place a user's colours are recorded.
fn describe(choice: theme::ThemeChoice, probed: Option<theme::FgBg>) -> String {
    if !theme::wants_terminal_palette(choice) {
        return format!("{choice:?}, not asked");
    }
    match probed {
        Some((fg, bg)) => format!(
            "{choice:?}, terminal fg #{:02x}{:02x}{:02x} bg #{:02x}{:02x}{:02x}",
            fg.0, fg.1, fg.2, bg.0, bg.1, bg.2
        ),
        None => format!("{choice:?}, no answer from the terminal, using tokyo-night"),
    }
}

/// How this session draws: the resolved palette, and the already-resolved
/// `--no-color`/`NO_COLOR` answer.
///
/// Bundled because they are one decision. A frame cannot style one pane and not
/// another, and the only way to keep that true is for every draw to be handed
/// the same pair.
struct Look<'a> {
    theme: &'a Theme,
    no_color: bool,
}

/// The input loop.
///
/// Exactly one thread ever calls [`crossterm::event::poll`], and it never makes
/// a blocking read: the poll carries a timeout, so daemon events, the tick
/// clock and toast expiry are all serviced even while the user types nothing.
///
/// `replay` is whatever [`palette::query`] read that was not part of a reply.
/// It is folded in before the first poll, so a keystroke the probe happened to
/// be sitting on still reaches the app instead of being lost to a colour
/// decision.
///
/// `look` is how this session draws — palette and the resolved colour answer
/// together — and `debug` is where this session's diagnostics go, never
/// stderr, because the session owns it.
fn session(
    screen: &mut Terminal<CrosstermBackend<std::io::Stdout>>,
    guard: &mut TerminalGuard,
    control: ControlFile,
    options: &Options,
    look: Look<'_>,
    debug: crate::client::DebugLog,
    replay: Vec<crossterm::event::Event>,
) -> CliResult<Option<String>> {
    let (tx, rx) = mpsc::channel::<DaemonEvent>();
    let worker = Worker::spawn(control.port, control.token, debug, tx);
    let mut state = App::new(Instant::now());
    if let Some(summary) = &options.picker {
        state.set_picker(summary.clone());
    }
    // Braille needs a UTF-8 terminal; the ASCII spinner is the honest fallback,
    // and it is chosen once rather than probed per frame.
    let braille = ["LC_ALL", "LC_CTYPE", "LANG"]
        .iter()
        .find_map(|key| std::env::var(key).ok())
        .map(|value| value.to_uppercase().contains("UTF"))
        .unwrap_or(true);
    state.set_motion(options.motion, braille, Instant::now());
    // The guard has already taken the mouse (or been told not to); the app's
    // copy of that starts from the same answer. `mouse_available` comes from
    // the guard too, so a `--no-mouse` session knows it has no mouse *to*
    // release rather than one it let go.
    state.set_mouse_captured(guard.mouse_captured());
    state.set_mouse_available(guard.mouse_available());
    // The palette probe's leftovers, in order, ahead of anything the terminal
    // sends from here on: they were typed first.
    let mut replay = replay.into_iter();
    let mut dirty = true;

    let outcome: CliResult<Option<String>> = loop {
        if state.is_quitting() {
            break Ok(state.picked().map(str::to_string));
        }

        // 0. Whatever the palette probe read on the user's behalf, first.
        for event in replay.by_ref() {
            let actions = fold_event(&mut state, event);
            dispatch(&worker, guard, &mut state, actions);
            dirty = true;
        }

        // 1. Wait briefly for input. Never a blocking read.
        let has_event = match crossterm::event::poll(EVENT_POLL) {
            Ok(has_event) => has_event,
            Err(e) => break Err(input_error(e)),
        };
        if has_event {
            let event = match crossterm::event::read() {
                Ok(event) => event,
                Err(e) => break Err(input_error(e)),
            };
            let actions = fold_event(&mut state, event);
            dispatch(&worker, guard, &mut state, actions);
            dirty = true;
        }

        // 2. Fold in whatever the daemon said, without blocking.
        let mut worker_gone = false;
        loop {
            match rx.try_recv() {
                Ok(event) => {
                    for action in fold_daemon_event(&mut state, &event) {
                        send(&worker, &mut state, action);
                    }
                    dirty = true;
                }
                Err(TryRecvError::Empty) => break,
                // The worker is gone: keep the last state on screen and let the
                // user quit, rather than spinning on a dead channel.
                Err(TryRecvError::Disconnected) => {
                    worker_gone = true;
                    break;
                }
            }
        }
        if worker_gone {
            state.set_connection(ConnectionState::Offline(
                "the daemon worker stopped".to_string(),
            ));
            dirty = true;
        }

        // 3. The clock.
        state.tick(Instant::now());
        if let Ok(size) = crossterm::terminal::size() {
            if state.viewport() != size {
                state.set_viewport(size.0, size.1);
                dirty = true;
            }
        }
        // Motion asks for its own redraw, coalesced through the same flag as
        // everything else, and only when the animation clock actually moved.
        if state.take_motion_dirty() {
            dirty = true;
        }
        if dirty {
            screen
                .draw(|frame| render::draw(frame, &state, look.theme, look.no_color))
                .map_err(|e| {
                    CliError::new(ErrorCode::InternalError, format!("cannot draw ({e})"))
                })?;
            dirty = false;
        }
    };

    worker.shutdown();
    outcome
}

fn input_error(reason: std::io::Error) -> CliError {
    CliError::new(
        ErrorCode::InternalError,
        format!("cannot read terminal input ({reason})"),
    )
}

/// Turns a terminal event into actions. An empty list means the event changed
/// nothing worth redrawing for.
fn fold_event(state: &mut App, event: Event) -> Vec<Action> {
    match event {
        // Release events never act: only a press is a keystroke.
        Event::Key(key) if key.kind != KeyEventKind::Release => {
            state.handle_key(key, Instant::now())
        }
        // Bracketed paste arrives as one event carrying the whole payload, so a
        // paste cannot be mistaken for a run of shortcuts.
        Event::Paste(text) => state.paste(&text),
        Event::Mouse(mouse) => mouse_actions(state, &mouse),
        Event::Resize(_, _) => Vec::new(),
        _ => Vec::new(),
    }
}

/// Mouse selection, focus and wheel scrolling. Every one of these has a keyboard
/// equivalent, so no feature requires a mouse.
fn mouse_actions(state: &App, mouse: &MouseEvent) -> Vec<Action> {
    match mouse.kind {
        MouseEventKind::ScrollUp => vec![Action::Scroll(-1)],
        MouseEventKind::ScrollDown => vec![Action::Scroll(1)],
        MouseEventKind::Down(MouseButton::Left) => click_actions(state, mouse),
        _ => Vec::new(),
    }
}

/// A click focuses whichever pane it landed in and, in the device pane, selects
/// the row it landed on.
fn click_actions(state: &App, mouse: &MouseEvent) -> Vec<Action> {
    let at = Position::new(mouse.column, mouse.row);
    let rects = state.layout();
    // Measured against the rectangle the renderer actually drew the list into:
    // the whole body in a picker (`render.rs`), the left pane otherwise. Using
    // the other one put a picker's rows a header's height away from the pointer.
    let device_pane = if state.is_picker() {
        rects.body()
    } else {
        rects.left
    };
    if device_pane.contains(at) {
        // `Block::bordered()` draws the pane title *on* the top border row, so
        // the first device row is the row immediately below the pane's top
        // edge — one row, not two. Counting two selected the row above the one
        // under the pointer and left the last device unclickable.
        let index = mouse.row.saturating_sub(device_pane.y + 1) as usize;
        vec![Action::FocusPane(Pane::Devices), Action::SelectRow(index)]
    } else if rects.right.contains(at) {
        vec![Action::FocusPane(Pane::Conversation)]
    } else {
        Vec::new()
    }
}

/// Applies each action locally, then routes whatever the app handed back to the
/// worker.
///
/// One action is not the app's to apply: [`Action::ToggleMouseCapture`] changes
/// the terminal, so the guard performs it and reports back what the terminal
/// actually did. The app's copy is set from *that*, never from the keypress —
/// a footer that claims a capture the terminal refused to give up is a lie the
/// user acts on.
fn dispatch(worker: &Worker, guard: &mut TerminalGuard, state: &mut App, actions: Vec<Action>) {
    for action in actions {
        if action == Action::ToggleMouseCapture {
            toggle_mouse(guard, state);
            continue;
        }
        // Everything `apply` returns still needs the daemon; nothing local
        // comes back out, so each one is sent exactly once.
        for next in state.apply(&action) {
            send(worker, state, next);
        }
    }
}

/// What `Ctrl-Space` answers in a session started with `--no-mouse`.
///
/// Named rather than inlined because the footer has to agree with it: both
/// describe the same session, and a footer that still offered `Ctrl-Space`
/// while the toast said the key cannot work would be the same lie in two
/// places.
pub const NO_MOUSE_MESSAGE: &str = "this session captures no mouse — Ctrl-Space will not take one";

/// Releases the mouse to the terminal, or takes it back.
///
/// A session started with `--no-mouse` never took the mouse, so there is
/// nothing to release and nothing `Ctrl-Space` can take: the guard's toggle
/// would return "not held" without writing a byte. Answering that with the
/// release message would describe a capture the terminal never made, and the
/// "Ctrl-Space takes it back" half would promise a key that cannot work, so
/// this says what is actually true about the session instead.
fn toggle_mouse(guard: &mut TerminalGuard, state: &mut App) {
    if !guard.mouse_available() {
        state.set_mouse_captured(guard.mouse_captured());
        state.notify(NO_MOUSE_MESSAGE);
        return;
    }
    match guard.toggle_mouse_capture() {
        Ok(held) => {
            state.set_mouse_captured(held);
            state.notify(if held {
                "the TUI has the mouse again"
            } else {
                "mouse released — the terminal can select and copy now; Ctrl-Space takes it back"
            });
        }
        Err(error) => {
            // The terminal said no. Keep the app in step with the guard, which
            // did not change, and say so instead of failing the session: a
            // mouse the user can live without is not worth quitting over.
            state.set_mouse_captured(guard.mouse_captured());
            state.notify(format!("cannot change the mouse: {}", error.message));
        }
    }
}

/// Routes one action to the worker, resolving whatever depends on what the user
/// is looking at rather than on what the reducer can see.
fn send(worker: &Worker, state: &mut App, action: Action) {
    let command = match action {
        Action::Quit => return,
        Action::SendText(text) => share(state, SharePayload::Text(text)),
        Action::SendFiles(paths) => share(state, SharePayload::Files(paths)),
        Action::SendClipboard => share(state, SharePayload::Clipboard),
        // A manual refresh goes to the poller, which re-arms without its cursor
        // rather than waiting out a long poll that is already outstanding.
        Action::Refresh => {
            worker.refresh();
            return;
        }
        // Followed through `/transfers?id=` by the worker; routing it here is
        // what keeps the "what is the outcome" question off the input loop.
        Action::TrackTransfer(request_id) => Some(WorkerCommand::WatchTransfer(request_id)),
        other => Some(WorkerCommand::Command(other)),
    };
    if let Some(command) = command {
        worker.send(command);
    }
}

/// A send needs a device. Without an open conversation there is nothing to send
/// to, so it is refused with a reason rather than dropped.
fn share(state: &mut App, payload: SharePayload) -> Option<WorkerCommand> {
    match state.open_device().map(str::to_string) {
        Some(device) => Some(WorkerCommand::Share { device, payload }),
        None => {
            state.notify("open a device first");
            None
        }
    }
}

/// Folds one daemon event into the app and returns any actions it implies.
fn fold_daemon_event(state: &mut App, event: &DaemonEvent) -> Vec<Action> {
    match event {
        DaemonEvent::Capabilities(info) => {
            state.set_daemon_info(info);
            Vec::new()
        }
        DaemonEvent::State(snapshot) => {
            let actions = state.apply_state(snapshot);
            // A QR share the daemon has started is worth showing on its own.
            if snapshot.qr_share.active {
                state.open_qr_overlay();
            }
            actions
        }
        DaemonEvent::History { page, older } => {
            if !state.apply_history(page.clone(), *older) {
                state.notify("a history page arrived for a conversation you left");
            }
            Vec::new()
        }
        DaemonEvent::ShareSubmitted {
            request_id,
            status,
            items,
            ..
        } => {
            state.notify(format!(
                "{} as {items} item(s), request {}",
                status.as_str(),
                short_id(request_id)
            ));
            // Follow it: what the TUI shows next is whatever `/transfers` says,
            // never an assumption made here.
            vec![Action::TrackTransfer(request_id.clone())]
        }
        DaemonEvent::TransferUpdated(request) => {
            state.notify(format!(
                "{} request {}",
                request.status.as_str(),
                short_id(&request.request_id)
            ));
            // The flourish is armed by the daemon's own terminal status and by
            // nothing else: a request that is merely still queued, or that
            // ended in a decline or a failure, gets its words and no animation,
            // because a celebration the user did not earn is a lie about the
            // outcome.
            if request.status == RequestStatus::Completed {
                state.celebrate(Instant::now());
            }
            Vec::new()
        }
        DaemonEvent::Failed { action, error } => {
            // A history failure must not leave the pane looking like an empty
            // conversation: say what went wrong where the messages would be.
            if action == "history" {
                state.fail_history(error);
            }
            state.notify(format!("{action} failed: {error}"));
            Vec::new()
        }
        DaemonEvent::Offline(reason) => {
            state.set_connection(ConnectionState::Offline(reason.clone()));
            Vec::new()
        }
    }
}

/// The last few characters of an id: enough to recognise, short enough not to
/// flood a one-line footer.
fn short_id(request_id: &str) -> String {
    let tail: String = request_id
        .chars()
        .rev()
        .take(6)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    if tail.is_empty() {
        "-".to_string()
    } else {
        tail
    }
}
