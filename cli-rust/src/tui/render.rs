//! Drawing.
//!
//! Every string that reaches this module is passed through
//! [`crate::tui::escape::sanitize`] on its way to a cell. That is enforced
//! here rather than left to convention: this is the only place the crate writes
//! to the screen, so there is nowhere else an untrusted string could get out.
//!
//! The renderer holds no state and does no I/O beyond drawing into the frame
//! ratatui handed it, which is why every screen here can be rendered into a
//! [`ratatui::backend::TestBackend`] and asserted on, at any size, with no
//! terminal involved.

use ratatui::layout::Rect;
use ratatui::style::Style;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, List, ListItem, Paragraph, Wrap};
use ratatui::Frame;

use crate::tui::app::{App, ConnectionState, Focus, InputMode, Mode, Overlay};
use crate::tui::escape::sanitize;
use crate::tui::qr::QrMatrix;
use crate::tui::text;
use crate::tui::theme::{
    Theme, OFFLINE_MARKER, PENDING_MARKER, REACHABLE_MARKER, SELECTED_MARKER, TRUSTED_MARKER,
    UNREAD_BADGE_CLOSE, UNREAD_BADGE_OPEN,
};

/// Two columns of blank selection marker, so an unselected row's name starts
/// exactly where a selected row's does.
const UNSELECTED_PADDING: &str = "  ";

/// Shown instead of the whole TUI when there is no room for it. The short half
/// comes first because the message is cut to the terminal's own width, and the
/// size hint is the part that can be dropped without losing the meaning.
pub const TOO_SMALL_MESSAGE: &str = "too small · need 20x8";

/// Legend shown in the device pane's border, so the glyph column is never a
/// code the user has to guess. The words are repeated in every row as well.
pub const DEVICE_LEGEND: &str = "Devices  * reachable  + trusted  ! pairing";

/// Every key the TUI answers to, and what it does. Rendered by the help overlay
/// and checked against the dispatcher in [`crate::tui::app`].
pub const KEY_HELP: &[(&str, &str)] = &[
    (
        "Tab / Shift-Tab",
        "move between the device and conversation panes",
    ),
    ("Up / Down", "select a device; scroll the conversation"),
    ("PageUp / PageDown", "page through older messages"),
    ("Enter", "open the selected device; start a message"),
    ("i", "write a message"),
    ("f", "send files by absolute path"),
    ("y", "send the clipboard"),
    (
        "Esc",
        "close the overlay, the compose line or the conversation",
    ),
    ("a", "decisions and secondary actions"),
    ("n", "rename this device"),
    ("r", "refresh now"),
    ("?", "this help"),
    (
        "Ctrl-Space",
        "release the mouse so the terminal can select and copy; press it again to take it back",
    ),
    ("q / Ctrl-C", "quit"),
];

/// The one entry point. `no_color` is the user's explicit `--no-color`.
pub fn draw(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool) {
    // Nothing can be drawn into a frame with no cells; every widget below would
    // still write its first character somewhere.
    if frame.area().width == 0 || frame.area().height == 0 {
        return;
    }
    if !app.fits() {
        draw_too_small(frame);
        return;
    }

    let rects = app.layout();
    draw_header(frame, app, theme, no_color, rects.header);
    if app.is_picker() {
        // A picker has one question, so it gets one pane: the device list,
        // across the whole body.
        draw_device_pane(frame, app, theme, no_color, rects.body());
    } else {
        draw_device_pane(frame, app, theme, no_color, rects.left);
        if rects.right.width > 0 {
            draw_conversation(frame, app, theme, no_color, rects.right);
        }
    }
    draw_footer(frame, app, theme, no_color, rects.footer);

    match app.overlay() {
        Overlay::None => {}
        Overlay::Help => draw_help(frame, theme, no_color, rects.overlay),
        Overlay::Actions => draw_actions(frame, app, theme, no_color, rects.overlay),
        Overlay::Settings => draw_settings(frame, app, theme, no_color, rects.overlay),
        Overlay::Qr => draw_qr(frame, app, theme, no_color, rects.overlay),
    }
}

/// The whole frame is one line of text. Nothing else is laid out, so nothing
/// else can be clipped at the edges.
///
/// The notice is cut to the terminal's own width rather than wrapped: a
/// wrapped two-line notice on a five-row terminal would push the second line
/// off the bottom, and a truncated word tells the user nothing. The short
/// half is first, so even a 10-column terminal gets the part that matters.
fn draw_too_small(frame: &mut Frame) {
    let area = frame.area();
    // A zero-sized frame has no cell to write into; rendering into one leaks a
    // single stray character into the terminal's own state.
    if area.width == 0 || area.height == 0 {
        return;
    }
    let row = Rect::new(area.x, area.height / 2, area.width, 1);
    let text: String = sanitize(TOO_SMALL_MESSAGE)
        .chars()
        .take(area.width as usize)
        .collect();
    frame.render_widget(Paragraph::new(text.trim_end()), row);
}

/// App name, daemon state and how many devices are visible — the three facts
/// needed to know whether what is on screen is current.
fn draw_header(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    let live = app.connection().is_live();
    let state = match app.connection() {
        ConnectionState::Offline(reason) => format!("offline: {}", sanitize(reason)),
        other => other.label().to_string(),
    };
    let self_name = app.self_device_name();
    let devices = app.devices().len();
    let count = format!("{devices} device{}", if devices == 1 { "" } else { "s" });
    // In the picker the header says what is being sent, not who is looking:
    // that is the one fact the caller could not have known on the CLI's behalf.
    let body = match app.mode() {
        Mode::Full => format!("klardrop  |  {self_name}  |  daemon: {state}  |  {count}"),
        Mode::Picker { summary } => {
            format!("klardrop  |  sharing: {summary}  |  daemon: {state}  |  {count}")
        }
    };
    // The spinner is drawn only while the daemon is genuinely not answering;
    // it is decoration on a state, never the state itself.
    let text = match app.motion().spinner(app.activity()) {
        Some(spinner) => format!("{spinner} {body}"),
        None => body,
    };
    let style = style(theme, no_color, theme.connection(live));
    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(sanitize(&text), style))),
        area,
    );
}

