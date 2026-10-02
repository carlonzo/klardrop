//! The TUI's whole state, and the pure function that changes it.
//!
//! [`App::handle_key`] performs no I/O: a key goes in, a list of [`Action`]s
//! comes out. [`App::apply`] is the other half — it turns those actions into
//! state changes, again without touching anything. Between them they hold every
//! keyboard behaviour the TUI has, which is why all of it is testable with no
//! terminal, no daemon and no threads, and why the input loop stays a loop and
//! nothing more.

use std::path::PathBuf;
use std::time::{Duration, Instant};

use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};

use crate::state::{
    ActiveTransfer, DaemonInfo, DeviceSummary, HistoryMessage, HistoryPage, IncomingTransfer,
    Notification, PairingDialog, QrShareState, Settings, TuiState, UpdateStatus,
};
use crate::tui::action::{Action, Pane};
use crate::tui::layout::{self, Rects, SinglePane};
use crate::tui::motion::{Activity, Motion, FLOURISH_DURATION};

/// How long a toast stays on screen.
pub const TOAST_LIFETIME: Duration = Duration::from_millis(4000);

/// How far one `PageUp`/`PageDown` moves the conversation, in display rows.
/// Kept as a constant rather than a screenful so the gesture is the same
/// whatever the terminal is sized to.
pub const HISTORY_PAGE: usize = 10;

/// Which pane the keyboard is driving.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Focus {
    Devices,
    Conversation,
}

/// Whether keys are navigating or being typed into.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputMode {
    Normal,
    Compose,
}

/// What the compose line is collecting. One buffer, three jobs: every one of
/// them is "type a thing, press Enter", and none of them can be reached by a
/// stray keystroke while navigating.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ComposeKind {
    Text,
    FilePaths,
    Rename,
    /// Absolute paths to publish through a QR share session.
    QrPaths,
}

impl ComposeKind {
    /// The prompt shown under the compose line.
    pub fn prompt(self) -> &'static str {
        match self {
            Self::Text => "message",
            Self::FilePaths => "absolute paths, separated by spaces",
            Self::Rename => "new name for this device",
            Self::QrPaths => "absolute paths to publish, separated by spaces",
        }
    }
}

/// The overlay currently on top of the panes, if any.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Overlay {
    None,
    Help,
    Actions,
    Settings,
    Qr,
}

/// What this TUI session is for.
///
/// [`Mode::Full`] is the ordinary two-pane client. [`Mode::Picker`] is the one
/// narrow job `klardrop share --pick` needs: the payload already exists, the
/// only question is *which device*, and the answer is handed back to the
/// caller. It is a separate mode rather than a flag because a picker's
/// keyboard must not be able to reach any of the full client's actions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Mode {
    Full,
    /// Choose a device for an already-supplied payload. `summary` is the
    /// one-line description of what is about to be sent, shown above the list.
    Picker {
        summary: String,
    },
}

impl Mode {
    pub fn is_picker(&self) -> bool {
        matches!(self, Self::Picker { .. })
    }
}

/// Whether the daemon is answering. The TUI keeps working when it is not:
/// navigation, compose and the last known devices all stay usable.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionState {
    Connecting,
    Live,
    Offline(String),
}

impl ConnectionState {
    /// The word shown in the header. Never colour alone.
    pub fn label(&self) -> &'static str {
        match self {
            Self::Connecting => "connecting",
            Self::Live => "connected",
            Self::Offline(_) => "offline",
        }
    }

    pub fn is_live(&self) -> bool {
        matches!(self, Self::Live)
    }
}

/// A transient message over the footer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Toast {
    pub message: String,
    expires_at: Instant,
}

impl Toast {
    pub fn expired(&self, now: Instant) -> bool {
        now >= self.expires_at
    }
}

/// One line of the actions overlay.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MenuEntry {
    AcceptPairing(String),
    RejectPairing(String),
    AcceptIncoming(i64),
    RejectIncoming(i64),
    SendFiles,
    SendClipboard,
    ShowQr,
    RenameDevice,
    Settings,
    CheckUpdate,
    ApplyUpdate,
}

impl MenuEntry {
    /// The label shown in the overlay.
    pub fn label(&self) -> String {
        match self {
            Self::AcceptPairing(id) => format!("accept pairing with {id}"),
            Self::RejectPairing(id) => format!("reject pairing with {id}"),
            Self::AcceptIncoming(id) => format!("accept incoming transfer #{id}"),
            Self::RejectIncoming(id) => format!("reject incoming transfer #{id}"),
            Self::SendFiles => "send files…".to_string(),
            Self::SendClipboard => "send clipboard".to_string(),
            Self::ShowQr => "start a QR share…".to_string(),
            Self::RenameDevice => "rename this device…".to_string(),
            Self::Settings => "settings".to_string(),
            Self::CheckUpdate => "check for updates".to_string(),
            Self::ApplyUpdate => "apply the staged update".to_string(),
        }
    }

    /// What choosing this entry does.
    pub fn to_action(&self) -> Action {
        match self {
            Self::AcceptPairing(id) => Action::AcceptPairing(id.clone()),
            Self::RejectPairing(id) => Action::RejectPairing(id.clone()),
            Self::AcceptIncoming(id) => Action::AcceptIncoming(*id),
            Self::RejectIncoming(id) => Action::RejectIncoming(*id),
            Self::SendFiles => Action::StartFilePrompt,
            Self::SendClipboard => Action::SendClipboard,
            Self::ShowQr => Action::StartQrPrompt,
            Self::RenameDevice => Action::StartRenamePrompt,
            Self::Settings => Action::OpenSettings,
            Self::CheckUpdate => Action::CheckUpdate,
            Self::ApplyUpdate => Action::ApplyUpdate,
        }
    }
}

/// Every fact the renderer needs, and every fact the keyboard may act on.
#[derive(Debug, Clone)]
pub struct App {
    focus: Focus,
    self_name: String,
    selected: usize,
    devices: Vec<DeviceSummary>,
    connection: ConnectionState,
    pending_overlay: Overlay,
    input_mode: InputMode,
    compose_kind: ComposeKind,
    compose: String,
    toast: Option<Toast>,
    quitting: bool,
    last_tick: Instant,
    viewport: (u16, u16),

    open_device: Option<String>,
    messages: Vec<HistoryMessage>,
    next_before: Option<i64>,
    history_loading_for: Option<String>,
    scroll: usize,

    pairing_dialog: Option<PairingDialog>,
    incoming: Vec<IncomingTransfer>,
    notifications: Vec<Notification>,
    qr_share: QrShareState,
    update: UpdateStatus,
    active_transfers: Vec<ActiveTransfer>,
    settings: Settings,
    capabilities: Vec<String>,

    /// Device id and unread count as of the last `POST /history/read`, so the
    /// daemon is told once per batch of messages rather than once per redraw.
    marked_read: Option<(String, u64)>,

    /// What this session is for, and — in picker mode — the device the user
    /// settled on.
    mode: Mode,
    picked: Option<String>,

    /// The animation clock. Disabled by `--no-motion`, in which case every
    /// question it is asked still has a truthful static answer.
    motion: Motion,
    /// `true` when the last [`Self::tick`] advanced the animation, so the input
    /// loop knows a redraw is due for motion's sake and not for the user's.
    motion_dirty: bool,

