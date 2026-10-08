//! The daemon worker: every network call in the TUI, off the input loop.
//!
//! The rule this module exists to enforce is that the input loop never waits
//! for the daemon. A `GET /state?since=` long poll blocks for up to the
//! server's own 30-second cap; if that ran on the thread reading the keyboard,
//! every keystroke would queue behind it and the TUI would look frozen. So the
//! keyboard thread does exactly three things: poll for a key with a short
//! timeout, draw, and forward intents. All of the waiting happens here.
//!
//! Two threads, not one, for the same reason in both directions: the long poll
//! must not delay a send, and a slow send must not delay the next state
//! change. They share nothing but the connection metadata and the event
//! channel, and each builds a fresh [`crate::client::Client`] per request, so
//! the existing per-request deadlines apply unchanged.
//!
//! Nothing here writes to the terminal. The bearer token is passed to the
//! client and nowhere else, and a diagnostic from a thread whose screen the
//! TUI owns goes to the session's [`DebugLog`] — a file, not the pane.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::client::{Client, DebugLog};
use crate::commands;
use crate::envelope::{CliError, CliResult, ErrorCode};
use crate::state::{
    parse_capabilities, parse_history, parse_share_response, parse_transfers_one, parse_tui_state,
    DaemonInfo, HistoryPage, RequestStatus, ShareRequest, TuiState,
};
use crate::tui::action::Action;

/// Long-poll budget. The daemon caps the wait at 30 s, so this is slack rather
/// than a second timeout: a `GET /state?since=` unanswered by then is a daemon
/// that is not answering at all.
pub const LONG_POLL_BUDGET: Duration = Duration::from_secs(35);

/// Budget for a single action — a send, a decision, a history page. Bounded so
/// one slow route cannot hold the worker.
pub const ACTION_BUDGET: Duration = Duration::from_secs(20);

/// How long a submitted transfer is followed through `GET /transfers?id=`.
pub const TRANSFER_WATCH_BUDGET: Duration = Duration::from_secs(300);

/// How often a watched transfer is re-read.
pub const TRANSFER_POLL_INTERVAL: Duration = Duration::from_millis(500);

/// How long a failed poll waits before trying again. Long enough not to hammer
/// a daemon that is restarting, short enough that recovery feels immediate.
const RETRY_BACKOFF: Duration = Duration::from_millis(500);

/// Longest a shutdown waits for a worker thread to finish. Past this the thread
/// is detached: it is inside a bounded socket call and will exit on its own,
/// and blocking the user's exit for the rest of a 30-second poll would be worse.
const JOIN_BOUND: Duration = Duration::from_millis(750);

/// Page size requested from `/history`. Bounded, and larger than any pane so an
/// ordinary conversation costs one request.
const HISTORY_PAGE_LIMIT: u32 = 50;

/// One payload kind for `POST /share`. Exactly one per request, as the daemon
/// requires.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SharePayload {
    Text(String),
    Files(Vec<PathBuf>),
    Clipboard,
}

impl SharePayload {
    fn to_body(&self, device: &str) -> serde_json::Value {
        let mut body = serde_json::json!({ "deviceId": device });
        match self {
            Self::Text(text) => body["text"] = serde_json::json!(text),
            Self::Files(paths) => {
                let rendered: Vec<String> = paths
                    .iter()
                    .map(|path| path.display().to_string())
                    .collect();
                body["paths"] = serde_json::json!(rendered);
            }
            Self::Clipboard => body["clipboard"] = serde_json::json!(true),
        }
        body
    }
}

/// What the coordinator asks the worker to do.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorkerCommand {
    /// Submit exactly one payload to exactly one device through `POST /share`.
    Share {
        device: String,
        payload: SharePayload,
    },
    /// Any other daemon route. The device or receive id it needs is already in
    /// the action, so the worker never has to know what the user is looking at.
    Command(Action),
    /// Follow a submitted request through `GET /transfers?id=` until it reaches
    /// a terminal outcome.
    WatchTransfer(String),
    /// Ask the poller for a fresh `/state` now.
    Refresh,
    /// Stop.
    Shutdown,
}

