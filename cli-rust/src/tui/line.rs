//! Line mode: what `klardrop interactive` does when a screen is not available.
//!
//! A full-screen client needs three things a terminal may not have: cursor
//! addressing, a colour depth worth drawing with, and enough cells to lay the
//! panes out in. When any of them is missing, the alternative is not an error
//! and not a blank screen — it is a *different, smaller* answer: the same
//! daemon state, printed as lines, once, with no escape sequence of any kind.
//!
//! ## The trigger
//!
//! Decided once, at startup, in this order:
//!
//! 1. `--line-mode` — the user asked for it, and an explicit request is never
//!    second-guessed.
//! 2. `TERM` unset, empty, or `dumb` — the terminal says it cannot address the
//!    screen. Everything the TUI draws would be a smear of literal escapes.
//! 3. A window smaller than [`layout::MIN_WIDTH`] × [`layout::MIN_HEIGHT`] —
//!    below that the layout has nothing to lay out. This is the same predicate
//!    the renderer uses, so "too small to draw" means one thing in the crate.
//!
//! A window that *becomes* too small while the TUI is running does not switch
//! modes mid-session: the TUI keeps its state and draws the "too small" notice,
//! because tearing the screen down to print a summary would destroy the
//! conversation the user is in the middle of.
//!
//! ## The output
//!
//! Plain text on stdout, no ANSI, no cursor movement, no alternate screen, and
//! no raw mode at all — the process never takes the terminal, so there is
//! nothing to restore and a crash cannot leave a user's shell broken. The exit
//! status is 0: line mode is a working answer, not a failure.

use std::fmt::Write as _;
use std::io::Write;

use crate::control_file::ControlFile;
use crate::envelope::{CliError, CliResult};
use crate::state::{parse_tui_state, TuiState};
use crate::tui::escape::sanitize;

/// Why the full-screen client cannot run here. Every variant names a terminal
/// property, so the message can tell the user what to change.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reason {
    /// `--line-mode` was passed.
    Requested,
    /// `TERM` is unset, empty, or `dumb`.
    NoScreenAddressing,
    /// The window is below the layout's minimum.
    TooSmall { width: u16, height: u16 },
}

impl std::fmt::Display for Reason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Reason::Requested => write!(f, "--line-mode was asked for"),
            Reason::NoScreenAddressing => write!(
                f,
                "this terminal reports no screen addressing (TERM is dumb or unset)"
            ),
            Reason::TooSmall { width, height } => write!(
                f,
                "the window is {width}x{height}, below the {required_w}x{required_h} the layout needs",
                required_w = crate::tui::layout::MIN_WIDTH,
                required_h = crate::tui::layout::MIN_HEIGHT,
            ),
        }
    }
}

/// The decision, as a pure function of the three things it reads.
///
/// Split out from the environment on purpose: "does this terminal get the TUI"
/// is a question with a table of cases, and a table of cases that can only be
/// checked by running the client is not a table of cases.
pub fn reason_for(term: Option<&str>, size: (u16, u16), requested: bool) -> Option<Reason> {
    if requested {
        return Some(Reason::Requested);
    }
    match term {
        None => return Some(Reason::NoScreenAddressing),
        Some(term) if term.trim().is_empty() || term == "dumb" => {
            return Some(Reason::NoScreenAddressing)
        }
        Some(_) => {}
    }
    if !crate::tui::layout::Rects::fits(size.0, size.1) {
        return Some(Reason::TooSmall {
            width: size.0,
            height: size.1,
        });
    }
    None
}

/// The decision, read off the real terminal. `None` means: draw the TUI.
pub fn fallback_reason(options: &super::Options) -> Option<Reason> {
    let term = std::env::var("TERM").ok();
    // `size()` is a `tty` ioctl; on a pipe it fails, and a failure here is not
    // a reason to leave the TUI — the caller has already refused a redirected
    // terminal long before this point.
    let size = crossterm::terminal::size().unwrap_or((u16::MAX, u16::MAX));
    reason_for(term.as_deref(), (size.0, size.1), options.line_mode)
}

