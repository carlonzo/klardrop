//! Flow tests for the interactive TUI, driven on a real pseudo terminal.
//!
//! These are the only tests in the crate that can fail on something a person
//! would call "that looks wrong". Each one starts the real `klardrop
//! interactive` against the fixture daemon on a pty, types at it, and asserts
//! on the **screen** — not on an exit code and not on "it did not hang".
//!
//! A daemon effect is asserted as an effect: accepting a pairing has to make
//! the pairing *disappear from the menu*, because that is what the user sees
//! happen. A 200 with nothing drawn would pass an HTTP-level test and tell a
//! person nothing.
//!
//! See [`support`] for the harness.

mod support;

use std::time::{Duration, Instant};

use support::*;

/// Asserts that exactly one device row carries the selection marker, and that
/// it is `expected`. The marker is the only difference between a selected and
/// an unselected row, so this reads the selection straight off the screen.
///
/// Counted as occurrences rather than as lines, deliberately. The pty master
/// only carries the client's own bytes on unix; on Windows it carries the
/// terminal's rendering, so the reconstructed screen there is a handful of very
/// long rows with every device row concatenated. The marker still sits
/// immediately in front of the selected device exactly once either way, so
/// counting it proves the same thing on both.
fn assert_selected(screen: &str, expected: &str) {
    const MARKER: &str = "\u{2502}> ";
    let marked = screen.matches(MARKER).count();
    assert_eq!(marked, 1, "expected one selected row in:\n{screen}");
    let at = screen.find(MARKER).expect("exactly one marker");
    let row: String = screen[at..].chars().take(96).collect();
    assert!(
        row.contains(expected),
        "expected the selected row to be {expected:?}, got {row:?}"
    );
}

/// Opens the actions overlay and waits for it.
fn open_actions(tui: &mut Tui) {
    tui.send("a");
    tui.wait_for("Actions");
}

/// Presses the digit that chooses the menu entry labelled `label`.
///
/// The overlay numbers its entries by position, and positions move as the
/// daemon's state changes — a pending pairing pushes everything down by two.
/// Reading the number off the screen is what a person does, and it keeps these
/// tests from asserting a menu layout that only happens to be true today.
fn choose(tui: &mut Tui, label: &str) {
    tui.wait_for(label);
    let screen = tui.text();
    // The number is drawn immediately before the label, with one space
    // between. Reading it back that way needs no knowledge of the border
    // characters around it.
    let digit = screen
        .lines()
        .find_map(|line| {
            let at = line.find(label)?;
            line[..at]
                .trim_end()
                .chars()
                .next_back()
                .filter(char::is_ascii_digit)
        })
        .unwrap_or_else(|| panic!("no numbered menu entry labelled {label:?} in:\n{screen}"));
    tui.send(&digit.to_string());
}

// ------------------------------------------------------------- the basics

#[test]
fn the_client_draws_the_daemons_devices_on_startup() {
    let fixture = Fixture::start("startup", &[]);
    let tui = Tui::launch(&fixture);

    let rows = tui.wait_for("Fixture Phone");
    let screen = rows.join("\n");
    assert!(screen.contains("connected"), "{screen}");
    assert!(screen.contains("Fixture Tablet"), "{screen}");
    assert!(
        !screen.contains("connecting"),
        "the header claims the daemon is not answering: {screen}"
    );
    capture(&tui, "01-device-list");
}

