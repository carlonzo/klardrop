//! Presentation, separated from layout and from the code that decides what is
//! on screen.
//!
//! [`crate::tui::render`] never mentions a style or a glyph directly: it asks
//! the theme. That is what lets a palette be swapped without touching a line
//! of drawing code.
//!
//! Every palette here respects one rule: **colour is never the only carrier of
//! meaning**. Selection is a marker glyph, a failure is a word and a marker,
//! reachability, trust and pairing are glyphs *and* words. So the ladder from
//! truecolor to indexed to monochrome loses saturation, never information.
//!
//! ## The shipped palette: Tokyo Night
//!
//! Values are taken verbatim from the
//! [tokyonight.nvim](https://github.com/folke/tokyonight.nvim) palette:
//! background `#1a1b26`, foreground `#c0caf5`, muted `#565f89`,
//! accent/focus `#7aa2f7`, success `#9ece6a`, warning `#e0af68`, error
//! `#f7768e`. The palette is MIT-licensed, © Follin Brun (folke/tokyonight.nvim);
//! the seven values are copied verbatim and no upstream code is vendored.
//!
//! ## The colour ladder
//!
//! [`Depth`] picks the richest colour the terminal can actually show:
//! * [`Depth::TrueColor`] for `COLORTERM=truecolor|24bit`,
//! * [`Depth::Indexed`] for a `TERM` that advertises 256 colours,
//! * [`Depth::Monochrome`] otherwise — and for `TERM=dumb`, and whenever the
//!   user passed `--no-color` or set `NO_COLOR`.
//!
//! ## `--no-color` and `NO_COLOR`
//!
//! These mean **no ANSI at all**, not "less colour": every style the renderer
//! draws with becomes the terminal's own default, so not one `SGR` sequence is
//! emitted. Suppression lives in [`crate::tui::render::draw`]'s `style()` helper
//! rather than in the palette, because it is a property of *drawing* and not of
//! a colour choice — that helper is the single point every drawn style passes
//! through, so no call site can reintroduce colour.
//!
//! Nothing is lost but the colour, because nothing was ever colour-only.
//!
//! ## `system` and `auto`
//!
//! [`ThemeChoice::System`] asks the terminal for its own foreground and
//! background — see [`crate::tui::palette`] for how, and for every reason that
//! query is interactive-only, bounded and non-destructive — and derives each
//! role from the pair:
//!
//! | role | derived as |
//! |------|-----------|
//! | body text, toasts | the terminal's foreground, unchanged |
//! | muted, unfocused | the midpoint of the terminal's two colours |
//! | accent, selection | the foreground moved [`HUE_MIX`] towards Tokyo Night's accent |
//! | success / warning / error | the foreground moved [`HUE_MIX`] towards Tokyo Night's matching role |
//! | QR modules | background and foreground, which are the two most different values the terminal already agreed to show |
//!
//! Mixing towards Tokyo Night keeps the *identity* of each role — an accent is
//! blue, an error is red — while sitting on the user's own foreground hue
//! instead of fighting it.
//!
//! Any failure falls back to Tokyo Night, and the fallback is the whole of the
//! behaviour: a terminal that does not implement the query, a reply that never
//! arrives, a reply that arrives half-formed, and a foreground equal to its
//! own background are all the same thing, which is Tokyo Night at the depth
//! the ladder picked. [`ThemeChoice::Auto`] is `system` with that fallback
//! already inside it, and is the default.

use ratatui::style::{Color, Modifier, Style};

/// The `--theme` choices. `Auto` is the default: it tries the terminal's own
/// palette and falls back to Tokyo Night.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThemeChoice {
    Auto,
    System,
    TokyoNight,
}

/// How much colour the terminal and the user allow.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Depth {
    /// 24-bit RGB.
    TrueColor,
    /// The xterm 256-colour cube/grayscale ramp.
    Indexed,
    /// No colour: attributes only.
    Monochrome,
}

