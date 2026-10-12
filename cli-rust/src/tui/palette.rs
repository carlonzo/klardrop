//! Asking the terminal for its own colours.
//!
//! `--theme system` is the one palette decision this client does not make for
//! itself: the foreground and background a person has configured are the ones
//! the client should draw in, so the answer is *read* from the terminal rather
//! than guessed from `TERM`. Getting it costs a round trip on the input
//! stream, and every rule below exists because of that.
//!
//! ## The rules
//!
//! * **Interactive only.** [`probe_allowed`] is the gate: both ends have to be
//!   terminals. A redirected stdout has no screen to colour and no input to
//!   read a reply from. Line mode answers before a palette is ever resolved,
//!   so it never reaches this module at all.
//! * **Bounded.** [`PROBE_TIMEOUT`] is a hard deadline on the whole exchange.
//!   A terminal that ignores OSC 10 and OSC 11 — a bare pty, `screen`, anything
//!   that is not an emulator — costs exactly one timeout and then falls back to
//!   Tokyo Night. It cannot hang the client.
//! * **Non-destructive.** The terminal is handed back before [`query`]
//!   returns, from `Drop`, so an early return or an error still restores it.
//!   Keystrokes the probe reads that are *not* part of a reply are not
//!   swallowed: they come back in [`Probe::replay`] and are folded into the app
//!   before the input loop polls again, so a character typed during the probe
//!   still reaches the compose box.
//! * **All or nothing.** A palette needs both colours. One reply, or two that
//!   cannot be true, is no reply at all: [`Probe::palette`] is `None` and the
//!   caller falls back.
//!
//! ## How the reply is read
//!
//! The client never owns input decoding — crossterm does, and
//! [`crate::tui::terminal`] restores what crossterm took. That is why the probe
//! reads through `poll`/`read` rather than taking the descriptor: it is the
//! only bounded, non-blocking, non-racing read available, and it means bytes
//! crossterm has not parsed yet stay in crossterm's own buffer for the input
//! loop rather than being stranded here.
//!
//! Crossterm does not know about OSC, so it reports `ESC ]` as the documented
//! `Alt` + `]` key and `BEL` (0x07) as `Ctrl-G`. [`Reply`] turns that stream
//! back into the reply it came from. That is the one place the crate leans on
//! how its dependency decodes, it is small, it is pure, and it fails *closed*:
//! anything that is not exactly a reply is handed back as a keystroke. The one
//! thing it cannot hand back is the `ESC ]` that opened a sequence which then
//! turned out not to be one — a keystroke the user has to press twice, in a
//! quarter-second window, at startup.

use std::io::Write;
use std::time::{Duration, Instant};

use crossterm::event::{Event, KeyCode, KeyEvent, KeyModifiers};

use crate::tui::theme::FgBg;

/// How long the whole exchange may take: the query goes out, and then the
/// client is willing to wait exactly this long for both answers.
///
/// Generous enough for a terminal over a slow ssh link, short enough that a
/// terminal which never answers is not felt as a hang.
pub const PROBE_TIMEOUT: Duration = Duration::from_millis(250);

/// OSC 10 asks for the foreground, OSC 11 for the background. `?` is the
/// xterm query form; a terminal that does not implement it simply stays quiet,
/// which is the same as falling back.
const QUERY: &str = "\x1b]10;?\x07\x1b]11;?\x07";

/// The longest payload accepted before a sequence is abandoned as not-a-reply.
/// A real colour spec is well under twenty bytes; the cap is what stops a
/// stream of ordinary typing from being accumulated into an endless "reply".
const MAX_PAYLOAD: usize = 64;

/// The longest OSC code accepted. `10` and `11` are the only ones this client
/// asks about.
const MAX_CODE: usize = 2;

/// One colour, as the terminal reported it.
pub type Rgb = (u8, u8, u8);

/// `true` when this session may ask the terminal for its palette.
///
/// False — and the probe is skipped entirely — whenever either end of the
/// terminal is not a terminal. That covers the two cases that cannot work: a
/// redirected stdout has nothing to colour, and a redirected stdin has nothing
/// to read a reply from without eating the automation's own input.
pub fn probe_allowed() -> bool {
    crate::tui::terminal::is_interactive_terminal()
}

/// The outcome of one probe: what the terminal said, and what it read that was
/// not an answer.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Probe {
    /// OSC 10, the foreground.
    pub fg: Option<Rgb>,
    /// OSC 11, the background.
    pub bg: Option<Rgb>,
    /// Keys typed while the probe was running, in the order they were read.
    ///
    /// Nothing is dropped: the input loop folds these in before it polls
    /// again, so a keystroke is never consumed by the palette decision.
    pub replay: Vec<Event>,
}

