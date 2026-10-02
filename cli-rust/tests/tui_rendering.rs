//! What the panes actually draw, against a daemon that makes them work hard.
//!
//! Two renderer guarantees cannot be checked from inside the process, because
//! both are about *budgeting*: a device row is a fixed set of columns and the
//! name is what gives way, and a conversation is a stream far longer than the
//! pane that shows it. A unit test can see the buffer; only a real terminal of
//! a known size can say what the user was actually shown.
//!
//! See [`support`] for the harness.

mod support;

use support::*;

/// The device row as the renderer builds it: selection, reachability, trust, the
/// name, then the same two facts in words. A name is shortened before a status
/// word is, because a shortened name is still a name and a clipped `unreachabl`
/// is not still the word "unreachable".
#[test]
fn a_wide_device_name_gives_way_before_the_words_beside_it() {
    let fixture = Fixture::start("device-row-wide", &["wide_device_names"]);
    let tui = Tui::launch(&fixture);

    // The CJK device is the second row: unreachable and untrusted, which is two
    // facts the user cannot afford to lose to a name.
    let rows = tui.wait_for("介于");
    let row = rows
        .iter()
        .find(|row| row.contains("介于"))
        .unwrap_or_else(|| panic!("the CJK device row is missing:\n{}", rows.join("\n")));

    // The renderer spells unreachable as `offline`, and that is the word that
    // has to be readable: a clipped `offlin` says nothing about whether this
    // device can be reached at all.
    assert!(
        row.contains("offline"),
        "the reachability word must survive the row's budgeting: {row:?}"
    );
    assert!(
        row.contains("untrusted"),
        "the trust word must survive the row's budgeting: {row:?}"
    );
    // And whole. A name that does not fit ends in the ellipsis, so the status
    // is the row's ending rather than something cut short before it: both
    // words and the separator between them survive together.
    assert!(
        row.contains("offline · untrusted"),
        "the status is clipped mid-word: {row:?}"
    );
    tui.dump("device-row-wide-name");
}

/// A conversation far longer than the pane, and every message wider than it.
///
/// The newest message has to arrive, and the window has to move: a pane that
/// drew the whole history would still show the newest message, and a pane that
/// drew only the oldest would fail on the first assertion. Only the window
/// satisfies both.
#[test]
fn a_long_conversation_shows_the_newest_message_and_scrolls_back() {
    let fixture = Fixture::start("conversation-long", &["long_history"]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Phone");

    tui.send(ENTER);
    let rows = tui.wait_for("message 500");
    let text = rows.join("\n");
    // A row's worth of the message, not the whole sentence: the sentence wraps
    // across two display rows, which is exactly what a window measured in
    // messages would have pushed below the bottom of the pane.
    assert!(
        text.contains("deliberately wider than any"),
        "the newest message is drawn, wrapped: {text}"
    );
    assert!(
        !text.contains("message 451"),
        "the oldest message of the page is not on screen: {text}"
    );
    tui.dump("conversation-newest");
    // Scrolling walks the window up over the same history, in display rows, so
    // a message that wraps is walked through rather than skipped whole.
    let before = tui.text();
    tui.send(PAGE_UP);
    let after_rows = tui.wait_until_gone("message 500");

    // `assert_ne!(before, after)` is what this test used to check, and it is
    // satisfiable by a blank screen: a client that panicked on the scroll and
    // died left an empty terminal, which is different from `before` and passed.
    // Two things must therefore hold — the client is still running, and an
    // EARLIER message is now on screen. Together they cannot be satisfied by a
    // crash.
    assert!(
        !tui.has_exited(),
        "the client died while scrolling; screen was:\n{}",
        after_rows.join("\n")
    );
    let after = after_rows.join("\n");
    assert_ne!(
        before, after,
        "the screen did not move, so the window did not either"
    );
    assert!(
        !after.trim().is_empty(),
        "the pane is blank after scrolling, which is what a dead client leaves:\n{after}"
    );
    assert!(
        after.contains("message 4") && !after.contains("message 500"),
        "scrolling back must show an EARLIER message, not an empty or unchanged pane:\n{after}"
    );
    tui.dump("conversation-scrolled");
}

/// The cheapest possible guard on the underflow that made the test above pass
/// while the client died: scroll with **no transfer in flight**, which is the
/// configuration that panicked. `tail` is empty unless a transfer is active, so
/// any scroll ≥ 1 used to underflow in `conversation_window`.
#[test]
fn scrolling_with_no_transfer_in_flight_does_not_kill_the_client() {
    let fixture = Fixture::start("conversation-scroll-idle", &["long_history"]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Phone");
    tui.send(ENTER);
    tui.wait_for("message 500");

    for key in [PAGE_UP, UP, UP, PAGE_UP] {
        tui.send(key);
        assert!(
            !tui.has_exited(),
            "the client died on {key:?} with no transfer in flight"
        );
    }

    // Still usable afterwards, which is the part a panic cannot fake.
    assert!(
        !tui.has_exited(),
        "the client must still be running after scrolling"
    );
    tui.send(CTRL_C);
    assert_eq!(
        tui.wait_for_exit(),
        0,
        "the client must still exit cleanly after scrolling"
    );
    tui.dump("conversation-scrolled-no-transfer");
}