/// Tokyo Night, the only shipped palette, from tokyonight.nvim.
mod palette {
    /// Background — never drawn as a fill, kept for reference and for the
    /// system-palette probe that wants a background to read.
    pub const BG: (u8, u8, u8) = (0x1a, 0x1b, 0x26);
    /// Default foreground.
    pub const FG: (u8, u8, u8) = (0xc0, 0xca, 0xf5);
    /// De-emphasised text.
    pub const MUTED: (u8, u8, u8) = (0x56, 0x5f, 0x89);
    /// Accent, and the focused pane.
    pub const ACCENT: (u8, u8, u8) = (0x7a, 0xa2, 0xf7);
    /// A confirmed outcome.
    pub const SUCCESS: (u8, u8, u8) = (0x9e, 0xce, 0x6a);
    /// Something pending: connecting, loading.
    pub const WARNING: (u8, u8, u8) = (0xe0, 0xaf, 0x68);
    /// A failure.
    pub const ERROR: (u8, u8, u8) = (0xf7, 0x76, 0x8e);
}

/// Everything the renderer needs to draw, in one place.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Theme {
    /// Selected device row.
    pub selected: Style,
    /// The focused pane's border and title.
    pub focus: Style,
    /// The pane that is not focused: still readable, clearly secondary.
    pub unfocus: Style,
    /// De-emphasised text: hints, device ids, byte counts.
    pub muted: Style,
    /// A confirmed outcome, and the daemon answering.
    pub success: Style,
    /// Something in progress: connecting, loading a conversation.
    pub warning: Style,
    /// A failure. Always paired with a word, never with colour alone.
    pub error: Style,
    /// The compose input line.
    pub input: Style,
    /// A transient message shown over the footer.
    pub toast: Style,
    /// Unread marker and transfer progress.
    pub badge: Style,
    /// A dark QR module: the filled half of a half-block cell.
    pub qr_dark: Style,
    /// A light QR module. Distinct from [`Self::qr_dark`] by glyph as well as
    /// by colour, so `NO_COLOR` costs legibility rather than the code itself.
    pub qr_light: Style,
}

/// Row marker for the selected device. A glyph, so selection survives both
/// `NO_COLOR` and a monochrome terminal.
pub const SELECTED_MARKER: &str = ">";
/// Reachable device glyph.
pub const REACHABLE_MARKER: &str = "*";
/// Trusted device glyph.
pub const TRUSTED_MARKER: &str = "+";
/// Device glyph when it cannot be reached right now.
pub const OFFLINE_MARKER: &str = "-";
/// A pending pairing decision.
pub const PENDING_MARKER: &str = "!";
/// Opening half of the unread badge.
pub const UNREAD_BADGE_OPEN: &str = "(";
/// Closing half of the unread badge.
pub const UNREAD_BADGE_CLOSE: &str = ")";
/// Left-pointing marker for an outgoing message.
pub const OUTGOING_MARKER: &str = ">";
/// Right-pointing marker for an incoming message.
pub const INCOMING_MARKER: &str = "<";
/// Shown after a message that failed to deliver.
pub const FAILED_MARKER: &str = "x";

impl Theme {
    /// The attribute-only theme. Legible because it never relies on colour.
    pub const fn monochrome() -> Self {
        Self {
            selected: Style::new().add_modifier(Modifier::REVERSED),
            focus: Style::new().add_modifier(Modifier::BOLD),
            unfocus: Style::new().add_modifier(Modifier::DIM),
            muted: Style::new().add_modifier(Modifier::DIM),
            success: Style::new().add_modifier(Modifier::BOLD),
            warning: Style::new().add_modifier(Modifier::ITALIC),
            error: Style::new()
                .add_modifier(Modifier::BOLD)
                .add_modifier(Modifier::CROSSED_OUT),
            input: Style::new().add_modifier(Modifier::BOLD),
            toast: Style::new().add_modifier(Modifier::REVERSED),
            badge: Style::new().add_modifier(Modifier::BOLD),
            // Reversal, not a colour. The two halves of a cell also differ by
            // glyph, so a monochrome terminal still reads the matrix.
            qr_dark: Style::new().add_modifier(Modifier::REVERSED),
            qr_light: Style::new(),
        }
    }