#[test]
fn the_selection_moves_down_and_up_the_device_list() {
    let fixture = Fixture::start("nav", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Laptop");

    tui.send(DOWN);
    tui.send(DOWN);
    assert_selected(&tui.text(), "Fixture Laptop");
    capture(&tui, "02-selection-moved-down");

    tui.send(UP);
    assert_selected(&tui.text(), "Fixture Tablet");
}

// ------------------------------------------------------------ the poller

/// The long poll must return a *changed* version, not an immediate copy of
/// what the client already had. `advancing_state` puts the counter in the
/// device name, so a poll that is doing real work is visible on the screen.
#[test]
fn the_state_poller_follows_a_daemon_that_keeps_changing() {
    let fixture = Fixture::start("poller", &["advancing_state"]);
    let tui = Tui::launch(&fixture);

    let first = tui.wait_for("Fixture Host v").join("\n");
    let start = version_in(&first).expect("the header should carry the state version");
    assert!(start > 0, "{first}");

    // The counter only moves if the client really re-polled the daemon, so
    // seeing it advance is a completed poll rather than one answer replayed.
    let deadline = Instant::now() + WAIT;
    loop {
        let screen = tui.text();
        let now = version_in(&screen).expect("the version stays in the header");
        if now > start {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the state version never moved past {start}:\n{screen}"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
    capture(&tui, "03-state-poller-advancing");
}

/// Pulls the `v<N>` counter out of the header.
fn version_in(screen: &str) -> Option<u64> {
    let start = screen.find("Fixture Host v")? + "Fixture Host v".len();
    let digits: String = screen[start..]
        .chars()
        .take_while(char::is_ascii_digit)
        .collect();
    digits.parse().ok()
}

// ------------------------------------------------------------- decisions

#[test]
fn accepting_a_pairing_removes_it_from_the_actions_menu() {
    let fixture = Fixture::start("accept-pair", &["pairing_dialog"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("accept pairing with 4444cccc");
    capture(&tui, "04-pairing-pending");

    choose(&mut tui, "accept pairing with");
    // The decision is only real once the daemon has taken it: the dialog is
    // gone and the overlay says out loud that nothing is pending any more.
    let screen = tui.wait_until_gone("accept pairing with").join("\n");
    assert!(
        !screen.contains("reject pairing with"),
        "the reject entry outlived the accept entry:\n{screen}"
    );
    assert!(
        screen.contains("nothing pending"),
        "with the dialog gone the overlay should say nothing is pending:\n{screen}"
    );
    capture(&tui, "05-pairing-accepted");
}

#[test]
fn rejecting_a_pairing_removes_it_from_the_actions_menu() {
    let fixture = Fixture::start("reject-pair", &["pairing_dialog"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("reject pairing with 4444cccc");

    choose(&mut tui, "reject pairing with");
    let screen = tui.wait_until_gone("reject pairing with").join("\n");
    assert!(
        !screen.contains("accept pairing with"),
        "the accept entry outlived the reject entry:\n{screen}"
    );
}

#[test]
fn accepting_an_incoming_transfer_removes_it_from_the_actions_menu() {
    let fixture = Fixture::start("accept-incoming", &["pending_incoming"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("accept incoming transfer #77");
    capture(&tui, "06-incoming-pending");

    choose(&mut tui, "accept incoming transfer");
    let screen = tui.wait_until_gone("accept incoming transfer").join("\n");
    assert!(
        !screen.contains("reject incoming transfer"),
        "the reject entry outlived the accept entry:\n{screen}"
    );
}

#[test]
fn rejecting_an_incoming_transfer_removes_it_from_the_actions_menu() {
    let fixture = Fixture::start("reject-incoming", &["pending_incoming"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("reject incoming transfer #77");

    choose(&mut tui, "reject incoming transfer");
    let screen = tui.wait_until_gone("reject incoming transfer").join("\n");
    assert!(
        !screen.contains("accept incoming transfer"),
        "the accept entry outlived the reject entry:\n{screen}"
    );
}

#[test]
fn a_pairing_that_already_failed_is_not_offered_as_a_decision() {
    let fixture = Fixture::start("pairing-error", &["pairing_dialog_error"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("nothing pending");
    let screen = tui.text();
    assert!(
        !screen.contains("accept pairing with") && !screen.contains("reject pairing with"),
        "a failed pairing is a message, not a decision:\n{screen}"
    );
}

// ------------------------------------------------------------- settings

#[test]
fn renaming_this_device_shows_the_new_name_in_the_header() {
    let fixture = Fixture::start("rename", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Host");

    tui.send("n");
    tui.send("Workshop");
    tui.send(ENTER);

    // The name in the header is `/state`'s answer, so this is the rename having
    // reached the daemon and come back.
    let screen = tui.wait_for("Workshop").join("\n");
    assert!(
        !screen.contains("Fixture Host"),
        "the old name is still in the header:\n{screen}"
    );
    capture(&tui, "07-renamed-device");
}

#[test]
fn the_settings_overlay_turns_background_discovery_off_and_the_daemon_agrees() {
    let fixture = Fixture::start("settings", &[]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    choose(&mut tui, "settings");
    tui.wait_for("background discovery: on");
    capture(&tui, "08-settings-on");

    tui.send("d");
    // The overlay reads `/state`, so "off" only appears once the daemon has
    // accepted the change and reported it back.
    tui.wait_for("background discovery: off");
    capture(&tui, "09-settings-off");
}

// --------------------------------------------------------------- updates

#[test]
fn a_checked_update_can_be_applied() {
    let fixture = Fixture::start("update", &["staged_update"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("check for updates");

    // Nothing is staged yet, so "apply" must not be on the menu: offering it
    // would be a button that cannot work.
    let before = tui.text();
    assert!(
        !before.contains("apply the staged update"),
        "apply is offered before anything is staged:\n{before}"
    );

    choose(&mut tui, "check for updates");
    tui.wait_for("apply the staged update");
    capture(&tui, "10-update-staged");

    choose(&mut tui, "apply the staged update");
    tui.wait_until_gone("apply the staged update");
}

#[test]
fn applying_an_update_during_a_transfer_is_refused_and_says_so() {
    let fixture = Fixture::start("update-409", &["staged_update", "transfer_active"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    choose(&mut tui, "check for updates");
    tui.wait_for("apply the staged update");

    choose(&mut tui, "apply the staged update");
    // The daemon's 409 has to reach the user as words. Swallowing it would look
    // exactly like the update having applied.
    let screen = tui.wait_for("apply update failed").join("\n");
    assert!(
        screen.contains("409") && screen.contains("/update/apply"),
        "the refusal should name the route that refused:\n{screen}"
    );
    // And the update is still staged, because it was refused rather than
    // applied: the menu entry outliving the failure is what proves that.
    assert!(
        screen.contains("apply the staged update"),
        "a refused apply must not look like an applied one:\n{screen}"
    );
    capture(&tui, "11-update-refused-409");
}

// ----------------------------------------------------------- capabilities

#[test]
fn a_capability_the_daemon_does_not_advertise_is_labelled_unavailable() {
    let fixture = Fixture::start("limited", &["limited_capabilities"]);
    let mut tui = Tui::launch(&fixture);
    open_actions(&mut tui);
    tui.wait_for("rename this device");
    let screen = tui.text();
    assert!(
        !screen.contains("start a QR share"),
        "QR share is offered by a daemon that does not advertise it:\n{screen}"
    );
    assert!(
        !screen.contains("check for updates"),
        "the update check is offered by a daemon that does not advertise it:\n{screen}"
    );
    assert!(
        screen.contains("unavailable on this daemon") && screen.contains("QR share"),
        "what is missing should be named, not merely omitted:\n{screen}"
    );
    capture(&tui, "12-capabilities-unavailable");
}

// ------------------------------------------------------------------ exit

#[test]
fn quitting_gives_the_terminal_back() {
    let fixture = Fixture::start("quit", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Phone");

    tui.send("q");
    assert_eq!(tui.wait_for_exit(), 0, "leaving the TUI is not a failure");

    // The alternate screen is left behind on exit, so the screen the user is
    // left looking at must not still be the TUI.
    let screen = tui.text();
    assert!(
        !screen.contains("Fixture Phone"),
        "the TUI was still on screen after it exited:\n{screen}"
    );
    capture(&tui, "13-after-quit");
}

#[test]
fn ctrl_c_quits_from_inside_an_overlay() {
    let fixture = Fixture::start("ctrl-c", &[]);
    let mut tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Phone");
    tui.send("?");
    tui.wait_for("Keys");

    tui.send(CTRL_C);
    assert_eq!(
        tui.wait_for_exit(),
        0,
        "Ctrl-C is the only interrupt raw mode leaves the user"
    );
}
/// The same escape-safety guarantee the line-mode test makes, on the path that
/// actually owns the screen.
///
/// The daemon's `hostile_names` behaviour reports a device name carrying an SGR
/// sequence, an OSC that retitles the window, a bell and a NUL. mDNS names come
/// from any machine on the LAN, so this is attacker-controlled input, and the
/// full-screen renderer is where it would land. Line mode was covered; the
/// panes were not, and they are the path a person actually uses.
///
/// ESC bytes are NOT asserted absent — the TUI legitimately writes hundreds of
/// them. BEL and NUL are the ones no drawing operation emits, so those are what
/// must not appear, plus the user's data must survive.
#[test]
fn a_hostile_device_name_cannot_reach_the_terminal_through_the_panes() {
    let fixture = Fixture::start("panes-hostile", &["hostile_names"]);
    let tui = Tui::launch(&fixture);
    tui.wait_for("evil");

    // What the user is actually shown, on every platform: the name's characters
    // survive and none of its control ones do. A BEL or NUL that reaches the
    // SCREEN is one that reached the terminal, which is the property.
    let screen = tui.text();
    for (what, byte) in [("a bell", 0x07u8), ("a NUL", 0x00)] {
        assert!(
            !screen.as_bytes().contains(&byte),
            "{what} reached the screen: a daemon-supplied name carried it\n{screen}"
        );
    }
    // The OSC must not be EXECUTED, which is the property. Its residue — the
    // printable `]0;pwned` — is drawn as ordinary text, and asserting that the
    // bare substring is absent would assert the opposite of correct: the client
    // is supposed to keep the user's characters and drop the control ones. What
    // must not exist is the ESC that would make it live.
    assert!(
        !screen.contains("\u{1b}]0;pwned"),
        "the device name's OSC reached the screen as a live sequence, which retitles the window"
    );
    assert!(
        screen.contains("]0;pwned"),
        "the name's printable residue must still be drawn as inert text:\n{screen}"
    );
    assert!(
        screen.contains("evil"),
        "the user's name must survive as inert text:\n{screen}"
    );

    // The wire itself is only the client's own bytes on unix. On Windows the
    // pty master carries the terminal's RENDERING: it opens by announcing its
    // window title as an OSC 0 sequence holding the executable path, BEL
    // terminated, and it emits CRLFs the client never writes. A BEL in that
    // stream is the terminal introducing itself, not this name ringing
    // anything, so the byte-level assertions are scoped to where the two are
    // the same thing. The sanitiser is pinned everywhere regardless: `escape`'s
    // and the renderer's own tests read the client's output before any pty is
    // involved, and everything above is asserted against the screen on all three.
    if !cfg!(windows) {
        let raw = tui.raw();
        assert!(
            !raw.contains(&0x07),
            "a bell reached the terminal: a daemon-supplied name rang it"
        );
        assert!(
            !raw.contains(&0x00),
            "a NUL reached the terminal: a daemon-supplied name carried it"
        );
        let text = String::from_utf8_lossy(&raw).into_owned();
        assert!(
            !text.contains("\u{1b}]0;pwned"),
            "the device name's OSC reached the terminal as a live sequence, which retitles the window"
        );
        assert!(
            text.contains("]0;pwned"),
            "the name's printable residue must still be drawn as inert text:\n{text}"
        );
    }
    tui.dump("panes-hostile-names");
    assert!(
        !tui.has_exited(),
        "the client died rendering a hostile name"
    );
}

/// A daemon that has found nothing yet. The empty state is a screen a person
/// looks at, so it has to say something rather than draw an empty box.
#[test]
fn no_devices_yet_renders_an_empty_state_rather_than_a_blank_pane() {
    let fixture = Fixture::start("no-devices", &["empty_state"]);
    let tui = Tui::launch(&fixture);

    let rows = tui.wait_for("0 devices");
    let text = rows.join("\n");
    assert!(
        text.to_lowercase().contains("no device"),
        "with no devices the header must say so:\n{text}"
    );
    assert!(
        text.contains("Searching") || text.contains("searching") || text.contains("No "),
        "an empty list must invite a wait, not look broken:\n{text}"
    );
    assert!(!tui.has_exited(), "the client died on an empty device list");
    tui.dump("no-devices-empty-state");
}