/// Prints the line-mode summary for `control` and returns.
///
/// One `/state`, then one write. Nothing here watches, animates, or waits for a
/// key: this is a report, and a report that needed input would be a TUI again.
pub fn run(control: &ControlFile, options: &super::Options, reason: Reason) -> CliResult<()> {
    let deadline = std::time::Instant::now() + options.connect_timeout;
    let value = crate::client::Client::new(
        control.port,
        &control.token,
        deadline,
        crate::client::DebugLog::stderr_if(options.debug),
    )?
    .get_json(crate::commands::STATE_PATH)?;
    let state = parse_tui_state(&value)?;

    let mut out = String::new();
    render(&mut out, &state, reason, options);
    let mut stdout = std::io::stdout().lock();
    stdout
        .write_all(out.as_bytes())
        .map_err(|e| CliError::new(crate::envelope::ErrorCode::InternalError, e.to_string()))?;
    stdout
        .flush()
        .map_err(|e| CliError::new(crate::envelope::ErrorCode::InternalError, e.to_string()))
}

/// Builds the summary. A pure function of the state, so the format is testable
/// without a daemon, a terminal, or a running client.
///
/// Every field that came from the daemon goes through
/// [`crate::tui::escape::sanitize`] on its way into `out`, exactly as it does
/// on its way into a drawn cell. Line mode never takes the terminal, so it
/// emits no escape sequence of its own — which is only worth saying if the
/// bytes it *prints* cannot bring one along either. A device name arrives over
/// mDNS from another machine, so without this funnel `--line-mode` was the one
/// writer in the crate that could rename the user's window and clear their
/// screen.
pub fn render(out: &mut String, state: &TuiState, reason: Reason, options: &super::Options) {
    let self_device = &state.self_device;
    writeln!(out, "klardrop: line mode ({reason})").unwrap();
    writeln!(
        out,
        "self: {} ({}, {}) [{}]",
        sanitize(&self_device.device_name),
        sanitize(&self_device.device_type),
        sanitize(&self_device.os_type),
        sanitize(&self_device.device_id),
    )
    .unwrap();
    writeln!(out, "daemon: connected").unwrap();
    writeln!(
        out,
        "protocols: klardrop={} nearby={} ble={}",
        state.protocols.klardrop, state.protocols.nearby, state.protocols.ble,
    )
    .unwrap();
    writeln!(
        out,
        "discovery: background {}",
        if state.settings.background_discovery_enabled {
            "on"
        } else {
            "off"
        },
    )
    .unwrap();

    let devices = state.devices();
    writeln!(out, "devices: {}", devices.len()).unwrap();
    for device in &devices {
        writeln!(
            out,
            "  {} {}  {}  {}  {}",
            mark(device.reachable),
            sanitize(&device.device_name),
            sanitize(&device.device_id),
            if device.reachable {
                "reachable"
            } else {
                "unreachable"
            },
            sanitize(trust_word(&device.trust_status, device.paired)),
        )
        .unwrap();
    }
    if devices.is_empty() {
        writeln!(out, "  (none seen yet)").unwrap();
    }

    let active: Vec<String> = state
        .transfers
        .iter()
        .map(|transfer| {
            format!(
                "{} {} {}/{} bytes",
                sanitize(&transfer.file_name),
                sanitize(&transfer.phase),
                transfer.transferred_size,
                transfer.total_size
            )
        })
        .collect();
    writeln!(out, "transfers: {}", active.len()).unwrap();
    for line in active {
        writeln!(out, "  {line}").unwrap();
    }

    writeln!(
        out,
        "the interactive view needs a terminal of at least {required_w}x{required_h} with \
         cursor addressing; re-run on one for the live view",
        required_w = crate::tui::layout::MIN_WIDTH,
        required_h = crate::tui::layout::MIN_HEIGHT,
    )
    .unwrap();
    if options.motion {
        writeln!(out, "animation: off (line mode has no frames to draw)").unwrap();
    }
}

/// Reachable devices get `*`, unreachable ones `-`: the same two glyphs the
/// full-screen client uses, chosen so the two views look alike in a log.
fn mark(reachable: bool) -> char {
    if reachable {
        '*'
    } else {
        '-'
    }
}

