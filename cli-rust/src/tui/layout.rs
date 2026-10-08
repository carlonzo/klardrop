//! Pure layout arithmetic.
//!
//! Every rectangle the TUI draws into is computed here, from nothing but the
//! terminal size and which pane owns the screen. Nothing here touches a
//! terminal, a backend or a widget, so the whole geometry — including the
//! degenerate cases — is unit-testable at sizes nobody would ever run
//! interactively.
//!
//! The rules:
//!
//!   * a terminal narrower than [`MIN_WIDTH`] or shorter than [`MIN_HEIGHT`]
//!     gets the "too small" notice and nothing else: no widget is laid out, so
//!     no widget can be clipped outside the buffer;
//!   * from [`TWO_PANE_MIN_WIDTH`] columns up there are two panes, the left one
//!     clamped to `[LEFT_MIN, LEFT_MAX]` columns so it stays readable at 200
//!     columns and does not disappear at 80;
//!   * below that there is exactly one pane, and which one depends on whether a
//!     device is open. Escape goes back to the list.

use ratatui::layout::Rect;

/// Below this width and height the TUI refuses to draw anything but a notice.
pub const MIN_WIDTH: u16 = 20;
pub const MIN_HEIGHT: u16 = 8;

/// Header and footer heights, in rows.
pub const HEADER_ROWS: u16 = 1;
pub const FOOTER_ROWS: u16 = 2;

/// Two panes from this width up; below it the single-pane layout.
pub const TWO_PANE_MIN_WIDTH: u16 = 80;

/// Left pane width bounds, and the percentage it is derived from.
///
/// The device row is `marker glyphs name  reachability · trust (unread)`, and
/// a real name ("Fixture Phone") plus both words and the unread badge is 43
/// columns. A pane narrower than that would silently drop the words or the
/// badge, so the maximum is set above that and the renderer shrinks the *name*
/// first when a terminal is too narrow for it.
pub const LEFT_PERCENT: u16 = 40;
pub const LEFT_MIN: u16 = 24;
pub const LEFT_MAX: u16 = 48;

/// Smallest right pane worth drawing; keeps the left clamp from eating the
/// whole row on a terminal just wide enough for two panes.
pub const RIGHT_MIN: u16 = 20;

/// Which pane owns the screen when there is only room for one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SinglePane {
    /// No device is open: the device list.
    List,
    /// A device is open: its conversation.
    Conversation,
}

/// Every rectangle the renderer is allowed to draw into.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rects {
    pub header: Rect,
    pub footer: Rect,
    pub left: Rect,
    pub right: Rect,
    pub overlay: Rect,
}

impl Rects {
    /// The nothing-to-draw answer for a terminal too small to lay anything out.
    pub fn empty() -> Self {
        Self {
            header: Rect::default(),
            footer: Rect::default(),
            left: Rect::default(),
            right: Rect::default(),
            overlay: Rect::default(),
        }
    }

    /// `true` when the terminal can show the TUI at all.
    pub fn fits(width: u16, height: u16) -> bool {
        width >= MIN_WIDTH && height >= MIN_HEIGHT
    }

    /// The pane that is actually visible, given the screen is too narrow for
    /// both. `None` in the two-pane layout, where both are visible.
    pub fn visible_pane(&self) -> Option<SinglePane> {
        match (self.left.width == 0, self.right.width == 0) {
            (false, false) => None,
            (false, true) => Some(SinglePane::List),
            (true, false) => Some(SinglePane::Conversation),
            (true, true) => None,
        }
    }

    /// Everything between the header and the footer, as one rectangle.
    ///
    /// The device picker draws the device list across the whole body, because
    /// a picker has nothing to put in a second pane.
    pub fn body(&self) -> Rect {
        Rect {
            x: self.header.x,
            y: self.header.y + self.header.height,
            width: self.footer.x + self.footer.width - self.header.x,
            height: self
                .footer
                .y
                .saturating_sub(self.header.y + self.header.height),
        }
    }
}