/// The footer is two rows by contract: discoverable shortcuts, then whatever
/// the TUI most recently needs to say.
fn draw_footer(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    if area.height < 2 {
        return;
    }
    let hints = Rect::new(area.x, area.y, area.width, 1);
    let keys = if app.is_picker() {
        "Up/Down choose  Enter share to this device  r refresh  Esc cancel"
    } else {
        "Tab pane  Up/Down select  Enter open  i text  f files  y clipboard  a actions  ? help  q quit"
    };
    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(
            sanitize(keys),
            style(theme, no_color, theme.muted),
        ))),
        hints,
    );
    let status = Rect::new(area.x, area.y + 1, area.width, 1);
    let (text, status_style) = match app.toast() {
        // The acknowledgement rides on the toast, because the toast is where
        // the daemon's own words about the send already are. It is added in
        // front of them rather than in place of them, so the result is legible
        // in the very first frame of the flourish.
        Some(toast) => match app.flourish() {
            Some(glyph) => (sanitize(&format!("{glyph} {}", toast.message)), theme.toast),
            None => (sanitize(&toast.message), theme.toast),
        },
        None if app.active_transfers().is_empty() => (status_line(app), theme.muted),
        // A transfer in flight outranks the idle hint: it is the one thing on
        // screen that is changing without the user doing anything.
        None => {
            let line = transfer_line(app, theme, no_color);
            let text = line
                .spans
                .first()
                .map(|span| span.content.to_string())
                .unwrap_or_default();
            (text, theme.badge)
        }
    };
    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(
            text,
            style(theme, no_color, status_style),
        ))),
        status,
    );
}

/// The second footer row when nothing transient is showing.
fn status_line(app: &App) -> String {
    match app.input_mode() {
        InputMode::Compose => {
            format!(
                "{}: {}",
                app.compose_kind().prompt(),
                sanitize(app.compose())
            )
        }
        InputMode::Normal => match (app.open_device(), app.is_picker()) {
            (Some(device), _) => sanitize(device),
            (None, true) => "Esc cancels — nothing is sent".to_string(),
            // The bypass is worth advertising exactly while it is engaged: that
            // is when the user is selecting text, and the key that undoes it is
            // the one thing they cannot guess.
            //
            // A session that never took the mouse is the other case, and the
            // two are not the same: under `--no-mouse` there is nothing
            // released, and the footer must not keep offering `Ctrl-Space` as
            // the way back when the guard could not take a mouse either.
            (None, false) if !app.mouse_available() => {
                "Esc quits · ? for keys · no mouse · Ctrl-Space stays off".to_string()
            }
            (None, false) if !app.mouse_captured() => {
                "Esc quits · ? for keys · mouse released".to_string()
            }
            (None, false) => "Esc quits · ? for keys".to_string(),
        },
    }
}

fn draw_device_pane(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    let focused = app.focus() == Focus::Devices;
    let block = Block::bordered().title(pane_title(DEVICE_LEGEND.into(), focused, theme, no_color));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    if inner.width == 0 || inner.height == 0 {
        return;
    }

    if app.devices().is_empty() {
        frame.render_widget(
            Paragraph::new(sanitize("No devices yet — press r to refresh"))
                .wrap(Wrap { trim: true }),
            inner,
        );
        return;
    }

    // Bounded: at most one row per row the pane has. This list is the daemon's
    // device list, not history, so it cannot outgrow the pane.
    let items: Vec<ListItem> = app
        .devices()
        .iter()
        .enumerate()
        .take(inner.height as usize)
        .map(|(index, _)| {
            let selected = index == app.selected();
            let mut line = device_line(app, index, inner.width);
            if selected {
                line = line.style(style(theme, no_color, theme.selected));
            }
            ListItem::new(line)
        })
        .collect();
    frame.render_widget(List::new(items), inner);
}

/// One device row, laid out for `width` columns.
///
/// The shape is fixed and readable without colour:
///
/// ```text
/// > *+ Fixture Phone  reachable · trusted (2)
///   - Fixture Tablet  offline · untrusted
/// ```
///
/// Column one is the selection marker, then reachability (`*` reachable,
/// `-` offline), trust (`+` trusted) and a pending pairing (`!`). An absent
/// state contributes NOTHING rather than a blank glyph, and the unselected
/// marker is padded so every name starts in the same column. The same two
/// facts are then spelled out in words, because a glyph column is a legend the
/// user has to learn and a word is not — and because status must survive a
/// terminal that cannot render the glyph at all.
///
/// The row is assembled from *already sanitized* parts and is not sanitized
/// again: [`sanitize`] collapses runs of whitespace, which is right for a
/// device name and wrong for the column padding this row is made of.
///
/// The unread badge is last, and the NAME is what shrinks when the pane is too
/// narrow. A clipped unread count or a clipped status word is a fact the user
/// loses; a shortened name is still a name, and the device id is in the
/// header's own count and in the conversation's title.
fn device_line(app: &App, index: usize, width: u16) -> Line<'static> {
    let device = &app.devices()[index];
    let marker = if index == app.selected() {
        SELECTED_MARKER
    } else {
        UNSELECTED_PADDING
    };
    let reach = if device.reachable {
        REACHABLE_MARKER
    } else {
        OFFLINE_MARKER
    };
    let trust = if device.paired { TRUSTED_MARKER } else { "" };
    let pending = if device.trust_status.eq_ignore_ascii_case("pairing") {
        PENDING_MARKER
    } else {
        ""
    };
    let unread = if device.unread_count > 0 {
        format!(
            " {UNREAD_BADGE_OPEN}{}{UNREAD_BADGE_CLOSE}",
            device.unread_count
        )
    } else {
        String::new()
    };
    let words = [
        if device.reachable {
            "reachable"
        } else {
            "offline"
        },
        if device.paired {
            "trusted"
        } else {
            "untrusted"
        },
    ]
    .join(" · ");
    // Measured in COLUMNS, not characters. `words.chars().count()` and
    // `chars().take()` both budgeted a CJK or emoji name at one column per
    // character, so the name overflowed the row and pushed the status words
    // off the end — losing exactly the facts the row exists to convey.
    // `marker` (2) + space + `reach`/`trust`/`pending` (3) + space.
    let prefix_len = 2 + 1 + 3 + 1;
    let spoken_for = prefix_len as u16
        + 2
        + text::display_width(&words) as u16
        + text::display_width(&unread) as u16;
    let name = shrink(
        &sanitize(&device.device_name),
        width.saturating_sub(spoken_for),
    );
    Line::from(format!(
        "{marker} {reach}{trust}{pending} {name}  {words}{unread}"
    ))
}

