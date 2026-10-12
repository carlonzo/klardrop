//! Command implementations and the helpers they share.

pub mod daemon;
pub mod devices;
pub mod discover;
pub mod interactive;
pub mod send;
pub mod share;
pub mod status;
pub mod transfers;

use std::time::{Duration, Instant};

use crate::cli::Cli;
use crate::client::{Client, DebugLog};
use crate::control_file;
use crate::envelope::{CliError, CliResult, ErrorCode, Output};
use crate::state::{parse_capabilities, DaemonInfo, DeviceSummary, StateSnapshot};

/// Default whole-command deadline for `devices` and `status`.
pub const DEFAULT_TIMEOUT_SECONDS: f64 = 15.0;
/// Default whole-command deadline for `discover` (wait window + slack).
pub const DEFAULT_DISCOVER_TIMEOUT_SECONDS: f64 = 20.0;
/// Default discovery window, preserving the previous CLI's `discover` default.
pub const DEFAULT_DISCOVER_WAIT_SECONDS: f64 = 5.0;
/// Upper bound accepted for `--timeout` and `--wait`, so no command can be
/// turned into an unbounded wait.
pub const MAX_SECONDS: f64 = 3600.0;

pub const STATE_PATH: &str = "/state";
pub const CAPABILITIES_PATH: &str = "/capabilities";

/// Default whole-command deadline for `share`, `send` and `share --wait`.
pub const DEFAULT_SHARE_TIMEOUT_SECONDS: f64 = 120.0;
/// How often `--wait` re-reads the request from `GET /transfers`.
pub const POLL_INTERVAL: Duration = Duration::from_millis(250);

pub const SHARE_PATH: &str = "/share";
pub const TRANSFERS_PATH: &str = "/transfers";

// Routes the interactive TUI drives. Every one of them already exists on the
// daemon; nothing here is a Klardrop invention.
pub const HISTORY_PATH: &str = "/history";
pub const HISTORY_READ_PATH: &str = "/history/read";
pub const ACCEPT_PAIR_PATH: &str = "/accept-pair";
pub const REJECT_PAIR_PATH: &str = "/reject-pair";
pub const ACCEPT_INCOMING_PATH: &str = "/accept-incoming";
pub const REJECT_INCOMING_PATH: &str = "/reject-incoming";
pub const RENAME_DEVICE_PATH: &str = "/rename-device";
pub const SETTINGS_PATH: &str = "/settings";
pub const QR_SHARE_PATH: &str = "/qr-share";
pub const UPDATE_CHECK_PATH: &str = "/update/check";
pub const UPDATE_APPLY_PATH: &str = "/update/apply";

/// What a command decided. `main` turns this into the process exit code, so a
/// command that already printed a richer envelope never has to re-derive one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandOutcome {
    /// The command's promise is kept: queued where queued is the answer,
    /// every item confirmed delivered where waiting was requested.
    Success,
    /// The command failed, with the exit code its error code maps to: a decline
    /// is 1, a forgotten request is 3, an undecided delivery state is 4.
    Failure(i32),
}

impl CommandOutcome {
    pub fn failed(error: &CliError) -> Self {
        Self::Failure(error.code.exit_code())
    }

    pub fn exit_code(self) -> i32 {
        match self {
            Self::Success => 0,
            Self::Failure(code) => code,
        }
    }
}

