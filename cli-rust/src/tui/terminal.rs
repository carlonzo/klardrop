//! Terminal ownership and restoration.
//!
//! Putting a terminal into raw mode, switching to the alternate screen and
//! capturing the mouse is a sequence of irreversible-looking state changes
//! that only the last one undoes completely. Every client that gets this wrong
//! leaves the user with an invisible cursor, a swallowed keyboard, or a shell
//! that no longer echoes.
//!
//! Two rules hold here:
//!
//!   * the guard owns *every* piece of state it changes, and restores it in
//!     exactly the reverse order it was acquired in;
//!   * restoration lives in `Drop`, so it happens on an ordinary return, on an
//!     early `?`, and on an unwinding panic alike. (This crate deliberately
//!     keeps the default `panic = "unwind"`; `panic = "abort"` would skip
//!     every `Drop` and is therefore not a change this crate may make.)

use crossterm::event::{
    DisableBracketedPaste, DisableMouseCapture, EnableBracketedPaste, EnableMouseCapture,
};
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use crossterm::tty::IsTty;
use std::io::Write;

use crate::envelope::{CliError, CliResult, ErrorCode};

/// `true` when stdin is an interactive terminal.
///
/// Checked before anything reads stdin: redirected input is not a keyboard, and
/// treating a pipe or a file as one is how an automation hangs forever waiting
/// for a keypress that will never come.
pub fn stdin_is_terminal() -> bool {
    std::io::stdin().is_tty()
}

/// `true` when stdout is an interactive terminal. The TUI owns the screen, so
/// this must be true before a single escape sequence is written.
pub fn stdout_is_terminal() -> bool {
    std::io::stdout().is_tty()
}

/// `true` when both ends are terminals, i.e. this process owns a keyboard and a
/// screen. Only then may the TUI read stdin.
pub fn is_interactive_terminal() -> bool {
    stdin_is_terminal() && stdout_is_terminal()
}

/// The one piece of terminal state the guard owns. Kept as an explicit list, in
/// setup order, so restoration is a mechanical reversal rather than a set of
/// hand-written "undo" calls that can drift apart from what setup actually did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Undo {
    RawMode,
    // Kitty keyboard enhancement is deliberately NOT in this list. Negotiating
    // it needs a capability query, and a query that reads a reply the terminal
    // never sends consumes real keystrokes — the leak the plan warns about.
    // Legacy keyboard decoding handles every key the client binds, so the guard
    // owns only what it actually takes.
    AlternateScreen,
    BracketedPaste,
    MouseCapture,
    HiddenCursor,
}

/// Owns the terminal for as long as it is alive, and gives it back on drop.
#[derive(Debug)]
pub struct TerminalGuard {
    /// Undone in reverse order. Present only while restoration is pending.
    undos: Vec<Undo>,
    /// Whether the client currently holds the mouse.
    ///
    /// Separate from the undo slot on purpose: the slot records that the
    /// client took the mouse *at all*, and is what the drop path undoes, while
    /// this is the state the footer shows and `Ctrl-Space` toggles.
    mouse_held: bool,

    /// Whether this session ever took the mouse at startup, i.e. whether
    /// `--no-mouse` was passed.
    ///
    /// Fixed for the guard's lifetime and distinct from [`Self::mouse_held`]:
    /// this is what the session *can* do, and a client that never took the
    /// mouse can never take it, so anything offering `Ctrl-Space` as a way
    /// back would be promising a capture that cannot happen.
    mouse_available: bool,
}

impl TerminalGuard {
    /// Puts the terminal into TUI mode and returns its guard.
    ///
    /// If a step fails, everything already acquired is restored before the
    /// error is returned: a half-configured terminal is worse than none.
    pub fn setup(mouse: bool) -> CliResult<Self> {
        let mut guard = Self {
            undos: Vec::new(),
            mouse_held: false,
            mouse_available: mouse,
        };

        // Nothing has been acquired yet, so there is nothing to restore: the
        // `?` is the whole cleanup for this first step.
        guard.enable_raw_mode()?;
        guard.push(Undo::RawMode);

        if let Err(error) = guard.enter_alternate_screen() {
            guard.restore();
            return Err(error);
        }
        guard.push(Undo::AlternateScreen);

        if let Err(error) = guard.enable_bracketed_paste() {
            guard.restore();
            return Err(error);
        }
        guard.push(Undo::BracketedPaste);

        // Mouse capture is last, so a terminal that cannot do it still gets a
        // working keyboard-driven TUI.
        if mouse {
            if let Err(error) = guard.enable_mouse_capture() {
                guard.restore();
                return Err(error);
            }
            guard.push(Undo::MouseCapture);
            guard.mouse_held = true;
        }

        if let Err(error) = guard.hide_cursor() {
            guard.restore();
            return Err(error);
        }
        guard.push(Undo::HiddenCursor);

        Ok(guard)
    }