/// Shortens `text` to `width` columns, marking that it did. `width` is the
/// number of columns already spoken for, so a name is cut only when the row
/// genuinely has no room for it.
///
/// Column-accurate, and never inside a character: a name is still a name after
/// this, just a shorter one.
fn shrink(text: &str, width: u16) -> String {
    let width = width as usize;
    if width == 0 {
        return text.to_string();
    }
    if text::display_width(text) <= width {
        return text.to_string();
    }
    let budget = width.saturating_sub(1);
    let mut used = 0usize;
    let mut kept = String::new();
    for ch in text.chars() {
        let step = text::display_width(&ch.to_string()).max(1);
        if used + step > budget {
            break;
        }
        kept.push(ch);
        used += step;
    }
    format!("{kept}…")
}

/// A pane's title, in its border.
///
/// The style goes through [`style`] like every other cell in this module: the
/// focus marker and `BOLD` are the same `SGR` sequence as the colour, so a
/// title styled directly here would put escape sequences on the wire under
/// `--no-color`, which promises none at all.
fn pane_title(name: String, focused: bool, theme: &Theme, no_color: bool) -> Line<'static> {
    let marker = if focused { ">" } else { " " };
    let title_style = if focused { theme.focus } else { theme.unfocus };
    Line::from(Span::styled(
        format!("{marker} {name}"),
        style(theme, no_color, title_style),
    ))
}

fn draw_conversation(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    let Some(device) = app.open_device() else {
        let block =
            Block::bordered().title(pane_title("Conversation".into(), false, theme, no_color));
        let inner = block.inner(area);
        frame.render_widget(block, area);
        frame.render_widget(
            Paragraph::new(sanitize("Select a device and press Enter")).wrap(Wrap { trim: true }),
            inner,
        );
        return;
    };

    let name = app
        .devices()
        .iter()
        .find(|candidate| candidate.device_id == device)
        .map(|candidate| candidate.device_name.clone())
        .unwrap_or_else(|| device.to_string());
    let focused = app.focus() == Focus::Conversation;
    let block = Block::bordered().title(pane_title(sanitize(&name), focused, theme, no_color));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    if inner.width == 0 || inner.height == 0 {
        return;
    }

    // Two rows are reserved: the compose line (when open) and nothing else.
    let composing = app.input_mode() == InputMode::Compose;
    let compose_rows = u16::from(composing);
    let body_height = inner.height.saturating_sub(compose_rows);
    let body = Rect::new(inner.x, inner.y, inner.width, body_height);

    // Windowed by *display row*, not by message. A message longer than the
    // pane is several rows tall, so slicing "the last N messages" and handing
    // N rows to the paragraph clips the overflow below the pane, where
    // scrolling cannot reach it: the newest message would never arrive on
    // screen at all. So each message is wrapped into rows of the pane's own
    // width first, and the window is a range of those rows.
    let rows = conversation_window(app, theme, no_color, inner.width, body_height);
    frame.render_widget(Paragraph::new(rows), body);

    if composing {
        let row = Rect::new(inner.x, inner.y + body_height, inner.width, 1);
        let prompt = format!(
            "{}> {}",
            app.compose_kind().prompt(),
            sanitize(app.compose())
        );
        frame.render_widget(
            Paragraph::new(Line::from(Span::styled(
                prompt,
                style(theme, no_color, theme.input),
            ))),
            row,
        );
    }
}

/// The rows of the conversation that fit in `capacity` rows, given `scroll`
/// rows of history to skip above them.
///
/// Three guarantees, in the order they matter:
///
///   * **the newest row is on screen.** At `scroll == 0` the window ends at the
///     last row, so a reply that has just arrived is the first thing drawn.
///   * **every older row is reachable.** `scroll` walks the window up over the
///     whole history, and a message that wraps is walked through one row at a
///     time rather than skipped whole.
///   * **nothing beyond the window is ever built.** The rows handed to the
///     paragraph are `capacity + scroll`, not "all 500 messages": a long
///     history costs the pane what it shows plus what the user scrolled back
///     to, and no more.
fn conversation_window<'a>(
    app: &App,
    theme: &Theme,
    no_color: bool,
    width: u16,
    capacity: u16,
) -> Vec<Line<'a>> {
    let capacity = capacity as usize;
    if app.is_history_loading() {
        return vec![Line::from(Span::styled(
            sanitize("loading…"),
            style(theme, no_color, theme.warning),
        ))];
    }
    let mut rows: Vec<Line<'a>> = Vec::new();
    if app.messages().is_empty() {
        rows.push(Line::from(sanitize(
            "no messages yet — press i to write one",
        )));
        rows.push(Line::from(""));
        rows.push(transfer_line(app, theme, no_color));
        return rows;
    }

    // What sits below the messages: the in-flight transfer block. It is part of
    // the window rather than an extra, so it scrolls away with everything else
    // instead of overwriting the newest message.
    let transfers = transfer_line(app, theme, no_color);
    let tail: Vec<Line<'a>> = if transfers.spans.is_empty() {
        Vec::new()
    } else {
        vec![Line::from(""), transfers]
    };

    // How far back the window sits, and which rows of the whole conversation it
    // covers. Scrolling is clamped here as well as in the reducer: only the
    // renderer knows the pane's width and height, so only the renderer knows
    // what "scrolled all the way up" means. Reaching the oldest row is the end
    // of the gesture, and there is nothing above that to show.
    let history = text::history_rows(app.messages(), width);
    let total = history + tail.len();
    let scroll = app.scroll().min(total.saturating_sub(1));
    let end = total - scroll;
    let start = end.saturating_sub(capacity);

    // One pass over the history, keeping only the rows inside [start, end).
    // The rows outside it are counted but never built, which is what keeps a
    // 500-message history from costing 500 wrapped strings every frame.
    let mut rows: Vec<Line<'a>> = Vec::new();
    let mut seen = 0usize;
    for message in app.messages() {
        if seen >= end {
            break;
        }
        let wrapped = text::wrap(&text::message_text(message), width);
        let first = start.saturating_sub(seen);
        let last = (end - seen).min(wrapped.len());
        if first < last {
            rows.extend(wrapped[first..last].iter().cloned().map(Line::from));
        }
        seen += wrapped.len();
    }
    let tail_start = history;
    let tail_first = start.saturating_sub(tail_start);
    // `end` is the first row past the window, and the tail starts at
    // `tail_start`. When the window ends INSIDE the history — which is the
    // normal case for any scroll up while no transfer is in flight, because
    // then `tail` is empty and `total == history` — `end - tail_start` has no
    // answer and a plain subtraction panics the whole client. The tail is only
    // visible when the window reaches it.
    let tail_last = end.saturating_sub(tail_start).min(tail.len());
    if tail_first < tail_last {
        rows.extend(tail[tail_first..tail_last].iter().cloned());
    }
    rows
}

