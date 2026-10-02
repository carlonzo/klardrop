//! Motion: the small, truthful animations.
//!
//! Motion in this client exists for exactly three states, and all three are
//! states the *daemon* is in, not states the client invents:
//!
//!   * **connecting** — the daemon has not answered the first `/state` yet;
//!   * **discovering** — a device list is being watched for a change;
//!   * **transferring** — the daemon reports bytes moving for a request.
//!
//! A fourth state decorates a result rather than a wait: **flourish**, the
//! acknowledgement drawn for a moment after the daemon reports a send
//! completed. It is the one animation here that is not a claim about work in
//! progress, which is exactly why it carries the same words in every frame —
//! see [`FLOURISH_STATIC`].
//!
//! Nothing here ever stands in for a result. An animation stops the instant the
//! daemon's own state changes, and `--no-motion` removes all of it without
//! changing a single word on screen: every animated row also has a static,
//! truthful form.
//!
//! ## Rate
//!
//! [`FRAME_INTERVAL`] is 100 ms — ten frames a second. That is fast enough to
//! read as continuous and slow enough to stay under a percent of a core. The
//! clock is only advanced while something is actually animating: an idle TUI
//! asks [`Motion::advance`] nothing and redraws nothing, so no timer is left
//! running for an animation nobody is watching.

use std::time::{Duration, Instant};

/// The fastest the client will ever redraw for animation: 10 fps.
pub const FRAME_INTERVAL: Duration = Duration::from_millis(100);

/// Braille spinner, drawn with half-block-free glyphs every terminal has.
const BRAILLE: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

/// ASCII spinner, for a terminal that cannot show braille at all.
const ASCII: [&str; 4] = ["|", "/", "-", "\\"];

/// A moving indicator for a transfer in flight. The filled part is the daemon's
/// own percentage — the bar never claims progress the daemon did not report.
const BAR_FILLED: char = '█';
/// The empty part of the same bar.
const BAR_EMPTY: char = '░';
const BAR_LEFT: char = '▕';
const BAR_RIGHT: char = '▏';
/// The partial cell at the head of a running transfer's bar, in its two
/// phases. Neither is [`BAR_FILLED`], so the filled count is always exactly
/// the daemon's percentage.
const BAR_HEAD: char = '▌';

const BAR_HEAD_ALT: char = '▎';

/// How long the success flourish runs. Long enough to read as a deliberate
/// "that landed", short enough that it never delays the next thing the user
/// does. It is a fixed window, not a repeating animation: it always ends.
pub const FLOURISH_DURATION: Duration = Duration::from_millis(800);

/// The flourish: a spark that converges on a check over three frames and then
/// rests. Every frame contains the check, so a reader who glances at any of
/// them sees the result rather than a shape they must wait for — withholding
/// the answer until the last frame would make the user wait on a result the
/// daemon has already given.
///
/// The spark is `U+00B7` MIDDLE DOT rather than a space because every drawn
/// string goes through [`crate::tui::escape::sanitize`], which collapses runs
/// of whitespace: a space-padded flourish would reach the screen as its final
/// frame on the first draw.
const FLOURISH: [&str; 4] = [
    "\u{2713}\u{00b7}\u{00b7}\u{00b7}",
    "\u{2713}\u{00b7}\u{00b7}",
    "\u{2713}\u{00b7}",
    "\u{2713}",
];

/// What `--no-motion` shows in place of the flourish: the final frame, held.
/// Motion is decoration on a result, never the result, so turning it off
/// changes which frame is on screen and nothing else.
pub const FLOURISH_STATIC: &str = "\u{2713}";
pub const BAR_WIDTH: usize = 20;

/// Why something is animating. The renderer turns this into a glyph; nothing
/// else needs to know.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Activity {
    /// Nothing is moving, so nothing should be drawn or redrawn.
    Idle,
    /// Waiting for the daemon to answer.
    Connecting,
    /// A request is in flight and its progress is known.
    ///
    /// `settled` is the daemon's own word, not the client's patience: a
    /// transfer the daemon has already finished keeps its bar, and its
    /// percentage, but stops moving the moment the phase turns terminal.
    Transferring { percent: u8, settled: bool },
    /// A send has just reached a terminal outcome and is being acknowledged.
    Flourish,
}

