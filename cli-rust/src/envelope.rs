//! Stable error codes, JSON envelope shape and the stdout/stderr discipline
//! shared by every command.
//!
//! Discipline:
//!   * with `--json`, stdout carries exactly ONE JSON value and nothing else;
//!   * every diagnostic, progress line and `--debug` log goes to stderr;
//!   * the bearer token is never printed.

use serde::Serialize;

/// Version of the CLI's own JSON contract (not the daemon's `apiVersion`).
pub const SCHEMA_VERSION: u32 = 1;

/// Stable, documented error codes. The mapping to process exit codes is part
/// of the public contract and must not drift.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    InvalidArgument,
    AmbiguousDevice,
    InvalidPath,
    DeviceNotFound,
    /// A command that needs a real terminal on both ends and does not have one.
    /// Constructed by `interactive` and by `share --pick`, both of which open an
    /// on-screen UI the user has to drive.
    TerminalRequired,
    DaemonNotRunning,
    DaemonControlInvalid,
    DaemonUnreachable,
    DaemonUnsupported,
    TransferUnknown,
    DaemonTimeout,
    DaemonProtocolError,
    DaemonResponseInvalid,
    DaemonHttpError,
    TransferFailed,
    /// A person cancelled an interactive workflow before it committed to
    /// anything — closing the `share --pick` device list without choosing.
    /// Deliberately not a plain failure: nothing was sent and nothing failed,
    /// and exit code 130 is what tells an agent the difference between "the
    /// user changed their mind" and "the transfer failed".
    Cancelled,
    InternalError,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::InvalidArgument => "invalid_argument",
            Self::AmbiguousDevice => "ambiguous_device",
            Self::InvalidPath => "invalid_path",
            Self::DeviceNotFound => "device_not_found",
            Self::TerminalRequired => "terminal_required",
            Self::DaemonNotRunning => "daemon_not_running",
            Self::DaemonControlInvalid => "daemon_control_invalid",
            Self::DaemonUnreachable => "daemon_unreachable",
            Self::DaemonUnsupported => "daemon_unsupported",
            Self::TransferUnknown => "transfer_unknown",
            Self::DaemonTimeout => "daemon_timeout",
            Self::DaemonProtocolError => "daemon_protocol_error",
            Self::DaemonResponseInvalid => "daemon_response_invalid",
            Self::DaemonHttpError => "daemon_http_error",
            Self::TransferFailed => "transfer_failed",
            Self::Cancelled => "cancelled",
            Self::InternalError => "internal_error",
        }
    }

    pub fn exit_code(self) -> i32 {
        match self {
            Self::InvalidArgument
            | Self::AmbiguousDevice
            | Self::InvalidPath
            | Self::DeviceNotFound
            | Self::TerminalRequired => 2,
            Self::DaemonNotRunning
            | Self::DaemonControlInvalid
            | Self::DaemonUnreachable
            | Self::DaemonUnsupported
            | Self::TransferUnknown => 3,
            Self::DaemonTimeout => 4,
            Self::DaemonProtocolError
            | Self::DaemonResponseInvalid
            | Self::DaemonHttpError
            | Self::TransferFailed
            | Self::InternalError => 1,
            Self::Cancelled => 130,
        }
    }
}

/// A failure with a stable code plus a human message (never containing the token).
#[derive(Debug, Clone)]
pub struct CliError {
    pub code: ErrorCode,
    pub message: String,
}

impl CliError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

pub type CliResult<T> = Result<T, CliError>;

#[derive(Serialize)]
struct ErrorBody<'a> {
    code: &'a str,
    message: &'a str,
}

#[derive(Serialize)]
struct FailureEnvelope<'a> {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    ok: bool,
    command: &'a str,
    error: ErrorBody<'a>,
}

/// Where output goes, and whether machine-readable output was requested.
pub struct Output {
    json: bool,
    debug: bool,
}

impl Output {
    pub fn new(json: bool, debug: bool) -> Self {
        Self { json, debug }
    }

    pub fn is_json(&self) -> bool {
        self.json
    }

    /// `--debug` diagnostics. Always stderr, never stdout.
    pub fn debug_log(&self, message: &str) {
        if self.debug {
            eprintln!("klardrop: debug: {message}");
        }
    }

    /// Progress / status chatter. Always stderr so `--json` stdout stays clean.
    pub fn note(&self, message: &str) {
        if !self.json {
            eprintln!("{message}");
        }
    }

    /// Transfer progress. Always stderr, even under `--json`: stdout stays one
    /// JSON value while a waiting caller can still see what is happening.
    pub fn progress(&self, message: &str) {
        eprintln!("{message}");
    }

