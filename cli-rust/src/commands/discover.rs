//! `klardrop discover` — polls `GET /state` until at least one device is
//! visible or the wait window expires.
//!
//! With `--json` this keeps the legacy array shape (a bare JSON array, not the
//! versioned envelope) so existing consumers keep parsing. See the crate
//! README for exactly which legacy fields are gone and why.

use std::time::{Duration, Instant};

use serde::Serialize;

use crate::cli::{Cli, Command};
use crate::commands::{
    connect, fetch_state, parse_seconds, timeout_of, DEFAULT_DISCOVER_WAIT_SECONDS,
};
use crate::envelope::{CliError, CliResult, ErrorCode, Output};
use crate::state::DeviceSummary;

/// Interval between `GET /state` polls; bounded so a wide `--wait` still polls
/// often enough to notice a device appearing.
const POLL_INTERVAL: Duration = Duration::from_millis(250);

/// The legacy device array. `os_type` and `connections` are intentionally
/// absent: the control API exposes neither.
#[derive(Serialize)]
struct LegacyDevice<'a> {
    device_id: &'a str,
    name: &'a str,
    device_type: &'a str,
}

pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<()> {
    let Command::Discover { wait, .. } = command else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "discover::run called for another subcommand",
        ));
    };

    // Validate every argument before touching the network.
    let wait_seconds = parse_seconds(wait.as_ref(), DEFAULT_DISCOVER_WAIT_SECONDS, "--wait")?;
    let timeout_seconds = timeout_of(cli)?;
    let session = connect(cli, out, timeout_seconds)?;
    let wait_until = min_deadline(session.deadline, wait_seconds);

    out.note(&format!(
        "Discovering nearby devices for up to {wait_seconds}s..."
    ));

    let devices: Vec<DeviceSummary> = loop {
        let devices = fetch_state(&session)?.devices();
        let now = Instant::now();
        if !devices.is_empty() || now >= wait_until {
            break devices;
        }
        // Ctrl-C ends the wait at the next poll boundary rather than being
        // swallowed: `--wait` runs up to an hour, and the handler keeps this
        // process alive precisely so that an unattended command can stop
        // waiting when a person asks it to. Nothing has been sent, so there is
        // no delivery state to report — a plain `cancelled` says it all.
        if crate::interrupt::was_interrupted() {
            return Err(CliError::new(
                ErrorCode::Cancelled,
                "interrupted while waiting for devices; nothing was sent",
            ));
        }
        crate::interrupt::sleep_interruptibly(POLL_INTERVAL.min(wait_until - now));
    };

    if out.is_json() {
        let legacy: Vec<LegacyDevice<'_>> = devices
            .iter()
            .map(|device| LegacyDevice {
                device_id: &device.device_id,
                name: &device.device_name,
                device_type: &device.device_type,
            })
            .collect();
        out.print_json(&legacy)?;
    } else {
        // stdout, not `note`/stderr: these lines are the command's *result*.
        // The Kotlin discover command printed them to stdout, and a user
        // redirecting `klardrop discover > devices.txt` would otherwise get a
        // file containing only the summary.
        for device in &devices {
            out.print_line(&format!(
                "Discovered device: {} ({}) [{}] {}",
                device.device_id, device.device_name, device.device_type, device.reachability
            ));
        }
        if devices.is_empty() {
            out.print_line("No devices discovered");
        } else {
            out.print_line(&format!(
                "Discovery complete - found {} device(s)",
                devices.len()
            ));
        }
    }
    Ok(())
}

/// The wait window never outlives the command deadline.
fn min_deadline(deadline: Instant, wait_seconds: f64) -> Instant {
    let wait_until = Instant::now() + Duration::from_secs_f64(wait_seconds);
    if wait_until < deadline {
        wait_until
    } else {
        deadline
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wait_window_never_outlives_the_deadline() {
        let deadline = Instant::now();
        assert!(min_deadline(deadline, 5.0) <= deadline);
        let later = Instant::now() + Duration::from_secs(30);
        assert!(min_deadline(later, 1.0) < later);
    }

    #[test]
    fn legacy_device_has_exactly_the_supported_fields() {
        let device = DeviceSummary {
            device_id: "11112222".to_string(),
            device_name: "Phone".to_string(),
            device_type: "ANDROID".to_string(),
            paired: true,
            reachable: true,
            trust_status: "trusted".to_string(),
            reachability: "reachable".to_string(),
            connection_types: vec!["KLARDROP".to_string()],
            has_unread: false,
            unread_count: 0,
        };
        let legacy = LegacyDevice {
            device_id: &device.device_id,
            name: &device.device_name,
            device_type: &device.device_type,
        };
        assert_eq!(
            serde_json::to_string(&legacy).expect("serializable"),
            r#"{"device_id":"11112222","name":"Phone","device_type":"ANDROID"}"#
        );
    }
}
