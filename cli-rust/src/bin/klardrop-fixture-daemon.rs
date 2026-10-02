//! TEST FIXTURE ONLY — **not** part of the shipped `klardrop` client.
//!
//! A minimal fake Klardrop control plane used by `tests/cli_contract.rs` and
//! `tests/tui_flows.rs`. It binds `127.0.0.1:0`, writes a control file, and
//! serves `/health`, `/capabilities`, `/state`, `GET /history`, `POST /share`,
//! `GET /transfers`, and the interactive routes the TUI drives — the four
//! decision routes, `/rename-device`, `/settings`, `/update/check` and
//! `/update/apply` — all behind a bearer-token check.
//!
//! The decision routes genuinely mutate the state `/state` reports: accepting
//! a pairing clears the dialog and advances the version, so a test can observe
//! an effect rather than a 200.
//!
//! | variable                       | meaning                                                |
//! |--------------------------------|--------------------------------------------------------|
//! | `KLARDROP_FIXTURE_CONTROL_FILE`| required: where to write the control file              |
//! | `KLARDROP_FIXTURE_TOKEN`       | bearer token to accept (default below)                 |
//! | `KLARDROP_FIXTURE_VERSION`     | version reported by `/capabilities`                    |
//! | `KLARDROP_FIXTURE_BEHAVIORS`   | comma-separated behaviour list, see [`Behavior`]       |
//!
//! Behaviours:
//!
//! | behaviour             | effect                                              |
//! |-----------------------|-----------------------------------------------------|
//! | `no_capabilities`     | `/capabilities` answers 404 (an older daemon)        |
//! | `no_self`             | `/state` has no `self`                              |
//! | `no_share`            | `/share` and `/transfers` answer 404 (pre-registry)  |
//! | `invalid_json`        | every body is truncated JSON                         |
//! | `http_500`            | every route answers 500                              |
//! | `huge_body`           | declares a body above the client's ceiling           |
//! | `hang`                | never answers                                       |
//! | `empty_state`         | `/state` lists no devices                            |
//! | `slow_drip`           | trickles one byte per interval                       |
//! | `share_decline`       | the recipient declines every item                    |
//! | `share_fail`          | every item fails                                    |
//! | `share_partial`       | the first item completes, the rest fail              |
//! | `share_never_completes` | items stay in progress forever                     |
//! | `share_slow`          | completion is acknowledged only after [`SLOW_ACK`]  |
//! | `share_unsendable`   | every item after the first fails to prepare (`null` id) |
//! | `daemon_not_ready`  | `/share` and `/transfers` answer 503 (no engine bound) |
//! | `redirect`          | every route answers 302 with a body that looks valid  |
//! | `chunked`           | every route answers with chunked transfer encoding    |
//! | `pairing_dialog`      | `/state` carries a pairing request waiting for an answer |
//! | `pairing_dialog_error`| `/state` carries a pairing dialog that already failed    |
//! | `pending_incoming`    | `/state` carries an incoming transfer awaiting approval  |
//! | `advancing_state`     | every `/state` answers with a new version                |
//! | `staged_update`       | a check finds an update and stages it                    |
//! | `transfer_active`     | a transfer is in flight, so applying an update is a 409   |
//! | `transfer_settles`   | a transfer in flight, then `completed` after a few seconds |
//! | `state_long_poll`    | `/state` is held open before answering, like the real one |
//! | `limited_capabilities`| `/capabilities` advertises only the oldest routes        |
//! | `long_history`      | `/history` reports hundreds of messages, each wider than the pane |
//! | `hostile_names`     | device, self and transfer names carry terminal control characters |
//! | `wide_device_names` | the device name is a long CJK one, two columns per glyph         |
//!
//! Any other name is a fatal startup error, so a test can never silently run
//! against the default behavior.

use std::env;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::Value;

const DEFAULT_TOKEN: &str = "fixture-token-0123456789abcdef";
const DEFAULT_VERSION: &str = "9.9.9-fixture";
/// Comfortably above the client's 4 MiB body ceiling.
const HUGE_BODY_BYTES: usize = 5 * 1024 * 1024;
/// Bound on how much of a request we are willing to read before giving up.
const MAX_REQUEST_BYTES: usize = 16 * 1024;
/// How long the `hang` behaviour stalls before dropping the connection.
const HANG_SECONDS: u64 = 600;
/// Announced body length for `slow_drip`: far more than is ever sent.
const SLOW_DRIP_BODY_BYTES: usize = 60_000;
/// Gap between dribbled bytes; small enough that the socket never looks idle.
const SLOW_DRIP_INTERVAL_MS: u64 = 50;
/// How long `share_slow` withholds its completion acknowledgement.
const SLOW_ACK: Duration = Duration::from_millis(1200);
/// Mirrors the daemon's own retention bound: the registry never grows without
/// limit, so an evicted id is a real 404 rather than an accident.
const MAX_RECORDS: usize = 256;
/// Mirrors the daemon's per-request path cap, so the client is exercised against
/// the same 400 a real daemon answers.
const MAX_SHARE_PATHS: usize = 64;

/// A name carrying the four ways a peer can attack a terminal: an `SGR`
/// sequence, an `OSC` that retitles the window, a bell, and a `NUL`. The word
/// is harmless and must survive; none of the four may.
const HOSTILE_NAME: &str = "evil\u{1b}[31m\u{1b}]0;pwned\u{7}\u{0}";

/// A CJK device name: thirty columns wide, and far more than a device pane has
/// to give. Every glyph is two columns, so budgeting the row in `char`s
/// overcounts by exactly the amount that pushes a status word off the end.
const WIDE_NAME: &str = "介于中间的测试用超长中文设备名称并再长一些";

