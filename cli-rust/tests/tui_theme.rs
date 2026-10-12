//! The `--theme` choice at the terminal, on a real pseudo terminal.
//!
//! `system` is the only palette decision this client does not make for itself,
//! and it is the only one that costs a round trip on the input stream. Every
//! rule that follows from that — interactive only, bounded, and never taking a
//! keystroke — is asserted here, from a pty, because none of them is observable
//! from a unit test.
//!
//! A pty is the honest adversary: it is a real terminal that implements neither
//! OSC 10 nor OSC 11, so it never answers, and "falls back and does not hang"
//! is tested against the real case rather than a mock.
//!
//! Every fixture here runs with `state_long_poll`, which holds `/state` open for
//! two seconds. `--debug` logs one line per daemon request, so against a daemon
//! that answers instantly the log would repaint the screen faster than the
//! client could draw on it and there would be nothing left to assert about.
//!
//! See [`support`] for the harness.

mod support;

use support::*;

/// A capable terminal with colour on, so the palette question is worth asking.
fn options() -> LaunchOptions {
    LaunchOptions {
        motion: false,
        mouse: false,
        color: true,
        ..LaunchOptions::default()
    }
}

/// The answer a terminal that answers would give: a warm off-white on a very
/// dark blue, deliberately unlike Tokyo Night so the two cannot be confused.
/// Every channel is a repeated digit, which is how terminals send `rgb:`.
const ANSWER: &str = "\x1b]10;rgb:e0e0/c0c0/a0a0\x07\x1b]11;rgb:1010/1414/1818\x07";

/// The same terminal, told which way to draw. `--theme` and its value are two
/// argv entries, because the harness writes them straight to `execve`.
fn with_theme(theme: &str) -> LaunchOptions {
    LaunchOptions {
        extra: vec![
            "--theme".to_string(),
            theme.to_string(),
            "--debug".to_string(),
        ],
        ..options()
    }
}

/// Waits for the client to be up and showing the fixture's devices, and returns
/// the screen it drew.
fn connected(tui: &Tui) -> String {
    tui.wait_for("Fixture Phone").join("\n")
}

#[test]
fn a_terminal_that_never_answers_falls_back_and_does_not_hang() {
    let fixture = Fixture::start("theme-silent", &["state_long_poll"]);
    let tui = Tui::launch_with(&fixture, &with_theme("system"));

    // The query really went out...
    tui.wait_for_raw("\x1b]11;?");
    // ...the client came up anyway, on the shipped palette, and the deadline
    // on the probe expired rather than the client hanging on it.
    let screen = connected(&tui);
    tui.wait_for_raw("no answer from the terminal, using tokyo-night");
    capture(&tui, "theme-system-no-answer");

    assert!(screen.contains("connected"), "{screen}");
}

#[test]
fn a_terminal_that_answers_is_drawn_in_its_own_colours() {
    let fixture = Fixture::start("theme-answers", &["state_long_poll"]);
    let mut tui = Tui::launch_with(&fixture, &with_theme("system"));

    // Answer only once the client has actually asked, so the bytes are a reply
    // and not a stray keystroke that happens to look like one.
    tui.wait_for_raw("\x1b]11;?");
    tui.send_raw(ANSWER.as_bytes());
    tui.wait_for_raw("terminal fg #e0c0a0 bg #101418");
    connected(&tui);
    capture(&tui, "theme-system-from-terminal");
}

#[test]
fn half_an_answer_is_not_an_answer() {
    let fixture = Fixture::start("theme-half", &["state_long_poll"]);
    let mut tui = Tui::launch_with(&fixture, &with_theme("system"));

    // Only the foreground arrives. A palette needs both, so this is no palette.
    tui.wait_for_raw("\x1b]11;?");
    tui.send_raw(b"\x1b]10;rgb:e0d0/c0b0/a090\x07");
    tui.wait_for_raw("no answer from the terminal, using tokyo-night");
    connected(&tui);
}

#[test]
fn auto_asks_the_same_question_and_falls_back_the_same_way() {
    let fixture = Fixture::start("theme-auto", &["state_long_poll"]);
    let tui = Tui::launch_with(&fixture, &with_theme("auto"));

    tui.wait_for_raw("\x1b]11;?");
    tui.wait_for_raw("no answer from the terminal, using tokyo-night");
    connected(&tui);
}

#[test]
fn tokyo_night_never_puts_a_question_on_the_wire() {
    let fixture = Fixture::start("theme-tokyo", &["state_long_poll"]);
    let tui = Tui::launch_with(&fixture, &with_theme("tokyo-night"));
    connected(&tui);

    let raw = String::from_utf8_lossy(&tui.raw()).into_owned();
    assert!(
        !raw.contains("\x1b]10;?"),
        "--theme tokyo-night must not ask the terminal anything"
    );
    tui.wait_for_raw("TokyoNight, not asked");
}