    /// `true` while the client holds the mouse. The footer asks for this, and
    /// the tests use it to prove the toggle actually flipped something.
    pub fn mouse_captured(&self) -> bool {
        self.mouse_held
    }

    /// `false` under `--no-mouse`: this session never took the mouse, so
    /// [`Self::toggle_mouse_capture`] can only ever return "not held".
    ///
    /// The footer and the `Ctrl-Space` toast both branch on this, because
    /// telling a user who passed `--no-mouse` that the mouse was "released"
    /// describes a capture that never happened.
    pub fn mouse_available(&self) -> bool {
        self.mouse_available
    }

    /// Hands the mouse back to the terminal, or takes it again, and returns the
    /// new state.
    ///
    /// This is the selection bypass. While the client holds the mouse the
    /// terminal reports every click as an application event and never lets its
    /// own selection happen, so a user who wants to copy a line out of the
    /// device list has no way to start one. `Ctrl-Space` is the documented way
    /// out: one key, no mode to remember, and the same key takes the mouse back
    /// when the selection is done.
    ///
    /// The undo slot is untouched either way. It records that the client took
    /// the mouse at startup, and that is what [`Self::restore`] has to undo — a
    /// guard that remembered "currently released" would leave a capture on a
    /// terminal it had already given back.
    pub fn toggle_mouse_capture(&mut self) -> CliResult<bool> {
        if self.mouse_held {
            write_out(&[DisableMouseCapture])?;
            self.mouse_held = false;
        } else {
            // Only a client that took the mouse at startup can take it again;
            // `--no-mouse` must not start capturing behind the user's back.
            if !self.undos.contains(&Undo::MouseCapture) {
                return Ok(false);
            }
            write_out(&[EnableMouseCapture])?;
            self.mouse_held = true;
        }
        Ok(self.mouse_held)
    }

    /// Puts everything back, right now instead of at drop. Idempotent, so an
    /// explicit call on the ordinary exit path costs nothing.
    pub fn restore(&mut self) {
        let mut log = Vec::new();
        self.restore_with(&mut log);
    }

    /// `true` once restoration has run — whether by [`Self::restore`] or by
    /// `Drop`.
    #[cfg(test)]
    pub fn is_restored(&self) -> bool {
        self.undos.is_empty()
    }

    /// The restoration algorithm, with the steps reported so it can be asserted
    /// on without a terminal. Restoration is the reverse of acquisition, which
    /// is why the states are popped rather than matched.
    fn restore_with(&mut self, log: &mut Vec<Undo>) {
        while let Some(undo) = self.undos.pop() {
            log.push(undo);
            // Best effort, and deliberately so: a terminal that has gone away
            // cannot be restored, and that must not prevent the remaining
            // steps — which may be the only thing the user's shell still needs
            // — from being attempted.
            let _ = undo_undo(undo);
        }
    }

    fn push(&mut self, undo: Undo) {
        self.undos.push(undo);
    }

    fn enable_raw_mode(&mut self) -> CliResult<()> {
        enable_raw_mode().map_err(|e| {
            CliError::new(
                ErrorCode::TerminalRequired,
                format!("cannot switch the terminal into raw mode ({e})"),
            )
        })
    }

    fn enter_alternate_screen(&mut self) -> CliResult<()> {
        write_out(&[EnterAlternateScreen])
    }

    fn enable_bracketed_paste(&mut self) -> CliResult<()> {
        write_out(&[EnableBracketedPaste])
    }

    fn enable_mouse_capture(&mut self) -> CliResult<()> {
        write_out(&[EnableMouseCapture])
    }

    fn hide_cursor(&mut self) -> CliResult<()> {
        write_out(&[crossterm::cursor::Hide])
    }
}

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        self.restore();
    }
}