#[derive(Debug, Clone, Copy, Default)]
struct Behaviors {
    no_capabilities: bool,
    no_self: bool,
    no_share: bool,
    invalid_json: bool,
    http_500: bool,
    slow_drip: bool,
    huge_body: bool,
    hang: bool,
    empty_state: bool,
    share_decline: bool,
    share_fail: bool,
    share_partial: bool,
    redirect: bool,
    chunked: bool,
    share_never_completes: bool,
    share_slow: bool,
    share_unsendable: bool,
    daemon_not_ready: bool,
    /// `/state` carries a pairing request nobody has answered yet.
    pairing_dialog: bool,
    /// `/state` carries a pairing dialog that has already failed, which is a
    /// message rather than a decision.
    pairing_dialog_error: bool,
    /// `/state` carries an incoming transfer still waiting for approval.
    pending_incoming: bool,
    /// Every `/state` answers with a new version, so a long-poll client can be
    /// observed doing real work rather than sitting on a quiet daemon.
    advancing_state: bool,
    /// A check finds an update and stages it, which is what makes "apply"
    /// reachable at all.
    staged_update: bool,
    /// A transfer is in flight, so applying an update without `force` is a 409.
    transfer_active: bool,
    /// A transfer that is genuinely in flight for the first few seconds and
    /// then reports a terminal phase, so a test can watch progress move and
    /// then watch it stop. `transfer_active` never finishes, and so cannot
    /// show the second half.
    transfer_settles: bool,
    /// `/state` holds the connection open before answering, the way a real
    /// long-polling control plane does. Without it the fixture answers
    /// instantly, and a client's poller becomes a hot loop that has nothing
    /// to do with how expensive the client is to draw.
    state_long_poll: bool,
    /// `/capabilities` advertises only the oldest routes.
    limited_capabilities: bool,
    /// `/history` reports a long conversation, and every message in it is
    /// wider than the conversation pane. Both halves are needed: more messages
    /// than fit is what makes a window necessary, and messages that wrap are
    /// what make a window measured in *messages* wrong rather than merely
    /// wasteful.
    long_history: bool,
    /// Every name the daemon reports — the device, this host, and a transfer's
    /// file — carries terminal control characters, so the client is exercised
    /// against what a hostile or buggy peer would really send.
    hostile_names: bool,
    /// The device name is a long CJK one. Every glyph is two columns, which is
    /// how a device pane that budgets in `char`s overflows the row it measured
    /// for and clips a status word in half.
    wide_device_names: bool,
}

impl Behaviors {
    fn from_env(raw: Option<&str>) -> Result<Self, String> {
        let mut behaviors = Self::default();
        let Some(raw) = raw else { return Ok(behaviors) };
        for name in raw.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            match name {
                "no_capabilities" => behaviors.no_capabilities = true,
                "no_self" => behaviors.no_self = true,
                "no_share" => behaviors.no_share = true,
                "invalid_json" => behaviors.invalid_json = true,
                "http_500" => behaviors.http_500 = true,
                "huge_body" => behaviors.huge_body = true,
                "hang" => behaviors.hang = true,
                "empty_state" => behaviors.empty_state = true,
                "slow_drip" => behaviors.slow_drip = true,
                "share_decline" => behaviors.share_decline = true,
                "share_fail" => behaviors.share_fail = true,
                "share_partial" => behaviors.share_partial = true,
                "share_never_completes" => behaviors.share_never_completes = true,
                "share_slow" => behaviors.share_slow = true,
                "redirect" => behaviors.redirect = true,
                "chunked" => behaviors.chunked = true,
                "share_unsendable" => behaviors.share_unsendable = true,
                "daemon_not_ready" => behaviors.daemon_not_ready = true,
                "pairing_dialog" => behaviors.pairing_dialog = true,
                "pairing_dialog_error" => behaviors.pairing_dialog_error = true,
                "pending_incoming" => behaviors.pending_incoming = true,
                "advancing_state" => behaviors.advancing_state = true,
                "staged_update" => behaviors.staged_update = true,
                "transfer_active" => behaviors.transfer_active = true,
                "transfer_settles" => behaviors.transfer_settles = true,
                "state_long_poll" => behaviors.state_long_poll = true,
                "long_history" => behaviors.long_history = true,
                "hostile_names" => behaviors.hostile_names = true,
                "wide_device_names" => behaviors.wide_device_names = true,
                "limited_capabilities" => behaviors.limited_capabilities = true,
                other => return Err(format!("unknown fixture behavior {other:?}")),
            }
        }
        Ok(behaviors)
    }
}

#[derive(Clone)]
struct Item {
    // `None` models an item the daemon could not even prepare (a path that is not a readable
    // regular file): the real daemon answers with an explicit `"transferId": null` for those,
    // so the fixture must be able to produce the same shape or the client's null handling is
    // never exercised.
    transfer_id: Option<String>,
    path: Option<String>,
    file_name: Option<String>,
    total_size: u64,
    transferred_size: u64,
    status: &'static str,
    error: Option<String>,
}

impl Item {
    fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "transferId": self.transfer_id,
            "path": self.path,
            "fileName": self.file_name,
            "totalSize": self.total_size,
            "transferredSize": self.transferred_size,
            "status": self.status,
            "error": self.error,
        })
    }
}

#[derive(Clone)]
struct Record {
    request_id: String,
    device_id: String,
    kind: &'static str,
    created_at: i64,
    updated_at: i64,
    items: Vec<Item>,
    created: Instant,
    /// How many times this record has been read back through `/transfers`.
    polls: u32,
    status: &'static str,
}

impl Record {
    /// The request-level status, derived from its items exactly as the daemon does:
    /// any failure first, then any decline, then all-completed, otherwise queued.
    /// Deriving rather than storing keeps the fixture from answering "queued" for a
    /// request that already carries a failed item.
    fn derived_status(&self) -> &'static str {
        if self.items.iter().any(|item| item.status == "failed") {
            "failed"
        } else if self.items.iter().any(|item| item.status == "declined") {
            "declined"
        } else if !self.items.is_empty() && self.items.iter().all(|item| item.status == "completed")
        {
            "completed"
        } else {
            "queued"
        }
    }

    fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "requestId": self.request_id,
            "deviceId": self.device_id,
            "kind": self.kind,
            "status": self.derived_status(),
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "items": self.items.iter().map(Item::to_json).collect::<Vec<_>>(),
        })
    }

    /// The submission answer, which is deliberately flat and carries no
    /// timestamps: the client only needs the ids and the real current status.
    fn submission_json(&self) -> serde_json::Value {
        serde_json::json!({
            "ok": true,
            "action": "share",
            "requestId": self.request_id,
            "deviceId": self.device_id,
            "kind": self.kind,
            "status": self.derived_status(),
            "items": self.items.iter().map(Item::to_json).collect::<Vec<_>>(),
        })
    }
}

#[derive(Default)]
struct Registry {
    next_id: u64,
    records: Vec<Record>,
}

impl Registry {
    fn mint(&mut self, device_id: String, kind: &'static str, mut items: Vec<Item>) {
        self.next_id += 1;
        // Transfer ids are unique per item across the whole daemon, so two
        // concurrent requests can never be confused for one another.
        for (index, item) in items.iter_mut().enumerate() {
            // An item that already failed at submission has no transfer to correlate, exactly as
            // the real daemon reports it, so it keeps `None` instead of being given an id that
            // can never progress.
            if item.status != "failed" {
                item.transfer_id = Some(format!("tx-{}-{index}", self.next_id));
            }
        }
        let now = epoch_millis();
        let record = Record {
            request_id: format!("req-{:016x}", self.next_id),
            device_id,
            kind,
            created_at: now,
            updated_at: now,
            items,
            created: Instant::now(),
            polls: 0,
            status: "queued",
        };
        // Newest first, with the same bound the real daemon uses: an evicted
        // request is genuinely gone, so a later lookup really is a 404.
        self.records.insert(0, record);
        self.records.truncate(MAX_RECORDS);
    }