    /// Tokyo Night at the given colour depth.
    pub fn tokyo_night(depth: Depth) -> Self {
        if depth == Depth::Monochrome {
            return Self::monochrome();
        }
        let c = |rgb: (u8, u8, u8)| Color::Rgb(rgb.0, rgb.1, rgb.2);
        let ci = |rgb: (u8, u8, u8)| Color::Indexed(nearest_index(rgb));
        let colour = match depth {
            Depth::TrueColor => c,
            _ => ci,
        };
        Self {
            selected: Style::new()
                .fg(colour(palette::ACCENT))
                .add_modifier(Modifier::BOLD),
            focus: Style::new().fg(colour(palette::ACCENT)),
            unfocus: Style::new().fg(colour(palette::MUTED)),
            muted: Style::new().fg(colour(palette::MUTED)),
            success: Style::new().fg(colour(palette::SUCCESS)),
            warning: Style::new().fg(colour(palette::WARNING)),
            error: Style::new()
                .fg(colour(palette::ERROR))
                .add_modifier(Modifier::BOLD),
            input: Style::new().fg(colour(palette::FG)),
            toast: Style::new()
                .fg(colour(palette::FG))
                .add_modifier(Modifier::BOLD),
            badge: Style::new().fg(colour(palette::WARNING)),
            // A scanner reads contrast, not hue: the dark module is the
            // palette's own background and the light one its foreground, which
            // are the two most different values the terminal already agreed to
            // show.
            qr_dark: Style::new().fg(colour(palette::BG)),
            qr_light: Style::new().fg(colour(palette::FG)),
        }
    }

    /// The style for a daemon connection state. Colour is a hint; the label
    /// and the weight are what survive a dumb terminal.
    pub fn connection(&self, connected: bool) -> Style {
        if connected {
            self.success
        } else {
            self.error
        }
    }
}

/// The nearest xterm-256 index for an RGB triple, searched over the whole
/// palette so it is correct for grey and cube colours alike.
fn nearest_index((r, g, b): (u8, u8, u8)) -> u8 {
    let mut best = (0u8, u32::MAX);
    for index in 16u8..=255 {
        let (ir, ig, ib) = xterm_rgb(index);
        let dr = r as i32 - ir as i32;
        let dg = g as i32 - ig as i32;
        let db = b as i32 - ib as i32;
        let distance = (dr * dr + dg * dg + db * db) as u32;
        if distance < best.1 {
            best = (index, distance);
        }
    }
    best.0
}

/// The RGB of one xterm-256 palette entry. Entries 16..231 are the 6×6×6 cube,
/// 232..255 the grayscale ramp, and 0..15 the sixteen base colours (approximated
/// with the well-known xterm values, which is where every nearest-colour search
/// starts anyway).
fn xterm_rgb(index: u8) -> (u8, u8, u8) {
    const BASE: [(u8, u8, u8); 16] = [
        (0, 0, 0),
        (128, 0, 0),
        (0, 128, 0),
        (128, 128, 0),
        (0, 0, 128),
        (128, 0, 128),
        (0, 128, 128),
        (192, 192, 192),
        (128, 128, 128),
        (255, 0, 0),
        (0, 255, 0),
        (255, 255, 0),
        (0, 0, 255),
        (255, 0, 255),
        (0, 255, 255),
        (255, 255, 255),
    ];
    if index < 16 {
        return BASE[index as usize];
    }
    if index < 232 {
        let n = index - 16;
        let levels = [0u8, 95, 135, 175, 215, 255];
        (
            levels[(n / 36) as usize],
            levels[((n % 36) / 6) as usize],
            levels[(n % 6) as usize],
        )
    } else {
        let level = 8 + (index - 232) as u16 * 10;
        let level = level as u8;
        (level, level, level)
    }
}

/// Reads the terminal's advertised colour depth from the environment.
///
/// `no_color` is the user's `--no-color`/`NO_COLOR` request; when it is set the
/// result is always [`Depth::Monochrome`] regardless of what the terminal
/// claims.
pub fn detect_depth(env: &dyn Env, no_color: bool) -> Depth {
    if no_color {
        return Depth::Monochrome;
    }
    if env.term_dumb() {
        return Depth::Monochrome;
    }
    if env.truecolor() {
        return Depth::TrueColor;
    }
    if env.indexed() {
        return Depth::Indexed;
    }
    Depth::Monochrome
}