/// Computes the layout for a `width` x `height` terminal.
///
/// `single` only matters below [`TWO_PANE_MIN_WIDTH`]; at or above it both
/// panes are returned and the caller ignores it.
pub fn compute(width: u16, height: u16, single: SinglePane) -> Rects {
    if !Rects::fits(width, height) {
        return Rects::empty();
    }

    let header = Rect {
        x: 0,
        y: 0,
        width,
        height: HEADER_ROWS,
    };
    let footer_y = height - FOOTER_ROWS;
    let footer = Rect {
        x: 0,
        y: footer_y,
        width,
        height: FOOTER_ROWS,
    };
    let body = Rect {
        x: 0,
        y: HEADER_ROWS,
        width,
        height: footer_y - HEADER_ROWS,
    };

    let (left, right) = if width >= TWO_PANE_MIN_WIDTH {
        let left_width = left_width(width);
        (
            Rect {
                width: left_width,
                ..body
            },
            Rect {
                x: left_width,
                width: width - left_width,
                ..body
            },
        )
    } else {
        match single {
            SinglePane::List => (body, Rect::default()),
            SinglePane::Conversation => (Rect::default(), body),
        }
    };

    Rects {
        header,
        footer,
        left,
        right,
        overlay: centered_overlay(width, height),
    }
}

/// The left pane's width: a percentage, clamped so it neither vanishes nor
/// swallows the conversation.
fn left_width(width: u16) -> u16 {
    let proportional = width.saturating_mul(LEFT_PERCENT) / 100;
    let clamped = proportional.clamp(LEFT_MIN, LEFT_MAX);
    // `RIGHT_MIN` is 20 and `TWO_PANE_MIN_WIDTH` is 80, so this can only bind
    // for a caller that asks for a two-pane layout below 80 columns.
    clamped.min(width.saturating_sub(RIGHT_MIN)).max(1)
}