impl Probe {
    /// No answer, and nothing was read. What every refused or unanswerable
    /// probe produces.
    pub fn none() -> Self {
        Self::default()
    }

    /// Both colours, or nothing.
    ///
    /// A partial answer is deliberately not a palette: half a palette is a
    /// theme the user never chose.
    pub fn palette(&self) -> Option<FgBg> {
        match (self.fg, self.bg) {
            (Some(fg), Some(bg)) => Some((fg, bg)),
            _ => None,
        }
    }
}

/// Asks the terminal, once, for its foreground and background.
///
/// Never blocks for longer than [`PROBE_TIMEOUT`] and never leaves the terminal
/// in raw mode, whatever happens in between.
pub fn query() -> Probe {
    if !probe_allowed() {
        return Probe::none();
    }
    // The guard is taken before a single byte is written and dropped before a
    // single byte is handed to the app, so the window in which the terminal is
    // not the user's is as small as it can be.
    let Some(_raw) = RawMode::take() else {
        return Probe::none();
    };
    if write_query().is_err() {
        return Probe::none();
    }
    drain_until(Instant::now() + PROBE_TIMEOUT)
}

/// Owns raw mode for the length of the probe and gives the terminal back on
/// drop — on the ordinary return, on the early returns above, and on an
/// unwinding panic alike.
struct RawMode;

impl RawMode {
    /// Takes the terminal, or reports that it could not be taken.
    fn take() -> Option<Self> {
        crossterm::terminal::enable_raw_mode().ok().map(|()| Self)
    }
}

impl Drop for RawMode {
    fn drop(&mut self) {
        // Best effort, and deliberately so: a terminal that has gone away
        // cannot be restored, and that must not stop the rest of the client
        // from running.
        let _ = crossterm::terminal::disable_raw_mode();
    }
}

/// Puts the two questions on the wire. A terminal that is not listening simply
/// never answers.
fn write_query() -> std::io::Result<()> {
    let mut out = std::io::stdout();
    out.write_all(QUERY.as_bytes())?;
    out.flush()
}

/// Reads until the palette is complete and the terminal has nothing left to
/// say, or until the deadline — whichever comes first.
///
/// Draining after the last answer is not tidiness: anything the terminal sent
/// and this loop did not read would come back out of crossterm's buffer later
/// as stray keystrokes, and a stray keystroke is a character in someone's
/// message.
fn drain_until(deadline: Instant) -> Probe {
    let mut reply = Reply::default();
    let mut probe = Probe::none();
    while Instant::now() < deadline {
        let wait = if reply.complete() {
            Duration::ZERO
        } else {
            deadline.saturating_duration_since(Instant::now())
        };
        match crossterm::event::poll(wait) {
            Ok(true) => {}
            // Nothing more is coming before the deadline: the ordinary path for
            // a terminal that does not implement OSC 10 or 11.
            Ok(false) => break,
            Err(_) => break,
        }
        let Ok(event) = crossterm::event::read() else {
            break;
        };
        if !reply.feed(&event, &mut probe.replay) {
            // Not an answer. The user typed it; it is not ours to swallow.
            probe.replay.push(event);
        }
    }
    probe.fg = reply.fg;
    probe.bg = reply.bg;
    probe
}

/// Which part of a reply is being read.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
enum Stage {
    /// Not in a reply at all.
    #[default]
    Idle,
    /// Reading the digits between `ESC ]` and `;`.
    Code,
    /// Reading the payload, up to `BEL` or `ESC \`.
    Payload,
}

/// Reassembles OSC replies from the key events crossterm reports them as.
///
/// Pure on purpose: it is the only part of the probe that can be *wrong*, so it
/// is the part with the most tests, and none of those tests need a terminal.
#[derive(Debug, Default)]
struct Reply {
    stage: Stage,
    code: String,
    payload: String,
    fg: Option<Rgb>,
    bg: Option<Rgb>,
}

impl Reply {
    /// Whether both colours are in. A probe stops waiting once they are.
    fn complete(&self) -> bool {
        self.fg.is_some() && self.bg.is_some()
    }