/// Live transfer progress, straight from the daemon's `/state` block.
fn transfer_line(app: &App, theme: &Theme, no_color: bool) -> Line<'static> {
    if app.active_transfers().is_empty() {
        return Line::from("");
    }
    let summary = app
        .active_transfers()
        .iter()
        .map(|transfer| {
            format!(
                "{} {} {}%",
                sanitize(&transfer.file_name),
                sanitize(&transfer.phase),
                transfer.percent()
            )
        })
        .collect::<Vec<_>>()
        .join("  ");
    // The bar is filled from the daemon's own percentage and never from a
    // local guess, so a still frame and a moving one say the same thing.
    let summary = match app.motion().transfer_bar(app.activity()) {
        Some(bar) => format!("{bar}  {summary}"),
        None => summary,
    };
    Line::from(Span::styled(
        sanitize(&summary),
        style(theme, no_color, theme.badge),
    ))
}

fn draw_help(frame: &mut Frame, theme: &Theme, no_color: bool, area: Rect) {
    let inner = overlay_block(frame, theme, no_color, area, "Keys");
    let lines: Vec<Line> = KEY_HELP
        .iter()
        .map(|(key, description)| {
            Line::from(vec![
                Span::styled(sanitize(key), style(theme, no_color, theme.focus)),
                Span::raw("  "),
                Span::raw(sanitize(description)),
            ])
        })
        .collect();
    frame.render_widget(Paragraph::new(lines), inner);
}

fn draw_actions(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    let inner = overlay_block(frame, theme, no_color, area, "Actions");
    let entries = app.menu_entries();
    if entries.is_empty() {
        frame.render_widget(
            Paragraph::new(sanitize("nothing to do here — Esc to go back")),
            inner,
        );
        return;
    }
    let mut lines: Vec<Line> = Vec::new();
    // What the daemon is waiting on comes first, and is said out loud when
    // there is none of it: the menu is never empty, but the *decisions* can be.
    if app.pending_decision_count() == 0 {
        lines.push(Line::from(Span::styled(
            sanitize("nothing pending — Esc to go back"),
            style(theme, no_color, theme.muted),
        )));
        lines.push(Line::from(""));
    }
    for (index, entry) in entries.iter().enumerate() {
        lines.push(Line::from(vec![
            Span::styled(
                format!("{} ", index + 1),
                style(theme, no_color, theme.focus),
            ),
            Span::raw(sanitize(&entry.label())),
        ]));
    }
    // A control the daemon does not advertise is named as unavailable rather
    // than left silently absent: the user asked what this client can do, and
    // "it is not on the list" is not an answer they can act on.
    let mut unavailable: Vec<&str> = UNAVAILABLE_LABELS
        .iter()
        .filter(|(route, _)| !app.has_capability(route))
        .map(|(_, label)| *label)
        .collect();
    if !unavailable.is_empty() {
        unavailable.sort_unstable();
        lines.push(Line::from(""));
        lines.push(Line::from(Span::styled(
            sanitize(&format!(
                "unavailable on this daemon: {}",
                unavailable.join(", ")
            )),
            style(theme, no_color, theme.muted),
        )));
    }
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
}

/// Routes the actions overlay would otherwise offer, paired with what to call
/// them when the daemon does not advertise them.
const UNAVAILABLE_LABELS: &[(&str, &str)] =
    &[("qr-share", "QR share"), ("update", "software updates")];

fn draw_settings(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    let inner = overlay_block(frame, theme, no_color, area, "Settings");
    let settings = app.settings();
    let supported = settings.supports_background_discovery;
    let lines = vec![
        Line::from(vec![
            Span::styled(
                sanitize("d  background discovery"),
                style(theme, no_color, theme.focus),
            ),
            Span::raw(sanitize(&format!(
                ": {}",
                if settings.background_discovery_enabled {
                    "on"
                } else {
                    "off"
                }
            ))),
        ]),
        Line::from(sanitize(&format!(
            "this daemon {} support background discovery",
            if supported { "does" } else { "does not" }
        ))),
        Line::from(""),
        Line::from(sanitize("Esc closes")),
    ];
    frame.render_widget(Paragraph::new(lines), inner);
}

fn draw_qr(frame: &mut Frame, app: &App, theme: &Theme, no_color: bool, area: Rect) {
    let inner = overlay_block(frame, theme, no_color, area, "QR share");
    let session = app.qr_share();
    if !session.active {
        let lines = vec![
            Line::from(sanitize("no QR share is running")),
            Line::from(""),
            Line::from(sanitize(
                "press a in the actions menu to start one with a file list",
            )),
        ];
        frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
        return;
    }
    let Some(url) = session.url.clone() else {
        let lines = vec![Line::from(sanitize(
            "the daemon started a share but sent no URL",
        ))];
        frame.render_widget(Paragraph::new(lines), inner);
        return;
    };

    match QrMatrix::parse(&session.qr) {
        // The code is unreadable as a QR code — ragged, non-square, or not
        // modules. Drawing half of it would be worse than not drawing it.
        Err(reason) => {
            let lines = vec![
                Line::from(sanitize(&url)),
                Line::from(""),
                Line::from(Span::styled(
                    sanitize(&format!("this code cannot be drawn: {reason}")),
                    style(theme, no_color, theme.error),
                )),
                Line::from(""),
                Line::from(sanitize(
                    "Open this URL in a reader that can show it as a code; Esc closes.",
                )),
            ];
            frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
        }
        // A code that will not fit is still a URL, and the user is told exactly
        // how much room it wants rather than being left guessing at the size.
        Ok(matrix) if !matrix.fits(inner.width, inner.height) => {
            let lines = vec![
                Line::from(sanitize(&url)),
                Line::from(""),
                Line::from(Span::styled(
                    sanitize("the code does not fit this window"),
                    style(theme, no_color, theme.warning),
                )),
                Line::from(sanitize(&format!(
                    "it needs {} columns by {} rows; this window has {} by {}",
                    matrix.columns(),
                    matrix.rows(),
                    inner.width,
                    inner.height
                ))),
                Line::from(""),
                Line::from(sanitize(
                    "make the window bigger, or open the URL in a reader; Esc closes.",
                )),
            ];
            frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
        }
        Ok(matrix) => draw_qr_matrix(frame, theme, no_color, inner, &matrix, &url),
    }
}