/// What the worker reports back.
#[derive(Debug, Clone)]
pub enum DaemonEvent {
    /// The daemon's advertised routes, fetched once at startup.
    Capabilities(DaemonInfo),
    /// A fresh `/state`. Boxed: a whole state is far larger than any other
    /// variant, and an enum is as big as its largest arm.
    State(Box<TuiState>),
    /// A page of history for `device`.
    History { page: HistoryPage, older: bool },
    /// `POST /share` was accepted. `status` is the daemon's real state at that
    /// moment — normally `queued`, never a delivery claim.
    ShareSubmitted {
        request_id: String,
        status: RequestStatus,
        items: usize,
    },
    /// A watched request's current state, exactly as `/transfers` reports it.
    TransferUpdated(ShareRequest),
    /// A command failed. Carries the message, never the token.
    Failed { action: String, error: String },
    /// The daemon stopped answering. The TUI keeps working with what it has.
    Offline(String),
}

/// A running worker. Dropping it shuts it down.
pub struct Worker {
    exec: Sender<WorkerCommand>,
    poll: Sender<WorkerCommand>,
    stop: Arc<AtomicBool>,
    threads: Vec<JoinHandle<()>>,
}

impl Worker {
    /// Starts the poller and the executor threads.
    ///
    /// `token` is moved into the threads and never logged, echoed or formatted
    /// into a message.
    pub fn spawn(port: u16, token: String, debug: DebugLog, events: Sender<DaemonEvent>) -> Self {
        let (exec_tx, exec_rx) = mpsc::channel::<WorkerCommand>();
        let (poll_tx, poll_rx) = mpsc::channel::<WorkerCommand>();
        let stop = Arc::new(AtomicBool::new(false));

        let poller_token = token.clone();
        let executor_token = token;
        let poller = {
            let stop = Arc::clone(&stop);
            let events = events.clone();
            let debug = debug.clone();
            thread::spawn(move || poll_loop(port, poller_token, &debug, stop, poll_rx, events))
        };
        let executor = {
            let stop = Arc::clone(&stop);
            thread::spawn(move || execute_loop(port, executor_token, &debug, stop, exec_rx, events))
        };

        Self {
            exec: exec_tx,
            poll: poll_tx,
            stop,
            threads: vec![poller, executor],
        }
    }

    /// Queues a command. Never blocks: the input loop must not wait on the
    /// worker, and if it has gone away the TUI is already showing `offline`.
    pub fn send(&self, command: WorkerCommand) {
        match command {
            WorkerCommand::Refresh | WorkerCommand::Shutdown => {
                let _ = self.poll.send(command);
            }
            other => {
                let _ = self.exec.send(other);
            }
        }
    }

    /// Asks the poller for a fresh `/state` now.
    pub fn refresh(&self) {
        self.send(WorkerCommand::Refresh);
    }

    /// Stops both threads and joins them, waiting at most [`JOIN_BOUND`] each.
    ///
    /// Leaving the TUI never cancels a transfer: the daemon owns it, and this
    /// only stops observing.
    pub fn shutdown(mut self) {
        self.stop_threads();
    }

    fn stop_threads(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = self.poll.send(WorkerCommand::Shutdown);
        let _ = self.exec.send(WorkerCommand::Shutdown);
        for handle in std::mem::take(&mut self.threads) {
            join_bounded(handle);
        }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.stop_threads();
    }
}

/// Joins `handle`, giving up after [`JOIN_BOUND`]. A worker stuck in a bounded
/// socket call finishes on its own; the user's exit does not wait for it.
fn join_bounded(handle: JoinHandle<()>) {
    let deadline = Instant::now() + JOIN_BOUND;
    while !handle.is_finished() {
        if Instant::now() >= deadline {
            return;
        }
        thread::sleep(Duration::from_millis(5));
    }
    let _ = handle.join();
}

/// A client bounded by `budget`, rebuilt for every request so one command's
/// deadline can never leak into the next one's.
fn client(port: u16, token: &str, debug: &DebugLog, budget: Duration) -> CliResult<Client> {
    Client::new(port, token, Instant::now() + budget, debug.clone())
}