impl Activity {
    /// Whether this state has a frame that changes over time.
    ///
    /// Everything else — an idle client, a transfer the daemon has finished —
    /// is drawn once and then left alone, which is what keeps the input loop's
    /// poll the only thing waking it up.
    pub fn is_animating(self) -> bool {
        matches!(
            self,
            Activity::Connecting
                | Activity::Flourish
                | Activity::Transferring { settled: false, .. }
        )
    }
}

/// The animation clock.
///
/// `enabled` is the user's `--no-motion`, resolved once at startup: with motion
/// off this type still answers every question, always with the truthful static
/// answer, so no call site needs a second code path.
#[derive(Debug, Clone)]
pub struct Motion {
    enabled: bool,
    frame: usize,
    last_advance: Instant,
    /// A braille spinner needs a terminal that can show braille.
    braille: bool,
}

impl Motion {
    /// A clock that never advances.
    pub fn disabled(now: Instant) -> Self {
        Self {
            enabled: false,
            frame: 0,
            last_advance: now,
            braille: true,
        }
    }

    /// A clock capped at [`FRAME_INTERVAL`], starting at `now`.
    pub fn enabled(now: Instant, braille: bool) -> Self {
        Self {
            enabled: true,
            frame: 0,
            last_advance: now,
            braille,
        }
    }

    /// The current frame index. Meaningless when motion is off, and always 0
    /// then, so a static screen cannot drift.
    pub fn frame(&self) -> usize {
        if self.enabled {
            self.frame
        } else {
            0
        }
    }

    /// Advances the clock and reports whether the screen must be redrawn.
    ///
    /// Returns `false` — and touches nothing — when motion is off, when the
    /// caller has nothing to animate, or when less than [`FRAME_INTERVAL`] has
    /// passed. That last rule is the rate cap, and the first two are why an
    /// idle client, and one whose transfer the daemon has finished, spend no
    /// CPU on animation at all.
    pub fn advance(&mut self, now: Instant, activity: Activity) -> bool {
        if !self.enabled || !activity.is_animating() {
            return false;
        }
        if now.duration_since(self.last_advance) < FRAME_INTERVAL {
            return false;
        }
        self.last_advance = now;
        self.frame = self.frame.wrapping_add(1);
        true
    }

