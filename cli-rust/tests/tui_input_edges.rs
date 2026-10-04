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

/// Which device the selection marker currently sits in front of.
///
/// Counted as an occurrence rather than as a line, deliberately. The pty master
/// only carries the client's own bytes on unix; on Windows it carries the
/// terminal's rendering, so the reconstructed screen there is a handful of very
/// long rows with every device row concatenated into them. The marker still sits
/// immediately in front of the selected device exactly once either way, which is
/// what this reads.
fn selected(screen: &str) -> String {
    const MARKER: &str = "\u{2502}> ";
    let marked = screen.matches(MARKER).count();
    assert_eq!(marked, 1, "expected one selected row in:\n{screen}");
    let at = screen.find(MARKER).expect("exactly one marker");
    screen[at..].chars().take(96).collect()
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
    // Mouse capture on, so this is the configuration a user actually has. Note that
    // `mouse: false` would NOT make this vacuous: `mouse_actions` in `tui/mod.rs`
    // answers any `Event::Mouse` the client receives, independently of whether the
    // guard took the mouse, so a click injected as bytes is parsed either way. What
    // makes the test meaningful is the assertion below, not the flag.
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

    let before = selected(&tui.text());
    assert!(
        before.contains("Fixture Phone"),
        "the first device starts selected: {before}"
    );

    // Walk down the device rows clicking each one, and take the first click that
    // moves the selection. The row is found by clicking rather than computed from
    // the screen, because the row a device is drawn on is not recoverable from the
    // pty on every platform: on Windows the master carries ConPTY's rendering, which
    // collapses the device rows into a couple of very long ones (measured: 3 rows on
    // windows, 40 on linux/macos for the same frame). Deriving a row number from that
    // would click the wrong line and fail for a reason that has nothing to do with
    // the mouse handling.
    //
    // What this still proves is the whole contract: with mouse capture on, an SGR
    // click reaches the client, is parsed, and moves the selection onto the device
    // under the pointer — which is a different device from the one selected at
    // startup. That the selection then tracks the list is `tui_flows.rs`'s
    // `the_selection_moves_down_and_up_the_device_list`.
    let deadline = Instant::now() + Duration::from_secs(20);
    let mut clicked: Option<(u16, String)> = None;
    for row in 2..=ROWS {
        tui.send_raw(&sgr_click(4, row));
        // The click is asynchronous: the bytes have to reach the app's poll loop
        // before the next draw. Polling is what a person does — one immediate read
        // would race the client — but with a short per-row settle, because a row that
        // does not land on a device must not spend the whole scan budget waiting.
        let settle = Instant::now() + Duration::from_millis(750);
        loop {
            let selection = selected(&tui.text());
            if !selection.contains("Fixture Phone") {
                clicked = Some((row, selection));
                break;
            }
            // Only the scan as a whole is bounded by `deadline`.
            if Instant::now() >= settle.min(deadline) {
                break;
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        if clicked.is_some() || Instant::now() >= deadline {
            break;
        }
    }
    let (row, after) = clicked.unwrap_or_else(|| {
        panic!("no click in the device pane moved the selection off Fixture Phone")
    });
    assert!(
        after.contains("Fixture"),
        "the click on row {row} must select the device it landed on, got: {after}"
    );
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