    /// When the success flourish stops being drawn, set only by a daemon
    /// report of a completed send. `None` means there is nothing to
    /// acknowledge, which is the state an idle client is always in.
    flourish_until: Option<Instant>,

    /// Whether this session currently holds the mouse. The terminal guard owns
    /// the capture; this is the copy the footer draws, kept in step by
    /// [`Action::ToggleMouseCapture`].
    mouse_captured: bool,

    /// Whether this session could hold a mouse at all, i.e. whether the
    /// terminal guard was started without `--no-mouse`.
    ///
    /// Separate from [`Self::mouse_captured`] because the two are not
    /// complements: a session started with `--no-mouse` reports "not
    /// captured" for its whole life, and a footer that reads that as "released
    /// — press Ctrl-Space to get it back" is describing a capture that never
    /// happened and offering a key that cannot work.
    mouse_available: bool,
}

impl App {
    pub fn new(now: Instant) -> Self {
        Self {
            focus: Focus::Devices,
            mode: Mode::Full,
            motion: Motion::disabled(now),
            motion_dirty: false,
            flourish_until: None,
            mouse_captured: false,
            mouse_available: true,
            picked: None,
            self_name: String::new(),
            selected: 0,
            devices: Vec::new(),
            connection: ConnectionState::Connecting,
            pending_overlay: Overlay::None,
            input_mode: InputMode::Normal,
            compose_kind: ComposeKind::Text,
            compose: String::new(),
            toast: None,
            quitting: false,
            last_tick: now,
            viewport: (80, 24),
            open_device: None,
            messages: Vec::new(),
            next_before: None,
            history_loading_for: None,
            scroll: 0,
            pairing_dialog: None,
            incoming: Vec::new(),
            notifications: Vec::new(),
            qr_share: QrShareState::default(),
            update: UpdateStatus::default(),
            active_transfers: Vec::new(),
            settings: Settings {
                background_discovery_enabled: false,
                supports_background_discovery: false,
            },
            capabilities: Vec::new(),
            marked_read: None,
        }
    }

    /// Turns this session into a device picker for `summary`.
    pub fn set_picker(&mut self, summary: impl Into<String>) {
        self.mode = Mode::Picker {
            summary: summary.into(),
        };
        self.picked = None;
        self.quitting = false;
        self.focus = Focus::Devices;
    }

    pub fn mode(&self) -> &Mode {
        &self.mode
    }

    pub fn is_picker(&self) -> bool {
        self.mode.is_picker()
    }

    /// The device the picker settled on, once the user has confirmed one.
    pub fn picked(&self) -> Option<&str> {
        self.picked.as_deref()
    }

    // ------------------------------------------------------------ accessors
    /// The display name of the machine this window belongs to, as the daemon
    /// reports it. Shown in the header so the user can tell which machine the
    /// window belongs to at a glance.
    pub fn self_device_name(&self) -> &str {
        &self.self_name
    }

    pub fn focus(&self) -> Focus {
        self.focus
    }

    pub fn selected(&self) -> usize {
        self.selected
    }

    pub fn selected_device(&self) -> Option<&DeviceSummary> {
        self.devices.get(self.selected)
    }

    pub fn devices(&self) -> &[DeviceSummary] {
        &self.devices
    }

    pub fn connection(&self) -> &ConnectionState {
        &self.connection
    }

    pub fn set_connection(&mut self, connection: ConnectionState) {
        self.connection = connection;
    }

    pub fn overlay(&self) -> Overlay {
        self.pending_overlay
    }

    pub fn input_mode(&self) -> InputMode {
        self.input_mode
    }

    pub fn compose_kind(&self) -> ComposeKind {
        self.compose_kind
    }

    pub fn compose(&self) -> &str {
        &self.compose
    }

    pub fn toast(&self) -> Option<&Toast> {
        self.toast.as_ref()
    }

    pub fn is_quitting(&self) -> bool {
        self.quitting
    }

    pub fn viewport(&self) -> (u16, u16) {
        self.viewport
    }

    pub fn set_viewport(&mut self, width: u16, height: u16) {
        self.viewport = (width, height);
    }

    pub fn open_device(&self) -> Option<&str> {
        self.open_device.as_deref()
    }

    pub fn messages(&self) -> &[HistoryMessage] {
        &self.messages
    }

    /// How far the conversation is scrolled back, in **display rows**.
    ///
    /// Rows, not messages: a message longer than the pane is several rows
    /// tall, and a scroll measured in messages would step over the middle of
    /// it. [`crate::tui::text::history_rows`] is the measure, and the renderer
    /// clamps to what actually fits.
    pub fn scroll(&self) -> usize {
        self.scroll
    }

    /// Columns the conversation text is wrapped at: the right pane minus its
    /// two border columns. `None` when no conversation pane is on screen, in
    /// which case nothing is drawn and nothing can be scrolled.
    fn conversation_width(&self) -> Option<u16> {
        if self.is_picker() || !self.fits() {
            return None;
        }
        let width = self.layout().right.width.saturating_sub(2);
        (width > 0).then_some(width)
    }

    /// Display rows the loaded history occupies at the current pane width —
    /// the ceiling every scroll is clamped to.
    ///
    /// Derived from the same layout the renderer draws, so the reducer's idea
    /// of "the whole conversation" and the pane's cannot drift apart.
    fn history_rows(&self) -> usize {
        match self.conversation_width() {
            Some(width) => crate::tui::text::history_rows(&self.messages, width),
            None => 0,
        }
    }

    pub fn qr_share(&self) -> &QrShareState {
        &self.qr_share
    }

