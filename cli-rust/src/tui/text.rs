//! Terminal display width, and turning a message into the rows it occupies.
//!
//! Two facts about a terminal make this module necessary, and both are ways
//! of getting "how much room does this need?" wrong:
//!
//!   * **A cell is a column, not a character.** `日` is one `char` and two
//!     columns, an emoji is one `char` and two columns, and a combining mark is
//!     one `char` and zero. Anything that measures a name in `char`s and then
//!     lays it out in columns overflows the row it budgeted for, and what
//!     overflows is whatever the row had promised to keep — the unread badge,
//!     the status words.
//!   * **A logical line is not a display row.** A 300-character message is
//!     five rows on a 60-column pane. A window that slices "the last N
//!     messages" and hands N rows to the pane is clipping overflow below the
//!     pane's bottom edge, where nothing can scroll to it.
//!
//! So the conversation is measured in *display rows* throughout: a message is
//! wrapped into rows of the pane's own width first, and only then windowed.
//!
//! The width table is [`unicode_width`], the same one ratatui wraps with, so a
//! row this module says fits is a row the widget agrees fits.

use unicode_width::UnicodeWidthStr;

use crate::state::HistoryMessage;
use crate::tui::escape::sanitize;
use crate::tui::theme::{FAILED_MARKER, INCOMING_MARKER, OUTGOING_MARKER};

/// Columns `text` occupies on one row, without wrapping.
///
/// The one place the crate answers "how wide is this", so no caller can answer
/// it in `char`s by accident.
pub fn display_width(text: &str) -> usize {
    UnicodeWidthStr::width(text)
}

/// Splits `text` into display rows of at most `width` columns.
///
/// Words are kept whole where they can be, because a row that breaks a word in
/// the middle is harder to read than one that breaks before it. A word longer
/// than the pane — a URL, a base64 blob, a CJK run with no spaces — is broken
/// by column instead, because it has to go somewhere and the pane is all there
/// is. The spaces a row breaks at are dropped, as a terminal's own wrapping
/// drops them.
///
/// `width` of zero is treated as one: the caller has no room for anything, and
/// returning nothing would lose the text rather than merely truncate it.
pub fn wrap(text: &str, width: u16) -> Vec<String> {
    let width = usize::from(width.max(1));
    if text.is_empty() {
        return vec![String::new()];
    }
    let mut rows: Vec<String> = Vec::new();
    let mut row = String::new();
    let mut used = 0usize;

    for word in text.split(' ') {
        let word_width = display_width(word);
        if word_width == 0 {
            // Only reachable through runs of spaces, which `sanitize` already
            // collapses; kept so the invariant "used == width(row)" holds even
            // if that ever stops being true.
            continue;
        }
        // The separating space counts only when a word already sits on the row.
        let separator = usize::from(!row.is_empty());
        if used + separator + word_width <= width {
            if separator == 1 {
                row.push(' ');
            }
            row.push_str(word);
            used += separator + word_width;
            continue;
        }
        if !row.is_empty() {
            rows.push(std::mem::take(&mut row));
            used = 0;
        }
        if word_width <= width {
            row.push_str(word);
            used = word_width;
            continue;
        }
        for chunk in break_word(word, width) {
            if !row.is_empty() {
                rows.push(std::mem::take(&mut row));
            }
            row.push_str(&chunk);
            used = display_width(&chunk);
            // A chunk that filled the row leaves nothing to add to.
            if used >= width {
                rows.push(std::mem::take(&mut row));
                used = 0;
            }
        }
    }
    if !row.is_empty() || rows.is_empty() {
        rows.push(row);
    }
    rows
}

/// Splits one over-long word into chunks of at most `width` columns, never
/// inside a character.
fn break_word(word: &str, width: usize) -> Vec<String> {
    let mut chunks: Vec<String> = Vec::new();
    let mut chunk = String::new();
    let mut used = 0usize;
    for ch in word.chars() {
        let ch_width = display_width(&ch.to_string()).max(1);
        if used + ch_width > width && !chunk.is_empty() {
            chunks.push(std::mem::take(&mut chunk));
            used = 0;
        }
        chunk.push(ch);
        used += ch_width;
    }
    if !chunk.is_empty() {
        chunks.push(chunk);
    }
    chunks
}

