//! Line mode: what happens when the terminal cannot host a screen.
//!
//! Two directions have to be proven, and neither is implied by the other:
//!
//! * a `dumb` terminal (or a window below the layout minimum) gets the daemon
//!   state as **lines and nothing else** — not one escape byte, no raw mode,
//!   no alternate screen, exit 0;
//! * a capable terminal still gets the full-screen client. A fallback that
//!   quietly became the only path would pass every test above and be a bug.

mod support;

use support::{strip_escapes, Fixture, LaunchOptions, Tui};

/// The bytes that mean "the client took the screen".
const ALT_SCREEN_ON: &str = "\x1b[?1049h";
/// The bytes that mean "the client took the keyboard".
const RAW_MODE_PROBE: &str = "\x1b[?2004h";

fn line_mode_options(term: &str) -> LaunchOptions {
    LaunchOptions {
        term: term.to_string(),
        // The pty has to outlive the client for the restored line discipline
        // to still be readable.
        keep_slave: true,
        ..LaunchOptions::default()
    }
}

fn assert_no_escape_bytes(tui: &Tui) {
    // The pty master carries the client's own bytes, so an ESC byte in that
    // stream is the client touching the screen.
    let raw = tui.raw();
    assert!(
        !raw.contains(&0x1b),
        "line mode wrote an escape byte; output was:\n{}",
        String::from_utf8_lossy(&raw)
    );
    assert!(
        !String::from_utf8_lossy(&raw).contains(ALT_SCREEN_ON),
        "line mode entered the alternate screen"
    );
    assert!(
        !String::from_utf8_lossy(&raw).contains(RAW_MODE_PROBE),
        "line mode enabled bracketed paste, which is raw-mode furniture"
    );
}

#[test]
fn a_dumb_terminal_prints_the_state_and_never_touches_the_screen() {
    let fixture = Fixture::start("line-mode-dumb", &[]);
    let mut tui = Tui::launch_with(&fixture, &line_mode_options("dumb"));

    let code = tui.wait_for_exit();
    assert_eq!(code, 0, "line mode is a working answer, not a failure");

    assert_no_escape_bytes(&tui);
    let text = strip_escapes(&tui.raw());
    assert!(text.contains("line mode"), "{text}");
    assert!(text.contains("dumb"), "the reason is named: {text}");
    assert!(text.contains("self:"), "the self device: {text}");
    assert!(text.contains("daemon: connected"), "{text}");
    for name in ["Fixture Phone", "Fixture Tablet", "Fixture Laptop"] {
        assert!(
            text.contains(name),
            "{name} missing from the summary:\n{text}"
        );
    }
    assert!(
        text.contains("reachable") && text.contains("unreachable"),
        "{text}"
    );
    assert!(
        text.contains("trusted") && text.contains("untrusted"),
        "{text}"
    );

    // The summary is a deliverable in its own right, so it is kept next to
    // the other captured frames rather than only asserted on.
    tui.dump("line-mode-dumb-terminal");

    // `None` = the pty refused the probe outright (macOS), which says nothing about
    // the client; `Some(false)` = it echoed nothing, which does.
    assert_ne!(
        tui.echoes_input(),
        Some(false),
        "the pty was never switched out of cooked mode, so there is nothing to restore"
    );
}

#[test]
fn a_window_below_the_layout_minimum_gets_lines_too() {
    let fixture = Fixture::start("line-mode-small", &[]);
    let options = line_mode_options("xterm-256color");
    // Below `layout::MIN_WIDTH` x `MIN_HEIGHT`, so the layout has nothing to
    // lay out and the TUI would only be able to draw its "too small" notice.
    let mut tui = Tui::launch_sized_with(&fixture, 10, 4, &options);

    assert_eq!(tui.wait_for_exit(), 0);
    assert_no_escape_bytes(&tui);
    let text = strip_escapes(&tui.raw());
    assert!(text.contains("line mode"), "{text}");
    assert!(text.contains("10x4"), "the size is named: {text}");
    // Deliberately no device-name assertion here. At ten columns the summary wraps
    // mid-word, so the name is not contiguous in the stream. This is not lost
    // coverage: a_dumb_terminal_prints_the_state_and_never_touches_the_screen
    // asserts every device name at a width where nothing wraps. What is specific
    // to a narrow window is that line mode is chosen at all and that it names
    // the size it was given — which is what the two assertions above check.
}

