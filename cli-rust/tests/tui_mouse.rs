//! The selection bypass: `Ctrl-Space` hands the mouse back to the terminal.
//!
//! While the TUI holds the mouse the terminal never lets its own selection
//! start, so a user who wants to copy a line out of the device list has no way
//! to begin one. The bypass has to be real — the capture state has to actually
//! flip, in the terminal and not only in the app's own copy of it — and it has
//! to stay shut when the user asked for no mouse at all.

mod support;

use support::{Fixture, LaunchOptions, Tui};

/// The enable/disable sequence crossterm writes for mouse capture. The `1000`
/// mode is the first of the three it turns on, so counting it counts the whole
/// change.
const CAPTURE_ON: &str = "\x1b[?1000h";
const CAPTURE_OFF: &str = "\x1b[?1000l";

/// What a terminal sends for `Ctrl-Space`: one NUL byte, and nothing else.
const CTRL_SPACE: &str = "\x00";

fn occurrences(haystack: &str, needle: &str) -> usize {
    haystack.matches(needle).count()
}

fn tui_with_mouse(mouse: bool) -> LaunchOptions {
    LaunchOptions {
        mouse,
        ..LaunchOptions::default()
    }
}

#[test]
fn ctrl_space_releases_the_mouse_and_takes_it_back() {
    let fixture = Fixture::start("mouse-bypass", &[]);
    let mut tui = Tui::launch_with(&fixture, &tui_with_mouse(true));
    tui.wait_for("Fixture Phone");

    let before = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert_eq!(
        occurrences(&before, CAPTURE_ON),
        1,
        "the TUI took the mouse once at startup"
    );
    assert_eq!(
        occurrences(&before, CAPTURE_OFF),
        0,
        "and gave it back zero times"
    );

    // Released: the terminal owns the mouse, and the screen says so, because
    // the footer is the only place a user can find the key that undoes this.
    tui.send(CTRL_SPACE);
    tui.wait_for("mouse released");
    let released = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert_eq!(
        occurrences(&released, CAPTURE_OFF),
        1,
        "Ctrl-Space must write the real disable sequence"
    );

    // Taken back: the same key, the same direction, and the terminal is
    // capturing again — so the bypass is a toggle, not a one-way trip.
    tui.send(CTRL_SPACE);
    tui.wait_for("the TUI has the mouse again");
    let retaken = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert_eq!(
        occurrences(&retaken, CAPTURE_ON),
        2,
        "the second Ctrl-Space must re-enable capture"
    );

    // And the capture it finally holds is still given back on the way out.
    tui.send("q");
    assert_eq!(tui.wait_for_exit(), 0);
    let after = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert!(
        occurrences(&after, CAPTURE_OFF) >= 2,
        "quitting must release a capture that is still held"
    );
}

#[test]
fn no_mouse_means_ctrl_space_can_never_start_capturing() {
    let fixture = Fixture::start("mouse-off", &[]);
    let mut tui = Tui::launch_with(&fixture, &tui_with_mouse(false));
    tui.wait_for("Fixture Phone");
    // The footer says the same thing before any key is pressed. A session that
    // never took a mouse must not read as one that let it go, and must not
    // keep advertising the key that would take it back.
    let idle = tui.wait_for("no mouse").join("\n");
    assert!(
        !idle.contains("mouse released"),
        "the idle footer describes a capture that never happened:\n{idle}"
    );

    let before = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert_eq!(
        occurrences(&before, CAPTURE_ON),
        0,
        "--no-mouse must not capture at startup"
    );

    tui.send(CTRL_SPACE);
    // The truth, not the old wording. This session never took a mouse, so
    // "mouse released" would describe a capture that did not happen, and
    // "Ctrl-Space takes it back" would promise a key that cannot work.
    tui.wait_for("captures no mouse");
    let after = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert_eq!(
        occurrences(&after, CAPTURE_ON),
        0,
        "Ctrl-Space must not start capturing for a session that was told not to"
    );
    assert_eq!(
        occurrences(&after, CAPTURE_OFF),
        0,
        "and must not emit a disable for a capture that was never taken"
    );
    // And it says plainly that the key cannot help, rather than offering it:
    // the user pressed the documented bypass and needs to be told it does not
    // apply here.
    let screen = tui.text();
    assert!(
        screen.contains("Ctrl-Space will not take one"),
        "the answer must deny the bypass outright: {screen}"
    );
    assert!(
        !screen.contains("mouse released"),
        "nothing on screen may claim a capture was released: {screen}"
    );
    tui.dump("mouse-off-ctrl-space");
}

/// The help overlay documents the bypass, so a user can find it without
/// guessing: the key is listed with what it does.
#[test]
fn the_help_overlay_documents_the_bypass() {
    let fixture = Fixture::start("mouse-help", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Phone");
    tui.send("?");

    let rows = tui.wait_for("Ctrl-Space");
    let text = rows.join("\n");
    assert!(text.contains("Ctrl-Space"), "{text}");
    assert!(
        text.contains("select") || text.contains("copy"),
        "the help says what the key is for: {text}"
    );
    tui.dump("mouse-released-help");
}