/// How many display rows `text` occupies at `width` columns.
///
/// Deliberately [`wrap`] and a length: one measurement, one implementation. An
/// earlier version computed this from column arithmetic — `ceil(columns /
/// width)` — which silently disagrees with the character-by-character break
/// whenever a wide glyph does not divide a row evenly, and a scroll ceiling
/// that disagrees with what is drawn is exactly the bug this module exists to
/// prevent.
pub fn row_count(text: &str, width: u16) -> usize {
    wrap(text, width).len()
}

/// The conversation's own text for one message: direction marker, body, and
/// whatever the delivery state has to say — sanitized, because all three parts
/// come from the daemon or from another person.
///
/// [`crate::tui::render`] draws this and [`crate::tui::app`] measures it. They
/// have to agree: if the renderer drew a different string than the one the
/// scroll arithmetic counted, the newest message would be "present" in the
/// count and absent from the screen.
pub fn message_text(message: &HistoryMessage) -> String {
    let marker = if message.is_sender {
        OUTGOING_MARKER
    } else {
        INCOMING_MARKER
    };
    let status = if message.failed() {
        format!(" {FAILED_MARKER}")
    } else if message.is_sender && !message.is_read {
        " ...".to_string()
    } else {
        String::new()
    };
    let body = match &message.file {
        Some(file) => format!("[file] {} ({} bytes)", file.file_name, file.file_size),
        None => message.content.clone(),
    };
    sanitize(&format!("{marker} {body}{status}"))
}

/// Rows the loaded history occupies at `width` columns.
pub fn history_rows(messages: &[HistoryMessage], width: u16) -> usize {
    messages
        .iter()
        .map(|message| row_count(&message_text(message), width))
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The corpus every width-based property below is checked against. It is
    /// deliberately mixed: ASCII, spaces, wide glyphs, a word longer than any
    /// pane, and the empty string.
    const CORPUS: &[&str] = &[
        "",
        "x",
        "hello",
        "hello world",
        "one two three four five six seven",
        "日本語のとても長いデバイス名前テスト用の非常に長い名前です",
        "mixed 日本 text with spaces 日本語です",
        "averyveryverylongwordwithnospacesatallthatcannotfit",
        "short supercalifragilisticexpialidocious",
        "emoji 👨‍👩‍👧‍👦 family and 🇯🇵 flag",
    ];

    #[test]
    fn no_row_is_ever_wider_than_the_pane() {
        // A one-column pane is excluded: a two-column glyph cannot be split,
        // so it is the one case where a row is unavoidably too wide.
        for width in [2u16, 5, 7, 12, 20, 40, 60] {
            for text in CORPUS {
                for row in wrap(text, width) {
                    assert!(
                        display_width(&row) <= usize::from(width),
                        "{row:?} is {} columns wide, pane is {width}",
                        display_width(&row)
                    );
                }
            }
        }
    }

    #[test]
    fn wrapping_keeps_every_character() {
        for width in [1u16, 3, 9, 25] {
            for text in CORPUS {
                let rejoined: String = wrap(text, width).concat();
                assert_eq!(
                    rejoined.chars().filter(|c| !c.is_whitespace()).count(),
                    text.chars().filter(|c| !c.is_whitespace()).count(),
                    "{text:?} at {width} columns lost or gained a character"
                );
            }
        }
    }

    #[test]
    fn a_wide_glyph_costs_two_columns() {
        assert_eq!(display_width("日"), 2);
        assert_eq!(display_width("ab"), 2);
        assert_eq!(
            display_width("日本語"),
            6,
            "three chars, six columns: measuring in chars is the bug this module exists for"
        );
    }

    #[test]
    fn an_over_long_word_is_broken_rather_than_dropped() {
        let rows = wrap("abcdefghij", 4);
        assert_eq!(rows, vec!["abcd", "efgh", "ij"]);
        assert_eq!(row_count("abcdefghij", 4), 3);
    }

    #[test]
    fn a_zero_width_pane_still_returns_the_text() {
        // A pane with no columns is measured as one column, not as zero: the
        // text has to go somewhere, and dropping it would lose a message.
        let rows = wrap("hello", 0);
        assert_eq!(rows.concat(), "hello");
        assert_eq!(rows.len(), 5, "one column per row, nothing lost");
    }

    #[test]
    fn an_empty_message_is_one_row_not_none() {
        assert_eq!(row_count("", 20), 1);
        assert_eq!(wrap("", 20), vec![String::new()]);
    }
}