    /// The spinner glyph for the current frame, or `None` when no connection is
    /// being waited on — so a caller cannot draw a spinner that means nothing.
    pub fn spinner(&self, activity: Activity) -> Option<&'static str> {
        if activity != Activity::Connecting {
            return None;
        }
        let set = if self.braille {
            &BRAILLE[..]
        } else {
            &ASCII[..]
        };
        Some(set[self.frame() % set.len()])
    }

    /// The acknowledgement shown after the daemon reports a completed send.
    ///
    /// The words are the same in every frame and under `--no-motion`; only the
    /// inking changes. That is the whole contract: the result is never withheld
    /// to make an animation watchable.
    pub fn flourish(&self, activity: Activity) -> Option<&'static str> {
        if activity != Activity::Flourish {
            return None;
        }
        if !self.enabled {
            return Some(FLOURISH_STATIC);
        }
        Some(FLOURISH[(self.frame() / 2).min(FLOURISH.len() - 1)])
    }

    /// The transfer bar. Truthful in both forms: the filled cells are the
    /// daemon's own percentage, and the head moves only while the daemon says
    /// the transfer is still running.
    ///
    /// A `settled` transfer keeps its bar and its percentage — that is still
    /// what the daemon reported — but the head stops alternating, so the last
    /// thing on screen stops moving the instant the outcome is terminal.
    pub fn transfer_bar(&self, activity: Activity) -> Option<String> {
        let Activity::Transferring { percent, settled } = activity else {
            return None;
        };
        let percent = percent.min(100) as usize;
        let filled = percent * BAR_WIDTH / 100;
        let mut bar = String::with_capacity(BAR_WIDTH + 2);
        bar.push(BAR_LEFT);
        for cell in 0..BAR_WIDTH {
            if cell < filled {
                bar.push(BAR_FILLED);
            } else if cell == filled && percent < 100 {
                // A moving head exactly on the boundary: a partial cell, never
                // a whole one, so the filled count is always the percentage.
                // `settled` freezes it — the daemon has already said how this
                // ended, so nothing here is still in motion.
                bar.push(if self.enabled && !settled && self.frame % 2 == 1 {
                    BAR_HEAD
                } else {
                    BAR_HEAD_ALT
                });
            } else {
                bar.push(BAR_EMPTY);
            }
        }
        bar.push(BAR_RIGHT);
        Some(bar)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t0() -> Instant {
        Instant::now()
    }

    /// A transfer the daemon still reports as in flight.
    fn running(percent: u8) -> Activity {
        Activity::Transferring {
            percent,
            settled: false,
        }
    }

    #[test]
    fn nothing_animates_when_nothing_is_happening() {
        let mut motion = Motion::enabled(t0(), true);
        let mut now = t0();
        for _ in 0..100 {
            now += FRAME_INTERVAL;
            assert!(
                !motion.advance(now, Activity::Idle),
                "an idle clock never advances"
            );
        }
        assert_eq!(motion.frame(), 0);
        assert_eq!(motion.spinner(Activity::Idle), None);
        assert_eq!(motion.transfer_bar(Activity::Idle), None);
    }

    #[test]
    fn no_motion_means_no_advancement_and_a_static_screen() {
        let mut motion = Motion::disabled(t0());
        let mut now = t0();
        for _ in 0..100 {
            now += FRAME_INTERVAL;
            assert!(!motion.advance(now, Activity::Connecting));
        }
        assert_eq!(motion.frame(), 0, "the frame never moves with --no-motion");
        // The row is still truthful, it is just still.
        assert!(motion.spinner(Activity::Connecting).is_some());
        let bar = motion
            .transfer_bar(running(50))
            .expect("a bar is still drawn");
        assert_eq!(bar.matches(BAR_FILLED).count(), BAR_WIDTH / 2);
    }

    #[test]
    fn the_rate_is_capped_at_ten_frames_a_second() {
        let mut motion = Motion::enabled(t0(), true);
        let start = t0();
        // Ten ticks spread over one second, each 100 ms apart: every one is a
        // redraw, and none of them is early.
        let mut redraws = 0;
        for step in 1..=10 {
            let now = start + FRAME_INTERVAL * step;
            if motion.advance(now, Activity::Connecting) {
                redraws += 1;
            }
        }
        assert_eq!(redraws, 10, "one second of motion is ten frames");

        // A tick 99 ms after the last one is refused.
        let mut motion = Motion::enabled(t0(), true);
        let base = t0();
        assert!(motion.advance(base + FRAME_INTERVAL, Activity::Connecting));
        assert!(
            !motion.advance(
                base + FRAME_INTERVAL * 2 - Duration::from_millis(1),
                Activity::Connecting
            ),
            "the cap holds"
        );
    }

    #[test]
    fn a_sub_interval_tick_burst_produces_one_frame() {
        let mut motion = Motion::enabled(t0(), true);
        let start = t0();
        let mut redraws = 0;
        // Sixty poll wake-ups in the first second, as the input loop really does.
        for step in 0..60 {
            let now = start + Duration::from_millis(1000 * step / 60);
            if motion.advance(now, Activity::Connecting) {
                redraws += 1;
            }
        }
        assert_eq!(redraws, 9, "sixty wake-ups become nine frames, not sixty");
    }

    #[test]
    fn the_spinner_cycles_through_its_own_glyph_set() {
        let mut motion = Motion::enabled(t0(), true);
        let start = t0();
        let mut seen = std::collections::HashSet::new();
        for step in 0..10 {
            motion.advance(start + FRAME_INTERVAL * step, Activity::Connecting);
            seen.insert(motion.spinner(Activity::Connecting).expect("spinner"));
        }
        assert_eq!(seen.len(), 10, "every braille frame is distinct");
    }

    #[test]
    fn the_bar_reports_the_daemons_percentage_and_nothing_more() {
        let motion = Motion::disabled(t0());
        let none = motion.transfer_bar(running(0)).expect("bar");
        assert_eq!(none.chars().filter(|c| *c == BAR_FILLED).count(), 0);

        let half = motion.transfer_bar(running(50)).expect("bar");
        assert_eq!(
            half.chars().filter(|c| *c == BAR_FILLED).count(),
            BAR_WIDTH / 2
        );

        let full = motion.transfer_bar(running(100)).expect("bar");
        assert_eq!(full.chars().filter(|c| *c == BAR_FILLED).count(), BAR_WIDTH);
        assert!(
            !full.contains(BAR_EMPTY),
            "a completed transfer has no empty cells left: {full}"
        );
    }

    #[test]
    fn a_percentage_over_one_hundred_is_clamped_not_wrapped() {
        let motion = Motion::disabled(t0());
        let bar = motion.transfer_bar(running(250)).expect("bar");
        assert_eq!(bar.chars().filter(|c| *c == BAR_FILLED).count(), BAR_WIDTH);
    }

    #[test]
    fn a_settled_transfer_keeps_its_bar_but_stops_moving() {
        let mut motion = Motion::enabled(t0(), true);
        let start = t0();
        let settled = Activity::Transferring {
            percent: 50,
            settled: true,
        };

        let mut bars = Vec::new();
        for step in 0..10 {
            // The daemon has said how this ended, so the clock is never asked
            // to move: no advance, no redraw, no change.
            assert!(
                !motion.advance(start + FRAME_INTERVAL * step, settled),
                "a settled transfer must not ask for a redraw"
            );
            bars.push(
                motion
                    .transfer_bar(settled)
                    .expect("the bar is still drawn"),
            );
        }
        assert!(
            bars.windows(2).all(|pair| pair[0] == pair[1]),
            "a settled bar is frozen, not moving: {bars:?}"
        );
        assert_eq!(
            bars[0].chars().filter(|c| *c == BAR_FILLED).count(),
            BAR_WIDTH / 2,
            "freezing must not change the daemon's percentage"
        );
    }

    #[test]
    fn a_running_transfer_moves_and_a_settled_one_does_not() {
        let mut motion = Motion::enabled(t0(), true);
        let start = t0();
        let running_bar = motion.transfer_bar(running(50)).expect("bar");
        assert!(motion.advance(start + FRAME_INTERVAL, running(50)));
        let next_bar = motion.transfer_bar(running(50)).expect("bar");
        assert_ne!(
            running_bar, next_bar,
            "an in-flight transfer's head has to move"
        );
        assert_eq!(
            running_bar.chars().filter(|c| *c == BAR_FILLED).count(),
            next_bar.chars().filter(|c| *c == BAR_FILLED).count(),
            "the head may move but the filled count may not: the filled cells are the daemon's percentage"
        );
    }

    #[test]
    fn the_flourish_never_withholds_its_result_and_always_ends() {
        let mut motion = Motion::enabled(t0(), true);
        let start = t0();
        let mut seen = Vec::new();
        for step in 0..8 {
            motion.advance(start + FRAME_INTERVAL * step, Activity::Flourish);
            let glyph = motion.flourish(Activity::Flourish).expect("a glyph");
            assert!(
                glyph.contains('\u{2713}'),
                "every frame carries the result, not a shape to wait for: {glyph:?}"
            );
            seen.push(glyph);
        }
        assert!(
            seen.windows(2).any(|pair| pair[0] != pair[1]),
            "the flourish has to actually animate: {seen:?}"
        );
        // It settles rather than cycling, so it cannot become a permanent
        // animation with a wakeup behind it.
        let last = seen[seen.len() - 1];
        motion.advance(start + FRAME_INTERVAL * 8, Activity::Flourish);
        assert_eq!(motion.flourish(Activity::Flourish), Some(last));
    }

    #[test]
    fn no_motion_shows_the_static_result_and_never_animates() {
        let motion = Motion::disabled(t0());
        assert_eq!(
            motion.flourish(Activity::Flourish),
            Some(FLOURISH_STATIC),
            "--no-motion still acknowledges the send"
        );
        let bar = motion
            .transfer_bar(running(50))
            .expect("a bar is still drawn");
        assert_eq!(bar.matches(BAR_FILLED).count(), BAR_WIDTH / 2);
    }

    #[test]
    fn only_states_that_change_over_time_are_animating() {
        assert!(Activity::Connecting.is_animating());
        assert!(Activity::Flourish.is_animating());
        assert!(running(50).is_animating());
        assert!(!Activity::Idle.is_animating());
        assert!(
            !Activity::Transferring {
                percent: 100,
                settled: true
            }
            .is_animating(),
            "a finished transfer leaves no timer behind it"
        );
    }
}