/// A terminal's answer to "what colours are you": its foreground and its
/// background, as RGB triples.
pub type FgBg = ((u8, u8, u8), (u8, u8, u8));

/// The slice of the environment the colour ladder needs. A trait, not
/// `std::env` directly, so the ladder is testable without touching the real
/// process environment.
///
/// The terminal's own colours are *not* read here: that needs a round trip on
/// the input stream, and it belongs to [`crate::tui::palette`], which owns
/// every rule about doing it safely.
pub trait Env {
    fn var(&self, key: &str) -> Option<String>;
    fn term_dumb(&self) -> bool {
        self.var("TERM").is_some_and(|term| term == "dumb")
    }
    fn truecolor(&self) -> bool {
        self.var("COLORTERM")
            .is_some_and(|c| c == "truecolor" || c == "24bit")
    }
    fn indexed(&self) -> bool {
        self.var("TERM")
            .is_some_and(|term| term.contains("256color") || term.contains("kitty"))
    }
}

/// The real process environment.
pub struct ProcessEnv;

impl Env for ProcessEnv {
    fn var(&self, key: &str) -> Option<String> {
        std::env::var(key).ok()
    }
}

/// Whether colour has been suppressed by flag or by `NO_COLOR` in the
/// environment.
pub fn no_color_requested(flag: bool, env: &dyn Env) -> bool {
    flag || env.var("NO_COLOR").is_some_and(|v| !v.is_empty())
}

/// Resolves the `--theme` choice into a palette, at an already-probed depth.
///
/// * `TokyoNight` is always Tokyo Night, at the terminal's depth. It never
///   touches the terminal's own colours, so asking for it never costs a round
///   trip.
/// * `System` uses the colours the terminal answered with; anything else falls
///   back to Tokyo Night.
/// * `Auto` tries `System` first and falls back the same way.
///
/// `probed` is [`crate::tui::palette::Probe::palette`]. The caller owns the
/// question, because owning it is what makes "interactive only" enforceable at
/// all.
///
/// Suppression (`--no-color` / `NO_COLOR`) is honoured by the depth ladder
/// and, at draw time, by `render::draw`'s `style()` helper.
pub fn resolve(choice: ThemeChoice, probed: Option<FgBg>, depth: Depth) -> Theme {
    match choice {
        ThemeChoice::TokyoNight => Theme::tokyo_night(depth),
        // `auto` is `system` with the fallback already inside it.
        ThemeChoice::System | ThemeChoice::Auto => system_theme(probed, depth),
    }
}

/// Whether a choice can only be answered by asking the terminal.
///
/// The caller skips the probe for every other choice, which is why
/// `--theme tokyo-night` never writes a byte to the terminal to find out what
/// colour it is.
pub fn wants_terminal_palette(choice: ThemeChoice) -> bool {
    matches!(choice, ThemeChoice::System | ThemeChoice::Auto)
}

/// How far a semantic role moves from the terminal's own foreground towards
/// the Tokyo Night colour that gives the role its meaning.
///
/// Enough to be recognisably that role — a success is green, an error is red —
/// and not so far that the client's palette stops belonging to the terminal the
/// user is looking at.
const HUE_MIX: f32 = 0.6;

