//! Typed views over the control plane's `GET /state` and `GET /capabilities`
//! responses.
//!
//! The daemon's own response is an implementation detail; this module is where
//! "too old to answer" is detected. A 200 response that does not carry the
//! fields a command needs is `daemon_unsupported` — never an empty success.

use serde::{Deserialize, Serialize};

use crate::envelope::{CliError, CliResult, ErrorCode};

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SelfDevice {
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub os_type: String,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Protocols {
    pub klardrop: bool,
    pub nearby: bool,
    pub ble: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub background_discovery_enabled: bool,
    pub supports_background_discovery: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub supported: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawDevice {
    device_id: String,
    device_name: String,
    device_type: String,
    trust_status: String,
    reachability: String,
    #[serde(default)]
    connection_types: Vec<String>,
    #[serde(default)]
    has_unread: bool,
    #[serde(default)]
    unread_count: u64,
}

/// One device as reported by the CLI's own JSON contract.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSummary {
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub paired: bool,
    pub reachable: bool,
    pub trust_status: String,
    pub reachability: String,
    pub connection_types: Vec<String>,
    pub has_unread: bool,
    pub unread_count: u64,
}

/// Everything `/state` must provide for the read-only commands to work.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StateSnapshot {
    #[serde(rename = "self")]
    pub self_device: SelfDevice,
    devices: Vec<RawDevice>,
    #[serde(default)]
    pub trusted_ids: Vec<String>,
    pub protocols: Protocols,
    pub settings: Settings,
    #[serde(default)]
    pub transfers: Vec<serde_json::Value>,
    #[serde(default)]
    pub update: UpdateInfo,
}

impl StateSnapshot {
    /// Devices with pairing and reachability resolved.
    pub fn devices(&self) -> Vec<DeviceSummary> {
        self.devices
            .iter()
            .map(|device| DeviceSummary {
                device_id: device.device_id.clone(),
                device_name: device.device_name.clone(),
                device_type: device.device_type.clone(),
                paired: self.trusted_ids.contains(&device.device_id),
                reachable: device.reachability == "reachable",
                trust_status: device.trust_status.clone(),
                reachability: device.reachability.clone(),
                connection_types: device.connection_types.clone(),
                has_unread: device.has_unread,
                unread_count: device.unread_count,
            })
            .collect()
    }

    pub fn paired_count(&self) -> usize {
        self.devices().iter().filter(|device| device.paired).count()
    }

    pub fn reachable_count(&self) -> usize {
        self.devices()
            .iter()
            .filter(|device| device.reachable)
            .count()
    }
}

/// Daemon identity as advertised by `GET /capabilities`. Unknown for a daemon
/// too old to implement that route.
#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DaemonInfo {
    #[serde(default)]
    pub api_version: Option<u32>,
    #[serde(default)]
    pub version: Option<String>,
    /// The daemon's advertised route list. A control that the daemon does not
    /// advertise is labelled unavailable rather than drawn as a button that
    /// would silently do nothing.
    ///
    /// Never serialized: `daemon` in the JSON envelope is a published contract
    /// with a fixed key order, and the TUI's internal list has no business
    /// changing what `status --json` prints.
    #[serde(default, skip_serializing)]
    pub capabilities: Vec<String>,
}

/// Parses a `/state` body. Anything that is not a JSON object, or that lacks a
/// field this client needs, is reported as `daemon_unsupported`.
pub fn parse_state(value: &serde_json::Value) -> CliResult<StateSnapshot> {
    if !value.is_object() {
        return Err(CliError::new(
            ErrorCode::DaemonResponseInvalid,
            "daemon returned a /state body that is not a JSON object",
        ));
    }
    serde_json::from_value::<StateSnapshot>(value.clone()).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonUnsupported,
            format!(
                "daemon returned a /state body this client cannot use \
                 (incompatible or incomplete schema: {e}); the daemon is too old — upgrade it"
            ),
        )
    })
}

