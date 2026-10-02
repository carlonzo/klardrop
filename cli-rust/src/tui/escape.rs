//! Every string that reaches the screen is untrusted: device names come from
//! other machines, message bodies from other people, file names from other
//! filesystems, and daemon error strings from whatever the daemon said.
//!
//! A terminal is not a document renderer. If a payload carries `\x1b]0;title\x07`
//! it renames the window; if it carries `\x1b[31m` it repaints the user's
//! screen; if it carries a bidi override it reorders what the user reads. This
//! module is the single funnel every drawn string goes through, and it is
//! deliberately lossy: it is always better to lose a glyph than to execute a
//! control sequence on the user's terminal.
//!
//! What it does, in order:
//!
//!   * C0 controls (`U+0000..=U+001F`), `DEL` and C1 (`U+0080..=U+009F`) are
//!     removed. That covers `ESC` itself, so no escape sequence survives;
//!   * newlines, carriage returns, tabs and the other whitespace controls
//!     collapse to a single space instead of moving the cursor;
//!   * runs of whitespace collapse, and the result is trimmed;
//!   * Unicode format characters (`U+200B..=U+200F`, the bidi overrides,
//!     zero-width joiners) are dropped: they are invisible, which makes them a
//!     way to smuggle misleading text past a reader;
//!   * the result is capped, so a 10 MB "device name" cannot be laid out.

/// Default cap on a sanitized string, in characters.
pub const MAX_CHARS: usize = 400;

/// Marker appended when [`sanitize`] truncates.
pub const TRUNCATION_MARK: &str = "…";

/// [`MAX_CHARS`]-bounded [`sanitize`].
pub fn sanitize(raw: &str) -> String {
    sanitize_with_limit(raw, MAX_CHARS)
}

/// Sanitizes `raw` and caps it at `max_chars` characters.
///
/// The cap counts sanitized characters, not bytes, so a name made of wide
/// characters cannot be cut in half through the middle of a code point.
pub fn sanitize_with_limit(raw: &str, max_chars: usize) -> String {
    if max_chars == 0 {
        return String::new();
    }
    let mut out = String::with_capacity(raw.len().min(max_chars * 2));
    let mut pending_space = false;
    let mut count = 0usize;
    let mut truncated = false;

    for ch in raw.chars() {
        let replacement = classify(ch);
        match replacement {
            // Dropped outright: contributes nothing, not even a separator.
            Classified::Drop => continue,
            Classified::Space => {
                // Leading and repeated whitespace is dropped; a single space
                // survives so words stay words.
                if !out.is_empty() {
                    pending_space = true;
                }
                continue;
            }
            Classified::Keep => {}
        }

        if pending_space {
            if count == max_chars {
                truncated = true;
                break;
            }
            out.push(' ');
            pending_space = false;
            count += 1;
        }

        if count == max_chars {
            truncated = true;
            break;
        }
        out.push(ch);
        count += 1;
    }

    if truncated {
        // Keep room for the marker so the total never exceeds the cap.
        let keep = max_chars.saturating_sub(TRUNCATION_MARK.chars().count());
        out = out.chars().take(keep).collect();
        out.push_str(TRUNCATION_MARK);
    }
    out
}

enum Classified {
    Keep,
    Space,
    Drop,
}