    /// Offers one event to the reply, and reports whether it was consumed.
    ///
    /// Anything not consumed goes onto `replay` instead, and that includes the
    /// characters already accumulated when a sequence turns out not to be a
    /// reply after all — a half-read reply cannot be replayed as part of one.
    fn feed(&mut self, event: &Event, replay: &mut Vec<Event>) -> bool {
        match self.stage {
            Stage::Idle => {
                if is_alt(event, ']') {
                    self.code.clear();
                    self.stage = Stage::Code;
                    return true;
                }
                false
            }
            Stage::Code => match plain_char(event) {
                // The code is at most two digits, so `100` is already not a
                // reply and must not be allowed to keep accumulating.
                Some(c) if c.is_ascii_digit() && self.code.len() < MAX_CODE => {
                    self.code.push(c);
                    true
                }
                Some(';') => {
                    self.payload.clear();
                    self.stage = Stage::Payload;
                    true
                }
                _ => {
                    self.abandon(replay);
                    false
                }
            },
            Stage::Payload => {
                // `BEL` is control byte 0x07, which crossterm decodes as
                // `Ctrl-G`; `ESC \` (string terminator) is `Alt-\`. Either one
                // ends the reply.
                if is_bell(event) || is_alt(event, '\\') {
                    let finished = self.take();
                    self.reset();
                    return finished;
                }
                match plain_char(event) {
                    Some(c) if self.payload.len() < MAX_PAYLOAD => {
                        self.payload.push(c);
                        true
                    }
                    _ => {
                        self.abandon(replay);
                        false
                    }
                }
            }
        }
    }

    /// Records a finished reply. Anything that is not a colour this client
    /// asked for is still consumed — it *is* terminal chatter, and it must not
    /// reach the compose box.
    fn take(&mut self) -> bool {
        let Some(colour) = parse_colour(&self.payload) else {
            return true;
        };
        match self.code.as_str() {
            "10" => self.fg = Some(colour),
            "11" => self.bg = Some(colour),
            _ => {}
        }
        true
    }

    /// Gives back everything read so far, because this was not a reply.
    fn abandon(&mut self, replay: &mut Vec<Event>) {
        for c in self.code.chars().chain(self.payload.chars()) {
            replay.push(Event::Key(KeyEvent::new(
                KeyCode::Char(c),
                KeyModifiers::NONE,
            )));
        }
        self.reset();
    }

    fn reset(&mut self) {
        self.stage = Stage::Idle;
        self.code.clear();
        self.payload.clear();
    }
}

/// `Alt` + a character, which is how crossterm reports anything the terminal
/// sent as `ESC` followed by one more byte.
fn is_alt(event: &Event, expected: char) -> bool {
    matches!(
        event,
        Event::Key(key)
            if key.code == KeyCode::Char(expected) && key.modifiers.contains(KeyModifiers::ALT)
    )
}

/// A bare character, with the modifier crossterm adds for a capital letter and
/// nothing else. Anything carrying `Alt` or `Control` is not payload.
fn plain_char(event: &Event) -> Option<char> {
    match event {
        Event::Key(key) if key.modifiers.is_empty() || key.modifiers == KeyModifiers::SHIFT => {
            match key.code {
                KeyCode::Char(c) => Some(c),
                _ => None,
            }
        }
        _ => None,
    }
}

/// `BEL`. Terminals normally end an OSC reply with 0x07, which crossterm
/// decodes as `Ctrl-G`.
fn is_bell(event: &Event) -> bool {
    matches!(
        event,
        Event::Key(key)
            if key.code == KeyCode::Char('g') && key.modifiers.contains(KeyModifiers::CONTROL)
    )
}

/// Parses the payload of an OSC 10 or 11 reply.
///
/// Accepts both forms terminals use in practice: the X11 `#rgb` shorthand and
/// the `rgb:rrrr/gggg/bbbb` form, with an optional alpha component that is
/// discarded — a terminal's alpha is its own business, not something the
/// client draws. `?`, which is what a terminal that does not implement the
/// query sends back, is not a colour and yields nothing.
fn parse_colour(payload: &str) -> Option<Rgb> {
    if let Some(hex) = payload.strip_prefix('#') {
        // `#rgb`, `#rrggbb`, `#rrrgggbbb`, `#rrrrggggbbbb`: three channels of
        // equal width, one to four digits each.
        if !matches!(hex.len(), 3 | 6 | 9 | 12) {
            return None;
        }
        let width = hex.len() / 3;
        let (r, rest) = hex.split_at(width);
        let (g, b) = rest.split_at(width);
        return Some((
            scale(hex_value(r)?, width)?,
            scale(hex_value(g)?, width)?,
            scale(hex_value(b)?, width)?,
        ));
    }
    let rest = payload
        .strip_prefix("rgb:")
        .or_else(|| payload.strip_prefix("rgba:"))?;
    let parts: Vec<&str> = rest.split('/').collect();
    if !(3..=4).contains(&parts.len()) {
        return None;
    }
    let width = parts[0].len();
    if !(1..=4).contains(&width) || parts.iter().any(|part| part.len() != width) {
        return None;
    }
    Some((
        scale(hex_value(parts[0])?, width)?,
        scale(hex_value(parts[1])?, width)?,
        scale(hex_value(parts[2])?, width)?,
    ))
}