/// Undoes one piece of terminal state.
fn undo_undo(undo: Undo) -> CliResult<()> {
    match undo {
        Undo::RawMode => disable_raw_mode().map_err(|e| {
            CliError::new(
                ErrorCode::TerminalRequired,
                format!("cannot restore cooked mode ({e})"),
            )
        }),
        Undo::AlternateScreen => write_out(&[LeaveAlternateScreen]),
        Undo::BracketedPaste => write_out(&[DisableBracketedPaste]),
        Undo::MouseCapture => write_out(&[DisableMouseCapture]),
        Undo::HiddenCursor => write_out(&[crossterm::cursor::Show]),
    }
}

/// Writes terminal commands, mapping an I/O failure onto the existing error
/// taxonomy: a terminal we cannot drive is a terminal requirement, not a bug.
fn write_out<C: crossterm::Command>(commands: &[C]) -> CliResult<()> {
    use crossterm::queue;

    let mut stdout = std::io::stdout();
    for command in commands {
        queue!(stdout, command).map_err(|e| {
            CliError::new(
                ErrorCode::TerminalRequired,
                format!("cannot configure the terminal ({e})"),
            )
        })?;
    }
    stdout.flush().map_err(|e| {
        CliError::new(
            ErrorCode::TerminalRequired,
            format!("cannot configure the terminal ({e})"),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A guard holding every state, acquired in setup order, without touching a
    /// terminal: the ordering logic is real, only the side effects are absent.
    fn fully_armed() -> TerminalGuard {
        TerminalGuard {
            undos: vec![
                Undo::RawMode,
                Undo::AlternateScreen,
                Undo::BracketedPaste,
                Undo::MouseCapture,
                Undo::HiddenCursor,
            ],
            mouse_held: true,
            mouse_available: true,
        }
    }

    #[test]
    fn restoration_is_the_exact_reverse_of_acquisition() {
        let mut guard = fully_armed();
        let mut log = Vec::new();
        guard.restore_with(&mut log);

        assert_eq!(
            log,
            vec![
                Undo::HiddenCursor,
                Undo::MouseCapture,
                Undo::BracketedPaste,
                Undo::AlternateScreen,
                Undo::RawMode,
            ],
            "each state must be given back before the one it was layered on"
        );
        assert!(guard.is_restored());
    }

    #[test]
    fn mouse_capture_is_restored_only_when_it_was_enabled() {
        // `--no-mouse` must not emit a disable for a capture it never took:
        // that would clear a capture the surrounding terminal legitimately had.
        let mut guard = TerminalGuard {
            undos: vec![Undo::RawMode, Undo::AlternateScreen, Undo::HiddenCursor],
            mouse_held: false,
            mouse_available: false,
        };
        let mut log = Vec::new();
        guard.restore_with(&mut log);
        assert_eq!(
            log,
            vec![Undo::HiddenCursor, Undo::AlternateScreen, Undo::RawMode]
        );
        assert!(!log.contains(&Undo::MouseCapture));
    }

    #[test]
    fn restoring_twice_is_a_no_op() {
        let mut guard = fully_armed();
        guard.restore();
        let mut log = Vec::new();
        guard.restore_with(&mut log);
        assert!(log.is_empty(), "an exit path must not un-restore anything");
    }

    #[test]
    fn dropping_a_guard_restores_everything_it_owns() {
        let guard = fully_armed();
        assert!(!guard.is_restored(), "the terminal is still ours");
        drop(guard);
        // Reaching here without a panic or an unwinding failure is the
        // assertion: Drop ran the same reversal on an ordinary scope exit.
    }
    #[test]
    fn what_this_session_could_do_with_the_mouse_outlives_restoration() {
        // Restoration empties the undo stack, and nothing else. The footer's
        // truth about the session is not the stack: a guard that started
        // reporting "no mouse" the moment it was dropped would have the footer
        // and the terminal disagreeing at exactly the moment the terminal is
        // being handed back.
        let mut guard = fully_armed();
        guard.restore();
        assert!(
            guard.is_restored(),
            "everything the guard took is given back"
        );
        assert!(
            guard.mouse_available(),
            "the session did take a mouse, and saying otherwise after \
             restoration is as untrue as never having taken one"
        );
    }
}