/// Parses a `/capabilities` body. A non-string entry in `capabilities` is
/// skipped rather than fatal: the list is advisory, and one odd entry must not
/// cost the caller the daemon's version too.
pub fn parse_capabilities(value: &serde_json::Value) -> CliResult<DaemonInfo> {
    if !value.is_object() {
        return Err(CliError::new(
            ErrorCode::DaemonResponseInvalid,
            "daemon returned a /capabilities body that is not a JSON object",
        ));
    }
    let api_version = value
        .get("apiVersion")
        .map(|v| {
            v.as_u64()
                .and_then(|n| u32::try_from(n).ok())
                .ok_or_else(|| {
                    CliError::new(
                        ErrorCode::DaemonResponseInvalid,
                        "daemon returned a non-integral apiVersion in /capabilities",
                    )
                })
        })
        .transpose()?;
    let version = value
        .get("version")
        .and_then(|v| v.as_str())
        .map(String::from);
    let capabilities: Vec<String> = value
        .get("capabilities")
        .and_then(|v| v.as_array())
        .map(|entries| {
            entries
                .iter()
                .filter_map(|entry| entry.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default();
    Ok(DaemonInfo {
        api_version,
        version,
        capabilities,
    })
}

// ------------------------------------------------------- share / transfers

/// Outcome of one submitted item, as the daemon sees it.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum TransferStatus {
    Queued,
    Awaiting,
    Transferring,
    Completed,
    Declined,
    Failed,
}

impl TransferStatus {
    /// `true` once the item can no longer change state. Only a terminal
    /// outcome may be reported as a delivery result.
    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Declined | Self::Failed)
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Awaiting => "awaiting",
            Self::Transferring => "transferring",
            Self::Completed => "completed",
            Self::Declined => "declined",
            Self::Failed => "failed",
        }
    }
}

/// Overall state of a whole request. The daemon derives it from its items:
/// any failure wins, then any decline, then "everything completed",
/// otherwise the request is still `queued`.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RequestStatus {
    Queued,
    Completed,
    Declined,
    Failed,
}

impl RequestStatus {
    /// `true` when the request can no longer change state.
    pub fn is_terminal(self) -> bool {
        !matches!(self, Self::Queued)
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Completed => "completed",
            Self::Declined => "declined",
            Self::Failed => "failed",
        }
    }
}

/// Which payload kind a request carries.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ShareKind {
    Files,
    Text,
    Clipboard,
}

impl ShareKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Files => "files",
            Self::Text => "text",
            Self::Clipboard => "clipboard",
        }
    }
}

/// One submitted item. The daemon always sends every key — `path`, `fileName`
/// and `error` are explicit `null` for a text or clipboard item — so the CLI's
/// own output keeps the same stable shape.
///
/// Field order is part of the published envelope contract.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ShareItem {
    // An item the daemon could not prepare (a path that vanished, a directory, an
    // unreadable file) has no transfer to correlate, and the daemon answers with an
    // explicit `null`. Deserializing that as a plain `String` would turn a truthful
    // partial-failure report into "the daemon is too old — upgrade it".
    #[serde(default)]
    pub transfer_id: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub file_name: Option<String>,
    pub total_size: u64,
    pub transferred_size: u64,
    pub status: TransferStatus,
    #[serde(default)]
    pub error: Option<String>,
}

/// One `/transfers` record: the exact outcome of one `/share` submission.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ShareRequest {
    pub request_id: String,
    pub device_id: String,
    pub kind: ShareKind,
    pub status: RequestStatus,
    pub created_at: i64,
    pub updated_at: i64,
    pub items: Vec<ShareItem>,
}

/// The flat answer to `POST /share`: the submission was accepted and the
/// daemon has already minted the request id and its transfer ids. Its
/// `status` is the request's real state at that moment — normally `queued`,
/// never a delivery claim.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SubmittedShare {
    pub request_id: String,
    pub device_id: String,
    pub kind: ShareKind,
    pub status: RequestStatus,
    pub items: Vec<ShareItem>,
}