    /// Human-facing result text. Suppressed entirely when `--json` is active.
    pub fn print_line(&self, message: &str) {
        if !self.json {
            println!("{message}");
        }
    }

    /// Writes exactly one JSON value (plus a trailing newline) to stdout.
    pub fn print_json<T: Serialize>(&self, value: &T) -> CliResult<()> {
        let encoded = serde_json::to_string(value).map_err(|e| {
            CliError::new(
                ErrorCode::InternalError,
                format!("failed to serialize output: {e}"),
            )
        })?;
        println!("{encoded}");
        Ok(())
    }

    /// Reports a failure and returns the process exit code.
    pub fn fail(&self, command: &str, error: CliError) -> i32 {
        eprintln!("klardrop: error: {}", error.message);
        if self.json {
            let envelope = FailureEnvelope {
                schema_version: SCHEMA_VERSION,
                ok: false,
                command,
                error: ErrorBody {
                    code: error.code.as_str(),
                    message: &error.message,
                },
            };
            // Serialization of this shape cannot fail; if it somehow did, fall
            // back to stderr rather than emitting a partial stdout value.
            match serde_json::to_string(&envelope) {
                Ok(encoded) => println!("{encoded}"),
                Err(e) => eprintln!("klardrop: internal error: failed to serialize error: {e}"),
            }
        }
        error.code.exit_code()
    }

    /// Reports a failure whose operation context is already known — the request
    /// id, the target device, the per-item outcomes — and prints the extended
    /// envelope:
    ///
    /// ```text
    /// {"schemaVersion":1,"ok":false,"command":…,<context keys in order>,"error":{…}}
    /// ```
    ///
    /// `context` holds already-encoded JSON fragments (see [`json_fragment`])
    /// rather than `serde_json::Value`s, because the key order of both the
    /// context and of nested items is part of the published contract and
    /// `serde_json::Map` sorts keys. Returns the exit code the error code maps
    /// to; `main` never re-derives it.
    pub fn fail_with_context(
        &self,
        command: &str,
        error: &CliError,
        context: &[(&str, String)],
    ) -> i32 {
        eprintln!("klardrop: error: {}", error.message);
        if self.json {
            println!("{}", encode_context_failure(command, error, context));
        }
        error.code.exit_code()
    }
}

/// Renders the extended failure envelope. Split out from [`Output`] so the byte
/// layout is testable without capturing a process's stdout.
fn encode_context_failure(command: &str, error: &CliError, context: &[(&str, String)]) -> String {
    let mut encoded = String::from("{\"schemaVersion\":");
    encoded.push_str(&SCHEMA_VERSION.to_string());
    encoded.push_str(",\"ok\":false,\"command\":");
    push_json_string(&mut encoded, command);
    for (key, value) in context {
        encoded.push(',');
        push_json_string(&mut encoded, key);
        encoded.push(':');
        encoded.push_str(value);
    }
    encoded.push_str(",\"error\":{\"code\":");
    push_json_string(&mut encoded, error.code.as_str());
    encoded.push_str(",\"message\":");
    push_json_string(&mut encoded, &error.message);
    encoded.push_str("}}");
    encoded
}

/// Encodes one context value. Structs keep their declared field order here,
/// where routing through a `serde_json::Value` would sort the keys and break
/// the published layout.
pub fn json_fragment<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "null".to_string())
}