/// The overlay box: centred, and always inside the buffer.
fn centered_overlay(width: u16, height: u16) -> Rect {
    const MARGIN: u16 = 2;
    const MAX_WIDTH: u16 = 72;
    const MAX_HEIGHT: u16 = 20;

    let available_width = width.saturating_sub(MARGIN * 2).max(1);
    let available_height = height.saturating_sub(MARGIN * 2).max(1);
    let box_width = available_width.min(MAX_WIDTH);
    let box_height = available_height.min(MAX_HEIGHT);

    Rect {
        x: (width - box_width) / 2,
        y: (height - box_height) / 2,
        width: box_width,
        height: box_height,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every rectangle must be inside the buffer, with no overlap between the
    /// persistent panes. This is the invariant that makes "no widget is laid
    /// out outside the buffer" checkable rather than aspirational.
    fn assert_sane(rects: &Rects, width: u16, height: u16, label: &str) {
        for (name, rect) in [
            ("header", rects.header),
            ("footer", rects.footer),
            ("left", rects.left),
            ("right", rects.right),
            ("overlay", rects.overlay),
        ] {
            assert!(
                rect.x + rect.width <= width && rect.y + rect.height <= height,
                "{label}: {name} {rect:?} escapes {width}x{height}"
            );
        }
        let overlap_x = rects.left.x < rects.right.x + rects.right.width
            && rects.right.x < rects.left.x + rects.left.width;
        let overlap_y = rects.left.y < rects.right.y + rects.right.height
            && rects.right.y < rects.left.y + rects.left.height;
        assert!(
            !(overlap_x && overlap_y),
            "{label}: panes overlap: {:?} / {:?}",
            rects.left,
            rects.right
        );
    }

    #[test]
    fn a_wide_terminal_clamps_the_left_pane_to_its_maximum() {
        let rects = compute(200, 50, SinglePane::List);
        assert_sane(&rects, 200, 50, "200x50");

        assert_eq!(rects.header, Rect::new(0, 0, 200, 1));
        assert_eq!(rects.footer, Rect::new(0, 48, 200, 2));
        assert_eq!(rects.left, Rect::new(0, 1, LEFT_MAX, 47));
        assert_eq!(rects.right, Rect::new(LEFT_MAX, 1, 200 - LEFT_MAX, 47));
        assert_eq!(rects.visible_pane(), None, "two panes are visible");
    }

    #[test]
    fn a_medium_terminal_takes_the_percentage_clamped_into_range() {
        let rects = compute(120, 40, SinglePane::List);
        assert_sane(&rects, 120, 40, "120x40");

        // 120 * 40 / 100 = 48, which is already the maximum.
        assert_eq!(rects.left.width, LEFT_MAX);
        assert_eq!(rects.right.width, 120 - LEFT_MAX);
        assert_eq!(rects.left.height, 40 - HEADER_ROWS - FOOTER_ROWS);
        assert_eq!(rects.footer.y, 38);
    }

    #[test]
    fn the_device_pane_is_wide_enough_for_a_full_row() {
        // The widest thing the device pane must ever show without shrinking:
        // two marker columns, three glyphs, a 13-column name, two spaces, both
        // status words and the unread badge.
        const FULL_ROW: u16 = 2 + 1 + 3 + 1 + 13 + 2 + 19 + 4;
        for width in TWO_PANE_MIN_WIDTH..=240u16 {
            let inner = compute(width, 24, SinglePane::List).left.width - 2;
            if width * LEFT_PERCENT / 100 >= FULL_ROW + 2 {
                assert!(
                    inner >= FULL_ROW,
                    "{width} columns: a device pane of {inner} columns would clip the badge"
                );
            }
        }
    }

    #[test]
    fn exactly_eighty_columns_still_gets_two_panes() {
        let rects = compute(80, 24, SinglePane::List);
        assert_sane(&rects, 80, 24, "80x24");

        assert_eq!(rects.left.width, 80 * LEFT_PERCENT / 100);
        assert!(rects.left.width >= LEFT_MIN);
        assert_eq!(rects.left.width + rects.right.width, 80);
        assert_eq!(rects.visible_pane(), None);
    }

    #[test]
    fn one_column_short_of_two_panes_collapses_to_one_pane() {
        let list = compute(79, 24, SinglePane::List);
        assert_sane(&list, 79, 24, "79x24 list");
        assert_eq!(list.left, Rect::new(0, 1, 79, 21));
        assert_eq!(list.right, Rect::default(), "no second pane below 80");
        assert_eq!(list.visible_pane(), Some(SinglePane::List));

        let conversation = compute(79, 24, SinglePane::Conversation);
        assert_sane(&conversation, 79, 24, "79x24 conversation");
        assert_eq!(conversation.left, Rect::default());
        assert_eq!(conversation.right, Rect::new(0, 1, 79, 21));
        assert_eq!(
            conversation.visible_pane(),
            Some(SinglePane::Conversation),
            "escape returns to the list, so the conversation takes the screen"
        );
    }

    #[test]
    fn a_small_but_usable_terminal_lays_out_exactly_once() {
        let rects = compute(40, 12, SinglePane::List);
        assert_sane(&rects, 40, 12, "40x12");

        assert_eq!(rects.header, Rect::new(0, 0, 40, 1));
        assert_eq!(rects.footer, Rect::new(0, 10, 40, 2));
        assert_eq!(rects.left, Rect::new(0, 1, 40, 9));
        assert_eq!(rects.right, Rect::default());
    }

    #[test]
    fn a_terminal_that_is_too_small_gets_nothing_at_all() {
        let rects = compute(10, 5, SinglePane::List);
        assert_eq!(rects, Rects::empty(), "no widget may be laid out at 10x5");
        assert_eq!(rects.visible_pane(), None);
        assert!(!Rects::fits(10, 5));

        // Both thresholds are independent: narrow-and-tall and short-and-wide
        // are refused for different reasons but behave identically.
        assert_eq!(compute(19, 40, SinglePane::List), Rects::empty());
        assert_eq!(compute(40, 7, SinglePane::List), Rects::empty());
        assert!(Rects::fits(20, 8), "20x8 is exactly enough");
    }

    #[test]
    fn the_overlay_stays_inside_the_buffer_at_every_usable_size() {
        for width in MIN_WIDTH..=200u16 {
            for height in MIN_HEIGHT..=60u16 {
                for single in [SinglePane::List, SinglePane::Conversation] {
                    let rects = compute(width, height, single);
                    assert_sane(&rects, width, height, "overlay bounds");
                    assert!(rects.overlay.width >= 1 && rects.overlay.height >= 1);
                }
            }
        }
    }

    #[test]
    fn the_left_pane_never_starves_the_right_one() {
        for width in TWO_PANE_MIN_WIDTH..=400u16 {
            let rects = compute(width, 24, SinglePane::List);
            assert!(
                rects.right.width >= RIGHT_MIN,
                "{width} columns left only {} for the conversation",
                rects.right.width
            );
            assert!(rects.left.width >= LEFT_MIN || width < LEFT_MIN + RIGHT_MIN);
        }
    }
}
