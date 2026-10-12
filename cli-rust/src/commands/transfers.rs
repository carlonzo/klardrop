//! `klardrop transfers` — what the daemon still knows about share requests.
//!
//! Without `--id` this is the daemon's recent-request registry, newest first.
//! With `--id` it is exactly one request. A request the daemon no longer knows
//! is `transfer_unknown` — never a synthesized "it never happened" and never
//! a delivery verdict.

use serde::Serialize;

use crate::cli::{Cli, Command};
use crate::commands::{connect, timeout_of, CommandOutcome, TRANSFERS_PATH};
use crate::envelope::{CliError, CliResult, ErrorCode, Output, SCHEMA_VERSION};
use crate::state::{parse_transfers_list, parse_transfers_one, ShareRequest};

#[derive(Serialize)]
struct TransfersListEnvelope<'a> {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    ok: bool,
    command: &'a str,
    requests: &'a [ShareRequest],
}

#[derive(Serialize)]
struct TransfersOneEnvelope<'a> {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    ok: bool,
    command: &'a str,
    request: &'a ShareRequest,
}

pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<CommandOutcome> {
    let Command::Transfers { id, .. } = command else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "transfers::run called for another subcommand",
        ));
    };
    let timeout = timeout_of(cli)?;
    let session = connect(cli, out, timeout)?;

    match id {
        None => {
            let Some(value) = session.client.get_json_optional(TRANSFERS_PATH)? else {
                return Err(CliError::new(
                    ErrorCode::DaemonUnsupported,
                    "the daemon does not implement GET /transfers; it is too old — upgrade it",
                ));
            };
            let requests = parse_transfers_list(&value)?;
            if out.is_json() {
                out.print_json(&TransfersListEnvelope {
                    schema_version: SCHEMA_VERSION,
                    ok: true,
                    command: "transfers",
                    requests: &requests,
                })?;
            } else {
                out.print_line(&format!("Recent requests: {}", requests.len()));
                if requests.is_empty() {
                    out.print_line("The daemon has no share requests on record.");
                }
                for request in &requests {
                    out.print_line(&describe(request));
                }
            }
        }
        Some(id) => {
            let path = format!("{TRANSFERS_PATH}?id={id}");
            let Some(value) = session.client.get_json_optional(&path)? else {
                return Err(CliError::new(
                    ErrorCode::TransferUnknown,
                    format!(
                        "the daemon no longer knows request {id} — it was evicted from the \
                         recent-request list or the daemon restarted. That is not a delivery \
                         result: run `klardrop transfers` to see what it still knows, and \
                         check the recipient directly."
                    ),
                ));
            };
            let request = parse_transfers_one(&value)?;
            if out.is_json() {
                out.print_json(&TransfersOneEnvelope {
                    schema_version: SCHEMA_VERSION,
                    ok: true,
                    command: "transfers",
                    request: &request,
                })?;
            } else {
                out.print_line(&describe(&request));
                for item in &request.items {
                    out.print_line(&format!("  {}", describe_item(item)));
                }
            }
        }
    }
    Ok(CommandOutcome::Success)
}

fn describe(request: &ShareRequest) -> String {
    format!(
        "{}  {}  {}  {}  {} item(s)",
        request.request_id,
        request.device_id,
        request.kind.as_str(),
        request.status.as_str(),
        request.items.len()
    )
}

fn describe_item(item: &crate::state::ShareItem) -> String {
    let name = item.file_name.as_deref().unwrap_or("(no name)");
    match item.error.as_deref() {
        Some(reason) => format!("{name}: {} ({reason})", item.status.as_str()),
        None => format!("{name}: {}", item.status.as_str()),
    }
}