/// Appends `value` to `out` as a quoted, escaped JSON string. Infallible:
/// rendering a string as JSON is quoting and escaping it.
fn push_json_string(out: &mut String, value: &str) {
    out.push_str(&serde_json::Value::String(value.to_string()).to_string());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_codes_map_to_documented_exit_codes() {
        for (code, expected) in [
            (ErrorCode::InvalidArgument, 2),
            (ErrorCode::AmbiguousDevice, 2),
            (ErrorCode::InvalidPath, 2),
            (ErrorCode::DeviceNotFound, 2),
            (ErrorCode::TerminalRequired, 2),
            (ErrorCode::DaemonNotRunning, 3),
            (ErrorCode::DaemonControlInvalid, 3),
            (ErrorCode::DaemonUnreachable, 3),
            (ErrorCode::DaemonUnsupported, 3),
            (ErrorCode::TransferUnknown, 3),
            (ErrorCode::DaemonTimeout, 4),
            (ErrorCode::DaemonProtocolError, 1),
            (ErrorCode::DaemonResponseInvalid, 1),
            (ErrorCode::DaemonHttpError, 1),
            (ErrorCode::TransferFailed, 1),
            (ErrorCode::Cancelled, 130),
            (ErrorCode::InternalError, 1),
        ] {
            assert_eq!(code.exit_code(), expected, "{}", code.as_str());
        }
    }

    /// Pins the published code table byte for byte: automation matches on these
    /// strings, so renaming one is a contract break.
    #[test]
    fn error_code_table_is_byte_exact() {
        let table: Vec<(&str, i32)> = [
            ErrorCode::InvalidArgument,
            ErrorCode::AmbiguousDevice,
            ErrorCode::InvalidPath,
            ErrorCode::DeviceNotFound,
            ErrorCode::TerminalRequired,
            ErrorCode::DaemonNotRunning,
            ErrorCode::DaemonControlInvalid,
            ErrorCode::DaemonUnreachable,
            ErrorCode::DaemonUnsupported,
            ErrorCode::TransferUnknown,
            ErrorCode::DaemonTimeout,
            ErrorCode::DaemonProtocolError,
            ErrorCode::DaemonResponseInvalid,
            ErrorCode::DaemonHttpError,
            ErrorCode::TransferFailed,
            ErrorCode::Cancelled,
            ErrorCode::InternalError,
        ]
        .into_iter()
        .map(|code| (code.as_str(), code.exit_code()))
        .collect();
        assert_eq!(
            table,
            vec![
                ("invalid_argument", 2),
                ("ambiguous_device", 2),
                ("invalid_path", 2),
                ("device_not_found", 2),
                ("terminal_required", 2),
                ("daemon_not_running", 3),
                ("daemon_control_invalid", 3),
                ("daemon_unreachable", 3),
                ("daemon_unsupported", 3),
                ("transfer_unknown", 3),
                ("daemon_timeout", 4),
                ("daemon_protocol_error", 1),
                ("daemon_response_invalid", 1),
                ("daemon_http_error", 1),
                ("transfer_failed", 1),
                ("cancelled", 130),
                ("internal_error", 1),
            ]
        );
    }

    #[test]
    fn failure_envelope_shape_is_stable() {
        let envelope = FailureEnvelope {
            schema_version: SCHEMA_VERSION,
            ok: false,
            command: "status",
            error: ErrorBody {
                code: "daemon_not_running",
                message: "no daemon",
            },
        };
        let encoded = serde_json::to_string(&envelope).expect("serializable");
        assert_eq!(
            encoded,
            r#"{"schemaVersion":1,"ok":false,"command":"status","error":{"code":"daemon_not_running","message":"no daemon"}}"#
        );
    }

    /// Context keys keep the order the caller supplies them — never
    /// alphabetical — nested item fields keep their declared order, and
    /// `error` is always last.
    #[test]
    fn context_failure_envelope_layout_is_byte_exact() {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct Item {
            transfer_id: String,
            path: Option<String>,
            file_name: Option<String>,
            total_size: u64,
            transferred_size: u64,
            status: &'static str,
            error: Option<String>,
        }
        let item = Item {
            transfer_id: "42".to_string(),
            path: Some("/tmp/report.pdf".to_string()),
            file_name: Some("report.pdf".to_string()),
            total_size: 12,
            transferred_size: 0,
            status: "declined",
            error: Some("recipient declined the transfer".to_string()),
        };
        let error = CliError::new(ErrorCode::TransferFailed, "recipient declined the transfer");
        let context = [
            ("requestId", json_fragment(&"req-0123456789abcdef")),
            ("deviceId", json_fragment(&"11112222")),
            ("status", json_fragment(&"declined")),
            ("items", json_fragment(&[item])),
        ];
        assert_eq!(
            encode_context_failure("share", &error, &context),
            concat!(
                r#"{"schemaVersion":1,"ok":false,"command":"share","#,
                r#""requestId":"req-0123456789abcdef","deviceId":"11112222","#,
                r#""status":"declined","items":[{"transferId":"42","path":"/tmp/report.pdf","#,
                r#""fileName":"report.pdf","totalSize":12,"transferredSize":0,"status":"declined","#,
                r#""error":"recipient declined the transfer"}],"#,
                r#""error":{"code":"transfer_failed","message":"recipient declined the transfer"}}"#,
            )
        );
    }

    #[test]
    fn context_values_are_escaped_not_injected() {
        let error = CliError::new(ErrorCode::TransferFailed, "line\nbreak \"quoted\" \\ tail");
        let encoded =
            encode_context_failure("send", &error, &[("status", json_fragment(&"failed"))]);
        assert_eq!(
            encoded,
            r#"{"schemaVersion":1,"ok":false,"command":"send","status":"failed","error":{"code":"transfer_failed","message":"line\nbreak \"quoted\" \\ tail"}}"#
        );
        assert!(
            serde_json::from_str::<serde_json::Value>(&encoded).is_ok(),
            "an odd device name or file name must never break the JSON: {encoded}"
        );
    }
}