    pub fn active_transfers(&self) -> &[ActiveTransfer] {
        &self.active_transfers
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    pub fn has_capability(&self, capability: &str) -> bool {
        self.capabilities
            .iter()
            .any(|name| name.as_str() == capability)
    }

    /// Records the daemon's advertised routes. A control for a route the daemon
    /// does not advertise is labelled unavailable rather than offered.
    pub fn set_daemon_info(&mut self, info: &DaemonInfo) {
        self.capabilities = info.capabilities.clone();
    }

    // --------------------------------------------------------------- layout

    /// Which pane owns the screen at the current size, and the rectangles.
    pub fn layout(&self) -> Rects {
        layout::compute(self.viewport.0, self.viewport.1, self.single_pane())
    }

    fn single_pane(&self) -> SinglePane {
        if self.open_device.is_some() {
            SinglePane::Conversation
        } else {
            SinglePane::List
        }
    }

    /// `true` when the terminal can show the TUI at all.
    pub fn fits(&self) -> bool {
        Rects::fits(self.viewport.0, self.viewport.1)
    }

    /// The device whose conversation is actually on screen right now.
    ///
    /// This is the only device whose read state the TUI may change, and it is
    /// derived from the same [`layout`] the renderer draws, so "focused and
    /// visible" cannot drift from "drawn". Selecting around the device list
    /// never opens a conversation, so a user scrolling past a device marks
    /// nothing.
    pub fn visible_device_id(&self) -> Option<&str> {
        if !self.fits() {
            return None;
        }
        match self.layout().visible_pane() {
            Some(SinglePane::List) => None,
            _ => self.open_device.as_deref(),
        }
    }

    // ----------------------------------------------------------------- state

    /// Folds one `/state` response into the app and returns the actions the
    /// daemon now owes us.
    pub fn apply_state(&mut self, state: &TuiState) -> Vec<Action> {
        self.devices = state.devices();
        self.self_name = state.self_name().to_string();
        self.connection = ConnectionState::Live;
        self.settings = state.settings.clone();
        self.pairing_dialog = state.pairing_dialog.clone();
        self.incoming = state.incoming.clone();
        self.notifications = state.notifications.clone();
        self.qr_share = state.qr_share.clone();
        self.update = state.update.clone();
        self.active_transfers = state.transfers.clone();
        self.reconcile_devices();
        self.reconcile()
    }

    /// Keeps the view honest after the device list changes shape: the selection
    /// lands on a real row, and a conversation whose device disappeared does not
    /// keep showing stale history as if it were live.
    fn reconcile_devices(&mut self) {
        if self.devices.is_empty() {
            self.selected = 0;
        } else if self.selected >= self.devices.len() {
            self.selected = self.devices.len() - 1;
        }
        let gone = match &self.open_device {
            Some(open) => !self.devices.iter().any(|d| &d.device_id == open),
            None => false,
        };
        if gone {
            self.close_device();
        }
    }

    /// The actions implied by the current view. Called after every state
    /// change, so read marking is a consequence of what is on screen rather
    /// than a side effect of which key was pressed.
    pub fn reconcile(&mut self) -> Vec<Action> {
        let Some(device_id) = self.visible_device_id().map(str::to_string) else {
            return Vec::new();
        };
        let unread = self
            .devices
            .iter()
            .find(|device| device.device_id == device_id)
            .map(|device| device.unread_count)
            .unwrap_or(0);
        if unread == 0 {
            return Vec::new();
        }
        let already = self
            .marked_read
            .as_ref()
            .is_some_and(|(id, count)| id == &device_id && *count == unread);
        if already {
            return Vec::new();
        }
        self.marked_read = Some((device_id.clone(), unread));
        vec![Action::MarkRead(device_id)]
    }

    /// Records that a history load for `device` has started, so the pane can say
    /// so instead of showing an empty conversation that reads as "no messages".
    pub fn begin_history_load(&mut self, device: &str) {
        self.history_loading_for = Some(device.to_string());
        self.messages.clear();
        self.next_before = None;
        self.scroll = 0;
        self.marked_read = None;
    }

    /// Folds one `/history` page into the conversation.
    ///
    /// A page that arrives after its conversation was closed is dropped: a slow
    /// answer for a device the user has moved on from must not repopulate the
    /// pane they are now looking at.
    pub fn apply_history(&mut self, page: HistoryPage, older: bool) -> bool {
        if self.open_device.as_deref() != Some(page.device_id.as_str()) {
            return false;
        }
        self.history_loading_for = None;
        if older {
            let known: Vec<i64> = self.messages.iter().map(|m| m.id).collect();
            for message in page.messages {
                if !known.contains(&message.id) {
                    self.messages.insert(0, message);
                }
            }
        } else {
            self.messages = page.messages;
        }
        self.next_before = page.next_before;
        self.scroll = 0;
        true
    }

    /// Records that a history load failed, so the pane can say why rather than
    /// looking empty.
    pub fn fail_history(&mut self, message: &str) {
        self.history_loading_for = None;
        self.messages.clear();
        self.next_before = None;
        self.notify(message);
    }

    pub fn is_history_loading(&self) -> bool {
        self.history_loading_for.is_some()
    }

    /// Closes the conversation and returns to the device list.
    fn close_device(&mut self) {
        self.open_device = None;
        self.messages.clear();
        self.next_before = None;
        self.history_loading_for = None;
        self.scroll = 0;
        self.marked_read = None;
    }

    /// Shows a transient message over the footer.
    pub fn notify(&mut self, message: impl Into<String>) {
        let now = Instant::now();
        self.toast = Some(Toast {
            message: message.into(),
            expires_at: now + TOAST_LIFETIME,
        });
    }

    /// Advances the clock. Three things change here and none of them blocks: an
    /// expired toast is cleared, an expired flourish is dropped, and the
    /// animation clock is advanced — which reports whether a redraw is owed.
    /// When nothing is animating the motion clock is never even asked to
    /// advance, so an idle TUI leaves no timer running.
    pub fn tick(&mut self, now: Instant) {
        self.last_tick = now;
        if self.toast.as_ref().is_some_and(|toast| toast.expired(now)) {
            self.toast = None;
            self.motion_dirty = true;
        }
        // The flourish has a fixed lifetime, so it always ends. Dropping it
        // here is what guarantees the acknowledgement cannot leave a timer
        // running for a result the user has already read.
        if self.flourish_until.is_some_and(|until| now >= until) {
            self.flourish_until = None;
            self.motion_dirty = true;
        }
        let activity = self.activity();
        self.motion_dirty |= self.motion.advance(now, activity);
    }

    /// Turns animation on or off. `braille` is whether the terminal can render
    /// the braille spinner; a terminal that cannot gets the ASCII one, which is
    /// why this is decided once at startup rather than probed per frame.
    pub fn set_motion(&mut self, enabled: bool, braille: bool, now: Instant) {
        self.motion = if enabled {
            Motion::enabled(now, braille)
        } else {
            Motion::disabled(now)
        };
        self.motion_dirty = false;
    }

    /// Acknowledges a send the daemon has just reported as completed.
    ///
    /// The caller is the daemon's own event, never a timer: nothing here can
    /// start a flourish for a result that has not arrived.
    pub fn celebrate(&mut self, now: Instant) {
        self.flourish_until = Some(now + FLOURISH_DURATION);
        self.motion_dirty = true;
    }

    /// The acknowledgement glyph, or `None` when there is nothing to
    /// acknowledge. The renderer shows it beside the daemon's own words, which
    /// are present whether or not this is.
    pub fn flourish(&self) -> Option<&'static str> {
        self.flourish_until?;
        self.motion.flourish(Activity::Flourish)
    }

    /// What, if anything, is genuinely moving right now. Every animated pixel
    /// in the UI comes from this one answer, which is why an animation can
    /// never claim a state the daemon has not reported.
    ///
    /// The order is a precedence, not a preference: an open overlay outranks
    /// all of it, then a transfer the daemon is still moving outranks a
    /// spinner, which outranks the acknowledgement of something that already
    /// finished.
    ///
    /// The overlay rule is about cost, not meaning. An overlay covers the panes
    /// the spinner and the bar are drawn in, so animating behind it is a wakeup
    /// and a redraw the user cannot see. Nothing is forgotten: the state is
    /// untouched and the bar is back the moment the overlay closes.
    pub fn activity(&self) -> Activity {
        if self.pending_overlay != Overlay::None {
            return Activity::Idle;
        }
        if !self.active_transfers.is_empty() {
            let percent = self
                .active_transfers
                .iter()
                .map(|transfer| transfer.percent())
                .max()
                .unwrap_or(0);
            // Settled only when the daemon has said every listed transfer is
            // finished: one still running keeps the whole bar moving.
            let settled = self
                .active_transfers
                .iter()
                .all(|transfer| transfer.is_settled());
            return Activity::Transferring {
                percent: percent as u8,
                settled,
            };
        }
        if self.connection == ConnectionState::Connecting {
            return Activity::Connecting;
        }
        if self.flourish_until.is_some() {
            return Activity::Flourish;
        }
        Activity::Idle
    }

    /// The animation clock, for the renderer.
    pub fn motion(&self) -> &Motion {
        &self.motion
    }