/// The terminal's own palette, or Tokyo Night when it did not answer.
///
/// Every way this can fail is the same way: nothing in, Tokyo Night out. That
/// covers a terminal which does not implement OSC 10 and 11, a reply that
/// never arrived before the deadline, a reply that only half arrived, and a
/// foreground equal to its own background — an answer this shape cannot be.
fn system_theme(probed: Option<FgBg>, depth: Depth) -> Theme {
    // With no colour to draw there is no palette to derive, and asking the
    // terminal would cost a round trip to decide nothing.
    if depth == Depth::Monochrome {
        return Theme::monochrome();
    }
    let Some((fg, bg)) = probed else {
        return Theme::tokyo_night(depth);
    };
    if fg == bg {
        // An answer this shape cannot be right; treat it as no answer.
        return Theme::tokyo_night(depth);
    }

    // De-emphasis is the midpoint between the terminal's own two colours: the
    // only muted value that is guaranteed readable on a background it was not
    // computed for, on a light and a dark terminal alike.
    let muted = mix(fg, bg, 0.5);
    // The rest keep the identity of their Tokyo Night role while sitting on the
    // user's own foreground hue instead of fighting it.
    let role = |tokyo: (u8, u8, u8)| mix(fg, tokyo, HUE_MIX);
    let accent = role(palette::ACCENT);

    Theme {
        selected: bold(recolour(Style::new(), accent, depth)),
        focus: recolour(Style::new(), accent, depth),
        unfocus: recolour(Style::new(), muted, depth),
        muted: recolour(Style::new(), muted, depth),
        success: recolour(Style::new(), role(palette::SUCCESS), depth),
        warning: recolour(Style::new(), role(palette::WARNING), depth),
        error: bold(recolour(Style::new(), role(palette::ERROR), depth)),
        input: recolour(Style::new(), fg, depth),
        toast: bold(recolour(Style::new(), fg, depth)),
        badge: bold(recolour(Style::new(), role(palette::WARNING), depth)),
        // A scanner reads contrast, not hue: the dark module is the terminal's
        // own background and the light one its foreground, which are the two
        // most different values the terminal already agreed to show.
        qr_dark: recolour(Style::new(), bg, depth),
        qr_light: recolour(Style::new(), fg, depth),
    }
}

/// The same style in bold.
fn bold(style: Style) -> Style {
    style.add_modifier(Modifier::BOLD)
}

/// `a` moved `t` of the way towards `b`.
fn mix(a: (u8, u8, u8), b: (u8, u8, u8), t: f32) -> (u8, u8, u8) {
    let channel = |from: u8, to: u8| (from as f32 + (to as f32 - from as f32) * t).round() as u8;
    (channel(a.0, b.0), channel(a.1, b.1), channel(a.2, b.2))
}