/// The code itself, one text row per line, then the URL and the download count
/// underneath if there is room left for them.
///
/// Each cell is one character: a half block for a module in one half, a full
/// block for a module in both, a shaded block for neither. The glyph carries
/// *which* half and the style carries *dark or not*, so `--no-color` loses the
/// colour and keeps the code.
fn draw_qr_matrix(
    frame: &mut Frame,
    theme: &Theme,
    no_color: bool,
    inner: Rect,
    matrix: &QrMatrix,
    url: &str,
) {
    let mut lines: Vec<Line> = Vec::with_capacity(matrix.rows() as usize);
    for row in 0..matrix.rows() as usize {
        let spans: Vec<Span> = matrix
            .line(row)
            .into_iter()
            .map(|(glyph, dark)| {
                let cell_style = if dark { theme.qr_dark } else { theme.qr_light };
                Span::styled(glyph.to_string(), style(theme, no_color, cell_style))
            })
            .collect();
        lines.push(Line::from(spans));
    }

    // The URL is the fallback for anything that cannot scan the screen — a
    // photograph of it, a reader that will not take a terminal — so it is shown
    // whenever the code did not use every row.
    if inner.height > matrix.rows() {
        lines.push(Line::from(""));
        lines.push(Line::from(Span::styled(
            sanitize(url),
            style(theme, no_color, theme.muted),
        )));
    }
    frame.render_widget(Paragraph::new(lines), inner);
}

/// Draws the overlay frame and returns its inner area.
fn overlay_block(
    frame: &mut Frame,
    theme: &Theme,
    no_color: bool,
    area: Rect,
    title: &'static str,
) -> Rect {
    let border = style(theme, no_color, theme.focus);
    let block = Block::bordered()
        .title(Span::styled(sanitize(title), border))
        .border_style(border);
    let inner = block.inner(area);
    // Clear first, so a shorter overlay never leaves the panes' text showing
    // through it.
    frame.render_widget(Clear, area);
    frame.render_widget(block, area);
    inner
}

/// Strips the style entirely when colour is suppressed, in one place, so no
/// call site can reintroduce it.
///
/// `--no-color` and `NO_COLOR` mean *no ANSI at all*, not "less colour": the
/// style becomes the terminal's own default, which emits no `SGR` sequence.
/// This is why suppression cannot live in the palette — a palette chooses
/// colours, and here there is nothing left to choose.
///
/// Attributes go with the colours because they are the same escape sequence:
/// `BOLD` and `REVERSED` are `SGR`, so keeping them while dropping the colour
/// would still be colour output wearing a hat. Nothing is lost, because nothing
/// was attribute-only: selection is the [`SELECTED_MARKER`] glyph, a failure is
/// the [`FAILED_MARKER`] glyph and a word, and reachability, trust and pairing
/// are glyphs as well.
fn style(_theme: &Theme, no_color: bool, style: Style) -> Style {
    if no_color {
        Style::default()
    } else {
        style
    }
}

#[cfg(test)]
mod tests {
    use std::time::Instant;

    use ratatui::backend::TestBackend;
    use ratatui::Terminal;

    use super::*;
    use crate::state::{DaemonInfo, HistoryMessage, HistoryPage, TuiState};
    use crate::tui::action::Action;

    /// A base instant for deterministic tests. `Instant` has no public
    /// constant, and none is needed: nothing here reads a duration, only
    /// compares and adds.
    fn t0() -> Instant {
        Instant::now()
    }

    /// Builds a `/state` body and parses it through the real parser, so a render
    /// test goes through the same required-field contract the daemon is held to.
    fn state(overrides: serde_json::Value) -> TuiState {
        let base = serde_json::json!({
            "ok": true,
            "version": 3,
            "self": {"deviceId": "me0", "deviceName": "Host", "osType": "LINUX", "deviceType": "DESKTOP"},
            "devices": [
                {"deviceId": "a1", "deviceName": "Fixture Phone", "deviceType": "ANDROID",
                 "trustStatus": "trusted", "reachability": "reachable",
                 "connectionTypes": ["KLARDROP"], "hasUnread": true, "unreadCount": 2},
                {"deviceId": "b2", "deviceName": "Fixture Tablet", "deviceType": "ANDROID",
                 "trustStatus": "untrusted", "reachability": "unreachable",
                 "connectionTypes": [], "hasUnread": false, "unreadCount": 0}
            ],
            "trustedIds": ["a1"],
            "protocols": {"klardrop": true, "nearby": true, "ble": false},
            "settings": {"backgroundDiscoveryEnabled": true, "supportsBackgroundDiscovery": false},
            "incoming": [],
            "notifications": [],
            "pairingDialog": null,
            "qrShare": {"active": false, "url": null, "expiresAt": null, "downloadCount": 0, "downloads": []},
            "transfers": [],
            "update": {"status": "up_to_date", "supported": true}
        });
        let mut value = base.as_object().expect("base object").clone();
        for (key, replacement) in overrides.as_object().expect("overrides object") {
            value.insert(key.clone(), replacement.clone());
        }
        crate::state::parse_tui_state(&serde_json::Value::Object(value)).expect("valid /state body")
    }

    fn app_with_devices(width: u16, height: u16) -> App {
        let mut app = App::new(t0());
        app.apply_state(&state(serde_json::json!({})));
        app.set_viewport(width, height);
        app
    }