    fn find(&mut self, request_id: &str, behaviors: &Behaviors) -> Option<&Record> {
        let record = self
            .records
            .iter_mut()
            .find(|r| r.request_id == request_id)?;
        advance(record, behaviors);
        Some(record)
    }
}

fn epoch_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

/// Moves one record one step closer to whatever its behaviour promises. The
/// status always follows the items, exactly as the real registry does.
fn advance(record: &mut Record, behaviors: &Behaviors) {
    record.polls += 1;
    record.updated_at = epoch_millis();
    let polls = record.polls;
    let elapsed = record.created.elapsed();

    if behaviors.share_never_completes {
        for item in &mut record.items {
            item.status = "transferring";
            item.transferred_size = (item.total_size / 10) * polls.min(9) as u64;
        }
        record.status = "queued";
        return;
    }

    if behaviors.share_slow && elapsed < SLOW_ACK {
        for item in &mut record.items {
            item.status = "transferring";
            item.transferred_size = item.total_size / 2;
        }
        record.status = "queued";
        return;
    }

    if behaviors.share_decline {
        for item in &mut record.items {
            item.status = "declined";
            item.error = Some("recipient declined the transfer".to_string());
        }
        record.status = "declined";
        return;
    }

    if behaviors.share_fail {
        for item in &mut record.items {
            item.status = "failed";
            item.error = Some("the peer closed the connection".to_string());
        }
        record.status = "failed";
        return;
    }

    if behaviors.share_partial {
        for (index, item) in record.items.iter_mut().enumerate() {
            if index == 0 {
                item.status = "completed";
                item.transferred_size = item.total_size;
                item.error = None;
            } else {
                item.status = "failed";
                item.error = Some("the peer closed the connection".to_string());
            }
        }
        record.status = "failed";
        return;
    }

    // Default: queued, then visibly transferring, then completed.
    match polls {
        1 => {
            for item in &mut record.items {
                item.status = "transferring";
                item.transferred_size = item.total_size / 2;
            }
            record.status = "queued";
        }
        _ => {
            for item in &mut record.items {
                item.status = "completed";
                item.transferred_size = item.total_size;
                item.error = None;
            }
            record.status = "completed";
        }
    }
}

/// A pairing request the host is asking about.
#[derive(Clone)]
struct Pairing {
    device_id: &'static str,
    device_name: &'static str,
    is_error: bool,
}

/// One incoming transfer the peer is waiting on.
#[derive(Clone)]
struct Incoming {
    receive_id: i64,
    device_id: &'static str,
    device_name: &'static str,
    file_name: &'static str,
}

/// Everything the interactive routes read or change, behind one lock.
///
/// The version is the whole point of it: every mutation advances it, exactly as
/// the daemon's own state counter does, so a long-polling client learns about
/// the effect of its decision instead of having to be told to look.
struct Host {
    version: i64,
    self_name: String,
    background_discovery: bool,
    pairing: Option<Pairing>,
    incoming: Vec<Incoming>,
    update_status: &'static str,
    update_staged: bool,
    /// How many `/state` answers have gone out, used by `advancing_state` to
    /// make each answer visibly different.
    polls: u64,
    /// When this daemon started. `transfer_settles` is measured against it, so
    /// the transfer is in flight for a span of wall time a test can watch
    /// rather than for a number of polls a fast poller can burn in a
    /// millisecond.
    started: Instant,
}

impl Host {
    fn new(behaviors: &Behaviors) -> Self {
        let pairing = if behaviors.pairing_dialog {
            Some(Pairing {
                device_id: "4444cccc",
                device_name: "Fixture Watch",
                is_error: false,
            })
        } else if behaviors.pairing_dialog_error {
            Some(Pairing {
                device_id: "4444cccc",
                device_name: "Fixture Watch",
                is_error: true,
            })
        } else {
            None
        };
        let incoming = if behaviors.pending_incoming {
            vec![Incoming {
                receive_id: 77,
                device_id: "5555dddd",
                device_name: "Fixture Sender",
                file_name: "holiday.zip",
            }]
        } else {
            Vec::new()
        };
        Self {
            version: 3,
            self_name: "Fixture Host".to_string(),
            background_discovery: true,
            pairing,
            incoming,
            update_status: "up_to_date",
            update_staged: false,
            polls: 0,
            started: Instant::now(),
        }
    }

    /// Advances the state counter. Nothing else touches `version`, so a client
    /// that waits for a change is never woken without one.
    fn bump(&mut self) {
        self.version += 1;
    }

    fn update_json(&self) -> serde_json::Value {
        let available = if self.update_staged {
            Value::String("9.9.9-fixture".to_string())
        } else {
            Value::Null
        };
        serde_json::json!({
            "status": self.update_status,
            "supported": true,
            "staged": self.update_staged,
            "version": available,
            "currentVersion": "9.8.8-fixture",
            "channel": "stable",
            "error": Value::Null,
        })
    }

    /// The device name `/state` reports. Under `advancing_state` it carries the
    /// state counter, so a poll that is doing real work is visible on screen,
    /// and under `hostile_names` it is the control-character name — this host's
    /// own name is a string the daemon chose, exactly like any peer's.
    fn self_json(&self, behaviors: &Behaviors) -> serde_json::Value {
        let name = if behaviors.hostile_names {
            HOSTILE_NAME.to_string()
        } else if behaviors.advancing_state {
            format!("{} v{}", self.self_name, self.version)
        } else {
            self.self_name.clone()
        };
        serde_json::json!({
            "deviceId": "fixture00",
            "deviceName": name,
            "osType": "LINUX",
            "deviceType": "DESKTOP",
        })
    }
}

#[derive(Clone)]
struct Fixture {
    token: String,
    version: String,
    behaviors: Behaviors,
    port: u16,
    registry: Arc<Mutex<Registry>>,
    host: Arc<Mutex<Host>>,
}

fn main() {
    if let Err(message) = run() {
        eprintln!("klardrop-fixture-daemon: {message}");
        std::process::exit(2);
    }
}