/// The same style with its foreground replaced, at the resolved depth.
fn recolour(style: Style, rgb: (u8, u8, u8), depth: Depth) -> Style {
    match depth {
        Depth::TrueColor => style.fg(Color::Rgb(rgb.0, rgb.1, rgb.2)),
        Depth::Indexed => style.fg(Color::Indexed(nearest_index(rgb))),
        // Nothing to recolour with: keep the attributes, drop nothing.
        Depth::Monochrome => style,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::style::Color;

    struct FakeEnv(
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
    );

    impl Env for FakeEnv {
        fn var(&self, key: &str) -> Option<String> {
            match key {
                "TERM" => self.0.clone(),
                "COLORTERM" => self.1.clone(),
                "NO_COLOR" => self.2.clone(),
                _ => self.3.clone(),
            }
        }
    }

    #[test]
    fn depth_follows_what_the_terminal_claims() {
        let none = FakeEnv(None, None, None, None);
        assert_eq!(detect_depth(&none, false), Depth::Monochrome);

        let dumb = FakeEnv(Some("dumb".into()), Some("truecolor".into()), None, None);
        assert_eq!(
            detect_depth(&dumb, false),
            Depth::Monochrome,
            "TERM=dumb is monochrome even when COLORTERM lies"
        );

        let indexed = FakeEnv(Some("xterm-256color".into()), None, None, None);
        assert_eq!(detect_depth(&indexed, false), Depth::Indexed);

        let truecolor = FakeEnv(
            Some("xterm-kitty".into()),
            Some("truecolor".into()),
            None,
            None,
        );
        assert_eq!(detect_depth(&truecolor, false), Depth::TrueColor);

        let bit24 = FakeEnv(Some("xterm".into()), Some("24bit".into()), None, None);
        assert_eq!(detect_depth(&bit24, false), Depth::TrueColor);
    }

    #[test]
    fn no_color_flag_or_env_forces_monochrome() {
        let rich = FakeEnv(
            Some("xterm-256color".into()),
            Some("truecolor".into()),
            None,
            None,
        );
        assert_eq!(detect_depth(&rich, true), Depth::Monochrome, "--no-color");

        let env_off = FakeEnv(
            Some("xterm-256color".into()),
            Some("truecolor".into()),
            Some("1".into()),
            None,
        );
        assert!(no_color_requested(false, &env_off), "NO_COLOR=1");
        assert_eq!(
            detect_depth(&env_off, no_color_requested(false, &env_off)),
            Depth::Monochrome,
            "NO_COLOR is honoured through the same path the TUI uses"
        );
        assert_eq!(
            resolve(
                ThemeChoice::TokyoNight,
                None,
                detect_depth(&env_off, no_color_requested(false, &env_off))
            ),
            Theme::monochrome(),
            "and end to end: with no colour allowed, every role is the terminal's own default"
        );

        let env_empty = FakeEnv(
            Some("xterm-256color".into()),
            None,
            Some(String::new()),
            None,
        );
        assert!(!no_color_requested(false, &env_empty), "empty NO_COLOR");
        assert_eq!(
            detect_depth(&env_empty, false),
            Depth::Indexed,
            "an empty NO_COLOR is not a request"
        );
    }

    #[test]
    fn truecolor_emits_rgb_and_indexed_emits_an_index() {
        let rgb = Theme::tokyo_night(Depth::TrueColor);
        assert_eq!(
            rgb.focus.fg,
            Some(Color::Rgb(
                palette::ACCENT.0,
                palette::ACCENT.1,
                palette::ACCENT.2
            ))
        );

        let idx = Theme::tokyo_night(Depth::Indexed);
        match idx.focus.fg {
            Some(Color::Indexed(n)) => assert!(n >= 16, "a palette entry, not a base colour"),
            other => panic!("expected an indexed colour, got {other:?}"),
        }
    }

    #[test]
    fn monochrome_never_emits_a_colour() {
        let theme = Theme::tokyo_night(Depth::Monochrome);
        for style in [
            theme.selected,
            theme.focus,
            theme.unfocus,
            theme.muted,
            theme.success,
            theme.warning,
            theme.error,
            theme.input,
            theme.toast,
            theme.badge,
        ] {
            assert_eq!(style.fg, None, "monochrome must not set a foreground");
            assert_eq!(style.bg, None, "monochrome must not set a background");
        }
    }

    #[test]
    fn selection_and_failure_are_distinguishable_without_colour() {
        let theme = Theme::monochrome();
        assert_ne!(theme.selected, theme.unfocus);
        assert_ne!(theme.error, theme.success);
        assert_ne!(theme.warning, theme.error);
    }

    #[test]
    fn connection_style_follows_the_truth() {
        let theme = Theme::monochrome();
        assert_ne!(theme.connection(true), theme.connection(false));
    }

    #[test]
    fn the_indexed_ladder_stays_in_the_xterm_palette() {
        for rgb in [
            palette::BG,
            palette::FG,
            palette::MUTED,
            palette::ACCENT,
            palette::SUCCESS,
            palette::WARNING,
            palette::ERROR,
        ] {
            let index = nearest_index(rgb);
            assert!((16..=255).contains(&index), "{rgb:?} -> {index}");
        }
    }

    #[test]
    fn the_palette_is_tokyonight_nvims_verbatim() {
        // Pinned so a future edit to a colour is a deliberate, visible choice.
        assert_eq!(palette::BG, (0x1a, 0x1b, 0x26));
        assert_eq!(palette::FG, (0xc0, 0xca, 0xf5));
        assert_eq!(palette::MUTED, (0x56, 0x5f, 0x89));
        assert_eq!(palette::ACCENT, (0x7a, 0xa2, 0xf7));
        assert_eq!(palette::SUCCESS, (0x9e, 0xce, 0x6a));
        assert_eq!(palette::WARNING, (0xe0, 0xaf, 0x68));
        assert_eq!(palette::ERROR, (0xf7, 0x76, 0x8e));
    }

    /// The colour of one role, as the eight-bit triple it must have been.
    fn rgb_of(style: Style) -> (u8, u8, u8) {
        match style.fg {
            Some(Color::Rgb(r, g, b)) => (r, g, b),
            other => panic!("expected an rgb role, got {other:?}"),
        }
    }

    /// A terminal with a warm, dark, off-the-shelf palette.
    fn probed() -> Option<FgBg> {
        Some(((0xe0, 0xd0, 0xc0), (0x10, 0x14, 0x18)))
    }

    #[test]
    fn only_the_choices_that_need_the_terminal_ask_it() {
        assert!(wants_terminal_palette(ThemeChoice::System));
        assert!(wants_terminal_palette(ThemeChoice::Auto));
        assert!(
            !wants_terminal_palette(ThemeChoice::TokyoNight),
            "--theme tokyo-night must never put a query on the wire"
        );
    }

    #[test]
    fn system_draws_in_the_terminals_own_foreground() {
        let theme = resolve(ThemeChoice::System, probed(), Depth::TrueColor);
        assert_eq!(
            rgb_of(theme.input),
            (0xe0, 0xd0, 0xc0),
            "body text is the terminal's own foreground"
        );
        assert_eq!(rgb_of(theme.toast), (0xe0, 0xd0, 0xc0));
        assert_eq!(rgb_of(theme.qr_light), (0xe0, 0xd0, 0xc0));
        assert_eq!(
            rgb_of(theme.qr_dark),
            (0x10, 0x14, 0x18),
            "the QR's dark half is the terminal's own background"
        );
    }

    #[test]
    fn system_keeps_every_role_distinct_on_a_light_terminal() {
        let light = Some(((0x20, 0x20, 0x20), (0xff, 0xff, 0xff)));
        let theme = resolve(ThemeChoice::System, light, Depth::TrueColor);
        // De-emphasis is the midpoint of the terminal's own two colours, which
        // is the one muted value that reads on a background it was not
        // computed for.
        assert_eq!(rgb_of(theme.muted), (0x90, 0x90, 0x90));
        assert_eq!(rgb_of(theme.unfocus), (0x90, 0x90, 0x90));
        // And the semantic roles keep their identity: an accent is blue, a
        // success is green and an error is red, whatever the foreground was.
        let (ar, ag, ab) = rgb_of(theme.focus);
        let (sr, sg, sb) = rgb_of(theme.success);
        let (er, eg, eb) = rgb_of(theme.error);
        assert!(ab > ar && ab > ag, "the accent leans blue");
        assert!(sg > sr && sg > sb, "a success leans green");
        assert!(er > eg && er > eb, "an error leans red");
        assert_ne!(theme.warning, theme.error);
    }

    #[test]
    fn system_and_auto_answer_the_same_way() {
        assert_eq!(
            resolve(ThemeChoice::Auto, probed(), Depth::TrueColor),
            resolve(ThemeChoice::System, probed(), Depth::TrueColor)
        );
        assert_eq!(
            resolve(ThemeChoice::Auto, None, Depth::TrueColor),
            Theme::tokyo_night(Depth::TrueColor),
            "auto is system with the fallback already inside it"
        );
    }

    #[test]
    fn every_way_the_probe_can_fail_falls_back_to_tokyo_night() {
        // A terminal that does not implement OSC 10 and 11: nothing came back.
        assert_eq!(
            resolve(ThemeChoice::System, None, Depth::TrueColor),
            Theme::tokyo_night(Depth::TrueColor)
        );
        // A foreground equal to its own background is a glitch, not an answer.
        assert_eq!(
            resolve(
                ThemeChoice::System,
                Some(((0x20, 0x20, 0x20), (0x20, 0x20, 0x20))),
                Depth::TrueColor
            ),
            Theme::tokyo_night(Depth::TrueColor)
        );
        // A reply that only half arrived is not a palette: this is where
        // palette::Probe turns it into `None` before it is ever resolved.
        assert_eq!(
            crate::tui::palette::Probe {
                fg: Some((1, 2, 3)),
                bg: None,
                replay: Vec::new(),
            }
            .palette(),
            None
        );
    }

    #[test]
    fn tokyo_night_ignores_the_terminal_entirely() {
        assert_eq!(
            resolve(ThemeChoice::TokyoNight, probed(), Depth::TrueColor),
            Theme::tokyo_night(Depth::TrueColor),
            "an explicit choice is an answer whatever the terminal says"
        );
    }

    #[test]
    fn a_monochrome_depth_draws_no_colour_even_with_a_probe() {
        let theme = resolve(ThemeChoice::System, probed(), Depth::Monochrome);
        assert_eq!(theme, Theme::monochrome());
        for style in [theme.focus, theme.success, theme.error, theme.qr_dark] {
            assert_eq!(style.fg, None, "no colour may survive a monochrome depth");
        }
    }
}