impl ShareRequest {
    /// A record read back while waiting, in the same shape the submission had,
    /// so a caller only ever has to reason about one type.
    pub fn into_submission(self) -> SubmittedShare {
        SubmittedShare {
            request_id: self.request_id,
            device_id: self.device_id,
            kind: self.kind,
            status: self.status,
            items: self.items,
        }
    }
}

/// Parses a `POST /share` body.
pub fn parse_share_response(value: &serde_json::Value) -> CliResult<SubmittedShare> {
    expect_ok_object(value, "/share")?;
    serde_json::from_value::<SubmittedShare>(value.clone()).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonUnsupported,
            format!(
                "daemon returned a POST /share body this client cannot use \
                 (incompatible or incomplete schema: {e}); the daemon is too old — upgrade it"
            ),
        )
    })
}

/// Parses a `GET /transfers` body (the whole registry, newest first).
pub fn parse_transfers_list(value: &serde_json::Value) -> CliResult<Vec<ShareRequest>> {
    expect_ok_object(value, "/transfers")?;
    let requests = value
        .get("requests")
        .ok_or_else(|| missing("/transfers", "requests"))?;
    serde_json::from_value::<Vec<ShareRequest>>(requests.clone()).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonUnsupported,
            format!(
                "daemon returned a /transfers registry this client cannot use \
                 (incompatible or incomplete schema: {e}); the daemon is too old — upgrade it"
            ),
        )
    })
}

/// Parses a `GET /transfers?id=…` body (exactly one request).
pub fn parse_transfers_one(value: &serde_json::Value) -> CliResult<ShareRequest> {
    expect_ok_object(value, "/transfers")?;
    let request = value
        .get("request")
        .ok_or_else(|| missing("/transfers", "request"))?;
    serde_json::from_value::<ShareRequest>(request.clone()).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonUnsupported,
            format!(
                "daemon returned a /transfers record this client cannot use \
                 (incompatible or incomplete schema: {e}); the daemon is too old — upgrade it"
            ),
        )
    })
}

/// Every daemon answer carries `ok`; a body that says otherwise never counts
/// as a result.
fn expect_ok_object(value: &serde_json::Value, path: &str) -> CliResult<()> {
    if !value.is_object() {
        return Err(CliError::new(
            ErrorCode::DaemonResponseInvalid,
            format!("daemon returned a {path} body that is not a JSON object"),
        ));
    }
    match value.get("ok") {
        Some(serde_json::Value::Bool(true)) | None => Ok(()),
        Some(other) => Err(CliError::new(
            ErrorCode::DaemonResponseInvalid,
            format!("daemon answered {path} with ok={other} on a successful request"),
        )),
    }
}

// ------------------------------------------------------ interactive state

/// One pending incoming transfer as `/state` reports it.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct IncomingTransfer {
    pub receive_id: i64,
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default)]
    pub device_name: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    /// Whether the peer is still waiting for this side's decision. Only a
    /// pending request can be accepted or rejected.
    #[serde(default)]
    pub pending_auth: bool,
    #[serde(default)]
    pub file_count: u64,
    #[serde(default)]
    pub file_names: Vec<String>,
    #[serde(default)]
    pub total_size: u64,
    #[serde(default)]
    pub text: Option<String>,
}

/// The pairing decision the host is currently asking about, or `null`.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PairingDialog {
    pub device_id: String,
    pub device_name: String,
    #[serde(default)]
    pub is_error: bool,
    #[serde(default)]
    pub error_message: Option<String>,
}

/// One queued UI notification. `type` is a keyword, so it is renamed here.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Notification {
    pub id: i64,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default)]
    pub device_name: Option<String>,
}

/// One download in progress against a QR share session.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QrDownload {
    pub file_name: String,
    #[serde(default)]
    pub percentage: u64,
    #[serde(default)]
    pub bytes_transferred: u64,
    #[serde(default)]
    pub total_bytes: u64,
}