    /// Renders and returns the terminal's text, one string per row.
    fn render(app: &App, theme: &Theme) -> Vec<String> {
        let backend = TestBackend::new(app.viewport().0.max(1), app.viewport().1.max(1));
        let mut terminal = Terminal::new(backend).expect("test terminal");
        terminal
            .draw(|frame| draw(frame, app, theme, false))
            .expect("draw");
        let buffer = terminal.backend().buffer().clone();
        (0..buffer.area.height)
            .map(|y| {
                (0..buffer.area.width)
                    .map(|x| buffer[(x, y)].symbol().to_string())
                    .collect::<String>()
            })
            .collect()
    }

    fn joined(rows: &[String]) -> String {
        rows.join("\n")
    }

    fn message(id: i64, content: &str, sender: bool, status: &str) -> HistoryMessage {
        HistoryMessage {
            id,
            content: content.to_string(),
            timestamp: 1_700_000_000_000,
            is_sender: sender,
            message_type: "text".to_string(),
            delivery_status: status.to_string(),
            is_read: true,
            mime_type: "text/plain".to_string(),
            file_transfer_id: None,
            file: None,
        }
    }

    #[test]
    fn the_two_pane_layout_shows_every_device_with_its_badge() {
        let app = app_with_devices(120, 40);
        let text = joined(&render(&app, &Theme::monochrome()));

        assert!(text.contains("Fixture Phone"), "{text}");
        assert!(text.contains("Fixture Tablet"), "{text}");
        assert!(text.contains("daemon: connected"), "{text}");
        assert!(text.contains("2 devices"), "{text}");
        assert!(text.contains("(2)"), "the unread count is shown: {text}");

        let selected = text
            .lines()
            .find(|line| line.contains("Fixture Phone"))
            .expect("first device row");
        assert!(
            selected.contains("> *+ Fixture Phone"),
            "the selected row carries a selection marker: {selected:?}"
        );
    }

    #[test]
    fn the_selection_marker_moves_with_the_selection() {
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::SelectNextDevice);
        let text = joined(&render(&app, &Theme::monochrome()));

        let second = text
            .lines()
            .find(|line| line.contains("Fixture Tablet"))
            .expect("second row");
        assert!(
            second.contains("> "),
            "selection followed to the tablet: {second:?}"
        );