#[test]
fn asking_the_terminal_does_not_eat_the_next_keystroke() {
    let fixture = Fixture::start("theme-keystroke", &["state_long_poll"]);
    // No `--debug` here: this test asserts on the *screen*, and the debug log
    // repaints it faster than the client can draw on it.
    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            extra: vec!["--theme".to_string(), "system".to_string()],
            ..options()
        },
    );

    // The query ran: the client either got an answer or timed out, and the
    // terminal was handed back either way.
    tui.wait_for_raw("\x1b]11;?");
    connected(&tui);

    // Typed straight after the probe finished, this is an ordinary keystroke
    // and has to arrive as that keystroke. `i` opens the compose line, which
    // is where a character the user typed is actually shown.
    tui.send("i");
    tui.send("Z");
    let screen = tui.wait_for("Z").join("\n");
    assert!(
        screen.contains("Z"),
        "the character typed after the palette query never arrived:\n{screen}"
    );
    capture(&tui, "theme-system-keystroke-kept");
}

#[test]
fn line_mode_never_asks_the_terminal_anything() {
    let fixture = Fixture::start("theme-line-mode", &[]);
    let mut tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            extra: vec![
                "--theme".to_string(),
                "system".to_string(),
                "--line-mode".to_string(),
            ],
            keep_slave: true,
            ..options()
        },
    );

    assert_eq!(tui.wait_for_exit(), 0);
    let raw = tui.raw();
    assert!(
        !raw.contains(&0x1b),
        "line mode wrote an escape byte, so it asked the terminal something; output was:\n{}",
        String::from_utf8_lossy(&raw)
    );
}
/// Nothing is styled, on the wire.
///
/// A full-screen client still has to move the cursor and take the screen, so it
/// cannot be byte-free. What it must not do is *style* anything: no colour and
/// no attribute, because a `BOLD` pane title is a `SGR` sequence just as much
/// as a tinted row is, and "less colour" is not what the flag promises.
///
/// The sequences are matched whole, terminator included, and only the ones
/// that set something are counted. Two things make the count meaningful: a
/// filter that asked whether the *parameters* ended in `m` would match nothing
/// ever and pass no matter what the client wrote, and ratatui's crossterm
/// backend ends every `draw` with an unconditional `ESC[39m ESC[49m ESC[0m`
/// that no client can suppress — so a bare "the list of `SGR` sequences is
/// empty" is not a contract a full-screen draw can keep. See
/// [`support::styling_sequences`].
#[test]
fn no_color_styles_nothing_on_the_wire() {
    let fixture = Fixture::start("theme-no-color", &["state_long_poll"]);
    let tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            // Colour off is what `--no-color` means to this client.
            color: false,
            ..options()
        },
    );
    connected(&tui);

    let raw = String::from_utf8_lossy(&tui.raw()).into_owned();
    let styled = styling_sequences(&raw);
    assert!(styled.is_empty(), "--no-color styled something: {styled:?}");
    capture(&tui, "theme-no-color");
}

/// The same contract from the environment, with no flag at all.
///
/// `NO_COLOR` is a promise the user's shell makes, not one the command line
/// has to repeat: a user who exports it once expects every tool to honour it
/// whatever its flags say. Testing only `--no-color` would leave half the
/// contract unproven.
#[test]
fn the_no_color_variable_alone_styles_nothing_on_the_wire() {
    let fixture = Fixture::start("theme-no-color-env", &["state_long_poll"]);
    let tui = Tui::launch_with(
        &fixture,
        &LaunchOptions {
            // A capable terminal, and *no* `--no-color`: the variable has to
            // do this on its own.
            color: true,
            env: vec![("NO_COLOR".to_string(), "1".to_string())],
            ..options()
        },
    );
    connected(&tui);

    let raw = String::from_utf8_lossy(&tui.raw()).into_owned();
    let styled = styling_sequences(&raw);
    assert!(styled.is_empty(), "NO_COLOR=1 styled something: {styled:?}");
    capture(&tui, "theme-no-color-env");
}

/// The control the two tests above need: the same fixture, the same terminal
/// and the same client, with colour on.
///
/// "Nothing is styled" is only a real assertion if the client styles when it
/// is allowed to, and a filter that found nothing in *any* stream would make
/// both of the tests above pass while the client painted every cell.
#[test]
fn the_same_client_on_the_same_terminal_does_style_when_colour_is_allowed() {
    let fixture = Fixture::start("theme-color-on", &["state_long_poll"]);
    let tui = Tui::launch_with(&fixture, &options());
    connected(&tui);

    let raw = String::from_utf8_lossy(&tui.raw()).into_owned();
    let styled = styling_sequences(&raw);
    assert!(
        !styled.is_empty(),
        "nothing was styled in a run that is allowed to style, so \
         'nothing is styled' is not a claim about this client"
    );
}