    /// Whether the last tick moved the animation, and clears the flag so one
    /// frame is drawn for one advance and not one per poll.
    pub fn take_motion_dirty(&mut self) -> bool {
        std::mem::take(&mut self.motion_dirty)
    }

    /// Whether this session currently holds the mouse, for the footer.
    pub fn mouse_captured(&self) -> bool {
        self.mouse_captured
    }

    /// Records what the terminal guard actually did. The guard is the owner:
    /// when a write fails, the input loop calls this to put the two back in
    /// step, so the footer never claims a capture the terminal does not have.
    pub fn set_mouse_captured(&mut self, captured: bool) {
        self.mouse_captured = captured;
    }

    /// Whether this session can hold a mouse at all, for the footer and the
    /// `Ctrl-Space` toast.
    pub fn mouse_available(&self) -> bool {
        self.mouse_available
    }

    /// Records whether the guard took the mouse at startup, so the two can be
    /// told apart. The guard is the owner, exactly as for
    /// [`Self::set_mouse_captured`].
    pub fn set_mouse_available(&mut self, available: bool) {
        self.mouse_available = available;
    }

    // --------------------------------------------------------------- reducer

    /// Maps one key event to the actions it implies. No I/O, no daemon, no
    /// drawing: this function only decides.
    pub fn handle_key(&mut self, key: KeyEvent, now: Instant) -> Vec<Action> {
        self.last_tick = now;

        // Ctrl-C quits from every mode, including mid-compose and mid-overlay.
        // Raw mode stops the terminal delivering SIGINT, so this is the only
        // interrupt the user has.
        // A release is the other half of the same keystroke, not a second one.
        // Only a press may act, so a terminal that reports both does not run
        // every command twice.
        if key.kind == KeyEventKind::Release {
            return Vec::new();
        }

        // `is_char` deliberately rejects a key that carries CONTROL, so Ctrl-C has to be matched
        // on the code directly — otherwise the one interrupt raw mode leaves the user never fires.
        if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
            self.quitting = true;
            return vec![Action::Quit];
        }

        // Ctrl-Space is the selection bypass, and it works from every mode
        // except the compose line: a user reaching for it is asking about the
        // mouse, not typing. A terminal sends it as NUL, which crossterm
        // decodes as Ctrl-Space.
        if key.code == KeyCode::Char(' ')
            && key.modifiers.contains(KeyModifiers::CONTROL)
            && self.input_mode == InputMode::Normal
        {
            return vec![Action::ToggleMouseCapture];
        }

