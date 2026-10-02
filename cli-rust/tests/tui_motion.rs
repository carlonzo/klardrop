//! Motion at the terminal, on a real pseudo terminal.
//!
//! [`motion`](klardrop) has unit tests for the clock, the spinner and the
//! transfer bar, and they are worth having — but they prove the arithmetic, not
//! the claim. The claim is that a frame *changes on a terminal*, and that when
//! the daemon stops saying something is happening it *stops*. Neither is
//! visible from a unit test, so both are asserted here, from the screen.
//!
//! Every test uses the same shape: read the screen, wait long enough for
//! several animation frames to have passed, read it again, and compare. What is
//! compared is the decoded grid, so an assertion can only pass if the bytes on
//! the wire really changed.
//!
//! See [`support`] for the harness.

mod support;

use std::time::Duration;

use support::*;

/// Enough wall time for several frames at the module's 10 fps cap, and for the
/// transfer's own percentage to have moved — so "it did not change" is a
/// statement about a settled indicator and not about looking too quickly.
const GAP: Duration = Duration::from_millis(700);

/// A capable terminal with motion on, and colour off so the frames are a pure
/// function of the daemon's state.
fn moving() -> LaunchOptions {
    LaunchOptions {
        motion: true,
        mouse: false,
        color: false,
        ..LaunchOptions::default()
    }
}

/// The same terminal with `--no-motion`.
fn still() -> LaunchOptions {
    LaunchOptions {
        motion: false,
        ..moving()
    }
}

/// The first glyph of the header row, which is the spinner while the daemon is
/// still answering and nothing at all once it has.
fn header_glyph(screen: &[String]) -> String {
    screen
        .first()
        .map(|row| row.trim_start())
        .and_then(|row| row.split_whitespace().next())
        .unwrap_or_default()
        .to_string()
}

/// The footer row carrying a transfer, if one is on screen.
fn transfer_row(screen: &[String]) -> Option<String> {
    screen
        .iter()
        .find(|row| row.contains("holiday.zip"))
        .cloned()
}

#[test]
fn the_spinner_turns_while_the_daemon_is_still_answering() {
    // `state_long_poll` holds `/state` open, which is what keeps the client in
    // the connecting state long enough to watch.
    let fixture = Fixture::start("motion-spinner", &["state_long_poll"]);
    let tui = Tui::launch_with(&fixture, &moving());
    tui.wait_for("connecting");

    let first = tui.screen();
    std::thread::sleep(GAP);
    let second = tui.screen();
    capture(&tui, "motion-spinner-turning");

    assert_ne!(
        first, second,
        "two captures of a connecting client must not be identical"
    );
    assert_ne!(
        header_glyph(&first),
        header_glyph(&second),
        "the header glyph is the spinner, and it has to change"
    );
    assert_eq!(
        first.len(),
        second.len(),
        "only characters should change, not the shape of the screen"
    );
    // Only the header moves. An animation that repainted the whole screen
    // would be a redraw, not an animation, and would cost a thousand times
    // more for the same picture.
    for (index, (before, after)) in first.iter().zip(second.iter()).enumerate() {
        if before == after {
            continue;
        }
        eprintln!("row {index} changed: {before:?} -> {after:?}");
        assert!(
            after.contains("connecting"),
            "only the spinner line may change, but row {index} went {before:?} -> {after:?}"
        );
    }
    assert!(
        second.join("\n").contains("connecting"),
        "and the client was still connecting at the end of it"
    );
}

#[test]
fn no_motion_draws_the_same_frame_twice_while_waiting() {
    let fixture = Fixture::start("motion-static", &["state_long_poll"]);
    let tui = Tui::launch_with(&fixture, &still());
    tui.wait_for("connecting");

    let first = tui.screen();
    std::thread::sleep(GAP);
    let second = tui.screen();
    capture(&tui, "motion-disabled-static");

    assert_eq!(
        first,
        second,
        "--no-motion must not change a frame while it is waiting; the difference was:\n{}",
        second.join("\n")
    );
    // Still the same truthful picture: an animation removed is not a state
    // removed, and the words are still the daemon's.
    assert!(
        second.join("\n").contains("connecting"),
        "{}",
        second.join("\n")
    );
}

#[test]
fn a_transfer_indicator_moves_while_it_is_running_and_stops_when_it_is_not() {
    // `transfer_settles` is in flight for a few seconds and then reports
    // `completed`, which is the only way to watch both halves of this.
    let fixture = Fixture::start("motion-transfer", &["transfer_settles"]);
    let tui = Tui::launch_with(&fixture, &moving());
    tui.wait_for("receiving");

    let running_first = transfer_row(&tui.screen());
    std::thread::sleep(GAP);
    let running_second = transfer_row(&tui.screen());
    capture(&tui, "motion-transfer-moving");

    let running_first = running_first.expect("the footer shows the transfer");
    let running_second = running_second.expect("the footer still shows it");
    assert!(running_first.contains("receiving"), "{running_first}");
    assert_ne!(
        running_first, running_second,
        "an in-flight transfer has to move: {running_first:?} then {running_second:?}"
    );

    // The daemon has now finished it. The indicator must go still, and the
    // daemon's own words must stay exactly where they were.
    tui.wait_for("completed");
    let settled_first = transfer_row(&tui.screen()).expect("the transfer row");
    std::thread::sleep(GAP);
    let settled_second = transfer_row(&tui.screen()).expect("the transfer row");
    capture(&tui, "motion-transfer-settled");

    assert_eq!(
        settled_first, settled_second,
        "a settled transfer must stop moving: {settled_first:?} then {settled_second:?}"
    );
    assert!(
        settled_second.contains("holiday.zip completed 100%"),
        "the daemon's own words are what stays on screen, got {settled_second:?}"
    );
}

#[test]
fn quitting_after_an_animation_still_gives_the_terminal_back() {
    let fixture = Fixture::start("motion-restore", &["state_long_poll"]);
    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            // So the restored line discipline is still readable after the
            // client's own descriptors are gone.
            keep_slave: true,
            ..moving()
        },
    );
    // Let the spinner actually turn, so the terminal is demonstrably in use
    // when it is handed back.
    tui.wait_for("connecting");
    std::thread::sleep(GAP);

    let while_running = tui.raw_mode_furniture();
    assert!(
        !while_running.is_empty(),
        "the client had not taken the terminal at all, so there is nothing to give back"
    );

    tui.send("q");
    assert_eq!(tui.wait_for_exit(), 0, "q quits the client");
    assert_eq!(
        tui.raw_mode_furniture(),
        while_running,
        "an animation is not an excuse to leave the terminal taken"
    );
    assert!(
        tui.echoes_input(),
        "cooked mode did not come back with the client"
    );
}