#[test]
fn an_explicit_line_mode_asks_for_lines_on_a_capable_terminal() {
    let fixture = Fixture::start("line-mode-flag", &[]);
    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            extra: vec!["--line-mode".to_string()],
            ..line_mode_options("xterm-256color")
        },
    );

    assert_eq!(tui.wait_for_exit(), 0);
    assert_no_escape_bytes(&tui);
    let text = strip_escapes(&tui.raw());
    assert!(
        text.contains("--line-mode"),
        "the reason is the request: {text}"
    );
    assert!(text.contains("Fixture Phone"), "{text}");
}

#[test]
fn a_capable_terminal_still_gets_the_full_screen_client() {
    let fixture = Fixture::start("line-mode-capable", &[]);
    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            // So the restored line discipline is still readable after the
            // client's own descriptors are gone.
            keep_slave: true,
            ..LaunchOptions::default()
        },
    );
    tui.wait_for("Fixture Phone");

    let raw = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert!(
        raw.contains(ALT_SCREEN_ON),
        "a capable terminal must still get the alternate screen; the fallback is not the default"
    );
    // And the state it took is given back: the client owns raw mode only while
    // it is running, and this is the same pty the line-mode test above asserts
    // was never touched at all. Both halves matter — the furniture must not
    // grow, and the pty has to be typing again.
    let while_running = tui.raw_mode_furniture();
    tui.send("q");
    assert_eq!(tui.wait_for_exit(), 0, "q quits the client");
    assert_eq!(
        tui.raw_mode_furniture(),
        while_running,
        "the client took more of the terminal than it gave back"
    );
    assert_ne!(
        tui.echoes_input(),
        Some(false),
        "cooked mode did not come back with the client"
    );
}

/// The same guarantee against a daemon that means it: every name it reports —
/// this host's own, the device's, and a transfer's file — carries an `SGR`
/// sequence, an `OSC` that retitles the window, a bell and a `NUL`.
///
/// The word `evil` is the user's data and has to survive; the four control
/// characters are an attack on the terminal and have to be gone. Asserting
/// only the first would pass on a client that stripped the name entirely, and
/// asserting only the second would pass on one that threw the conversation
/// away.
#[test]
fn a_hostile_name_cannot_reach_the_terminal_through_line_mode() {
    let fixture = Fixture::start("line-mode-hostile", &["hostile_names"]);
    let mut tui = Tui::launch_with(&fixture, &line_mode_options("dumb"));

    let code = tui.wait_for_exit();
    assert_eq!(code, 0, "line mode is a working answer, not a failure");

    assert_no_escape_bytes(&tui);
    // The user's data survives and the control bytes do not, asserted on both
    // the screen and the wire: the pty master carries the client's own bytes.
    let screen = tui.text();
    for (what, byte) in [("a bell", 0x07u8), ("a NUL", 0x00)] {
        assert!(
            !screen.as_bytes().contains(&byte),
            "{what} reached the screen; output was:\n{screen}"
        );
    }
    let raw = tui.raw();
    assert!(
        !raw.contains(&0x07) && !raw.contains(&0x00),
        "a control byte reached the terminal; output was:\n{}",
        String::from_utf8_lossy(&raw)
    );
    let text = String::from_utf8_lossy(&raw).into_owned();
    assert!(
        text.contains("evil"),
        "the name is still readable, which is the other half of the contract: {text}"
    );
    tui.dump("line-mode-hostile-names");
}
