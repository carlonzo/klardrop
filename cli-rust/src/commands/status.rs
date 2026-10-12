//! `klardrop status` — `GET /capabilities` (optional) plus `GET /state`.

use serde::Serialize;

use crate::cli::{Cli, Command};
use crate::commands::{connect, daemon_info, fetch_state, timeout_of};
use crate::envelope::{CliError, CliResult, ErrorCode, Output};
use crate::state::{DaemonInfo, Protocols, SelfDevice, Settings, UpdateInfo};

#[derive(Serialize)]
struct StatusEnvelope<'a> {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    ok: bool,
    command: &'a str,
    daemon: &'a DaemonInfo,
    #[serde(rename = "self")]
    self_device: &'a SelfDevice,
    protocols: &'a Protocols,
    settings: &'a Settings,
    #[serde(rename = "deviceCount")]
    device_count: usize,
    #[serde(rename = "pairedCount")]
    paired_count: usize,
    #[serde(rename = "reachableCount")]
    reachable_count: usize,
    #[serde(rename = "activeTransfers")]
    active_transfers: usize,
    update: &'a UpdateInfo,
}

pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<()> {
    let Command::Status { .. } = command else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "status::run called for another subcommand",
        ));
    };
    let timeout = timeout_of(cli)?;
    let session = connect(cli, out, timeout)?;
    let daemon = daemon_info(&session, out);
    let snapshot = fetch_state(&session)?;

    let device_count = snapshot.devices().len();
    let paired_count = snapshot.paired_count();
    let reachable_count = snapshot.reachable_count();
    let active_transfers = snapshot.transfers.len();

    if out.is_json() {
        out.print_json(&StatusEnvelope {
            schema_version: crate::envelope::SCHEMA_VERSION,
            ok: true,
            command: "status",
            daemon: &daemon,
            self_device: &snapshot.self_device,
            protocols: &snapshot.protocols,
            settings: &snapshot.settings,
            device_count,
            paired_count,
            reachable_count,
            active_transfers,
            update: &snapshot.update,
        })?;
    } else {
        let version = daemon.version.as_deref().unwrap_or("unknown");
        let api_version = daemon
            .api_version
            .map_or_else(|| "unknown".to_string(), |v| v.to_string());
        out.print_line(&format!("Daemon:    {version} (api {api_version})"));
        out.print_line(&format!(
            "Device:    {}  [{} / {}]  id={}",
            snapshot.self_device.device_name,
            snapshot.self_device.device_type,
            snapshot.self_device.os_type,
            snapshot.self_device.device_id
        ));
        out.print_line(&format!(
            "Protocols: klardrop={} nearby={} ble={}",
            yes_no(snapshot.protocols.klardrop),
            yes_no(snapshot.protocols.nearby),
            yes_no(snapshot.protocols.ble),
        ));
        out.print_line(&format!(
            "Discovery: background={} supported={}",
            yes_no(snapshot.settings.background_discovery_enabled),
            yes_no(snapshot.settings.supports_background_discovery),
        ));
        out.print_line(&format!(
            "Devices:   {device_count} visible, {paired_count} paired, {reachable_count} reachable"
        ));
        out.print_line(&format!("Transfers: {active_transfers} active"));
        out.print_line(&format!(
            "Update:    {} (supported: {})",
            snapshot.update.status,
            yes_no(snapshot.update.supported)
        ));
    }
    Ok(())
}

fn yes_no(value: bool) -> &'static str {
    if value {
        "yes"
    } else {
        "no"
    }
}