/// Sleeps in short slices so a shutdown does not wait out the whole backoff.
fn sleep_bounded(stop: &AtomicBool, total: Duration) {
    let deadline = Instant::now() + total;
    while Instant::now() < deadline {
        if stop.load(Ordering::SeqCst) {
            return;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

/// Long-polls `/state?since=` and re-arms immediately, forever.
fn poll_loop(
    port: u16,
    token: String,
    debug: &DebugLog,
    stop: Arc<AtomicBool>,
    commands: Receiver<WorkerCommand>,
    events: Sender<DaemonEvent>,
) {
    // Capabilities are fetched once: the route list does not change while the
    // process runs, and a daemon too old to have it simply reports nothing.
    if let Ok(client) = client(port, &token, debug, ACTION_BUDGET) {
        if let Ok(Some(value)) = client.get_json_optional(commands::CAPABILITIES_PATH) {
            if let Ok(info) = parse_capabilities(&value) {
                let _ = events.send(DaemonEvent::Capabilities(info));
            }
        }
    }

    let mut since: Option<i64> = None;
    loop {
        if stop.load(Ordering::SeqCst) {
            return;
        }
        // A `Refresh` that arrived while the previous long poll was outstanding
        // is honoured by re-arming without the cursor.
        if matches!(commands.try_recv(), Ok(WorkerCommand::Refresh)) {
            since = None;
        }

        let path = match since {
            Some(version) => format!("{STATE_PATH}?since={version}"),
            None => STATE_PATH.to_string(),
        };
        let outcome = client(port, &token, debug, LONG_POLL_BUDGET).and_then(|c| c.get_json(&path));

        match outcome {
            Ok(value) => match parse_tui_state(&value) {
                Ok(state) => {
                    since = Some(state.version);
                    let _ = events.send(DaemonEvent::State(Box::new(state)));
                }
                Err(error) => {
                    // A body this client cannot use is not a transient failure:
                    // say so and keep the cursor, so a daemon mid-upgrade does
                    // not spin.
                    let _ = events.send(DaemonEvent::Offline(error.message));
                    sleep_bounded(&stop, RETRY_BACKOFF);
                }
            },
            // The daemon is quiet rather than gone: re-arm without claiming
            // anything about its health.
            Err(error) if error.code == ErrorCode::DaemonTimeout => {}
            Err(error) => {
                let _ = events.send(DaemonEvent::Offline(error.message));
                sleep_bounded(&stop, RETRY_BACKOFF);
            }
        }

        match commands.try_recv() {
            Ok(WorkerCommand::Shutdown) => return,
            Ok(WorkerCommand::Refresh) => since = None,
            // Everything else belongs to the executor; the channels are split,
            // so this cannot happen and must not be silently ignored.
            Ok(other) => {
                let _ = events.send(DaemonEvent::Failed {
                    action: describe(&other),
                    error: "that command does not belong to the state poller".to_string(),
                });
            }
            Err(_) => {}
        }
    }
}

/// Runs commands: sends, decisions, history pages, transfer follow-up.
fn execute_loop(
    port: u16,
    token: String,
    debug: &DebugLog,
    stop: Arc<AtomicBool>,
    commands: Receiver<WorkerCommand>,
    events: Sender<DaemonEvent>,
) {
    while let Ok(command) = commands.recv() {
        if stop.load(Ordering::SeqCst) {
            break;
        }
        match command {
            WorkerCommand::Shutdown => break,
            WorkerCommand::Refresh => {}
            WorkerCommand::Share { device, payload } => {
                match submit_share(port, &token, debug, &device, &payload) {
                    Ok(event) => {
                        let _ = events.send(event);
                    }
                    Err(error) => report(&events, "share", &error),
                }
            }
            WorkerCommand::WatchTransfer(request_id) => {
                watch_transfer(port, &token, debug, &stop, &request_id, &events);
            }
            WorkerCommand::Command(action) => match perform(port, &token, debug, &action) {
                Ok(Some(event)) => {
                    let _ = events.send(event);
                }
                Ok(None) => {}
                Err(error) => report(&events, &describe(&WorkerCommand::Command(action)), &error),
            },
        }
    }
}

fn report(events: &Sender<DaemonEvent>, action: &str, error: &CliError) {
    let _ = events.send(DaemonEvent::Failed {
        action: action.to_string(),
        error: error.message.clone(),
    });
}

const STATE_PATH: &str = "/state";

fn submit_share(
    port: u16,
    token: &str,
    debug: &DebugLog,
    device: &str,
    payload: &SharePayload,
) -> CliResult<DaemonEvent> {
    let value = client(port, token, debug, ACTION_BUDGET)?
        .post_json(commands::SHARE_PATH, &payload.to_body(device))?;
    let submitted = parse_share_response(&value)?;
    Ok(DaemonEvent::ShareSubmitted {
        request_id: submitted.request_id,
        status: submitted.status,
        items: submitted.items.len(),
    })
}

/// Follows one submitted request through `GET /transfers?id=` until it is
/// terminal or the watch budget expires. What the TUI shows is therefore always
/// what the daemon reported, never a guess made here.
fn watch_transfer(
    port: u16,
    token: &str,
    debug: &DebugLog,
    stop: &AtomicBool,
    request_id: &str,
    events: &Sender<DaemonEvent>,
) {
    let deadline = Instant::now() + TRANSFER_WATCH_BUDGET;
    let path = format!("{}?id={request_id}", commands::TRANSFERS_PATH);
    while Instant::now() < deadline && !stop.load(Ordering::SeqCst) {
        match client(port, token, debug, ACTION_BUDGET).and_then(|c| c.get_json(&path)) {
            Ok(value) => match parse_transfers_one(&value) {
                Ok(request) => {
                    let terminal = request.status.is_terminal();
                    let _ = events.send(DaemonEvent::TransferUpdated(request));
                    if terminal {
                        return;
                    }
                }
                Err(error) => return report(events, "transfers", &error),
            },
            // The daemon forgot the id. That is a real answer, not a failure to
            // wait longer: stop following rather than pretend it is in flight.
            Err(error) if error.code == ErrorCode::TransferUnknown => return,
            Err(error) => return report(events, "transfers", &error),
        }
        sleep_bounded(stop, TRANSFER_POLL_INTERVAL);
    }
}

/// One non-share daemon route. `Ok(Some(event))` is a result the TUI draws;
/// `Ok(None)` means the route was a bare acknowledgement.
fn perform(
    port: u16,
    token: &str,
    debug: &DebugLog,
    action: &Action,
) -> CliResult<Option<DaemonEvent>> {
    if let Action::LoadHistory { device, before } = action {
        let mut path = format!(
            "{}?device={}&limit={HISTORY_PAGE_LIMIT}",
            commands::HISTORY_PATH,
            percent_encode(device)
        );
        if let Some(before) = before {
            path.push_str(&format!("&before={before}"));
        }
        let value = client(port, token, debug, ACTION_BUDGET)?.get_json(&path)?;
        let page = parse_history(&value)?;
        return Ok(Some(DaemonEvent::History {
            page,
            older: before.is_some(),
        }));
    }

    let empty = serde_json::json!({});
    let (path, body): (&str, serde_json::Value) = match action {
        Action::MarkRead(device) => (
            commands::HISTORY_READ_PATH,
            serde_json::json!({ "deviceId": device }),
        ),
        Action::AcceptPairing(device) => (
            commands::ACCEPT_PAIR_PATH,
            serde_json::json!({ "deviceId": device }),
        ),
        Action::RejectPairing(device) => (
            commands::REJECT_PAIR_PATH,
            serde_json::json!({ "deviceId": device }),
        ),
        Action::AcceptIncoming(receive_id) => (
            commands::ACCEPT_INCOMING_PATH,
            serde_json::json!({ "receiveId": receive_id }),
        ),
        Action::RejectIncoming(receive_id) => (
            commands::REJECT_INCOMING_PATH,
            serde_json::json!({ "receiveId": receive_id }),
        ),
        Action::RenameDevice(name) => (
            commands::RENAME_DEVICE_PATH,
            serde_json::json!({ "name": name }),
        ),
        Action::SetBackgroundDiscovery(enabled) => (
            commands::SETTINGS_PATH,
            serde_json::json!({ "backgroundDiscovery": enabled }),
        ),
        Action::ShowQr(paths) => (
            commands::QR_SHARE_PATH,
            serde_json::json!({
                "paths": paths.iter().map(|p| p.display().to_string()).collect::<Vec<_>>()
            }),
        ),
        // Non-force: a daemon answering 409 has transfers in flight, and that
        // stays a 409 the user reads rather than something retried for them.
        Action::CheckUpdate => (commands::UPDATE_CHECK_PATH, empty),
        Action::ApplyUpdate => (commands::UPDATE_APPLY_PATH, empty),
        other => {
            return Err(CliError::new(
                ErrorCode::InternalError,
                format!("the daemon worker cannot perform {other:?}"),
            ))
        }
    };

    client(port, token, debug, ACTION_BUDGET)?.post_json(path, &body)?;
    Ok(None)
}

/// Percent-encodes everything outside the unreserved set. A device id is the
/// only thing ever interpolated into a query string here, and it comes from the
/// daemon rather than from a typed line — so it is encoded rather than trusted.
fn percent_encode(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for byte in raw.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// A short label for a command, for the failure toast.
fn describe(command: &WorkerCommand) -> String {
    match command {
        WorkerCommand::Share { .. } => "share".to_string(),
        WorkerCommand::Command(action) => match action {
            Action::LoadHistory { .. } => "history".to_string(),
            Action::MarkRead(_) => "mark read".to_string(),
            Action::AcceptPairing(_) => "accept pairing".to_string(),
            Action::RejectPairing(_) => "reject pairing".to_string(),
            Action::AcceptIncoming(_) => "accept incoming".to_string(),
            Action::RejectIncoming(_) => "reject incoming".to_string(),
            Action::RenameDevice(_) => "rename device".to_string(),
            Action::SetBackgroundDiscovery(_) => "settings".to_string(),
            Action::ShowQr(_) => "qr share".to_string(),
            Action::CheckUpdate => "check update".to_string(),
            Action::ApplyUpdate => "apply update".to_string(),
            other => format!("{other:?}"),
        },
        WorkerCommand::WatchTransfer(_) => "transfer".to_string(),
        WorkerCommand::Refresh => "refresh".to_string(),
        WorkerCommand::Shutdown => "shutdown".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payload_json(payload: &SharePayload) -> serde_json::Value {
        payload.to_body("11112222")
    }

    #[test]
    fn a_share_body_carries_exactly_one_payload_kind() {
        // The daemon refuses a mixture; building it wrong here would only show
        // up as a confusing 400 at send time.
        let files = payload_json(&SharePayload::Files(vec![
            PathBuf::from("/tmp/a.pdf"),
            PathBuf::from("/tmp/b.pdf"),
        ]));
        assert_eq!(files["deviceId"], "11112222");
        assert_eq!(
            files["paths"],
            serde_json::json!(["/tmp/a.pdf", "/tmp/b.pdf"])
        );
        assert!(files.get("text").is_none() && files.get("clipboard").is_none());

        let text = payload_json(&SharePayload::Text("hi".into()));
        assert_eq!(text["text"], "hi");
        assert!(text.get("paths").is_none() && text.get("clipboard").is_none());

        let clipboard = payload_json(&SharePayload::Clipboard);
        assert_eq!(clipboard["clipboard"], true);
        assert!(clipboard.get("paths").is_none() && clipboard.get("text").is_none());
    }

    #[test]
    fn a_device_id_is_encoded_before_it_reaches_a_query_string() {
        assert_eq!(percent_encode("11112222"), "11112222");
        assert_eq!(percent_encode("a b&c=d"), "a%20b%26c%3Dd");
        assert_eq!(percent_encode("../../../etc"), "..%2F..%2F..%2Fetc");
        assert_eq!(percent_encode(""), "");
    }

    #[test]
    fn every_performed_route_is_named_in_the_failure_label() {
        let cases = [
            (
                WorkerCommand::Command(Action::MarkRead("a".into())),
                "mark read",
            ),
            (
                WorkerCommand::Command(Action::AcceptPairing("a".into())),
                "accept pairing",
            ),
            (
                WorkerCommand::Command(Action::RejectIncoming(3)),
                "reject incoming",
            ),
            (
                WorkerCommand::Command(Action::SetBackgroundDiscovery(true)),
                "settings",
            ),
            (WorkerCommand::Command(Action::CheckUpdate), "check update"),
            (WorkerCommand::Command(Action::ApplyUpdate), "apply update"),
            (
                WorkerCommand::Share {
                    device: "a".into(),
                    payload: SharePayload::Clipboard,
                },
                "share",
            ),
        ];
        for (command, expected) in cases {
            assert_eq!(describe(&command), expected);
        }
    }

    #[test]
    fn an_action_the_worker_does_not_implement_is_refused_rather_than_ignored() {
        // Silently dropping one would look like "the daemon did nothing",
        // which is exactly the lie this TUI must not tell.
        let error = perform(1, "t", &DebugLog::Off, &Action::Refresh).expect_err("not performable");
        assert_eq!(error.code, ErrorCode::InternalError);
        assert!(
            error.message.contains("Refresh"),
            "the refusal must name what it could not do: {}",
            error.message
        );
    }

    #[test]
    fn a_worker_stops_within_its_join_bound() {
        let (tx, rx) = mpsc::channel();
        let worker = Worker::spawn(1, "token".to_string(), DebugLog::Off, tx);
        drop(rx);
        let started = Instant::now();
        worker.shutdown();
        assert!(
            started.elapsed() < JOIN_BOUND * 4,
            "quitting must not wait out a poll: {:?}",
            started.elapsed()
        );
    }
}