        match self.input_mode {
            InputMode::Compose => self.handle_compose_key(&key),
            InputMode::Normal => self.handle_normal_key(&key),
        }
    }

    /// Keys while navigating. An overlay swallows everything but its own keys.
    fn handle_normal_key(&mut self, key: &KeyEvent) -> Vec<Action> {
        if self.pending_overlay != Overlay::None {
            return self.handle_overlay_key(key);
        }
        if let Mode::Picker { .. } = self.mode {
            return self.handle_picker_key(key);
        }
        match key.code {
            KeyCode::Esc => vec![Action::Back],
            KeyCode::Tab => vec![Action::MoveFocus(1)],
            KeyCode::BackTab => vec![Action::MoveFocus(-1)],
            KeyCode::Up => vec![match self.focus {
                Focus::Devices => Action::SelectPrevDevice,
                Focus::Conversation => Action::PageHistoryUp,
            }],
            KeyCode::Down => vec![match self.focus {
                Focus::Devices => Action::SelectNextDevice,
                Focus::Conversation => Action::PageHistoryDown,
            }],
            KeyCode::PageUp => vec![Action::PageHistoryUp],
            KeyCode::PageDown => vec![Action::PageHistoryDown],
            KeyCode::Enter => vec![match self.focus {
                Focus::Devices => Action::OpenDevice,
                Focus::Conversation => Action::StartCompose,
            }],
            _ if is_char(key, '?') => vec![Action::Help],
            _ if is_char(key, 'q') => vec![Action::Quit],
            _ if is_char(key, 'a') => vec![Action::OpenActionsMenu],
            _ if is_char(key, 'r') => vec![Action::Refresh],
            _ if is_char(key, 'i') => vec![Action::StartCompose],
            _ if is_char(key, 'y') => {
                if self.open_device.is_some() {
                    vec![Action::SendClipboard]
                } else {
                    vec![Action::Back]
                }
            }
            _ if is_char(key, 'f') => vec![Action::StartFilePrompt],
            _ if is_char(key, 'n') => vec![Action::StartRenamePrompt],
            _ => Vec::new(),
        }
    }

    /// Keys in the device picker.
    ///
    /// A picker answers exactly one question, so it accepts exactly the keys
    /// that answer it: move, accept, refresh, cancel. Every other key — and
    /// that is every letter, including the ones that would start a compose
    /// line — does nothing at all, because a caller that only wanted a device
    /// must not be able to send something by accident.
    fn handle_picker_key(&mut self, key: &KeyEvent) -> Vec<Action> {
        match key.code {
            KeyCode::Up => vec![Action::SelectPrevDevice],
            KeyCode::Down => vec![Action::SelectNextDevice],
            // With nothing to choose there is nothing to confirm; refreshing
            // is the only useful answer, and the daemon may still be starting.
            KeyCode::Enter if self.devices.is_empty() => vec![Action::Refresh],
            KeyCode::Enter => vec![Action::ConfirmDevice],
            KeyCode::Esc => vec![Action::Quit],
            _ if is_char(key, 'q') => vec![Action::Quit],
            _ if is_char(key, 'r') => vec![Action::Refresh],
            _ => Vec::new(),
        }
    }

    /// Keys while an overlay is up. Escape and the overlay's own keys work;
    /// nothing behind it is reachable.
    fn handle_overlay_key(&mut self, key: &KeyEvent) -> Vec<Action> {
        match self.pending_overlay {
            Overlay::None => Vec::new(),
            Overlay::Help => {
                if is_char(key, 'q') {
                    vec![Action::Quit]
                } else if matches!(key.code, KeyCode::Esc) || is_char(key, '?') {
                    vec![Action::Back]
                } else {
                    Vec::new()
                }
            }
            Overlay::Actions => {
                if matches!(key.code, KeyCode::Esc) {
                    vec![Action::Back]
                } else if let KeyCode::Char(digit) = key.code {
                    self.menu_action(digit)
                } else {
                    Vec::new()
                }
            }
            Overlay::Settings => {
                if matches!(key.code, KeyCode::Esc) {
                    vec![Action::Back]
                } else if is_char(key, 'd') {
                    vec![Action::SetBackgroundDiscovery(
                        !self.settings.background_discovery_enabled,
                    )]
                } else {
                    Vec::new()
                }
            }
            Overlay::Qr => {
                if matches!(key.code, KeyCode::Esc) || is_char(key, 'q') {
                    vec![Action::Back]
                } else {
                    Vec::new()
                }
            }
        }
    }

    /// The action behind digit `digit` in the actions overlay. Entries are
    /// built from what the daemon reported, so an unlisted digit does nothing.
    fn menu_action(&self, digit: char) -> Vec<Action> {
        let index = match digit.to_digit(10) {
            Some(index) if index >= 1 => index as usize - 1,
            _ => return Vec::new(),
        };
        match self.menu_entries().get(index) {
            Some(entry) => vec![entry.to_action()],
            None => Vec::new(),
        }
    }

    /// Keys while the compose line is open. Every other key is text, so a pasted
    /// payload containing newlines cannot submit anything.
    fn handle_compose_key(&mut self, key: &KeyEvent) -> Vec<Action> {
        match key.code {
            KeyCode::Esc => vec![Action::CancelCompose],
            KeyCode::Enter => vec![Action::SubmitCompose],
            KeyCode::Backspace => {
                self.compose.pop();
                Vec::new()
            }
            KeyCode::Char(ch) if is_char(key, ch) => {
                self.compose.push(ch);
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    /// Appends pasted text verbatim. Bracketed paste delivers the whole payload
    /// as one event, so a multi-line paste lands in the buffer instead of
    /// triggering the shortcut its first character happens to be.
    pub fn paste(&mut self, text: &str) -> Vec<Action> {
        if self.input_mode != InputMode::Compose {
            return Vec::new();
        }
        self.compose.push_str(text);
        Vec::new()
    }

    /// Commits the compose buffer into exactly one action — or refuses, with a
    /// reason, having sent nothing.
    fn submit_compose(&mut self) -> Vec<Action> {
        let raw = std::mem::take(&mut self.compose);
        let trimmed = raw.trim().to_string();
        if trimmed.is_empty() {
            self.input_mode = InputMode::Normal;
            self.notify("nothing to send");
            return Vec::new();
        }
        match self.compose_kind {
            ComposeKind::Text => {
                self.input_mode = InputMode::Normal;
                vec![Action::SendText(trimmed)]
            }
            ComposeKind::Rename => {
                self.input_mode = InputMode::Normal;
                vec![Action::RenameDevice(trimmed)]
            }
            ComposeKind::FilePaths => match absolute_paths(&trimmed) {
                Ok(paths) => {
                    self.input_mode = InputMode::Normal;
                    vec![Action::SendFiles(paths)]
                }
                Err(reason) => {
                    // Keep the text so the user can correct it instead of
                    // retyping a long path.
                    self.compose = raw;
                    self.notify(reason);
                    Vec::new()
                }
            },
            ComposeKind::QrPaths => match absolute_paths(&trimmed) {
                Ok(paths) => {
                    self.input_mode = InputMode::Normal;
                    vec![Action::ShowQr(paths)]
                }
                Err(reason) => {
                    self.compose = raw;
                    self.notify(reason);
                    Vec::new()
                }
            },
        }
    }

    /// The actions overlay's contents, built from what the daemon actually
    /// reported. Nothing is offered that the daemon did not ask about: no
    /// pairing, acceptance or transfer is ever decided by this client alone.
    pub fn menu_entries(&self) -> Vec<MenuEntry> {
        let mut entries = Vec::new();
        if let Some(dialog) = &self.pairing_dialog {
            if !dialog.is_error {
                entries.push(MenuEntry::AcceptPairing(dialog.device_id.clone()));
                entries.push(MenuEntry::RejectPairing(dialog.device_id.clone()));
            }
        }
        for item in self.incoming.iter().filter(|item| item.pending_auth) {
            entries.push(MenuEntry::AcceptIncoming(item.receive_id));
            entries.push(MenuEntry::RejectIncoming(item.receive_id));
        }
        if self.open_device.is_some() {
            entries.push(MenuEntry::SendFiles);
            entries.push(MenuEntry::SendClipboard);
        }
        if self.has_capability("qr-share") {
            entries.push(MenuEntry::ShowQr);
        }
        entries.push(MenuEntry::RenameDevice);
        entries.push(MenuEntry::Settings);
        if self.has_capability("update") {
            entries.push(MenuEntry::CheckUpdate);
            if self.update.can_apply() {
                entries.push(MenuEntry::ApplyUpdate);
            }
        }
        entries
    }

    /// How many entries in the actions overlay are *decisions the daemon is
    /// waiting on* — a pairing request, or an incoming transfer nobody has
    /// answered yet.
    ///
    /// The overlay says so when this is zero: "nothing pending" is the truth
    /// about what needs the user, and it is not the same question as "is the
    /// menu empty", because rename, settings and the update check are always
    /// there and are not decisions.
    pub fn pending_decision_count(&self) -> usize {
        let pairing = usize::from(
            self.pairing_dialog
                .as_ref()
                .is_some_and(|dialog| !dialog.is_error),
        );
        pairing
            + self
                .incoming
                .iter()
                .filter(|item| item.pending_auth)
                .count()
    }

    /// Applies one [`Action`] that only changes what is on screen, and returns
    /// the actions that still need the daemon.
    ///
    /// Daemon actions are passed through untouched: this function never performs
    /// one, so a send is never half-done by the input loop.
    pub fn apply(&mut self, action: &Action) -> Vec<Action> {
        let mut follow_up = Vec::new();
        match action {
            Action::Quit => self.quitting = true,
            // The mouse belongs to the terminal guard, not to this reducer: the
            // input loop performs the capture change and then calls
            // `set_mouse_captured` with what the terminal actually did. So
            // there is nothing to apply here, and nothing to ask the daemon
            // for.
            Action::ToggleMouseCapture => {}
            Action::Help => {
                self.pending_overlay = match self.pending_overlay {
                    Overlay::Help => Overlay::None,
                    _ => Overlay::Help,
                };
            }
            // The picker's answer. It is recorded here and read by the
            // coordinator, and the session ends — this is the whole job.
            Action::ConfirmDevice => {
                if let Some(device) = self.selected_device() {
                    self.picked = Some(device.device_id.clone());
                }
                self.quitting = true;
            }
            Action::Back => {
                if self.pending_overlay != Overlay::None {
                    self.pending_overlay = Overlay::None;
                } else if self.input_mode == InputMode::Compose {
                    self.compose.clear();
                    self.input_mode = InputMode::Normal;
                } else if self.open_device.is_some() {
                    self.close_device();
                    self.focus = Focus::Devices;
                }
            }
            Action::MoveFocus(delta) => {
                self.focus = match (self.focus, delta.is_positive()) {
                    (Focus::Devices, true) => Focus::Conversation,
                    (Focus::Conversation, false) => Focus::Devices,
                    (focus, _) => focus,
                };
            }
            Action::FocusPane(pane) => {
                self.focus = match pane {
                    Pane::Devices => Focus::Devices,
                    Pane::Conversation => Focus::Conversation,
                };
            }
            Action::SelectNextDevice => self.move_selection(1),
            Action::SelectPrevDevice => self.move_selection(-1),
            Action::SelectRow(index) => {
                if *index < self.devices.len() {
                    self.selected = *index;
                }
            }
            Action::OpenDevice => {
                if let Some(device) = self.selected_device() {
                    let id = device.device_id.clone();
                    if self.open_device.as_deref() == Some(id.as_str()) {
                        return follow_up;
                    }
                    self.open_device = Some(id.clone());
                    self.focus = Focus::Conversation;
                    self.begin_history_load(&id);
                    follow_up.push(Action::LoadHistory {
                        device: id,
                        before: None,
                    });
                }
            }
            Action::PageHistoryUp => {
                // Clamped in display rows, because that is what `scroll`
                // counts: a ceiling in messages let a wrapped history scroll
                // back only a fraction of the way, and stopped the paging
                // gesture from ever reaching the top of what was loaded.
                let ceiling = self.history_rows();
                if let (Some(device), Some(before)) = (self.open_device.clone(), self.next_before) {
                    if self.scroll >= ceiling {
                        follow_up.push(Action::LoadHistory {
                            device,
                            before: Some(before),
                        });
                    }
                }
                self.scroll = self.scroll.saturating_add(HISTORY_PAGE).min(ceiling);
            }
            Action::PageHistoryDown => {
                self.scroll = self.scroll.saturating_sub(HISTORY_PAGE);
            }
            Action::Scroll(delta) => {
                let target = self.scroll as i64 + i64::from(*delta);
                let ceiling = self.history_rows() as i64;
                self.scroll = target.clamp(0, ceiling) as usize;
            }
            Action::StartCompose => self.begin_compose(ComposeKind::Text),
            Action::StartFilePrompt => self.begin_compose(ComposeKind::FilePaths),
            Action::StartRenamePrompt => self.begin_compose(ComposeKind::Rename),
            Action::StartQrPrompt => self.begin_compose(ComposeKind::QrPaths),
            Action::SubmitCompose => {
                follow_up.extend(self.submit_compose());
            }
            Action::CancelCompose => {
                self.compose.clear();
                self.input_mode = InputMode::Normal;
            }
            Action::OpenActionsMenu => self.pending_overlay = Overlay::Actions,
            Action::OpenSettings => self.pending_overlay = Overlay::Settings,
            // `ShowQr` itself is a daemon call; the overlay is opened by the
            // coordinator once the daemon reports a live session.
            // Everything below needs the daemon and is performed by the worker.
            _ => follow_up.push(action.clone()),
        }
        follow_up.extend(self.reconcile());
        follow_up
    }

    fn begin_compose(&mut self, kind: ComposeKind) {
        self.compose.clear();
        self.compose_kind = kind;
        self.input_mode = InputMode::Compose;
    }

    /// Opens the QR overlay. The coordinator calls this once the daemon reports
    /// a live session, so the overlay never claims a share that is not running.
    pub fn open_qr_overlay(&mut self) {
        self.pending_overlay = Overlay::Qr;
    }

    fn move_selection(&mut self, delta: isize) {
        let len = self.devices.len();
        if len == 0 {
            return;
        }
        let next = (self.selected as isize + delta).rem_euclid(len as isize);
        self.selected = next as usize;
    }
}

/// Splits a typed path list into absolute paths. Every path must be absolute:
/// the daemon resolves against its own working directory, so a relative path
/// typed here would quietly mean something else over there.
fn absolute_paths(raw: &str) -> Result<Vec<PathBuf>, String> {
    let mut paths = Vec::new();
    for token in raw.split_whitespace() {
        let path = PathBuf::from(token);
        if !path.is_absolute() {
            return Err(format!("{token:?} is not an absolute path"));
        }
        paths.push(path);
    }
    if paths.is_empty() {
        return Err("type at least one absolute path".to_string());
    }
    Ok(paths)
}

/// `true` when the key is the character `c` with no control modifier. Shift is
/// allowed, so `I` and `i` are the same key.
fn is_char(key: &KeyEvent, c: char) -> bool {
    let KeyCode::Char(found) = key.code else {
        return false;
    };
    if key
        .modifiers
        .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT | KeyModifiers::SUPER)
    {
        return false;
    }
    found.eq_ignore_ascii_case(&c)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyEventKind;

    /// A base instant for deterministic tests. `Instant` has no public
    /// constant, and none is needed: nothing here reads a duration.
    fn t0() -> Instant {
        Instant::now()
    }

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn ctrl(c: char) -> KeyEvent {
        KeyEvent::new(KeyCode::Char(c), KeyModifiers::CONTROL)
    }

    fn char_key(c: char) -> KeyEvent {
        KeyEvent::new(KeyCode::Char(c), KeyModifiers::NONE)
    }

    fn device(id: &str, unread: u64) -> DeviceSummary {
        DeviceSummary {
            device_id: id.to_string(),
            device_name: format!("Device {id}"),
            device_type: "ANDROID".to_string(),
            paired: true,
            reachable: true,
            trust_status: "trusted".to_string(),
            reachability: "reachable".to_string(),
            connection_types: vec!["KLARDROP".to_string()],
            has_unread: unread > 0,
            unread_count: unread,
        }
    }

    /// An app with three devices and a two-pane terminal.
    fn app_with_devices() -> App {
        let mut app = App::new(t0());
        app.devices = vec![device("a1", 0), device("b2", 3), device("c3", 0)];
        app.set_viewport(120, 40);
        app
    }

    /// Drives a key through both halves of the reducer, the way the input loop
    /// does, and returns everything the worker would have been handed.
    fn press(app: &mut App, code: KeyCode) -> Vec<Action> {
        let mut queued = app.handle_key(key(code), t0());
        let mut daemon = Vec::new();
        for action in std::mem::take(&mut queued) {
            daemon.extend(app.apply(&action));
        }
        daemon
    }

    /// What the reducer itself decided for one key, before anything was
    /// applied. Separate from [`press`] because the two halves answer
    /// different questions: this one is "what did the keyboard ask for", the
    /// other is "what still needs the daemon".
    fn reducer(app: &mut App, event: KeyEvent) -> Vec<Action> {
        app.handle_key(event, t0())
    }

    fn press_char(app: &mut App, c: char) -> Vec<Action> {
        let mut queued = app.handle_key(char_key(c), t0());
        let mut daemon = Vec::new();
        for action in std::mem::take(&mut queued) {
            daemon.extend(app.apply(&action));
        }
        daemon
    }

    #[test]
    fn quitting_works_from_navigation_and_from_compose() {
        let mut app = app_with_devices();
        let asked = reducer(&mut app, char_key('q'));
        assert_eq!(asked, vec![Action::Quit]);
        app.apply(&asked[0]);
        assert!(app.is_quitting());

        // Ctrl-C is the only interrupt raw mode leaves the user, so it must
        // quit even with the compose line open.
        let mut app = app_with_devices();
        app.apply(&Action::StartCompose);
        let actions = app.handle_key(ctrl('c'), t0());
        assert_eq!(actions, vec![Action::Quit]);
        assert!(app.is_quitting());
    }

    #[test]
    fn arrow_keys_wrap_the_device_selection() {
        let mut app = app_with_devices();
        assert_eq!(app.selected(), 0);
        press(&mut app, KeyCode::Down);
        assert_eq!(app.selected(), 1);
        press(&mut app, KeyCode::Up);
        assert_eq!(app.selected(), 0);
        press(&mut app, KeyCode::Up);
        assert_eq!(app.selected(), 2, "selection wraps to the last device");
        press(&mut app, KeyCode::Down);
        assert_eq!(app.selected(), 0, "and back to the first");
    }

    #[test]
    fn an_empty_device_list_cannot_be_navigated_or_opened() {
        let mut app = App::new(t0());
        press(&mut app, KeyCode::Down);
        press(&mut app, KeyCode::Up);
        assert_eq!(press(&mut app, KeyCode::Enter), Vec::new());
        assert!(app.open_device().is_none());
        assert!(app.selected_device().is_none());
    }

    #[test]
    fn tab_moves_focus_and_back_tab_moves_it_back() {
        let mut app = app_with_devices();
        assert_eq!(app.focus(), Focus::Devices);
        assert_eq!(press(&mut app, KeyCode::Tab), Vec::new());
        assert_eq!(app.focus(), Focus::Conversation);
        assert_eq!(press(&mut app, KeyCode::BackTab), Vec::new());
        assert_eq!(app.focus(), Focus::Devices);

        // Shift-Tab from the first pane stays on the first pane instead of
        // wrapping into a pane that is not there.
        assert_eq!(press(&mut app, KeyCode::BackTab), Vec::new());
        assert_eq!(app.focus(), Focus::Devices);
    }

    #[test]
    fn opening_a_device_loads_its_history_and_marks_it_read_once() {
        let mut app = app_with_devices();
        let actions = press(&mut app, KeyCode::Enter);

        assert_eq!(app.open_device(), Some("a1"));
        assert_eq!(app.focus(), Focus::Conversation);
        assert_eq!(
            actions,
            vec![Action::LoadHistory {
                device: "a1".into(),
                before: None
            }],
            "opening must ask the daemon for that device's newest page"
        );
        assert!(app.is_history_loading(), "the pane knows a load is due");

        // The second device has unread messages and is now on screen, so it is
        // the only device whose read state may change.
        let mut app = app_with_devices();
        app.apply(&Action::SelectNextDevice);
        let first = press(&mut app, KeyCode::Enter);
        assert!(first.contains(&Action::LoadHistory {
            device: "b2".into(),
            before: None
        }));
        assert!(
            first.contains(&Action::MarkRead("b2".into())),
            "an unread conversation that is actually visible must be marked: {first:?}"
        );
        assert!(
            !first
                .iter()
                .any(|a| matches!(a, Action::MarkRead(id) if id == "a1")),
            "a device the user only scrolled past must never be marked"
        );

        // Repeating the reconcile does not re-post the same batch.
        assert_eq!(app.reconcile(), Vec::new());
    }

    #[test]
    fn escape_unwinds_one_level_at_a_time() {
        let mut app = app_with_devices();
        press(&mut app, KeyCode::Enter);
        assert_eq!(app.overlay(), Overlay::None);

        press_char(&mut app, '?');
        assert_eq!(app.overlay(), Overlay::Help);
        press(&mut app, KeyCode::Esc);
        assert_eq!(app.overlay(), Overlay::None, "escape closes the overlay");

        app.apply(&Action::StartCompose);
        assert_eq!(app.input_mode(), InputMode::Compose);
        app.handle_key(char_key('h'), t0());
        assert_eq!(app.compose(), "h");
        press(&mut app, KeyCode::Esc);
        assert_eq!(
            (app.input_mode(), app.compose()),
            (InputMode::Normal, ""),
            "escape clears the compose line"
        );

        // Focus is still on the conversation, so this escape is the one that
        // closes it: the device was opened at the top of this test.
        press(&mut app, KeyCode::Esc);
        assert_eq!(app.open_device(), None, "escape closes the conversation");
        assert_eq!(app.focus(), Focus::Devices);
    }

    #[test]
    fn help_toggles_and_only_quit_reaches_through_an_overlay() {
        let mut app = app_with_devices();
        assert_eq!(press_char(&mut app, '?'), Vec::new());
        assert_eq!(app.overlay(), Overlay::Help);

        // Navigation keys behind the overlay do nothing.
        assert_eq!(press(&mut app, KeyCode::Down), Vec::new());
        assert_eq!(app.selected(), 0);

        assert_eq!(press_char(&mut app, '?'), Vec::new());
        assert_eq!(app.overlay(), Overlay::None);

        app.apply(&Action::Help);
        assert_eq!(reducer(&mut app, char_key('q')), vec![Action::Quit]);
    }

    #[test]
    fn composing_a_message_sends_exactly_what_was_typed() {
        let mut app = app_with_devices();
        press(&mut app, KeyCode::Enter);
        press_char(&mut app, 'i');
        assert_eq!(app.input_mode(), InputMode::Compose);

        for c in "hi there".chars() {
            assert_eq!(app.handle_key(char_key(c), t0()), Vec::new());
        }
        assert_eq!(app.compose(), "hi there");

        assert_eq!(
            press(&mut app, KeyCode::Enter),
            vec![Action::SendText("hi there".into())]
        );
        assert_eq!(
            (app.input_mode(), app.compose()),
            (InputMode::Normal, ""),
            "the buffer is cleared after a send, ready for the next one"
        );
    }

    #[test]
    fn an_empty_message_is_refused_rather_than_sent() {
        let mut app = app_with_devices();
        app.apply(&Action::StartCompose);
        app.handle_key(char_key(' '), t0());
        assert_eq!(press(&mut app, KeyCode::Enter), Vec::new());
        assert_eq!(app.input_mode(), InputMode::Normal);
        assert_eq!(
            app.toast().map(|t| t.message.as_str()),
            Some("nothing to send")
        );
    }

    #[test]
    fn a_multi_line_paste_lands_in_the_buffer_and_sends_nothing() {
        let mut app = app_with_devices();
        app.apply(&Action::StartCompose);
        // Bracketed paste delivers this whole payload as one event.
        let actions = app.paste("line one\nline two");
        assert_eq!(actions, Vec::new(), "a paste is not a keystroke");
        assert_eq!(app.compose(), "line one\nline two");
        assert_eq!(app.input_mode(), InputMode::Compose);
        assert_eq!(
            press(&mut app, KeyCode::Enter),
            vec![Action::SendText("line one\nline two".into())],
            "only an explicit Enter submits"
        );
    }

    #[test]
    fn the_file_prompt_accepts_several_absolute_paths_and_refuses_relative_ones() {
        let mut app = app_with_devices();
        app.apply(&Action::StartCompose);
        press(&mut app, KeyCode::Enter);
        press_char(&mut app, 'f');
        assert_eq!(app.input_mode(), InputMode::Compose);
        app.paste("/tmp/one.pdf /tmp/two.pdf");
        assert_eq!(
            press(&mut app, KeyCode::Enter),
            vec![Action::SendFiles(vec![
                PathBuf::from("/tmp/one.pdf"),
                PathBuf::from("/tmp/two.pdf")
            ])]
        );

        // A relative path would mean something else in the daemon's working
        // directory, so it is refused and the text stays for correction.
        app.apply(&Action::StartFilePrompt);
        app.paste("relative/path.pdf");
        assert_eq!(press(&mut app, KeyCode::Enter), Vec::new());
        assert_eq!(app.input_mode(), InputMode::Compose);
        assert_eq!(app.compose(), "relative/path.pdf");
        assert!(app
            .toast()
            .is_some_and(|t| t.message.contains("not an absolute path")));
    }

    #[test]
    fn the_clipboard_shortcut_needs_an_open_conversation() {
        let mut app = app_with_devices();
        assert_eq!(press_char(&mut app, 'y'), Vec::new());
        assert!(!app.is_quitting());

        app.apply(&Action::OpenDevice);
        let actions = app.apply(&Action::SendClipboard);
        assert_eq!(actions, vec![Action::SendClipboard]);
    }

    #[test]
    fn a_narrow_terminal_shows_one_pane_and_escape_returns_to_the_list() {
        let mut app = app_with_devices();
        app.set_viewport(79, 24);
        assert_eq!(app.layout().visible_pane(), Some(SinglePane::List));

        press(&mut app, KeyCode::Enter);
        assert_eq!(
            app.layout().visible_pane(),
            Some(SinglePane::Conversation),
            "below 80 columns the open conversation takes the whole screen"
        );

        press(&mut app, KeyCode::Esc);
        assert_eq!(
            app.layout().visible_pane(),
            Some(SinglePane::List),
            "escape goes back to the device list"
        );
    }

    #[test]
    fn a_terminal_too_small_never_reports_a_visible_conversation() {
        let mut app = app_with_devices();
        app.set_viewport(10, 5);
        press(&mut app, KeyCode::Enter);
        assert!(app.open_device().is_some());
        assert_eq!(
            app.visible_device_id(),
            None,
            "nothing is drawn, so nothing may be marked read"
        );
        assert_eq!(app.reconcile(), Vec::new());
    }

    #[test]
    fn paging_backwards_asks_the_daemon_for_the_older_page() {
        let mut app = app_with_devices();
        app.apply(&Action::OpenDevice);
        let page = crate::state::HistoryPage {
            device_id: "a1".into(),
            next_before: Some(7),
            messages: vec![],
        };
        assert!(app.apply_history(page, false));
        assert!(!app.is_history_loading());

        let actions = app.apply(&Action::PageHistoryUp);
        assert_eq!(
            actions,
            vec![Action::LoadHistory {
                device: "a1".into(),
                before: Some(7)
            }],
            "reaching the top of the loaded window pages the daemon, it does not guess"
        );

        // With no cursor left there is nothing to ask for.
        app.next_before = None;
        assert_eq!(app.apply(&Action::PageHistoryDown), Vec::new());
    }

    #[test]
    fn a_history_page_for_a_closed_conversation_is_dropped() {
        let mut app = app_with_devices();
        app.apply(&Action::OpenDevice);
        assert_eq!(app.open_device(), Some("a1"));
        app.apply(&Action::Back);
        assert_eq!(app.open_device(), None);

        let late = crate::state::HistoryPage {
            device_id: "a1".into(),
            next_before: None,
            messages: vec![history_message(1, "late")],
        };
        assert!(
            !app.apply_history(late, false),
            "a slow answer for a closed conversation must not repopulate the pane"
        );
        assert!(app.messages().is_empty());
    }

    #[test]
    fn opening_a_device_that_vanished_closes_the_conversation() {
        let mut app = app_with_devices();
        app.apply(&Action::OpenDevice);
        assert_eq!(app.open_device(), Some("a1"));

        // The device leaves the network: the pane must not keep showing its
        // last known history as though it were live.
        app.devices.retain(|device| device.device_id != "a1");
        app.apply_state(&state_with(&["b2", "c3"]));
        assert_eq!(app.open_device(), None);
        assert!(app.messages().is_empty());
    }

    #[test]
    fn the_actions_menu_offers_exactly_what_the_daemon_asked_about() {
        let mut app = app_with_devices();
        app.set_daemon_info(&DaemonInfo {
            api_version: Some(1),
            version: Some("test".into()),
            capabilities: vec!["qr-share".into(), "update".into()],
        });
        app.apply(&Action::OpenDevice);

        let labels: Vec<String> = app.menu_entries().iter().map(MenuEntry::label).collect();
        assert!(
            labels.iter().any(|l| l.contains("send clipboard")),
            "an open conversation offers a clipboard send: {labels:?}"
        );
        assert!(
            labels.iter().any(|l| l.contains("QR share")),
            "an advertised qr-share route is offered: {labels:?}"
        );
        assert!(
            !labels.iter().any(|l| l.contains("incoming transfer")),
            "nothing pending means nothing offered: {labels:?}"
        );
        assert!(
            !labels.iter().any(|l| l.contains("accept pairing")),
            "this client never invents a pairing decision: {labels:?}"
        );

        // An unadvertised route is not offered, so it cannot silently fail.
        app.set_daemon_info(&DaemonInfo::default());
        let labels: Vec<String> = app.menu_entries().iter().map(MenuEntry::label).collect();
        assert!(!labels.iter().any(|l| l.contains("QR share")));
        assert!(!labels.iter().any(|l| l.contains("updates")));
    }

    #[test]
    fn menu_digits_select_the_matching_entry_and_nothing_else() {
        let mut app = app_with_devices();
        // With a conversation open and nothing pending, the first entry is the
        // one the user most wants: sending files.
        app.apply(&Action::OpenDevice);
        app.apply(&Action::OpenActionsMenu);
        assert_eq!(app.overlay(), Overlay::Actions);

        // `handle_key` QUEUES the action; `apply` consumes it. The assertion belongs on the
        // queued action, not on what consuming it returns.
        assert_eq!(
            app.handle_key(char_key('1'), t0()),
            vec![Action::StartFilePrompt],
            "the first entry of a conversation with nothing pending is the file prompt"
        );

        // A digit past the end of the menu does nothing at all.
        app.apply(&Action::OpenActionsMenu);
        assert_eq!(app.handle_key(char_key('9'), t0()), Vec::new());
        assert_eq!(app.overlay(), Overlay::Actions);
    }

    // ------------------------------------------------------------- fixtures

    fn history_message(id: i64, content: &str) -> HistoryMessage {
        HistoryMessage {
            id,
            content: content.to_string(),
            timestamp: 1_700_000_000_000,
            is_sender: true,
            message_type: "text".to_string(),
            delivery_status: "SENT".to_string(),
            is_read: true,
            mime_type: "text/plain".to_string(),
            file_transfer_id: None,
            file: None,
        }
    }

    /// A `/state` body parsed the way the worker parses it, so the test goes
    /// through the same required-field contract the daemon is held to.
    fn state_with(ids: &[&str]) -> TuiState {
        let devices: Vec<serde_json::Value> = ids
            .iter()
            .enumerate()
            .map(|(index, id)| {
                serde_json::json!({
                    "deviceId": id,
                    "deviceName": format!("Device {id}"),
                    "deviceType": "ANDROID",
                    "trustStatus": "trusted",
                    "reachability": "reachable",
                    "connectionTypes": ["KLARDROP"],
                    "hasUnread": false,
                    "unreadCount": 0
                })
                .as_object()
                .map(|_| {
                    serde_json::json!({
                        "deviceId": id,
                        "deviceName": format!("Device {id}"),
                        "deviceType": "ANDROID",
                        "trustStatus": "trusted",
                        "reachability": "reachable",
                        "connectionTypes": ["KLARDROP"],
                        "hasUnread": index == 0,
                        "unreadCount": 0
                    })
                })
                .expect("object")
            })
            .collect();
        let value = serde_json::json!({
            "ok": true,
            "version": 3,
            "self": {"deviceId": "me0", "deviceName": "Host", "osType": "LINUX", "deviceType": "DESKTOP"},
            "devices": devices,
            "trustedIds": ids,
            "protocols": {"klardrop": true, "nearby": true, "ble": false},
            "settings": {"backgroundDiscoveryEnabled": true, "supportsBackgroundDiscovery": true},
            "incoming": [],
            "notifications": [],
            "pairingDialog": null,
            "qrShare": {"active": false, "url": null, "expiresAt": null, "downloadCount": 0, "downloads": []},
            "transfers": [],
            "update": {"status": "up_to_date", "supported": true}
        });
        crate::state::parse_tui_state(&value).expect("valid /state body")
    }

    #[test]
    fn every_key_the_reducer_accepts_is_a_press() {
        // Guards against a future Windows-style key filter silently changing
        // what reaches the reducer: release events must not act.
        let mut app = app_with_devices();
        let mut release = KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE);
        release.kind = KeyEventKind::Release;
        assert_eq!(app.handle_key(release, t0()), Vec::new());
        assert!(!app.is_quitting());
    }
}