/// Picks exactly one device for an explicit command: an exact `deviceId`
/// first, then an unambiguous prefix. A display name is never a match — two
/// devices may share one — and an ambiguous prefix is never resolved by
/// taking the first candidate.
pub fn select_device<'a>(
    devices: &'a [DeviceSummary],
    wanted: &str,
) -> CliResult<&'a DeviceSummary> {
    if wanted.trim().is_empty() {
        return Err(CliError::new(
            ErrorCode::InvalidArgument,
            "the target device id must not be empty; pass --to <device-id>",
        ));
    }
    if let Some(device) = devices.iter().find(|device| device.device_id == wanted) {
        return Ok(device);
    }
    let candidates: Vec<&DeviceSummary> = devices
        .iter()
        .filter(|device| device.device_id.starts_with(wanted))
        .collect();
    match candidates.as_slice() {
        [only] => Ok(only),
        [] => Err(CliError::new(
            ErrorCode::DeviceNotFound,
            format!(
                "no device matches {wanted:?}. Visible device ids: {}",
                visible_ids(devices)
            ),
        )),
        _ => Err(CliError::new(
            ErrorCode::AmbiguousDevice,
            format!(
                "{wanted:?} matches {} devices; use a longer prefix or the full id. Candidates: {}",
                candidates.len(),
                candidates
                    .iter()
                    .map(|device| device.device_id.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
        )),
    }
}

fn visible_ids(devices: &[DeviceSummary]) -> String {
    if devices.is_empty() {
        return "none visible right now".to_string();
    }
    devices
        .iter()
        .map(|device| device.device_id.as_str())
        .collect::<Vec<_>>()
        .join(", ")
}

/// Resolves the `--to`/device argument against the daemon's current view.
pub fn resolve_device(session: &Session, wanted: &str) -> CliResult<DeviceSummary> {
    let snapshot = fetch_state(session)?;
    select_device(&snapshot.devices(), wanted).cloned()
}

/// A validated control file plus a client whose every operation is bounded by
/// the command deadline.
pub struct Session {
    pub client: Client,
    pub deadline: Instant,
}

/// Validates a duration flag. Rejects non-numeric, zero, negative and
/// non-finite values before any I/O happens.
pub fn parse_seconds(raw: Option<&String>, default: f64, flag: &str) -> CliResult<f64> {
    let invalid = |value: &str| -> CliError {
        CliError::new(
            ErrorCode::InvalidArgument,
            format!("{flag} must be a finite number of seconds greater than 0, got {value:?}"),
        )
    };

    let Some(raw) = raw else {
        return Ok(default);
    };
    let value: f64 = raw.trim().parse().map_err(|_| invalid(raw))?;
    if !value.is_finite() || value <= 0.0 {
        return Err(invalid(raw));
    }
    if value > MAX_SECONDS {
        return Err(CliError::new(
            ErrorCode::InvalidArgument,
            format!("{flag} must not exceed {MAX_SECONDS} seconds, got {value}"),
        ));
    }
    Ok(value)
}

/// Resolves and validates the control file, then opens a deadline-bounded client.
pub fn connect(cli: &Cli, out: &Output, timeout_seconds: f64) -> CliResult<Session> {
    let control = control_file::load(cli.control_file.as_deref())?;
    out.debug_log(&format!(
        "control file {} -> {}",
        control.path.display(),
        control.authority()
    ));
    let deadline = Instant::now() + Duration::from_secs_f64(timeout_seconds);
    let client = Client::new(
        control.port,
        &control.token,
        deadline,
        DebugLog::stderr_if(cli.debug),
    )?;
    Ok(Session { client, deadline })
}

/// Reads `GET /capabilities`. A daemon that does not implement the route (or
/// answers something unparseable there) yields unknown metadata instead of an
/// error — `/state` is the authority for the commands themselves.
pub fn daemon_info(session: &Session, out: &Output) -> DaemonInfo {
    match session.client.get_json_optional(CAPABILITIES_PATH) {
        Ok(Some(value)) => match parse_capabilities(&value) {
            Ok(info) => info,
            Err(error) => {
                out.debug_log(&format!(
                    "ignoring unusable /capabilities: {}",
                    error.message
                ));
                DaemonInfo::default()
            }
        },
        Ok(None) => {
            out.debug_log("daemon does not implement /capabilities (too old); reporting unknown");
            DaemonInfo::default()
        }
        Err(error) => {
            out.debug_log(&format!(
                "ignoring /capabilities failure ({}: {})",
                error.code.as_str(),
                error.message
            ));
            DaemonInfo::default()
        }
    }
}

/// Fetches and parses `GET /state`.
pub fn fetch_state(session: &Session) -> CliResult<StateSnapshot> {
    let value = session.client.get_json(STATE_PATH)?;
    crate::state::parse_state(&value)
}

/// Extracts the `--timeout` value of the current command.
pub fn timeout_of(cli: &Cli) -> CliResult<f64> {
    let (raw, default) = match &cli.command {
        Some(crate::cli::Command::Devices { timeout, .. })
        | Some(crate::cli::Command::Status { timeout, .. }) => (timeout, DEFAULT_TIMEOUT_SECONDS),
        Some(crate::cli::Command::Discover { timeout, .. }) => {
            (timeout, DEFAULT_DISCOVER_TIMEOUT_SECONDS)
        }
        Some(crate::cli::Command::Share { timeout, .. })
        | Some(crate::cli::Command::Send { timeout, .. }) => {
            (timeout, DEFAULT_SHARE_TIMEOUT_SECONDS)
        }
        Some(crate::cli::Command::Transfers { timeout, .. }) => (timeout, DEFAULT_TIMEOUT_SECONDS),
        // The TUI's `--timeout` bounds the initial connection only; the session
        // itself is bounded by its own per-request deadlines.
        Some(crate::cli::Command::Interactive { timeout, .. }) => {
            (timeout, DEFAULT_TIMEOUT_SECONDS)
        }
        // A forwarded engine owns its own lifetime; there is no client-side
        // deadline to compute for it.
        Some(crate::cli::Command::Daemon { .. }) => {
            return Err(CliError::new(
                ErrorCode::InternalError,
                "`klardrop daemon` forwards to the engine and has no --timeout",
            ))
        }
        None => {
            return Err(CliError::new(
                ErrorCode::InternalError,
                "no subcommand selected",
            ))
        }
    };
    parse_seconds(raw.as_ref(), default, "--timeout")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn arg(value: &str) -> Option<String> {
        Some(value.to_string())
    }

    #[test]
    fn accepts_positive_finite_seconds() {
        assert_eq!(parse_seconds(None, 15.0, "--timeout").unwrap(), 15.0);
        assert_eq!(
            parse_seconds(arg("1").as_ref(), 15.0, "--timeout").unwrap(),
            1.0
        );
        assert_eq!(
            parse_seconds(arg("0.5").as_ref(), 15.0, "--timeout").unwrap(),
            0.5
        );
        assert_eq!(
            parse_seconds(arg(" 2.5 ").as_ref(), 15.0, "--timeout").unwrap(),
            2.5
        );
        assert_eq!(
            parse_seconds(arg(&MAX_SECONDS.to_string()).as_ref(), 15.0, "--timeout").unwrap(),
            MAX_SECONDS
        );
    }

    #[test]
    fn rejects_invalid_seconds_before_any_request() {
        for value in [
            "0", "-1", "-0.5", "abc", "", " ", "1e400", "inf", "-inf", "NaN", "3601", "1,5",
        ] {
            let error = parse_seconds(arg(value).as_ref(), 15.0, "--timeout")
                .expect_err(&format!("{value} must be rejected"));
            assert_eq!(error.code, ErrorCode::InvalidArgument, "value {value:?}");
            assert_eq!(error.code.exit_code(), 2);
        }
    }

    // ------------------------------------------------------- device picking

    fn devices(ids: &[&str]) -> Vec<DeviceSummary> {
        ids.iter()
            .map(|id| DeviceSummary {
                device_id: (*id).to_string(),
                device_name: format!("Device {id}"),
                device_type: "ANDROID".to_string(),
                paired: true,
                reachable: true,
                trust_status: "trusted".to_string(),
                reachability: "reachable".to_string(),
                connection_types: vec!["KLARDROP".to_string()],
                has_unread: false,
                unread_count: 0,
            })
            .collect()
    }

    #[test]
    fn an_exact_id_wins_over_a_longer_one() {
        let devices = devices(&["1111", "11112222"]);
        assert_eq!(select_device(&devices, "1111").unwrap().device_id, "1111");
    }

    #[test]
    fn a_unique_prefix_resolves_to_its_full_id() {
        let devices = devices(&["11112222", "33334444"]);
        assert_eq!(
            select_device(&devices, "3333").unwrap().device_id,
            "33334444"
        );
    }

    #[test]
    fn an_ambiguous_prefix_lists_candidates_and_never_picks_one() {
        let devices = devices(&["1111aaaa", "1111bbbb", "2222cccc"]);
        let error = select_device(&devices, "1111").expect_err("ambiguous");
        assert_eq!(error.code, ErrorCode::AmbiguousDevice);
        assert_eq!(error.code.exit_code(), 2);
        assert!(
            error.message.contains("1111aaaa") && error.message.contains("1111bbbb"),
            "candidates must be named: {}",
            error.message
        );
    }

    #[test]
    fn an_unknown_id_lists_what_is_visible() {
        let error = select_device(&devices(&["11112222"]), "9999").expect_err("unknown");
        assert_eq!(error.code, ErrorCode::DeviceNotFound);
        assert_eq!(error.code.exit_code(), 2);
        assert!(
            error.message.contains("11112222"),
            "visible ids must be listed: {}",
            error.message
        );

        let error = select_device(&[], "9999").expect_err("no devices at all");
        assert_eq!(error.code, ErrorCode::DeviceNotFound);
        assert!(
            error.message.contains("none visible"),
            "an empty view must be stated: {}",
            error.message
        );
    }

    #[test]
    fn an_empty_target_is_an_argument_error() {
        let error = select_device(&devices(&["11112222"]), "  ").expect_err("empty");
        assert_eq!(error.code, ErrorCode::InvalidArgument);
    }

    #[test]
    fn a_display_name_is_never_a_match() {
        let mut devices = devices(&["11112222"]);
        devices[0].device_name = "Pixel".to_string();
        let error = select_device(&devices, "Pixel").expect_err("names are not ids");
        assert_eq!(error.code, ErrorCode::DeviceNotFound);
    }
}