/// The daemon's own trust word, with the paired flag as a fallback for a daemon
/// that reports neither. Never guessed: an unpaired device is `unpaired`, not
/// `untrusted`.
fn trust_word(trust_status: &str, paired: bool) -> &str {
    match trust_status {
        "" => {
            if paired {
                "paired"
            } else {
                "unpaired"
            }
        }
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::parse_tui_state;
    use serde_json::json;

    fn options(line_mode: bool) -> super::super::Options {
        super::super::Options {
            theme: crate::tui::theme::ThemeChoice::Auto,
            motion: false,
            line_mode,
            mouse: false,
            color: false,
            connect_timeout: std::time::Duration::from_secs(1),
            debug: false,
            picker: None,
        }
    }

    fn sample_state() -> TuiState {
        let body = json!({
            "version": 7,
            "self": {
                "deviceId": "host-1",
                "deviceName": "Fixture Host",
                "deviceType": "desktop",
                "osType": "linux"
            },
            "devices": [
                {
                    "deviceId": "aaaa1111",
                    "deviceName": "Fixture Phone",
                    "deviceType": "phone",
                    "paired": true,
                    "reachable": true,
                    "trustStatus": "trusted",
                    "reachability": "reachable",
                    "connectionTypes": ["klardrop"],
                    "hasUnread": true,
                    "unreadCount": 2
                },
                {
                    "deviceId": "bbbb2222",
                    "deviceName": "Fixture Watch",
                    "deviceType": "wearable",
                    "paired": false,
                    "reachable": false,
                    "trustStatus": "untrusted",
                    "reachability": "unreachable",
                    "connectionTypes": [],
                    "hasUnread": false,
                    "unreadCount": 0
                }
            ],
            "trustedIds": ["aaaa1111"],
            "protocols": { "klardrop": true, "nearby": true, "ble": false },
            "settings": {
                "backgroundDiscoveryEnabled": true,
                "supportsBackgroundDiscovery": true
            },
            "incoming": [],
            "notifications": [],
            "pairingDialog": null,
            "qrShare": { "active": false },
            "transfers": [
                {
                    "id": "tx-1",
                    "deviceId": "aaaa1111",
                    "fileName": "holiday.zip",
                    "totalSize": 100,
                    "transferredSize": 40,
                    "isSender": true,
                    "phase": "sending"
                }
            ],
            "update": { "status": "idle", "supported": false }
        });
        parse_tui_state(&body).expect("the sample state parses")
    }

    #[test]
    fn a_capable_terminal_gets_the_tui() {
        assert_eq!(reason_for(Some("xterm-256color"), (120, 40), false), None);
        assert_eq!(reason_for(Some("xterm"), (20, 8), false), None);
    }

    #[test]
    fn a_dumb_or_missing_term_is_line_mode() {
        assert_eq!(
            reason_for(Some("dumb"), (120, 40), false),
            Some(Reason::NoScreenAddressing)
        );
        assert_eq!(
            reason_for(Some(""), (120, 40), false),
            Some(Reason::NoScreenAddressing)
        );
        assert_eq!(
            reason_for(None, (120, 40), false),
            Some(Reason::NoScreenAddressing)
        );
        // A name that merely contains "dumb" is a real terminal.
        assert_eq!(reason_for(Some("dumb-term"), (120, 40), false), None);
    }

    #[test]
    fn a_window_below_the_layout_minimum_is_line_mode() {
        assert_eq!(
            reason_for(Some("xterm-256color"), (19, 40), false),
            Some(Reason::TooSmall {
                width: 19,
                height: 40
            })
        );
        assert_eq!(
            reason_for(Some("xterm-256color"), (120, 7), false),
            Some(Reason::TooSmall {
                width: 120,
                height: 7
            })
        );
    }

    #[test]
    fn asking_for_line_mode_always_wins() {
        assert_eq!(
            reason_for(Some("dumb"), (120, 40), true),
            Some(Reason::Requested),
            "an explicit request is not second-guessed, and is named as such"
        );
    }

    #[test]
    fn the_summary_names_the_device_the_daemon_and_every_device() {
        let mut out = String::new();
        render(
            &mut out,
            &sample_state(),
            Reason::NoScreenAddressing,
            &options(false),
        );
        let text = out.clone();

        assert!(text.contains("Fixture Host"), "{text}");
        assert!(text.contains("host-1"), "the self device id: {text}");
        assert!(text.contains("daemon: connected"), "{text}");
        for device in sample_state().devices() {
            assert!(
                text.contains(&device.device_name),
                "{device:?} missing from {text}"
            );
        }
        assert!(
            text.contains("reachable"),
            "reachability is spelled out: {text}"
        );
        assert!(text.contains("unreachable"), "{text}");
        assert!(text.contains("trusted"), "trust is spelled out: {text}");
        assert!(
            text.contains("holiday.zip"),
            "an in-flight transfer: {text}"
        );
        assert!(
            text.contains("cursor addressing"),
            "the user is told what the interactive view needs: {text}"
        );
    }

    /// A state whose every printed field carries a control sequence: the
    /// self device, both devices, both trust words, and the transfer's file
    /// name and phase.
    ///
    /// This is the fixture the escape-free assertions have to run against. The
    /// benign one could not fail them, because `render` never writes an escape
    /// of its own — the only way one reaches stdout is through a field.
    fn hostile_state() -> TuiState {
        const EVIL: &str = "evil\u{1b}]0;PWNED\u{7}\u{1b}[2J\u{0}\nsecond";
        let body = json!({
            "version": 7,
            "self": {
                "deviceId": EVIL,
                "deviceName": EVIL,
                "deviceType": EVIL,
                "osType": EVIL
            },
            "devices": [
                {
                    "deviceId": EVIL,
                    "deviceName": EVIL,
                    "deviceType": "phone",
                    "paired": true,
                    "reachable": true,
                    "trustStatus": EVIL,
                    "reachability": "reachable",
                    "connectionTypes": ["klardrop"],
                    "hasUnread": true,
                    "unreadCount": 2
                },
                {
                    "deviceId": "bbbb2222",
                    "deviceName": EVIL,
                    "deviceType": "wearable",
                    "paired": false,
                    "reachable": false,
                    "trustStatus": EVIL,
                    "reachability": "unreachable",
                    "connectionTypes": [],
                    "hasUnread": false,
                    "unreadCount": 0
                }
            ],
            "trustedIds": ["aaaa1111"],
            "protocols": { "klardrop": true, "nearby": true, "ble": false },
            "settings": {
                "backgroundDiscoveryEnabled": true,
                "supportsBackgroundDiscovery": true
            },
            "incoming": [],
            "notifications": [],
            "pairingDialog": null,
            "qrShare": { "active": false },
            "transfers": [
                {
                    "id": "tx-1",
                    "deviceId": "aaaa1111",
                    "fileName": EVIL,
                    "totalSize": 100,
                    "transferredSize": 40,
                    "isSender": true,
                    "phase": EVIL
                }
            ],
            "update": { "status": "idle", "supported": false }
        });
        parse_tui_state(&body).expect("the hostile state parses")
    }

    #[test]
    fn the_summary_is_never_an_escape_sequence() {
        let mut out = String::new();
        render(
            &mut out,
            &hostile_state(),
            Reason::Requested,
            &options(true),
        );
        for byte in ['\x1b', '\u{7}', '\u{0}'] {
            assert!(
                !out.contains(byte),
                "a hostile field reached stdout as {byte:?}: {out:?}"
            );
        }
        // Sanitized is not the same as silent: the words survive, so the row
        // still says who the device claims to be.
        assert!(out.contains("evil"), "the name is still readable: {out:?}");
    }

    #[test]
    fn a_daemon_with_no_devices_says_so_instead_of_printing_nothing() {
        // A daemon that has seen nothing yet still has to produce a summary,
        // so the empty branch is parsed from a real body rather than mocked.
        let raw = json!({
            "version": 1,
            "self": { "deviceId": "h", "deviceName": "H", "deviceType": "desktop", "osType": "linux" },
            "devices": [],
            "trustedIds": [],
            "protocols": { "klardrop": true, "nearby": false, "ble": false },
            "settings": { "backgroundDiscoveryEnabled": false, "supportsBackgroundDiscovery": true },
            "incoming": [],
            "notifications": [],
            "pairingDialog": null,
            "qrShare": { "active": false },
            "transfers": [],
            "update": { "status": "idle", "supported": false }
        });
        let empty = parse_tui_state(&raw).expect("parses");
        assert!(empty.devices().is_empty());
        let mut out = String::new();
        render(
            &mut out,
            &empty,
            Reason::NoScreenAddressing,
            &options(false),
        );
        assert!(out.contains("devices: 0"), "{out}");
        assert!(out.contains("(none seen yet)"), "{out}");
        assert!(
            out.ends_with('\n'),
            "the summary ends with a newline: {out:?}"
        );
    }

    #[test]
    fn an_unpaired_device_is_never_called_trusted() {
        // `trust_word` is the whole decision, so assert it directly: the word
        // printed is the daemon's own, with the paired flag only as a fallback
        // for a daemon that reports no trust status at all.
        assert_eq!(trust_word("", true), "paired");
        assert_eq!(trust_word("", false), "unpaired");
        assert_eq!(trust_word("trusted", false), "trusted");
        assert_eq!(trust_word("untrusted", true), "untrusted");
    }
}