/// The QR share session. Always present in `/state`, `active` false when there
/// is none — so "no session" is a real value, not an absent field.
#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QrShareState {
    #[serde(default)]
    pub active: bool,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub expires_at: Option<i64>,
    #[serde(default)]
    pub download_count: u64,
    #[serde(default)]
    pub downloads: Vec<QrDownload>,
    /// The module matrix for the share URL: one string per row, `1` dark, `0`
    /// light, and **no quiet zone**. Absent on a daemon that cannot draw one,
    /// which is why the overlay falls back to the URL rather than inventing
    /// modules.
    #[serde(default)]
    pub qr: Vec<String>,
}

/// A transfer the host is actively moving. Distinct from `GET /transfers`:
/// this is the live progress block inside `/state`, which the read-only
/// `transfers` command deliberately does not interpret.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ActiveTransfer {
    pub id: String,
    pub device_id: String,
    pub file_name: String,
    #[serde(default)]
    pub total_size: u64,
    #[serde(default)]
    pub transferred_size: u64,
    #[serde(default)]
    pub is_sender: bool,
    #[serde(default)]
    pub phase: String,
}

impl ActiveTransfer {
    /// Completion as a whole percent, clamped. A transfer whose total is not
    /// known yet is reported as 0, never as a division by zero or a 100 %.
    pub fn percent(&self) -> u64 {
        if self.total_size == 0 {
            return 0;
        }
        ((self.transferred_size as u128 * 100) / self.total_size as u128).min(100) as u64
    }

    /// Whether the daemon has already said this transfer ended.
    ///
    /// `phase` is a free-form string, so this recognises the words the daemon
    /// uses for a finished transfer and treats everything else — including a
    /// word this build has never heard of — as still running. Erring towards
    /// "running" is the only safe direction: a client that stops a progress
    /// animation too early is claiming a result the daemon never gave.
    pub fn is_settled(&self) -> bool {
        matches!(
            self.phase.to_ascii_lowercase().as_str(),
            "completed"
                | "complete"
                | "done"
                | "failed"
                | "declined"
                | "cancelled"
                | "canceled"
                | "aborted"
                | "error"
        )
    }
}

/// The update block. `status` and `supported` are required: a client that
/// cannot tell "up to date" from "this build cannot check" would offer a
/// control that silently does nothing.
#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UpdateStatus {
    pub status: String,
    pub supported: bool,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub staged: bool,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub current_version: Option<String>,
    #[serde(default)]
    pub channel: Option<String>,
}

impl UpdateStatus {
    /// Whether applying now can succeed without `force`. The daemon answers
    /// 409 while transfers are in flight, and the TUI must not offer the
    /// button as if it could not fail.
    pub fn can_apply(&self) -> bool {
        self.supported && self.staged
    }
}

/// Everything the TUI needs from one `/state` response.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TuiState {
    /// The long-poll cursor. `GET /state?since=<version>` blocks until the
    /// daemon's own counter passes this.
    pub version: i64,
    #[serde(rename = "self")]
    pub self_device: SelfDevice,
    devices: Vec<RawDevice>,
    #[serde(default)]
    pub trusted_ids: Vec<String>,
    /// Required for schema validation only: the TUI never enables or disables a
    /// transport, and inventing such a control is exactly what the brief
    /// forbids. Parsed so an incomplete body is still `daemon_unsupported`.
    #[allow(dead_code)]
    pub protocols: Protocols,
    pub settings: Settings,
    pub incoming: Vec<IncomingTransfer>,
    #[serde(default)]
    pub notifications: Vec<Notification>,
    pub pairing_dialog: Option<PairingDialog>,
    pub qr_share: QrShareState,
    pub transfers: Vec<ActiveTransfer>,
    pub update: UpdateStatus,
}

impl TuiState {
    /// Devices with pairing and reachability resolved, as the read-only
    /// commands resolve them.
    pub fn devices(&self) -> Vec<DeviceSummary> {
        self.devices
            .iter()
            .map(|device| DeviceSummary {
                device_id: device.device_id.clone(),
                device_name: device.device_name.clone(),
                device_type: device.device_type.clone(),
                paired: self.trusted_ids.contains(&device.device_id),
                reachable: device.reachability == "reachable",
                trust_status: device.trust_status.clone(),
                reachability: device.reachability.clone(),
                connection_types: device.connection_types.clone(),
                has_unread: device.has_unread,
                unread_count: device.unread_count,
            })
            .collect()
    }

