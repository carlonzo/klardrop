//! What the input loop asks the rest of the program to do.
//!
//! The reducer in [`crate::tui::app`] turns key events into these; the worker
//! in [`crate::tui::worker`] is the only thing that performs them. Keeping the
//! two apart is what makes the keyboard testable without a terminal and the
//! daemon testable without a keyboard.

use std::path::PathBuf;

/// Which pane a [`Action::FocusPane`] targets.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pane {
    Devices,
    Conversation,
}

/// An intent, never a result.
///
/// Anything that needs the daemon is an `Action`; anything that only changes
/// what is drawn is not. That split is what keeps the input loop from ever
/// blocking on the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Leave the TUI. Closing the observer never cancels a transfer: the
    /// daemon owns it.
    Quit,
    /// Toggle the keyboard help overlay.
    Help,
    /// Load one page of a device's history. `before` is `None` for the newest
    /// page and the daemon's `nextBefore` cursor for older ones.
    LoadHistory {
        device: String,
        before: Option<i64>,
    },
    /// The file-path prompt. Not reachable from a bare letter: it is a distinct
    /// mode, so an accidental keystroke cannot start a send.
    StartFilePrompt,
    /// The rename prompt for this (self) device.
    StartRenamePrompt,
    /// Move the conversation window by a number of lines. A mouse wheel click.
    Scroll(i32),
    SelectNextDevice,
    SelectPrevDevice,
    /// Select the device at this index. A mouse click; the keyboard equivalent
    /// is `Up`/`Down`, so no feature requires a mouse.
    SelectRow(usize),
    /// Hand the mouse back to the terminal, or take it again. `Ctrl-Space`.
    ///
    /// While the client holds the mouse the terminal's own selection cannot
    /// start, so this is the documented way to copy text out of the screen. It
    /// changes the terminal and nothing else: the daemon never hears about it.
    ToggleMouseCapture,
    /// Open the selected device's conversation and load its history.
    OpenDevice,
    /// Accept the currently selected device as the answer to "which device?".
    ///
    /// Only the device picker produces this: it is how `klardrop share --pick`
    /// learns where to send an already-supplied payload. In the ordinary TUI
    /// Enter means "open this conversation" and never this.
    ConfirmDevice,
    /// Close the overlay, the compose line or the open conversation, in that
    /// order — one key that always means "one level back".
    Back,
    FocusPane(Pane),
    /// Move focus by `+1`/`-1` through the panes. `Tab` and `Shift-Tab`.
    MoveFocus(i8),
    /// Begin typing. [`crate::tui::app::ComposeKind`] says what for.
    StartCompose,
    /// Commit the compose buffer. Produces a send/rename action, never a send
    /// by itself: an empty or whitespace-only buffer is refused.
    SubmitCompose,
    /// Discard the compose buffer.
    CancelCompose,
    /// Ask the daemon for a fresh `/state` immediately.
    Refresh,
    PageHistoryUp,
    PageHistoryDown,
    /// Send files to the open device through `POST /share`.
    SendFiles(Vec<PathBuf>),
    /// Send the open device a text message through `POST /share`.
    SendText(String),
    /// Send the daemon's current clipboard through `POST /share`.
    SendClipboard,
    OpenActionsMenu,
    /// Debug pairing decision for one device. Never automatic.
    AcceptPairing(String),
    RejectPairing(String),
    /// Approval decision for one pending incoming transfer. Never automatic.
    AcceptIncoming(i64),
    RejectIncoming(i64),
    /// Ask the daemon for a QR share of the given files. There is no picker:
    /// the paths are typed or pasted, exactly as for a direct send.
    ShowQr(Vec<PathBuf>),
    /// The QR path prompt, opened from the actions menu.
    StartQrPrompt,
    /// Rename this (self) device.
    RenameDevice(String),
    /// Open the settings overlay.
    OpenSettings,
    /// Set background discovery on or off (`POST /settings`).
    SetBackgroundDiscovery(bool),
    /// Ask the update checker to look now (`POST /update/check`).
    CheckUpdate,
    /// Apply a staged update. Non-force: a 409 stays a 409 and is reported.
    ApplyUpdate,
    /// Tell the daemon the user is actually reading this conversation.
    ///
    /// Issued only for the device that is focused *and* visible, mirroring the
    /// Qt UI's lifecycle — never for a device the user merely scrolled past.
    MarkRead(String),
    /// Follow one submitted request through `GET /transfers?id=`. Issued by
    /// the coordinator right after `POST /share` is accepted, so the outcome
    /// the user sees is the daemon's own, not a local guess.
    TrackTransfer(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_action_keeps_its_payload() {
        // Actions travel through an mpsc channel to another thread; a payload
        // that silently lost its value would turn a send into a no-op that
        // still looked like one.
        assert_eq!(
            Action::SendFiles(vec![PathBuf::from("/tmp/a")]),
            Action::SendFiles(vec![PathBuf::from("/tmp/a")])
        );
        assert_ne!(
            Action::AcceptPairing("1111".into()),
            Action::AcceptPairing("2222".into())
        );
        assert_ne!(Action::AcceptIncoming(7), Action::AcceptIncoming(8));
        assert_ne!(Action::MoveFocus(1), Action::MoveFocus(-1));
        assert_eq!(Action::MoveFocus(-1), Action::MoveFocus(-1));
    }

    #[test]
    fn history_cursors_and_scroll_deltas_survive_the_channel() {
        assert_eq!(
            Action::LoadHistory {
                device: "1111".into(),
                before: None
            },
            Action::LoadHistory {
                device: "1111".into(),
                before: None
            }
        );
        assert_ne!(
            Action::LoadHistory {
                device: "1111".into(),
                before: None
            },
            Action::LoadHistory {
                device: "1111".into(),
                before: Some(42)
            }
        );
        assert_ne!(Action::Scroll(1), Action::Scroll(-1));
    }
}