        let first = text
            .lines()
            .find(|line| line.contains("Fixture Phone"))
            .expect("first row");
        assert!(!first.contains("> "), "and left the old row: {first:?}");
    }

    #[test]
    fn an_unreachable_device_says_so_in_words() {
        let app = app_with_devices(120, 40);
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(
            text.contains("Fixture Tablet  offline · untrusted"),
            "{text}"
        );
    }

    #[test]
    fn a_narrow_terminal_shows_the_list_and_then_the_conversation() {
        let mut app = app_with_devices(79, 24);
        let list = joined(&render(&app, &Theme::monochrome()));
        assert!(list.contains("Fixture Phone"), "{list}");
        assert!(list.contains("Fixture Tablet"), "{list}");

        app.apply(&Action::OpenDevice);
        let loading = joined(&render(&app, &Theme::monochrome()));
        assert!(
            !loading.contains("Fixture Tablet"),
            "below 80 columns only one pane is drawn: {loading}"
        );
        assert!(
            loading.contains("loading…"),
            "a conversation that has not answered yet must not read as an empty one: {loading}"
        );
        assert!(
            !loading.contains("no messages yet"),
            "and the empty state waits for the answer, not for the keystroke: {loading}"
        );

        app.apply_history(
            HistoryPage {
                device_id: "a1".into(),
                next_before: None,
                messages: Vec::new(),
            },
            false,
        );
        let conversation = joined(&render(&app, &Theme::monochrome()));
        assert!(
            conversation.contains("no messages yet"),
            "an empty conversation says so instead of looking broken: {conversation}"
        );
    }

    #[test]
    fn a_conversation_renders_direction_and_failure() {
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::OpenDevice);
        assert!(app.apply_history(
            HistoryPage {
                device_id: "a1".into(),
                next_before: None,
                messages: vec![
                    message(1, "hello", true, "SENT"),
                    message(2, "hi back", false, "SENT"),
                    message(3, "never arrived", true, "FAILED"),
                ],
            },
            false
        ));
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(text.contains("> hello"), "outgoing: {text}");
        assert!(text.contains("< hi back"), "incoming: {text}");
        assert!(
            text.contains("> never arrived x"),
            "a failed send is labelled, never shown as merely late: {text}"
        );
    }

    #[test]
    fn a_very_long_history_is_windowed_not_rendered_whole() {
        // Every message is wider than the pane, so each one is several display
        // rows. A window measured in *messages* would then put the overflow
        // below the pane, where scrolling cannot reach it, and the newest
        // message would never arrive on screen at all.
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::OpenDevice);
        let messages: Vec<_> = (1..=500)
            .map(|id| {
                message(
                    id,
                    &format!("message {id} {}", "padding ".repeat(12)),
                    true,
                    "SENT",
                )
            })
            .collect();
        app.apply_history(
            HistoryPage {
                device_id: "a1".into(),
                next_before: None,
                messages,
            },
            false,
        );

        // What the user sees, in the only terms they can check: the newest
        // message is on screen and the oldest is not. Nothing here is a
        // property of `TestBackend`'s size — every row the renderer wrote is
        // on the screen regardless of how tall that screen is.
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(
            text.contains("message 500"),
            "the newest message is visible: {text}"
        );
        assert!(
            !text.contains("message 1 "),
            "a 500-message history is never drawn in full: {text}"
        );

        // And the other half of "not rendered whole", which the screen cannot
        // show: the rows *handed to the paragraph* are bounded by the pane, not
        // by the history. 500 wrapping messages are over a thousand display
        // rows; a renderer that built them all and let the terminal clip would
        // pass the two assertions above and still cost a frame's worth of
        // string allocation per repaint.
        let rects = app.layout();
        let theme = Theme::monochrome();
        let capacity = rects.right.height.saturating_sub(2);
        let built = conversation_window(
            &app,
            &theme,
            false,
            rects.right.width.saturating_sub(2),
            capacity,
        );
        assert!(
            built.len() as u16 <= capacity,
            "{} rows were built for a {capacity}-row pane; the window must not \
             grow with the history",
            built.len()
        );
    }

    #[test]
    fn an_untrusted_device_name_can_never_reach_the_terminal() {
        let hostile = "evil\u{1b}[31m\u{1b}]0;pwned\u{7}\u{0}\nsecond";
        let mut app = App::new(t0());
        app.apply_state(&state(serde_json::json!({
            "devices": [{
                "deviceId": "a1", "deviceName": hostile, "deviceType": "ANDROID",
                "trustStatus": "trusted", "reachability": "reachable",
                "connectionTypes": [], "hasUnread": false, "unreadCount": 0
            }],
            "trustedIds": []
        })));
        app.set_viewport(120, 40);

        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(!text.contains('\u{1b}'), "no escape survived: {text:?}");
        assert!(!text.contains('\u{7}'), "no BEL survived: {text:?}");
        assert!(!text.contains('\u{0}'), "no NUL survived: {text:?}");
        assert!(
            text.contains("evil[31m"),
            "the words are still shown: {text}"
        );
    }

    #[test]
    fn a_terminal_too_small_shows_only_the_notice() {
        for (width, height) in [(10u16, 5u16), (19, 40), (40, 7)] {
            let app = app_with_devices(width, height);
            let text = joined(&render(&app, &Theme::monochrome()));
            assert!(
                text.contains("too small"),
                "{width}x{height} must say why: {text:?}"
            );
            assert!(
                !text.contains("Fixture Phone"),
                "{width}x{height} must lay out no device pane: {text:?}"
            );
        }
    }

    #[test]
    fn a_terminal_with_no_room_left_gets_only_what_fits() {
        // A 0x0 viewport: there is no cell for the whole notice, and the one
        // cell the test backend still provides may hold one character of it.
        // What must never happen is a laid-out device pane.
        let app = app_with_devices(0, 0);
        let rows = render(&app, &Theme::monochrome());
        assert_eq!(rows.len(), 1, "one row, the backend's own: {rows:?}");
        assert!(!rows[0].contains("Fixture"), "no device pane: {rows:?}");
        assert_eq!(
            rows[0].chars().count(),
            1,
            "only the first character of the notice fits: {rows:?}"
        );
    }

    #[test]
    fn every_overlay_renders_without_panicking_at_every_size() {
        for width in 0..=90u16 {
            for height in 0..=30u16 {
                let mut app = app_with_devices(width, height);
                app.apply(&Action::OpenDevice);
                app.apply_history(
                    HistoryPage {
                        device_id: "a1".into(),
                        next_before: None,
                        messages: vec![message(1, "x", true, "SENT")],
                    },
                    false,
                );
                app.apply(&Action::StartCompose);
                for overlay in [Action::Help, Action::OpenActionsMenu, Action::OpenSettings] {
                    app.apply(&overlay);
                    let _ = render(&app, &Theme::monochrome());
                }
                app.open_qr_overlay();
                let _ = render(&app, &Theme::monochrome());
            }
        }
    }

    #[test]
    fn the_help_overlay_lists_the_keys_the_dispatcher_answers_to() {
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::Help);
        let text = joined(&render(&app, &Theme::monochrome()));
        for (key, _) in KEY_HELP {
            let head = key.split(" / ").next().unwrap_or(key);
            assert!(text.contains(head), "help must mention {head:?}: {text}");
        }
    }

    #[test]
    fn the_actions_overlay_only_offers_pending_decisions() {
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::OpenActionsMenu);
        let empty = joined(&render(&app, &Theme::monochrome()));
        assert!(empty.contains("nothing pending"), "{empty}");

        app.apply_state(&state(serde_json::json!({
            "pairingDialog": {"deviceId": "c3", "deviceName": "Fixture Laptop",
                              "isError": false, "errorMessage": null},
            "incoming": [{
                "receiveId": 7, "deviceId": "c3", "deviceName": "Fixture Laptop",
                "status": "PendingAuthorization", "pendingAuth": true, "fileCount": 1,
                "fileNames": ["report.pdf"], "totalSize": 4096, "text": null
            }]
        })));
        app.set_daemon_info(&DaemonInfo {
            api_version: Some(1),
            version: Some("test".into()),
            capabilities: vec!["qr-share".into()],
        });
        app.apply(&Action::OpenDevice);
        app.apply(&Action::OpenActionsMenu);

        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(text.contains("accept pairing with c3"), "{text}");
        assert!(text.contains("reject pairing with c3"), "{text}");
        assert!(text.contains("accept incoming transfer #7"), "{text}");
        assert!(text.contains("reject incoming transfer #7"), "{text}");
        assert!(text.contains("start a QR share"), "{text}");
    }

    #[test]
    fn a_paired_device_row_says_so_without_asking_for_a_colour() {
        let app = app_with_devices(120, 40);
        let rows = render(&app, &Theme::monochrome());
        let paired = rows
            .iter()
            .find(|row| row.contains("Fixture Phone"))
            .expect("paired row");
        assert!(
            paired.contains("> *+ Fixture Phone  reachable · trusted"),
            "selection, reachability and trust are all on the row: {paired:?}"
        );
        let unpaired = rows
            .iter()
            .find(|row| row.contains("Fixture Tablet"))
            .expect("unpaired row");
        assert!(
            unpaired.contains("   - Fixture Tablet  offline · untrusted"),
            "an untrusted, unreachable device says both in a glyph and a word: {unpaired:?}"
        );
    }

    /// A square module matrix of `size`, checkerboarded so dark and light
    /// modules both appear.
    fn qr_rows(size: usize) -> Vec<String> {
        (0..size)
            .map(|row| {
                (0..size)
                    .map(|column| if (row + column) % 2 == 0 { '1' } else { '0' })
                    .collect()
            })
            .collect()
    }

    /// An app whose share session carries `qr` as the daemon's module matrix.
    fn app_with_qr(width: u16, height: u16, qr: Vec<String>) -> App {
        let mut app = app_with_devices(width, height);
        app.apply_state(&state(serde_json::json!({
            "qrShare": {
                "active": true,
                "url": "https://127.0.0.1:8765/qr/abc\u{1b}[2J",
                "expiresAt": null,
                "downloadCount": 2,
                "downloads": [],
                "qr": qr
            }
        })));
        app.open_qr_overlay();
        app
    }

    #[test]
    fn the_qr_overlay_says_when_no_share_is_running() {
        let mut app = app_with_devices(120, 40);
        app.open_qr_overlay();
        let idle = joined(&render(&app, &Theme::monochrome()));
        assert!(idle.contains("no QR share is running"), "{idle}");
    }

    #[test]
    fn a_qr_that_fits_is_drawn_with_block_glyphs_and_keeps_the_url() {
        let app = app_with_qr(120, 40, qr_rows(21));
        let text = joined(&render(&app, &Theme::monochrome()));
        // Version 1 is 21 modules; with the quiet zone that is 58 columns and
        // 15 text rows, and the overlay's inner area is 70 by 18.
        // A checkerboard alternates, so no cell is dark in both halves: the
        // half blocks are what carries the code.
        assert!(
            text.contains('▀') || text.contains('▄'),
            "no dark module was drawn:\n{text}"
        );
        assert!(text.contains('░'), "no light module was drawn:\n{text}");
        assert!(
            text.contains("https://127.0.0.1:8765/qr/abc"),
            "the url must stay readable under the code:\n{text}"
        );
        assert!(
            !text.contains('\u{1b}'),
            "the url is sanitized too: {text:?}"
        );
    }

    #[test]
    fn a_qr_that_does_not_fit_says_so_and_says_how_much_room_it_wants() {
        // 63 columns leaves the overlay's inner area 57 wide, one short of the
        // 58 a version-1 code needs.
        let app = app_with_qr(63, 40, qr_rows(21));
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(text.contains("the code does not fit this window"), "{text}");
        assert!(text.contains("58 columns by 15 rows"), "{text}");
        assert!(
            text.contains("https://127.0.0.1:8765/qr/abc"),
            "the url is the fallback for anything that cannot scan the screen:\n{text}"
        );
    }

    #[test]
    fn drawing_starts_exactly_when_the_overlay_is_big_enough() {
        // The overlay is the viewport minus a 2-cell margin, and the block
        // border takes one more cell on each side: a 64-wide viewport leaves 58
        // inner columns and a 63-wide one leaves 57. A version-1 code needs
        // exactly 58, so those two widths are the whole boundary.
        let draws = |width: u16| {
            joined(&render(
                &app_with_qr(width, 40, qr_rows(21)),
                &Theme::monochrome(),
            ))
            .contains('▀')
        };
        assert!(
            draws(64),
            "58 inner columns is exactly enough and must draw"
        );
        assert!(
            !draws(63),
            "57 inner columns is one short and must not draw"
        );
    }

    #[test]
    fn a_malformed_qr_is_never_drawn_and_says_why() {
        // Three rows, the first four columns wide: a shape no decoder could
        // read, and exactly what must never be drawn as though it were a code.
        let app = app_with_qr(
            120,
            40,
            vec!["0110".to_string(), "100".to_string(), "0110".to_string()],
        );
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(
            text.contains("this code cannot be drawn:") && text.contains("not a square"),
            "the reason must be on screen:\n{text}"
        );
        assert!(!text.contains('▀'), "half a code is not a code:\n{text}");
        assert!(
            text.contains("https://127.0.0.1:8765/qr/abc"),
            "the url is still the fallback:\n{text}"
        );
    }

    #[test]
    fn with_colour_suppressed_the_two_modules_still_differ_by_glyph() {
        let app = app_with_qr(120, 40, qr_rows(21));
        let backend = TestBackend::new(app.viewport().0, app.viewport().1);
        let mut terminal = Terminal::new(backend).expect("test terminal");
        terminal
            .draw(|frame| draw(frame, &app, &Theme::monochrome(), true))
            .expect("draw");
        let buffer = terminal.backend().buffer().clone();
        let mut dark = false;
        let mut light = false;
        for y in 0..buffer.area.height {
            for x in 0..buffer.area.width {
                match buffer[(x, y)].symbol() {
                    "█" | "▀" | "▄" => dark = true,
                    "░" => light = true,
                    _ => {}
                }
            }
        }
        assert!(dark, "no dark module survived NO_COLOR");
        assert!(
            light,
            "a light module must differ by glyph, not by colour alone"
        );
    }

    #[test]
    fn the_settings_overlay_states_whether_the_daemon_supports_the_toggle() {
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::OpenSettings);
        let on = joined(&render(&app, &Theme::monochrome()));
        assert!(on.contains("background discovery"), "{on}");
        assert!(on.contains(": on"), "{on}");
        assert!(
            on.contains("this daemon does not support background discovery"),
            "a control the daemon cannot honour is labelled: {on}"
        );

        app.apply_state(&state(serde_json::json!({
            "settings": {"backgroundDiscoveryEnabled": false, "supportsBackgroundDiscovery": true}
        })));
        app.apply(&Action::OpenSettings);
        let off = joined(&render(&app, &Theme::monochrome()));
        assert!(off.contains(": off"), "{off}");
        assert!(
            off.contains("this daemon does support background discovery"),
            "{off}"
        );
    }

    #[test]
    fn the_compose_line_shows_the_prompt_and_what_has_been_typed() {
        let mut app = app_with_devices(120, 40);
        app.apply(&Action::OpenDevice);
        app.apply(&Action::StartFilePrompt);
        app.paste("/tmp/one.pdf /tmp/two.pdf");
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(
            text.contains("absolute paths, separated by spaces"),
            "{text}"
        );
        assert!(text.contains("/tmp/one.pdf /tmp/two.pdf"), "{text}");
    }

    #[test]
    fn live_transfer_progress_is_shown_as_the_daemon_reports_it() {
        let mut app = app_with_devices(120, 40);
        app.apply_state(&state(serde_json::json!({
            "transfers": [{
                "id": "tx-1", "deviceId": "a1", "fileName": "report.pdf",
                "totalSize": 200, "transferredSize": 50, "isSender": true, "phase": "progress"
            }]
        })));
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(text.contains("report.pdf progress 25%"), "{text}");
    }

    #[test]
    fn an_empty_device_list_and_an_offline_daemon_both_say_so() {
        let mut app = App::new(t0());
        app.apply_state(&state(serde_json::json!({"devices": [], "trustedIds": []})));
        app.set_connection(ConnectionState::Offline("connection refused".into()));
        app.set_viewport(120, 40);
        let text = joined(&render(&app, &Theme::monochrome()));
        assert!(text.contains("No devices yet"), "{text}");
        assert!(
            text.contains("daemon: offline: connection refused"),
            "{text}"
        );
        assert!(text.contains("0 devices"), "{text}");
    }
}