fn classify(ch: char) -> Classified {
    // Whitespace first and for every kind of it: a plain space, a tab, a
    // newline, and the Unicode spaces too. They all collapse to one space
    // below, which is what keeps a device name on one line without letting a
    // newline move the cursor.
    if ch.is_whitespace() {
        return Classified::Space;
    }
    match ch {
        // C0 controls and DEL, `ESC` included. There is no way to reach the
        // screen that a control character does not threaten. They are DROPPED,
        // not turned into spaces: `evil\x1b[31m` must read as `evil[31m`, and a
        // space here would be an invented word break in someone else's name.
        '\u{0}'..='\u{1f}' | '\u{7f}' => Classified::Drop,
        // C1 controls, which 8-bit-clean terminals still interpret.
        '\u{80}'..='\u{9f}' => Classified::Drop,
        // Zero-width and bidirectional formatting: invisible, and therefore a
        // way to make text read differently from how it is stored.
        '\u{200b}'..='\u{200f}'
        | '\u{202a}'..='\u{202e}'
        | '\u{2060}'..='\u{2064}'
        | '\u{feff}' => Classified::Drop,
        _ => Classified::Keep,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The exact payload the brief names: a colour sequence, an OSC window
    /// title with its BEL terminator, a NUL and a newline.
    const HOSTILE: &str = "ok\u{1b}[31mred\u{1b}]0;title\u{7}\u{0}\nsecond line";

    #[test]
    fn the_hostile_payload_loses_every_escape_and_keeps_the_words() {
        let out = sanitize(HOSTILE);
        // Controls are DROPPED, not replaced by a space: `evil\x1b[31m` must read as
        // `evil[31m`. Inventing a word break inside someone else's name is its own lie.
        assert_eq!(out, "ok[31mred]0;title second line");
        for ch in ['\u{1b}', '\u{7}', '\u{0}', '\n', '\r', '\t'] {
            assert!(!out.contains(ch), "{ch:?} survived sanitizing: {out:?}");
        }
        assert!(
            out.chars().all(|c| !c.is_control()),
            "no control character may reach the screen: {out:?}"
        );
    }

    #[test]
    fn a_bare_escape_and_a_c1_byte_are_removed_not_printed() {
        assert_eq!(sanitize("a\u{1b}b"), "ab");
        // U+009B is CSI in an 8-bit-clean terminal: it must not survive as a
        // prefix that a terminal could complete into a sequence.
        assert_eq!(sanitize("a\u{9b}31mb"), "a31mb");
    }

    #[test]
    fn whitespace_runs_collapse_and_the_edges_are_trimmed() {
        assert_eq!(sanitize("  hello \t\n  world \r\n "), "hello world");
        assert_eq!(sanitize("\n\n"), "");
        assert_eq!(sanitize("   "), "");
    }

    #[test]
    fn invisible_formatting_cannot_smuggle_words_past_a_reader() {
        // A right-to-left override would visually reorder "b < a" into "a < b".
        assert_eq!(sanitize("b \u{202e}< a"), "b < a");
        assert_eq!(sanitize("a\u{200b}b"), "ab");
    }

    #[test]
    fn the_length_cap_is_honoured_and_marked() {
        let long = "x".repeat(MAX_CHARS * 3);
        let out = sanitize(&long);
        assert_eq!(out.chars().count(), MAX_CHARS);
        assert!(out.ends_with(TRUNCATION_MARK), "{out:?}");

        let exact = "y".repeat(MAX_CHARS);
        assert_eq!(
            sanitize(&exact),
            exact,
            "a name at the cap is not truncated"
        );

        let custom = sanitize_with_limit(&long, 10);
        assert_eq!(custom.chars().count(), 10);
        assert!(custom.ends_with(TRUNCATION_MARK));
    }

    #[test]
    fn wide_characters_are_never_cut_in_half() {
        // Each of these is multi-byte; a byte-wise cap would panic or corrupt.
        let wide = "🎉".repeat(MAX_CHARS + 5);
        let out = sanitize(&wide);
        assert_eq!(out.chars().count(), MAX_CHARS);
        assert!(out.ends_with(TRUNCATION_MARK));
    }

    #[test]
    fn a_cap_smaller_than_the_marker_cannot_overflow() {
        let out = sanitize_with_limit(&"z".repeat(50), 0);
        assert_eq!(out.chars().count(), 0);
        let out = sanitize_with_limit(&"z".repeat(50), 1);
        assert_eq!(out.chars().count(), 1);
    }
}