    /// The self device's display name, shown in the header so the user can
    /// tell at a glance which machine this window belongs to.
    pub fn self_name(&self) -> &str {
        &self.self_device.device_name
    }
}

/// `/state` keys the TUI refuses to run without. A daemon missing any of them
/// is too old to drive, and saying so is far better than drawing an empty
/// device list that looks like "nothing nearby".
const REQUIRED_STATE_KEYS: &[&str] = &[
    "version",
    "self",
    "devices",
    "trustedIds",
    "protocols",
    "settings",
    "incoming",
    "notifications",
    "pairingDialog",
    "qrShare",
    "transfers",
    "update",
];

/// Parses a `/state` body for the TUI. Same contract as [`parse_state`], with
/// the keys the interactive views need additionally required.
pub fn parse_tui_state(value: &serde_json::Value) -> CliResult<TuiState> {
    expect_ok_object(value, "/state")?;
    for key in REQUIRED_STATE_KEYS {
        if value.get(*key).is_none() {
            return Err(missing("/state", key));
        }
    }
    serde_json::from_value::<TuiState>(value.clone()).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonUnsupported,
            format!(
                "daemon returned a /state body this client cannot use \
                 (incompatible or incomplete schema: {e}); the daemon is too old — upgrade it"
            ),
        )
    })
}

// ------------------------------------------------------------ history page

/// The file a history message refers to, when it is a file message.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryFile {
    pub file_name: String,
    #[serde(default)]
    pub file_path: Option<String>,
    #[serde(default)]
    pub file_size: u64,
    #[serde(default)]
    pub transferred_size: u64,
    #[serde(default)]
    pub status: Option<String>,
}

/// One message as `GET /history` reports it.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryMessage {
    pub id: i64,
    pub content: String,
    pub timestamp: i64,
    pub is_sender: bool,
    pub message_type: String,
    pub delivery_status: String,
    pub is_read: bool,
    pub mime_type: String,
    #[serde(default)]
    pub file_transfer_id: Option<String>,
    #[serde(default)]
    pub file: Option<HistoryFile>,
}

impl HistoryMessage {
    /// Whether the message will never arrive, so the pane can label it
    /// instead of leaving the user to assume it is merely late.
    pub fn failed(&self) -> bool {
        self.delivery_status.eq_ignore_ascii_case("FAILED")
    }
}

/// One page of `GET /history`. `next_before` is the cursor for the next older
/// page, and is `null` when the daemon has nothing older.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryPage {
    pub device_id: String,
    pub next_before: Option<i64>,
    pub messages: Vec<HistoryMessage>,
}

/// Parses a `GET /history` body. `deviceId`, `nextBefore` and `messages` are
/// required; every message must carry its own fields, so an incompatible
/// daemon is `daemon_unsupported` rather than a pane of blanks.
pub fn parse_history(value: &serde_json::Value) -> CliResult<HistoryPage> {
    expect_ok_object(value, "/history")?;
    for key in ["deviceId", "nextBefore", "messages"] {
        if value.get(key).is_none() {
            return Err(missing("/history", key));
        }
    }
    serde_json::from_value::<HistoryPage>(value.clone()).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonUnsupported,
            format!(
                "daemon returned a /history body this client cannot use \
                 (incompatible or incomplete schema: {e}); the daemon is too old — upgrade it"
            ),
        )
    })
}

