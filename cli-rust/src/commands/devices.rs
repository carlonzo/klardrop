//! `klardrop devices` — one bounded `GET /state`, devices with pairing and
//! reachability.

use serde::Serialize;

use crate::cli::{Cli, Command};
use crate::commands::{connect, daemon_info, fetch_state, timeout_of};
use crate::envelope::{CliError, CliResult, ErrorCode, Output};
use crate::state::{DaemonInfo, DeviceSummary};

#[derive(Serialize)]
struct DevicesEnvelope<'a> {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    ok: bool,
    command: &'a str,
    daemon: &'a DaemonInfo,
    devices: Vec<DeviceSummary>,
}

pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<()> {
    let Command::Devices { .. } = command else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "devices::run called for another subcommand",
        ));
    };
    let timeout = timeout_of(cli)?;
    let session = connect(cli, out, timeout)?;
    let daemon = daemon_info(&session, out);
    let snapshot = fetch_state(&session)?;
    let devices = snapshot.devices();

    if out.is_json() {
        out.print_json(&DevicesEnvelope {
            schema_version: crate::envelope::SCHEMA_VERSION,
            ok: true,
            command: "devices",
            daemon: &daemon,
            devices,
        })?;
    } else {
        out.print_line(&format!("Devices: {}", devices.len()));
        if devices.is_empty() {
            out.print_line("No devices currently visible.");
        }
        for device in &devices {
            out.print_line(&describe(device));
        }
    }
    Ok(())
}

fn describe(device: &DeviceSummary) -> String {
    let mut line = format!(
        "- {}  {}  [{}]  {}  {}",
        device.device_id,
        device.device_name,
        device.device_type,
        if device.paired { "paired" } else { "unpaired" },
        device.reachability,
    );
    if !device.connection_types.is_empty() {
        line.push_str(&format!("  via {}", device.connection_types.join(", ")));
    }
    if device.unread_count > 0 {
        line.push_str(&format!("  unread={}", device.unread_count));
    }
    line
}