fn run() -> Result<(), String> {
    let control_path = PathBuf::from(
        env::var("KLARDROP_FIXTURE_CONTROL_FILE")
            .map_err(|_| "KLARDROP_FIXTURE_CONTROL_FILE is required".to_string())?,
    );
    let token = env::var("KLARDROP_FIXTURE_TOKEN").unwrap_or_else(|_| DEFAULT_TOKEN.to_string());
    let version =
        env::var("KLARDROP_FIXTURE_VERSION").unwrap_or_else(|_| DEFAULT_VERSION.to_string());
    let behaviors = Behaviors::from_env(env::var("KLARDROP_FIXTURE_BEHAVIORS").ok().as_deref())?;

    let listener = TcpListener::bind("127.0.0.1:0")
        .map_err(|e| format!("cannot bind loopback listener: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("cannot read bound address: {e}"))?
        .port();

    let control = format!(
        r#"{{"port":{port},"token":"{token}","apiVersion":1,"capabilities":["health","state","capabilities","share","transfers"]}}"#
    );
    if let Some(parent) = control_path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("cannot create {}: {e}", parent.display()))?;
    }
    std::fs::write(&control_path, control)
        .map_err(|e| format!("cannot write {}: {e}", control_path.display()))?;

    let host = Arc::new(Mutex::new(Host::new(&behaviors)));
    let fixture = Fixture {
        token,
        version,
        behaviors,
        port,
        registry: Arc::new(Mutex::new(Registry::default())),
        host,
    };
    eprintln!("klardrop-fixture-daemon listening on 127.0.0.1:{port}");
    eprintln!(
        "klardrop-fixture-daemon control file {}",
        control_path.display()
    );

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let fixture = fixture.clone();
                thread::spawn(move || handle(fixture, stream));
            }
            Err(e) => eprintln!("klardrop-fixture-daemon accept error: {e}"),
        }
    }
    Ok(())
}

