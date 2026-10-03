//! Terminal input-edge coverage on a real pseudo terminal.
//!
//! The flow tests in `tui_flows.rs` cover what a person does; this file covers the
//! edges the plan names explicitly — cancelling an interactive workflow, a mouse
//! click, a terminal resize, and a control character that carries no meaning —
//! because each one has a way to fail that no non-PTY test can see: the client
//! has to hold the terminal, draw, and hand it back.
//!
//! See [`support`] for the harness.

mod support;

use std::fs;
use std::time::{Duration, Instant};

use support::*;

/// An SGR mouse press/release pair at a 1-based terminal position.
fn sgr_click(column: u16, row: u16) -> Vec<u8> {
    let press = format!("\x1b[<0;{column};{row}M");
    let release = format!("\x1b[<0;{column};{row}m");
    [press, release].concat().into_bytes()
}

/// The 1-based terminal row the device named `needle` is drawn on.
///
/// Read off the screen rather than hard-coded: the row moves whenever the header
/// or the pane's chrome changes, and a test that hard-codes it would keep passing
/// for the wrong reason.
fn row_of(screen: &[String], needle: &str) -> u16 {
    let index = screen
        .iter()
        .position(|line| line.contains(needle))
        .unwrap_or_else(|| panic!("{needle} is not on the screen:\n{}", screen.join("\n")));
    u16::try_from(index + 1).expect("screen fits a terminal")
}

/// Which device row currently carries the selection marker.
fn selected(screen: &[String]) -> String {
    screen
        .iter()
        .filter_map(|line| {
            let trimmed = line.trim_start_matches(['│', '|']);
            trimmed.starts_with('>').then_some(trimmed)
        })
        .next()
        .unwrap_or_else(|| panic!("no selected row:\n{}", screen.join("\n")))
        .to_string()
}

#[test]
fn cancelling_the_picker_exits_130_and_says_so_in_json() {
    // The plan gives cancellation its own exit code so an agent can tell "the
    // user changed their mind" from "the transfer failed". It is also the one
    // path where a `--json` caller used to get *nothing at all* on stdout: the
    // cancel used to print a suppressed human line and exit 0.
    let fixture = Fixture::start("picker-cancel", &[]);
    let payload = fixture.dir().join("report.pdf");
    fs::write(&payload, b"a report").expect("write payload");

    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            subcommand: vec![
                "share".into(),
                "--pick".into(),
                "--json".into(),
                payload.to_string_lossy().into_owned(),
            ],
            display_flags: false,
            keep_slave: true,
            ..LaunchOptions::default()
        },
    );
    tui.wait_for("Fixture Phone");

    // Escape backs out of the picker without choosing anything.
    tui.send("\x1b");
    assert_eq!(
        tui.wait_for_exit(),
        130,
        "cancelling an interactive workflow is 130, not success"
    );

    let screen = tui.text();
    assert!(
        screen.contains("\"cancelled\""),
        "the JSON envelope must say why nothing was sent:\n{screen}"
    );
    assert!(
        !screen.contains("\"ok\":true"),
        "a cancelled share is not a success:\n{screen}"
    );
}

#[test]
fn a_mouse_click_selects_the_device_under_the_pointer() {
    // Mouse capture on: with `--no-mouse` the terminal is never asked to report
    // anything, so the client never parses a click at all and this test would
    // pass for the wrong reason.
    let fixture = Fixture::start("mouse-click", &[]);
    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            mouse: true,
            ..LaunchOptions::default()
        },
    );
    tui.wait_for("Fixture Phone");
    tui.wait_for("Fixture Laptop");

    let before = selected(&tui.screen());
    assert!(
        before.contains("Fixture Phone"),
        "the first device starts selected: {before}"
    );

    let row = row_of(&tui.screen(), "Fixture Laptop");
    tui.send_raw(&sgr_click(4, row));

    // The click is asynchronous: the bytes have to reach the app's poll loop
    // before the next draw. Polling with a deadline is what a person does; one
    // immediate read would race the client and fail intermittently.
    let deadline = Instant::now() + Duration::from_secs(5);
    let after = loop {
        let selection = selected(&tui.screen());
        if selection.contains("Fixture Laptop") {
            break selection;
        }
        assert!(
            Instant::now() < deadline,
            "the click must select the row it landed on, got: {selection}"
        );
        std::thread::sleep(Duration::from_millis(25));
    };
    assert!(after.contains("Fixture Laptop"), "{after}");
}