/// The numeric value of a run of hex digits.
fn hex_value(digits: &str) -> Option<u32> {
    if digits.is_empty() {
        return None;
    }
    u32::from_str_radix(digits, 16).ok()
}

/// Widens a `width`-digit hex channel to a full byte.
///
/// Terminals repeat the digit rather than scale it (`1c1c`, not `1c`), but
/// scaling reads the same for the `rgb:` form and is what makes the `#` form's
/// one-digit channels work, so both go through here.
fn scale(value: u32, width: usize) -> Option<u8> {
    let max = 16u32.checked_pow(width as u32)?;
    if value >= max {
        return None;
    }
    // In floating point, because at four digits a single step of the channel
    // is a hundredth of a byte and integer arithmetic would round every low
    // value to zero.
    let scaled = value as f32 * 255.0 / (max - 1) as f32;
    Some(scaled.round().clamp(0.0, 255.0) as u8)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Feeds a whole raw reply and returns the colours it produced, plus how
    /// many bytes came back out as the user's.
    fn read(raw: &str) -> (Reply, Vec<Event>) {
        let mut reply = Reply::default();
        let mut replay = Vec::new();
        for event in events_for(raw) {
            if !reply.feed(&event, &mut replay) {
                replay.push(event);
            }
        }
        (reply, replay)
    }

    /// The key event crossterm reports for one input byte, for the bytes an
    /// OSC reply is made of.
    fn byte_event(byte: u8, escaped: bool) -> Event {
        let key = match byte {
            // crossterm maps every control byte in 0x01..=0x1a onto
            // Ctrl-<letter>; 0x07 is `BEL`, which is how a reply usually ends.
            0x01..=0x1a => KeyEvent::new(
                KeyCode::Char((byte - 0x01 + b'a') as char),
                KeyModifiers::CONTROL,
            ),
            // A byte the terminal prefixed with `ESC` is reported as `Alt`
            // and that byte, which is crossterm's documented reading of it.
            _ if escaped => KeyEvent::new(KeyCode::Char(byte as char), KeyModifiers::ALT),
            _ => KeyEvent::new(KeyCode::Char(byte as char), KeyModifiers::NONE),
        };
        Event::Key(key)
    }

    /// The events crossterm reports for a raw byte string.
    ///
    /// `ESC` is a prefix rather than an event — it is the byte crossterm
    /// turns into an `Alt` modifier on the byte after it — which is exactly
    /// why an OSC reply arrives here looking nothing like one.
    fn events_for(raw: &str) -> Vec<Event> {
        let mut escaped = false;
        let mut events = Vec::new();
        for byte in raw.bytes() {
            if byte == 0x1b {
                escaped = true;
                continue;
            }
            events.push(byte_event(byte, escaped));
            escaped = false;
        }
        events
    }

    /// The characters handed back as the user's, so a test can read what was
    /// replayed without matching on whole events.
    fn replayed(replay: &[Event]) -> String {
        replay
            .iter()
            .filter_map(|event| match event {
                Event::Key(key) => match key.code {
                    KeyCode::Char(c) => Some(c),
                    _ => None,
                },
                _ => None,
            })
            .collect()
    }

    #[test]
    fn a_bell_terminated_reply_is_understood() {
        let (reply, replay) = read("\x1b]10;rgb:e0e0/e0e0/e0e0\x07\x1b]11;rgb:1010/1414/1010\x07");
        assert_eq!(reply.fg, Some((0xe0, 0xe0, 0xe0)));
        assert_eq!(reply.bg, Some((0x10, 0x14, 0x10)));
        assert!(replay.is_empty(), "every byte belonged to a reply");
        assert!(reply.complete());
    }

    #[test]
    fn the_x11_shorthand_is_understood_at_every_width() {
        let (reply, _) = read("\x1b]10;#abc\x07");
        assert_eq!(reply.fg, Some((0xaa, 0xbb, 0xcc)));

        let (reply, _) = read("\x1b]11;#1c1c1c\x07");
        assert_eq!(reply.bg, Some((0x1c, 0x1c, 0x1c)));

        let (reply, _) = read("\x1b]10;rgb:1c1c/2a2a/3c3c\x07");
        assert_eq!(reply.fg, Some((0x1c, 0x2a, 0x3c)));
    }

    #[test]
    fn the_string_terminator_ends_a_reply_too() {
        // Some terminals use `ESC \` rather than `BEL`.
        let (reply, replay) = read("\x1b]11;rgb:0000/0000/0000\x1b\\");
        assert_eq!(reply.bg, Some((0, 0, 0)));
        assert!(replay.is_empty());
    }

    #[test]
    fn a_terminal_that_does_not_implement_the_query_answers_nothing() {
        let (reply, replay) = read("\x1b]10;?\x07\x1b]11;?\x07");
        assert_eq!(reply.fg, None);
        assert_eq!(reply.bg, None);
        assert!(
            !reply.complete(),
            "`?` is not a palette, so this has to fall back"
        );
        assert!(
            replay.is_empty(),
            "and still no byte may leak into the compose box"
        );
    }

    #[test]
    fn one_answer_is_not_a_palette() {
        let (reply, _) = read("\x1b]10;rgb:ffff/ffff/ffff\x07");
        assert!(!reply.complete(), "half an answer falls back");

        let probe = Probe {
            fg: Some((1, 2, 3)),
            bg: None,
            replay: Vec::new(),
        };
        assert_eq!(probe.palette(), None);
    }

    #[test]
    fn ordinary_typing_is_never_consumed() {
        let (reply, replay) = read("hello world");
        assert_eq!(replayed(&replay), "hello world");
        assert_eq!(reply.fg, None);
        assert_eq!(reply.bg, None);
    }

    #[test]
    fn typing_that_follows_a_reply_is_still_the_users() {
        // The reply ends, and then the user keeps typing. What comes after
        // `BEL` must come back out, not be read as a second reply.
        let (reply, replay) = read("\x1b]10;#fff\x07ab");
        assert_eq!(reply.fg, Some((0xff, 0xff, 0xff)));
        assert_eq!(replayed(&replay), "ab");
    }

    #[test]
    fn a_sequence_that_turns_out_not_to_be_a_reply_gives_everything_back() {
        // `Alt-]` then ordinary typing: the digits accumulate, a letter arrives
        // where a `;` should be, and the whole sequence is abandoned — with
        // the characters it had already eaten handed back.
        let (reply, replay) = read("\x1b]12xyz");
        assert_eq!(replayed(&replay), "12xyz");
        assert_eq!(reply.fg, None);
        assert_eq!(reply.bg, None);
    }

    #[test]
    fn an_unterminated_reply_is_never_a_palette() {
        let (reply, _) = read("\x1b]10;rgb:ffff/ffff");
        assert_eq!(reply.fg, None, "a payload with no terminator is not read");
    }

    #[test]
    fn a_never_ending_payload_is_capped() {
        // Far more payload than any colour spec: the cap is what stops
        // ordinary typing from being accumulated into an endless "reply".
        let (reply, _) = read(&format!("\x1b]10;{}", "0".repeat(MAX_PAYLOAD * 2)));
        assert_eq!(reply.fg, None);
        assert!(!reply.complete());
    }

    #[test]
    fn malformed_payloads_are_rejected() {
        assert_eq!(parse_colour("rgb:zzzz/0000/0000"), None);
        assert_eq!(parse_colour("rgb:00/00/00/00/00"), None);
        assert_eq!(parse_colour("rgb:0000/0000"), None);
        assert_eq!(parse_colour("rgb:0000/0000/00000"), None);
        assert_eq!(parse_colour("#ff"), None);
        assert_eq!(parse_colour("#gggggg"), None);
        assert_eq!(parse_colour(""), None);
        assert_eq!(parse_colour("nonsense"), None);
    }

    #[test]
    fn alpha_is_read_and_thrown_away() {
        let (reply, _) = read("\x1b]11;rgba:1c1c/1c1c/1c1c/8080\x07");
        assert_eq!(reply.bg, Some((0x1c, 0x1c, 0x1c)));
    }

    #[test]
    fn the_probe_refuses_when_the_terminal_is_not_a_terminal() {
        // The suite runs with the usual redirected harness, so this is the real
        // answer rather than a mock: no terminal on either end, no probe, and
        // nothing read.
        assert!(
            !probe_allowed(),
            "the test process has no terminal on stdin or stdout"
        );
        assert_eq!(query(), Probe::none());
    }
}