/// One request per connection, then close.
fn handle(fixture: Fixture, mut stream: TcpStream) {
    let request = match read_request(&mut stream) {
        Ok(request) => request,
        Err(message) => {
            eprintln!("klardrop-fixture-daemon: {message}");
            return;
        }
    };

    if fixture.behaviors.hang {
        thread::sleep(Duration::from_secs(HANG_SECONDS));
        return;
    }

    let (method, target) = split_request_line(&request.head);
    let (path, query) = match target.split_once('?') {
        Some((path, query)) => (path, Some(query)),
        None => (target, None),
    };

    if !is_authorized(&request.head, &fixture.token) {
        respond(
            &mut stream,
            401,
            "Unauthorized",
            br#"{"ok":false,"error":"unauthorized"}"#,
        );
        return;
    }

    if fixture.behaviors.slow_drip {
        write_slow_drip(&mut stream);
        return;
    }

    if fixture.behaviors.http_500 {
        respond(
            &mut stream,
            500,
            "Internal Server Error",
            br#"{"ok":false,"error":"internal"}"#,
        );
        return;
    }

    if fixture.behaviors.invalid_json {
        respond(&mut stream, 200, "OK", br#"{"ok":true,"self":{"#);
        return;
    }

    if fixture.behaviors.huge_body {
        write_huge_body(&mut stream);
        return;
    }
    if fixture.behaviors.redirect {
        // A loopback control plane has no reason to answer 3xx, and the client must refuse the
        // body rather than mistake a stranger's page for Klardrop state.
        let body = br#"{"ok":true,"self":{"deviceId":"ghost","deviceName":"Ghost","osType":"LINUX","deviceType":"DESKTOP"},"protocols":{"klardrop":true,"nearby":true,"ble":false},"settings":{"backgroundDiscoveryEnabled":false,"supportsBackgroundDiscovery":false},"devices":[]}"#;
        write!(
            &mut stream,
            "HTTP/1.1 302 Found\r\nLocation: http://example.invalid/state\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        )
        .ok();
        stream.write_all(body).ok();
        return;
    }

    if fixture.behaviors.chunked {
        write!(
            &mut stream,
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
        )
        .ok();
        // The daemon never uses chunked encoding, so the client must reject it outright rather
        // than assemble a body whose length was never declared.
        stream.write_all(b"5\r\n{\"ok\":\r\n0\r\n\r\n").ok();
        return;
    }

    match (method, path) {
        ("GET", "/health") => respond(
            &mut stream,
            200,
            "OK",
            format!(r#"{{"ok":true,"port":{}}}"#, fixture.port).as_bytes(),
        ),
        ("GET", "/capabilities") if fixture.behaviors.no_capabilities => not_found(&mut stream),
        ("GET", "/capabilities") => respond(
            &mut stream,
            200,
            "OK",
            capabilities_body(&fixture.version, &fixture.behaviors).as_bytes(),
        ),
        ("GET", "/state") => state(&fixture, &mut stream),
        ("GET", "/history") => history(&fixture, &mut stream, query),
        ("POST", "/accept-pair") => decide_pairing(&fixture, &mut stream, &request.body, true),
        ("POST", "/reject-pair") => decide_pairing(&fixture, &mut stream, &request.body, false),
        ("POST", "/accept-incoming") => decide_incoming(&fixture, &mut stream, &request.body, true),
        ("POST", "/reject-incoming") => {
            decide_incoming(&fixture, &mut stream, &request.body, false)
        }
        ("POST", "/rename-device") => rename_device(&fixture, &mut stream, &request.body),
        ("POST", "/settings") => settings(&fixture, &mut stream, &request.body),
        ("POST", "/update/check") => update_check(&fixture, &mut stream),
        ("POST", "/update/apply") => update_apply(&fixture, &mut stream, &request.body),
        ("POST", "/share") | ("GET", "/transfers") if fixture.behaviors.daemon_not_ready => {
            // A host that has not bound its engine answers 503, not an empty success: the client
            // must be able to tell "nothing in flight" apart from "the daemon is still starting".
            respond(
                &mut stream,
                503,
                "Service Unavailable",
                br#"{"ok":false,"error":"daemon is not ready yet: no engine is bound"}"#,
            )
        }
        ("POST", "/share") if fixture.behaviors.no_share => not_found(&mut stream),
        ("POST", "/share") => share(&fixture, &mut stream, &request.body),
        ("GET", "/transfers") if fixture.behaviors.no_share => not_found(&mut stream),
        ("GET", "/transfers") => transfers(&fixture, &mut stream, query),
        _ => not_found(&mut stream),
    }
}

/// `POST /share`: validate exactly one payload kind, mint the ids, and answer
/// with the request's real (normally `queued`) status.
fn share(fixture: &Fixture, stream: &mut TcpStream, body: &[u8]) {
    let parsed: Result<serde_json::Value, _> = serde_json::from_slice(body);
    let Ok(value) = parsed else {
        return bad_request(stream, "the request body is not valid JSON");
    };
    let Some(device_id) = value.get("deviceId").and_then(|v| v.as_str()) else {
        return bad_request(stream, "deviceId is required");
    };
    let device_id = device_id.to_string();

    let mut kinds: Vec<&str> = Vec::new();
    if value.get("paths").is_some() {
        kinds.push("paths");
    }
    if value.get("text").is_some() {
        kinds.push("text");
    }
    if value.get("clipboard").is_some() {
        kinds.push("clipboard");
    }
    match kinds.as_slice() {
        [] => {
            return bad_request(
                stream,
                "exactly one of paths, text or clipboard is required",
            )
        }
        [_] => {}
        _ => {
            return bad_request(
                stream,
                "paths, text and clipboard are alternatives; give exactly one",
            )
        }
    }

    let (kind, items) = if let Some(paths) = value.get("paths") {
        let Some(paths) = paths.as_array() else {
            return bad_request(stream, "paths must be an array");
        };
        if paths.is_empty() {
            return bad_request(stream, "paths must not be empty");
        }
        if paths.len() > MAX_SHARE_PATHS {
            return bad_request(
                stream,
                &format!(
                    "too many paths: at most {MAX_SHARE_PATHS} per request, got {}",
                    paths.len()
                ),
            );
        }
        let mut items = Vec::new();
        for (index, path) in paths.iter().enumerate() {
            let Some(path) = path.as_str() else {
                return bad_request(stream, "every entry of paths must be a string");
            };
            // Mirrors ControlPlane.kt's `substringAfterLast('/').substringAfterLast('\\')`:
            // splitting on `/` alone handed the whole verbatim `\\?\C:\…` path back as the
            // name on Windows, which is exactly the behaviour this double must not have.
            let file_name = path.rsplit(['/', '\\']).next().unwrap_or(path).to_string();
            // Mirrors the real daemon: a path that is not a readable REGULAR file is recorded
            // as a failed item with no transfer id, never queued for bytes that cannot be sent.
            let metadata = std::fs::metadata(path).ok();
            let force_unsendable = fixture.behaviors.share_unsendable && index > 0;
            let readable = metadata.as_ref().is_some_and(|m| m.is_file()) && !force_unsendable;
            let (status, error, total_size) = if readable {
                ("queued", None, metadata.map(|m| m.len()).unwrap_or(4096))
            } else {
                ("failed", Some(format!("no readable file at {path}")), 0)
            };
            items.push(Item {
                transfer_id: None,
                path: Some(path.to_string()),
                file_name: Some(file_name),
                total_size,
                transferred_size: 0,
                status,
                error,
            });
        }
        ("files", items)
    } else if let Some(text) = value.get("text") {
        let Some(text) = text.as_str() else {
            return bad_request(stream, "text must be a string");
        };
        if text.is_empty() {
            return bad_request(stream, "text must not be empty");
        }
        (
            "text",
            vec![Item {
                transfer_id: None,
                path: None,
                file_name: None,
                total_size: 0,
                transferred_size: 0,
                status: "queued",
                error: None,
            }],
        )
    } else {
        let Some(clipboard) = value.get("clipboard").and_then(|v| v.as_bool()) else {
            return bad_request(stream, "clipboard must be true");
        };
        if !clipboard {
            return bad_request(stream, "clipboard must be true");
        }
        (
            "clipboard",
            vec![Item {
                transfer_id: None,
                path: None,
                file_name: None,
                total_size: 0,
                transferred_size: 0,
                status: "queued",
                error: None,
            }],
        )
    };

    let mut registry = fixture
        .registry
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    registry.mint(device_id, kind, items);
    let body = registry.records[0].submission_json().to_string();
    drop(registry);
    respond(stream, 200, "OK", body.as_bytes());
}

/// `GET /transfers`, optionally narrowed to one id.
fn transfers(fixture: &Fixture, stream: &mut TcpStream, query: Option<&str>) {
    let mut registry = fixture
        .registry
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(id) = query.and_then(|query| query_param(query, "id")) else {
        let requests: Vec<serde_json::Value> =
            registry.records.iter().map(Record::to_json).collect();
        let body = serde_json::json!({ "ok": true, "requests": requests });
        drop(registry);
        return respond(stream, 200, "OK", body.to_string().as_bytes());
    };
    match registry.find(&id, &fixture.behaviors) {
        Some(record) => {
            let body = serde_json::json!({ "ok": true, "request": record.to_json() });
            drop(registry);
            respond(stream, 200, "OK", body.to_string().as_bytes())
        }
        None => respond(
            stream,
            404,
            "Not Found",
            br#"{"ok":false,"error":"unknown request id"}"#,
        ),
    }
}

/// Locks the host, recovering from a panic in another request thread: one
/// poisoned mutex must not take the whole fixture down.
fn lock_host(fixture: &Fixture) -> std::sync::MutexGuard<'_, Host> {
    fixture
        .host
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// How long `state_long_poll` holds a `/state` request open.
///
/// A client's poller has to be *waiting* rather than spinning, so the hold has
/// to be long compared with a round trip; and a client that is connecting has
/// to stay in that state long enough for a person — or a test — to watch a
/// spinner turn, so it has to be long compared with a glance.
///
/// Both of those are satisfied comfortably by ONE second, and one second is
/// also the whole point: the client caps every socket operation at
/// `MAX_SINGLE_IO` (2s), so a hold of exactly two seconds is a coin toss
/// between the daemon answering and the client's read deadline firing. Under
/// load it lost that toss — the three tests that use this behaviour reported
/// "daemon did not answer before the command deadline" instead of a screen.
/// The gap above is what the tests observe (they sleep 700ms and assert the
/// client is still connecting); the gap below is the client's cap. Both matter.
const LONG_POLL_HOLD: Duration = Duration::from_secs(1);

/// `GET /state`: the live state, including everything the interactive views
/// read. Under `advancing_state` every answer carries a new version, so a
/// long-polling client is woken by a genuine change.
///
/// Under `state_long_poll` the request is held open first, before the lock is
/// taken, because that is what the real control plane does: a client that
/// re-arms the moment it is answered is then waiting on a quiet daemon rather
/// than asking one a question a thousand times a second.
fn state(fixture: &Fixture, stream: &mut TcpStream) {
    if fixture.behaviors.state_long_poll {
        thread::sleep(LONG_POLL_HOLD);
    }
    let mut host = lock_host(fixture);
    if fixture.behaviors.advancing_state {
        host.bump();
    }
    host.polls += 1;
    let body = state_body(&fixture.behaviors, &host);
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// Reads a JSON body, answering 400 itself when it is not one.
fn parse_body(stream: &mut TcpStream, body: &[u8]) -> Option<serde_json::Value> {
    match serde_json::from_slice::<serde_json::Value>(body) {
        Ok(value) => Some(value),
        Err(_) => {
            bad_request(stream, "the request body is not valid JSON");
            None
        }
    }
}

/// `POST /accept-pair` and `POST /reject-pair`: both clear the dialog, because
/// either answer ends it. Answering 404 for a dialog that is not there is what
/// makes a decision observable — a 200 alone would say nothing about what
/// changed.
fn decide_pairing(fixture: &Fixture, stream: &mut TcpStream, body: &[u8], accept: bool) {
    let Some(value) = parse_body(stream, body) else {
        return;
    };
    let Some(device_id) = value.get("deviceId").and_then(|v| v.as_str()) else {
        return bad_request(stream, "deviceId is required");
    };
    let device_id = device_id.to_string();
    let mut host = lock_host(fixture);
    let matches = host
        .pairing
        .as_ref()
        .is_some_and(|pairing| pairing.device_id == device_id);
    if !matches {
        return not_found(stream);
    }
    host.pairing = None;
    host.bump();
    let body = serde_json::json!({
        "ok": true,
        "action": if accept { "accept-pair" } else { "reject-pair" },
        "deviceId": device_id,
        "version": host.version,
    })
    .to_string();
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// `POST /accept-incoming` and `POST /reject-incoming`. Same shape as the
/// pairing decision: the entry leaves `/state`, so its disappearance on screen
/// is the effect.
fn decide_incoming(fixture: &Fixture, stream: &mut TcpStream, body: &[u8], accept: bool) {
    let Some(value) = parse_body(stream, body) else {
        return;
    };
    let Some(receive_id) = value.get("receiveId").and_then(|v| v.as_i64()) else {
        return bad_request(stream, "receiveId is required");
    };
    let mut host = lock_host(fixture);
    let before = host.incoming.len();
    host.incoming.retain(|item| item.receive_id != receive_id);
    if host.incoming.len() == before {
        return not_found(stream);
    }
    host.bump();
    let body = serde_json::json!({
        "ok": true,
        "action": if accept { "accept-incoming" } else { "reject-incoming" },
        "receiveId": receive_id,
        "version": host.version,
    })
    .to_string();
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// `POST /rename-device`. The new name is what `/state` then reports for
/// `self`, so the header is the place a test can see it land.
fn rename_device(fixture: &Fixture, stream: &mut TcpStream, body: &[u8]) {
    let Some(value) = parse_body(stream, body) else {
        return;
    };
    let Some(name) = value.get("name").and_then(|v| v.as_str()) else {
        return bad_request(stream, "name is required");
    };
    let name = name.trim().to_string();
    if name.is_empty() {
        return bad_request(stream, "name must not be empty");
    }
    let mut host = lock_host(fixture);
    host.self_name = name;
    host.bump();
    let body = serde_json::json!({ "ok": true, "deviceName": host.self_name }).to_string();
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// `POST /settings`. The one control the daemon actually exposes: the TUI never
/// enables or disables a transport, so this route takes only the background
/// discovery flag.
fn settings(fixture: &Fixture, stream: &mut TcpStream, body: &[u8]) {
    let Some(value) = parse_body(stream, body) else {
        return;
    };
    let Some(enabled) = value.get("backgroundDiscovery").and_then(|v| v.as_bool()) else {
        return bad_request(stream, "backgroundDiscovery is required");
    };
    let mut host = lock_host(fixture);
    host.background_discovery = enabled;
    host.bump();
    let body = serde_json::json!({
        "ok": true,
        "backgroundDiscoveryEnabled": enabled,
        "supportsBackgroundDiscovery": true,
    })
    .to_string();
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// `POST /update/check`. Under `staged_update` it finds an update and stages
/// it, which is the only way "apply" becomes reachable without a `--force`.
fn update_check(fixture: &Fixture, stream: &mut TcpStream) {
    let mut host = lock_host(fixture);
    if fixture.behaviors.staged_update {
        host.update_status = "update_available";
        host.update_staged = true;
        host.bump();
    }
    let body = serde_json::json!({ "ok": true, "update": host.update_json() }).to_string();
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// `POST /update/apply`. A transfer in flight is a 409 unless `force` is set,
/// and the fixture refuses the unforced call for exactly that reason — the
/// client must not paper over it.
fn update_apply(fixture: &Fixture, stream: &mut TcpStream, body: &[u8]) {
    let forced = serde_json::from_slice::<serde_json::Value>(body)
        .ok()
        .and_then(|value| value.get("force").and_then(|v| v.as_bool()))
        .unwrap_or(false);
    let mut host = lock_host(fixture);
    if fixture.behaviors.transfer_active && !forced {
        return respond(
            stream,
            409,
            "Conflict",
            br#"{"ok":false,"error":"cannot apply an update while transfers are in flight"}"#,
        );
    }
    if !host.update_staged {
        return not_found(stream);
    }
    host.update_staged = false;
    host.update_status = "up_to_date";
    host.bump();
    let body = serde_json::json!({ "ok": true, "update": host.update_json() }).to_string();
    drop(host);
    respond(stream, 200, "OK", body.as_bytes());
}

/// How many messages `/history` returns when the client names no limit.
const DEFAULT_HISTORY_LIMIT: i64 = 50;
/// Ceiling on a page, so a client that asks for everything is refused rather
/// than answered with a body no pane can hold.
const MAX_HISTORY_LIMIT: i64 = 500;
/// How much conversation `long_history` holds. More than any pane, which is
/// what makes a window necessary rather than merely nice.
const LONG_HISTORY_MESSAGES: i64 = 500;
/// Filler appended to every second `long_history` message, and deliberately
/// wider than a conversation pane: a window measured in *messages* would put
/// the overflow below the pane, where scrolling cannot reach it, and the
/// newest message would never arrive on screen at all.
const LONG_HISTORY_FILLER: &str = " a message that is deliberately wider than any conversation pane, so that it has to wrap across several display rows";

/// `GET /history`: one page of a conversation, oldest first, with the cursor
/// for the page before it.
///
/// `device` is required and `limit` is bounded, because the client sends them
/// and because a page size the daemon honours unconditionally is a page size
/// that can be used to bury a client. `before` is exclusive — it is the id of
/// the newest message of the *previous* page — so asking twice with the same
/// cursor cannot hand back a message twice.
fn history(fixture: &Fixture, stream: &mut TcpStream, query: Option<&str>) {
    let query = query.unwrap_or_default();
    let Some(device) = query_param(query, "device") else {
        return bad_request(stream, "device is required");
    };
    if device.is_empty() {
        return bad_request(stream, "device must not be empty");
    }
    let limit = match query_param(query, "limit") {
        None => DEFAULT_HISTORY_LIMIT,
        Some(raw) => match raw.parse::<i64>() {
            Ok(limit) if limit > 0 => limit.min(MAX_HISTORY_LIMIT),
            _ => return bad_request(stream, "limit must be a positive integer"),
        },
    };
    let before = match query_param(query, "before") {
        None => None,
        Some(raw) => match raw.parse::<i64>() {
            Ok(cursor) => Some(cursor),
            Err(_) => return bad_request(stream, "before must be a message id"),
        },
    };
    let body = history_body(&fixture.behaviors, &device, limit, before);
    respond(stream, 200, "OK", body.as_bytes())
}

/// The page itself: ids `[start, end]`, oldest first.
///
/// An empty conversation is the honest default. A behaviour that wants a
/// conversation to look at says so, and every message then carries every field
/// a real daemon sends, because a client that cannot read the page it asked for
/// has to learn that from a daemon that sent it.
fn history_body(behaviors: &Behaviors, device: &str, limit: i64, before: Option<i64>) -> String {
    let total = if behaviors.long_history {
        LONG_HISTORY_MESSAGES
    } else {
        0
    };
    let end = before
        .map(|cursor| (cursor - 1).min(total))
        .unwrap_or(total)
        .max(0);
    let start = (end - limit + 1).max(1);
    let messages: Vec<serde_json::Value> = if end < start {
        Vec::new()
    } else {
        (start..=end).map(history_message).collect()
    };
    let next_before = if start > 1 {
        serde_json::Value::from(start - 1)
    } else {
        serde_json::Value::Null
    };
    serde_json::json!({
        "ok": true,
        "deviceId": device,
        "nextBefore": next_before,
        "messages": messages,
    })
    .to_string()
}

/// One message in the shape `/history` reports it.
fn history_message(id: i64) -> serde_json::Value {
    let content = if id % 2 == 0 {
        format!("message {id}{LONG_HISTORY_FILLER}")
    } else {
        format!("message {id}")
    };
    serde_json::json!({
        "id": id,
        "content": content,
        "timestamp": 1_700_000_000_000i64 + id * 60_000,
        "isSender": id % 2 == 0,
        "messageType": "text",
        "deliveryStatus": "SENT",
        "isRead": true,
        "mimeType": "text/plain",
        "fileTransferId": serde_json::Value::Null,
        "file": serde_json::Value::Null,
    })
}

fn query_param(query: &str, key: &str) -> Option<String> {
    query.split('&').find_map(|pair| {
        let (name, value) = pair.split_once('=')?;
        (name == key).then(|| value.to_string())
    })
}

fn not_found(stream: &mut TcpStream) {
    respond(
        stream,
        404,
        "Not Found",
        br#"{"ok":false,"error":"not found"}"#,
    );
}

fn bad_request(stream: &mut TcpStream, reason: &str) {
    let body = serde_json::json!({ "ok": false, "error": reason }).to_string();
    respond(stream, 400, "Bad Request", body.as_bytes());
}

struct Request {
    head: String,
    body: Vec<u8>,
}

/// Reads the request head byte by byte (there is no buffering here, so this
/// cannot over-read past the headers) and then exactly `Content-Length` bytes.
fn read_request(stream: &mut TcpStream) -> Result<Request, String> {
    let mut head: Vec<u8> = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        let read = stream
            .read(&mut byte)
            .map_err(|e| format!("read error: {e}"))?;
        if read == 0 {
            break;
        }
        head.push(byte[0]);
        if head.len() > MAX_REQUEST_BYTES {
            return Err("request head exceeds the fixture ceiling".to_string());
        }
        if head.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    if head.is_empty() {
        return Err("empty request".to_string());
    }
    let head = String::from_utf8_lossy(&head).to_string();

    let length = header_value(&head, "content-length")
        .and_then(|value| value.trim().parse::<usize>().ok())
        .unwrap_or(0);
    if length > MAX_REQUEST_BYTES {
        return Err("request body exceeds the fixture ceiling".to_string());
    }
    let mut body = vec![0u8; length];
    if length > 0 {
        stream
            .read_exact(&mut body)
            .map_err(|e| format!("body read error: {e}"))?;
    }
    Ok(Request { head, body })
}

fn split_request_line(head: &str) -> (&str, &str) {
    let line = head.lines().next().unwrap_or_default();
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("GET");
    (method, parts.next().unwrap_or("/"))
}

fn header_value<'a>(head: &'a str, name: &str) -> Option<&'a str> {
    head.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.trim().eq_ignore_ascii_case(name).then_some(value)
    })
}

fn is_authorized(head: &str, token: &str) -> bool {
    header_value(head, "authorization")
        .is_some_and(|value| value.trim() == format!("Bearer {token}"))
}

fn respond(stream: &mut TcpStream, status: u16, reason: &str, body: &[u8]) {
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

/// Declares a body above the client's 4 MiB ceiling; write errors are expected
/// because the client aborts the exchange.
fn write_huge_body(stream: &mut TcpStream) {
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {HUGE_BODY_BYTES}\r\nConnection: close\r\n\r\n"
    );
    if stream.write_all(head.as_bytes()).is_err() {
        return;
    }
    let chunk = vec![b'A'; 64 * 1024];
    let mut written = 0usize;
    while written < HUGE_BODY_BYTES {
        let take = chunk.len().min(HUGE_BODY_BYTES - written);
        if stream.write_all(&chunk[..take]).is_err() {
            return;
        }
        written += take;
    }
    let _ = stream.flush();
}

/// Announces a body far larger than what it sends, then trickles a byte every
/// [`SLOW_DRIP_INTERVAL_MS`]. A client that only bounded each individual socket
/// read would sit here for hours; the deadline is what has to end the exchange,
/// so write errors are expected once the client gives up.
fn write_slow_drip(stream: &mut TcpStream) {
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {SLOW_DRIP_BODY_BYTES}\r\nConnection: close\r\n\r\n"
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.flush();
    let byte = b" ";
    for _ in 0..SLOW_DRIP_BODY_BYTES {
        if stream.write_all(byte).is_err() || stream.flush().is_err() {
            return;
        }
        thread::sleep(Duration::from_millis(SLOW_DRIP_INTERVAL_MS));
    }
}

fn capabilities_body(version: &str, behaviors: &Behaviors) -> String {
    // `limited_capabilities` is the shape of a daemon from before the
    // interactive routes existed. A client must label what it cannot do rather
    // than offer a control that would answer 404.
    let routes: &[&str] = if behaviors.limited_capabilities {
        &[
            "health",
            "state",
            "capabilities",
            "history",
            "send-text",
            "share",
            "transfers",
        ]
    } else {
        &[
            "health",
            "state",
            "capabilities",
            "history",
            "send-text",
            "send-file",
            "send-clipboard",
            "pair",
            "unpair",
            "accept-pair",
            "reject-pair",
            "accept-incoming",
            "reject-incoming",
            "retry",
            "rename-device",
            "settings",
            "qr-share",
            "update",
            "share",
            "transfers",
        ]
    };
    let list = routes
        .iter()
        .map(|route| format!("\"{route}\""))
        .collect::<Vec<_>>()
        .join(",");
    format!(r#"{{"ok":true,"apiVersion":1,"version":"{version}","capabilities":[{list}]}}"#)
}

/// `GET /state` as the interactive client requires it. Every key the TUI's
/// parser insists on is present, because a body missing one is exactly the
/// "too old a daemon" case that the TUI reports instead of rendering.
fn state_body(behaviors: &Behaviors, host: &Host) -> String {
    let devices = if behaviors.empty_state {
        "[]".to_string()
    } else {
        // Built rather than pasted, because two of the three names are
        // behaviour-dependent and one of them carries control characters: a
        // hand-written JSON literal is exactly where such a name would have to
        // be escaped by hand, by whoever wrote the test, every time.
        let phone = if behaviors.hostile_names {
            HOSTILE_NAME
        } else {
            "Fixture Phone"
        };
        let tablet = if behaviors.wide_device_names {
            WIDE_NAME
        } else {
            "Fixture Tablet"
        };
        serde_json::json!([
            {"deviceId":"11112222","deviceName":phone,"deviceType":"ANDROID","trustStatus":"trusted","reachability":"reachable","connectionTypes":["KLARDROP"],"hasUnread":true,"unreadCount":2,"pairingError":null},
            {"deviceId":"3333aaaa","deviceName":tablet,"deviceType":"ANDROID","trustStatus":"untrusted","reachability":"unreachable","connectionTypes":[],"hasUnread":false,"unreadCount":0,"pairingError":null},
            {"deviceId":"3333bbbb","deviceName":"Fixture Laptop","deviceType":"DESKTOP","trustStatus":"untrusted","reachability":"reachable","connectionTypes":["KLARDROP"],"hasUnread":false,"unreadCount":0,"pairingError":null}
        ])
        .to_string()
    };
    let self_object = if behaviors.no_self {
        String::new()
    } else {
        format!(r#""self":{},"#, host.self_json(behaviors))
    };
    let pairing = match &host.pairing {
        Some(pairing) => serde_json::json!({
            "deviceId": pairing.device_id,
            "deviceName": pairing.device_name,
            "isError": pairing.is_error,
            "errorMessage": if pairing.is_error {
                serde_json::Value::String("the pairing code was rejected".to_string())
            } else {
                serde_json::Value::Null
            },
        })
        .to_string(),
        None => "null".to_string(),
    };
    let incoming: Vec<serde_json::Value> = host
        .incoming
        .iter()
        .map(|item| {
            serde_json::json!({
                "receiveId": item.receive_id,
                "deviceId": item.device_id,
                "deviceName": item.device_name,
                "status": "pending_auth",
                "pendingAuth": true,
                "fileCount": 1,
                "fileNames": [item.file_name],
                "totalSize": 2048,
                "text": serde_json::Value::Null,
            })
        })
        .collect();
    let transfers = transfers_json(behaviors, host);
    format!(
        r#"{{"ok":true,"version":{version},{self_object}"protocols":{{"klardrop":true,"nearby":true,"ble":false}},"settings":{{"backgroundDiscoveryEnabled":{discovery},"supportsBackgroundDiscovery":true}},"devices":{devices},"trustedIds":["11112222"],"incoming":{incoming},"notifications":[],"pairingDialog":{pairing},"qrShare":{{"active":false,"url":null,"expiresAt":null,"downloadCount":0,"downloads":[]}},"transfers":{transfers},"update":{update}}}"#,
        version = host.version,
        discovery = host.background_discovery,
        incoming = serde_json::Value::Array(incoming),
        update = host.update_json(),
    )
}

/// How long `transfer_settles` stays in flight before it reports a terminal
/// phase. Long enough for a test to capture a moving indicator and then a
/// still one, short enough not to be the slowest thing in the suite.
const TRANSFER_SETTLE_AFTER: Duration = Duration::from_secs(3);

/// The transfer list `/state` carries.
///
/// `transfer_active` is a transfer that never finishes, which is exactly what
/// the update-apply refusal needs and exactly what cannot show an indicator
/// stopping. `transfer_settles` is the same transfer with an ending: it runs
/// for [`TRANSFER_SETTLE_AFTER`] and then reports `completed`, so both halves
/// of "moving, then still" are observable.
fn transfers_json(behaviors: &Behaviors, host: &Host) -> String {
    const TOTAL: u64 = 2048;
    // A file name is a name like any other, and this is the row that is
    // redrawn on every frame while a transfer runs, so under `hostile_names`
    // it carries control characters too — whichever transfer the other
    // behaviours asked for.
    let file_name = if behaviors.hostile_names {
        HOSTILE_NAME
    } else {
        "holiday.zip"
    };
    if behaviors.transfer_settles {
        let elapsed = host.started.elapsed();
        let (phase, done) = if elapsed >= TRANSFER_SETTLE_AFTER {
            ("completed", TOTAL)
        } else {
            // Never more than nine tenths: a running transfer must never be
            // mistakable for a finished one by anything reading the bytes.
            let tenths = elapsed.as_millis() as u64 * 9 / TRANSFER_SETTLE_AFTER.as_millis() as u64;
            ("receiving", TOTAL * tenths.min(9) / 10)
        };
        return serde_json::json!([{
            "id": "tx-live-1",
            "deviceId": "5555dddd",
            "fileName": file_name,
            "totalSize": TOTAL,
            "transferredSize": done,
            "isSender": false,
            "phase": phase,
        }])
        .to_string();
    }
    if behaviors.transfer_active {
        return serde_json::json!([{
            "id": "tx-live-1",
            "deviceId": "5555dddd",
            "fileName": file_name,
            "totalSize": 2048,
            "transferredSize": 1024,
            "isSender": false,
            "phase": "receiving",
        }])
        .to_string();
    }
    // A transfer exists here so the hostile name is not confined to the panes:
    // the transfer line is redrawn on every frame while a send is in flight,
    // and a name that survives one draw but not the next is no better than one
    // that never survived at all.
    if behaviors.hostile_names {
        return serde_json::json!([{
            "id": "tx-hostile-1",
            "deviceId": "5555dddd",
            "fileName": file_name,
            "totalSize": 2048,
            "transferredSize": 1024,
            "isSender": false,
            "phase": "receiving",
        }])
        .to_string();
    }
    "[]".to_string()
}