#[test]
fn resizing_the_terminal_redraws_to_fit_the_new_size() {
    let fixture = Fixture::start("resize", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Laptop");

    // The harness parses a fixed geometry, so the terminal is told about the new
    // size directly and the client is given the same SIGWINCH a real resize
    // produces.
    //
    // What is asserted is the LAYOUT, not how much was drawn. The previous
    // version checked `screen.len() <= 14` and `line.chars().count() <= 60`,
    // both guaranteed by the harness itself (it re-geometries its own VT
    // parser), so a client that ignored the resize completely passed them — as
    // did a client that had died and left a blank screen.
    //
    // The observable that actually moves is the pane count: `TWO_PANE_MIN_WIDTH`
    // is 80, so below it the renderer must fall back to the single-pane layout.
    // Counting box tops is a pure function of the drawn frame.

    // Wide enough for two panes.
    tui.resize(100, 30);
    let wide = wait_for_layout(&tui, 2, "resize-100x30");
    assert!(
        wide.contains("Conversation"),
        "at 100 columns the right pane must be drawn:\n{wide}"
    );
    assert!(!tui.has_exited(), "the client died on resize:\n{wide}");

    // Below the threshold: one pane, and the device list is what survives.
    tui.resize(60, 14);
    let narrow = wait_for_layout(&tui, 1, "resize-60x14");
    assert!(
        narrow.contains("Fixture Phone"),
        "the single-pane layout must still show the devices:\n{narrow}"
    );
    assert!(
        !narrow.contains("Conversation"),
        "below TWO_PANE_MIN_WIDTH there is no second pane to draw:\n{narrow}"
    );
    assert!(!tui.has_exited(), "the client died on resize:\n{narrow}");

    // And growing back must bring the second pane back. A client that latched
    // into single-pane mode and never left would satisfy both checks above.
    tui.resize(100, 30);
    let back = wait_for_layout(&tui, 2, "resize-back-to-100x30");
    assert!(
        back.contains("Conversation"),
        "growing back must restore the two-pane layout:\n{back}"
    );
    assert!(!tui.has_exited(), "the client died on resize:\n{back}");
}

/// Waits until the drawn frame has exactly `boxes` box tops, and returns it.
///
/// A count rather than a substring, because "the border is somewhere" is what a
/// clipped or stale frame also satisfies; the pane count is the thing that
/// actually changes when the geometry does.
fn wait_for_layout(tui: &Tui, boxes: usize, frame: &str) -> String {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let text = tui.text();
        if text.matches('┌').count() == boxes {
            tui.dump(frame);
            return text;
        }
        assert!(
            !tui.has_exited(),
            "the client died before {boxes} pane(s):\n{text}"
        );
        assert!(
            Instant::now() < deadline,
            "the frame never reached {boxes} pane(s); last screen was:\n{text}"
        );
        std::thread::sleep(Duration::from_millis(25));
    }
}

#[test]
fn ctrl_d_does_not_break_the_session_or_the_terminal() {
    // Ctrl-D is a byte in raw mode, not an end-of-file: whatever the client
    // decides it means, it must neither panic nor wedge the draw loop, and the
    // terminal it holds must still be handed back on the way out.
    let fixture = Fixture::start("ctrl-d", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Phone");

    tui.send("\x04");
    tui.send(CTRL_C);
    assert_eq!(tui.wait_for_exit(), 0);

    let screen = tui.text();
    assert!(
        !screen.contains("Fixture Phone"),
        "the alternate screen must be left behind on exit:\n{screen}"
    );
    // The alternate screen has to be handed back, and the only place that can be
    // observed is the client's own byte stream: the decoded screen is whatever
    // the terminal did with those bytes, not what the client sent.
    let raw = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert!(
        raw.contains("\u{1b}[?1049h"),
        "the client must have taken the alternate screen at all:\n{raw}"
    );
    assert!(
        raw.contains("\u{1b}[?1049l"),
        "the client must restore the previous screen before exiting:\n{raw}"
    );
}