fn missing(path: &str, field: &str) -> CliError {
    CliError::new(
        ErrorCode::DaemonUnsupported,
        format!("daemon answered {path} without {field}; it is too old — upgrade it"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state_value() -> serde_json::Value {
        serde_json::json!({
            "ok": true,
            "version": 7,
            "self": {
                "deviceId": "abcdef12",
                "deviceName": "Laptop",
                "osType": "LINUX",
                "deviceType": "DESKTOP"
            },
            "protocols": {"klardrop": true, "nearby": true, "ble": false},
            "settings": {
                "backgroundDiscoveryEnabled": true,
                "supportsBackgroundDiscovery": true
            },
            "devices": [
                {
                    "deviceId": "11112222",
                    "deviceName": "Phone",
                    "deviceType": "ANDROID",
                    "trustStatus": "trusted",
                    "reachability": "reachable",
                    "connectionTypes": ["KLARDROP"],
                    "hasUnread": true,
                    "unreadCount": 3,
                    "pairingError": null
                },
                {
                    "deviceId": "33334444",
                    "deviceName": "Tablet",
                    "deviceType": "ANDROID",
                    "trustStatus": "untrusted",
                    "reachability": "unreachable",
                    "connectionTypes": [],
                    "hasUnread": false,
                    "unreadCount": 0
                }
            ],
            "trustedIds": ["11112222"],
            "transfers": [],
            "update": {"status": "up_to_date", "supported": true}
        })
    }

    #[test]
    fn parses_devices_with_pairing_and_reachability() {
        let snapshot = parse_state(&state_value()).expect("valid state");
        let devices = snapshot.devices();
        assert_eq!(devices.len(), 2);
        assert!(devices[0].paired);
        assert!(devices[0].reachable);
        assert_eq!(devices[0].unread_count, 3);
        assert!(!devices[1].paired);
        assert!(!devices[1].reachable);
        assert_eq!(snapshot.paired_count(), 1);
        assert_eq!(snapshot.reachable_count(), 1);
    }

    #[test]
    fn serializes_device_summary_in_contract_order() {
        let snapshot = parse_state(&state_value()).expect("valid state");
        let encoded = serde_json::to_string(&snapshot.devices()[0]).expect("serializable");
        assert_eq!(
            encoded,
            r#"{"deviceId":"11112222","deviceName":"Phone","deviceType":"ANDROID","paired":true,"reachable":true,"trustStatus":"trusted","reachability":"reachable","connectionTypes":["KLARDROP"],"hasUnread":true,"unreadCount":3}"#
        );
    }

    #[test]
    fn missing_self_is_unsupported_not_empty_success() {
        let mut value = state_value();
        value.as_object_mut().expect("object").remove("self");
        let error = parse_state(&value).expect_err("missing self");
        assert_eq!(error.code, ErrorCode::DaemonUnsupported);
        assert_eq!(error.code.exit_code(), 3);
    }

    #[test]
    fn missing_devices_array_is_unsupported() {
        let mut value = state_value();
        value.as_object_mut().expect("object").remove("devices");
        let error = parse_state(&value).expect_err("missing devices");
        assert_eq!(error.code, ErrorCode::DaemonUnsupported);
    }

    #[test]
    fn missing_protocols_is_unsupported() {
        let mut value = state_value();
        value.as_object_mut().expect("object").remove("protocols");
        let error = parse_state(&value).expect_err("missing protocols");
        assert_eq!(error.code, ErrorCode::DaemonUnsupported);
    }

    #[test]
    fn non_object_state_body_is_a_response_error() {
        let error = parse_state(&serde_json::json!([])).expect_err("array state");
        assert_eq!(error.code, ErrorCode::DaemonResponseInvalid);
        assert_eq!(error.code.exit_code(), 1);
    }

    #[test]
    fn optional_state_fields_default() {
        let mut value = state_value();
        let object = value.as_object_mut().expect("object");
        object.remove("update");
        object.remove("transfers");
        object.remove("trustedIds");
        let snapshot = parse_state(&value).expect("valid without optionals");
        assert_eq!(snapshot.update, UpdateInfo::default());
        assert!(snapshot.transfers.is_empty());
        assert!(!snapshot.devices()[0].paired);
    }

    #[test]
    fn parses_capabilities() {
        let info = parse_capabilities(&serde_json::json!({
            "ok": true,
            "apiVersion": 1,
            "version": "1.2.3",
            "capabilities": ["state", "capabilities"]
        }))
        .expect("valid capabilities");
        assert_eq!(info.api_version, Some(1));
        assert_eq!(info.version.as_deref(), Some("1.2.3"));
    }

    #[test]
    fn capabilities_without_api_version_is_unknown_not_an_error() {
        let info = parse_capabilities(&serde_json::json!({"ok": true})).expect("no apiVersion");
        assert_eq!(info.api_version, None);
        assert_eq!(info.version, None);
        assert_eq!(
            serde_json::to_string(&info).expect("serializable"),
            r#"{"apiVersion":null,"version":null}"#
        );
    }

    // ------------------------------------------------- share / transfers

    fn submitted_body() -> serde_json::Value {
        serde_json::json!({
            "ok": true,
            "action": "share",
            "requestId": "req-0123456789abcdef",
            "deviceId": "11112222",
            "kind": "files",
            "status": "queued",
            "items": [{
                "transferId": "42",
                "path": "/tmp/report.pdf",
                "fileName": "report.pdf",
                "totalSize": 12,
                "transferredSize": 0,
                "status": "queued",
                "error": null
            }]
        })
    }

    fn transfers_record(request_id: &str) -> serde_json::Value {
        serde_json::json!({
            "requestId": request_id,
            "deviceId": "11112222",
            "kind": "files",
            "status": "declined",
            "createdAt": 1_750_000_000_000i64,
            "updatedAt": 1_750_000_005_000i64,
            "items": [{
                "transferId": "42",
                "path": "/tmp/report.pdf",
                "fileName": "report.pdf",
                "totalSize": 12,
                "transferredSize": 0,
                "status": "declined",
                "error": "recipient declined the transfer"
            }]
        })
    }

    #[test]
    fn parses_a_submission_as_queued_not_as_delivered() {
        let submitted = parse_share_response(&submitted_body()).expect("valid submission");
        assert_eq!(submitted.request_id, "req-0123456789abcdef");
        assert_eq!(submitted.device_id, "11112222");
        assert_eq!(submitted.kind, ShareKind::Files);
        assert_eq!(submitted.status, RequestStatus::Queued);
        assert!(!submitted.status.is_terminal());
        assert_eq!(submitted.items.len(), 1);
        let item = &submitted.items[0];
        assert_eq!(item.transfer_id.as_deref(), Some("42"));
        assert_eq!(item.total_size, 12);
        assert_eq!(item.transferred_size, 0);
        assert_eq!(item.status, TransferStatus::Queued);
        assert!(!item.status.is_terminal());
        assert_eq!(item.error, None);
    }

    #[test]
    fn an_item_that_was_never_sent_parses_with_a_null_transfer_id() {
        // The daemon answers `"transferId": null` for a path it could not prepare. Treating that
        // as an incompatible schema would turn a truthful partial-failure report into
        // "the daemon is too old", so the whole answer must stay parseable.
        let mut body = submitted_body();
        body["items"][0]["transferId"] = serde_json::Value::Null;
        body["items"][0]["status"] = serde_json::json!("failed");
        body["items"][0]["error"] = serde_json::json!("no readable file at /tmp/adir");
        body["status"] = serde_json::json!("failed");

        let submitted = parse_share_response(&body).expect("a failed item is still a valid answer");
        assert_eq!(submitted.status, RequestStatus::Failed);
        assert_eq!(submitted.items[0].transfer_id, None);
        assert_eq!(submitted.items[0].status, TransferStatus::Failed);
        assert_eq!(
            submitted.items[0].error.as_deref(),
            Some("no readable file at /tmp/adir")
        );
    }

    #[test]
    fn text_items_keep_their_null_path_and_zero_sizes() {
        let submitted = parse_share_response(&serde_json::json!({
            "ok": true,
            "requestId": "req-0000000000000001",
            "deviceId": "11112222",
            "kind": "text",
            "status": "queued",
            "items": [{
                "transferId": "7",
                "path": null,
                "fileName": null,
                "totalSize": 0,
                "transferredSize": 0,
                "status": "awaiting",
                "error": null
            }]
        }))
        .expect("valid text submission");
        assert_eq!(submitted.kind, ShareKind::Text);
        let item = &submitted.items[0];
        assert_eq!(item.path, None);
        assert_eq!(item.file_name, None);
        assert_eq!(item.status, TransferStatus::Awaiting);
        // The CLI re-emits the same stable shape, nulls included.
        assert_eq!(
            serde_json::to_string(item).expect("serializable"),
            r#"{"transferId":"7","path":null,"fileName":null,"totalSize":0,"transferredSize":0,"status":"awaiting","error":null}"#
        );
    }

    #[test]
    fn parses_a_transfers_record_with_its_timestamps() {
        let request = parse_transfers_one(&serde_json::json!({
            "ok": true,
            "request": transfers_record("req-a")
        }))
        .expect("valid record");
        assert_eq!(request.request_id, "req-a");
        assert_eq!(request.status, RequestStatus::Declined);
        assert!(request.status.is_terminal());
        assert_eq!(request.created_at, 1_750_000_000_000);
        assert_eq!(request.updated_at, 1_750_000_005_000);
        assert_eq!(
            request.items[0].error.as_deref(),
            Some("recipient declined the transfer")
        );
    }

    #[test]
    fn parses_a_transfers_list_in_daemon_order() {
        let requests = parse_transfers_list(&serde_json::json!({
            "ok": true,
            "requests": [transfers_record("req-new"), transfers_record("req-old")]
        }))
        .expect("valid list");
        let ids: Vec<&str> = requests
            .iter()
            .map(|request| request.request_id.as_str())
            .collect();
        assert_eq!(ids, vec!["req-new", "req-old"]);
    }

    #[test]
    fn an_empty_registry_is_an_empty_list() {
        let requests =
            parse_transfers_list(&serde_json::json!({"ok": true, "requests": []})).expect("empty");
        assert!(requests.is_empty());
    }

    #[test]
    fn a_daemon_too_old_to_report_transfers_is_never_an_empty_success() {
        // No `requests` key at all: a daemon from before the registry existed.
        let error = parse_transfers_list(&serde_json::json!({"ok": true})).expect_err("too old");
        assert_eq!(error.code, ErrorCode::DaemonUnsupported);
        assert_eq!(error.code.exit_code(), 3);

        // An unknown item status must not be read as "queued".
        let mut body = serde_json::json!({
            "ok": true,
            "request": transfers_record("req-a")
        });
        body["request"]["items"][0]["status"] = serde_json::json!("teleported");
        let error = parse_transfers_one(&body).expect_err("unknown status");
        assert_eq!(error.code, ErrorCode::DaemonUnsupported);

        // A submission without its ids is not a success either.
        let mut submitted = submitted_body();
        submitted
            .as_object_mut()
            .expect("object")
            .remove("requestId");
        let error = parse_share_response(&submitted).expect_err("no request id");
        assert_eq!(error.code, ErrorCode::DaemonUnsupported);
    }

    #[test]
    fn a_non_object_or_not_ok_answer_is_a_response_error() {
        for value in [serde_json::json!([1, 2]), serde_json::json!("queued")] {
            let error = parse_transfers_list(&value).expect_err("not an object");
            assert_eq!(error.code, ErrorCode::DaemonResponseInvalid);
        }
        let error = parse_share_response(&serde_json::json!({"ok": false, "error": "nope"}))
            .expect_err("not ok");
        assert_eq!(error.code, ErrorCode::DaemonResponseInvalid);
    }

    #[test]
    fn terminal_statuses_are_exactly_the_three_documented_ones() {
        for status in [
            TransferStatus::Completed,
            TransferStatus::Declined,
            TransferStatus::Failed,
        ] {
            assert!(status.is_terminal(), "{}", status.as_str());
        }
        for status in [
            TransferStatus::Queued,
            TransferStatus::Awaiting,
            TransferStatus::Transferring,
        ] {
            assert!(!status.is_terminal(), "{}", status.as_str());
        }
    }
}
