//! `klardrop share` — submit one payload to one device and report exactly what
//! the daemon knows about it.
//!
//! The rules this command exists to enforce:
//!   * files, text and clipboard are alternatives; a mixture is refused before
//!     anything is sent;
//!   * every path is checked locally first, so an unreadable file is an error
//!     here and not a silently skipped item over there;
//!   * without `--wait` the answer is `queued`, never "delivered";
//!   * with `--wait` only a confirmed terminal outcome for *every* item is a
//!     success, and the wait follows this request's own ids — never another
//!     operation's;
//!   * a deadline that expires mid-wait leaves delivery UNKNOWN: the transfer
//!     is neither cancelled nor retried.

use std::fs;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::json;

use crate::cli::{Cli, Command};
use crate::commands::{
    connect, parse_seconds, resolve_device, timeout_of, CommandOutcome, Session, POLL_INTERVAL,
    SHARE_PATH, TRANSFERS_PATH,
};
use crate::envelope::{json_fragment, CliError, CliResult, ErrorCode, Output, SCHEMA_VERSION};
use crate::state::{
    parse_share_response, parse_transfers_one, RequestStatus, ShareItem, SubmittedShare,
    TransferStatus,
};

/// Exactly one payload kind per request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Payload {
    Files(Vec<PathBuf>),
    Text(String),
    Clipboard,
}

/// A fully validated invocation: one target, one payload kind.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShareSpec {
    pub to: String,
    pub payload: Payload,
}

#[derive(Serialize)]
struct ShareEnvelope<'a> {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    ok: bool,
    command: &'a str,
    #[serde(rename = "requestId")]
    request_id: &'a str,
    #[serde(rename = "deviceId")]
    device_id: &'a str,
    status: &'a str,
    items: &'a [ShareItem],
}

/// The context every `share`/`send` failure carries, in the published key
/// order: what was asked, to whom, how far it got, and which item is which.
fn context<'a>(
    request_id: &'a str,
    device_id: &'a str,
    status: &'a str,
    items: &'a [ShareItem],
) -> [(&'static str, String); 4] {
    [
        ("requestId", json_fragment(&request_id)),
        ("deviceId", json_fragment(&device_id)),
        ("status", json_fragment(&status)),
        ("items", json_fragment(&items)),
    ]
}

pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<CommandOutcome> {
    let Command::Share {
        to,
        pick,
        text,
        clipboard,
        paths,
        wait,
        timeout,
        ..
    } = command
    else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "share::run called for another subcommand",
        ));
    };

    let payload = payload_from_flags(text.as_deref(), *clipboard, paths)?;
    let timeout_seconds = parse_seconds(timeout.as_ref(), timeout_of(cli)?, "--timeout")?;

    if *pick {
        return pick_and_submit(cli, out, payload, *wait, timeout_seconds);
    }
    let Some(to) = to.as_deref() else {
        return Err(CliError::new(
            ErrorCode::InvalidArgument,
            "--to <device-id> is required: an imperative share never opens a device \
             picker, so nothing can choose a target for you. To pick one on \
             screen, run `klardrop share --pick` (or `klardrop interactive`) \
             deliberately — from the desktop entry, the file-manager action or \
             the Omarchy menu — and never from an unattended command.",
        ));
    };
    submit_and_report(
        cli,
        out,
        "share",
        ShareSpec {
            to: required_target(to)?,
            payload,
        },
        *wait,
        timeout_seconds,
    )
}

/// `share --pick`: choose the device in the TUI, then submit exactly as
/// `share --to` would.
///
/// The order is the whole design. The terminal is checked before anything
/// else, so a pipe gets `terminal_required` and no prompt; the payload is
/// validated *before* the picker opens, so nobody is asked to choose a device
/// and only then told a path cannot be read; and the picker itself sends
/// nothing — it returns a device id, and the same submission path runs after
/// the screen is gone.
fn pick_and_submit(
    cli: &Cli,
    out: &Output,
    payload: Payload,
    wait: bool,
    timeout_seconds: f64,
) -> CliResult<CommandOutcome> {
    if !crate::tui::terminal::is_interactive_terminal() {
        return Err(CliError::new(
            ErrorCode::TerminalRequired,
            "share --pick chooses a device on screen, so it needs a real terminal \
             on both stdin and stdout; this invocation has at least one of them \
             redirected. Pass --to <device-id> instead.",
        ));
    }
    // Local, pre-flight checks: a path the caller cannot read is reported
    // before anyone is asked to pick a device.
    if let Payload::Files(paths) = &payload {
        absolute_existing_paths(paths)?;
    }

    // `--timeout` is ONE budget for the whole command (connect, pick, submit,
    // wait). Starting the clock here and passing only what is left to the
    // picker keeps it that way; giving the picker its own full timeout made
    // `share --pick --timeout 120` able to run for 240 seconds.
    let started = Instant::now();
    let remaining = || (timeout_seconds - started.elapsed().as_secs_f64()).max(0.001);

    let control = crate::control_file::load(cli.control_file.as_deref())?;
    let options = crate::tui::Options {
        theme: crate::tui::theme::ThemeChoice::Auto,
        motion: true,
        mouse: true,
        color: true,
        line_mode: false,
        connect_timeout: Duration::from_secs_f64(remaining()),
        debug: cli.debug,
        picker: Some(summarize(&payload)),
    };

    let Some(device) = crate::tui::run(control, options)? else {
        // Nothing was chosen, so nothing was sent. This is a cancellation and not a
        // failure, and it still owes the caller exactly one JSON value on stdout —
        // `out.fail` prints the envelope under --json and the reason on stderr
        // otherwise. Exit code 130 is what separates "the user changed their mind"
        // from "the transfer failed" for an agent reading the status.
        return Err(CliError::new(
            ErrorCode::Cancelled,
            "cancelled: no device was chosen, so nothing was sent",
        ));
    };
    out.debug_log(&format!("picked device {device}"));
    submit_and_report(
        cli,
        out,
        "share",
        ShareSpec {
            to: device,
            payload,
        },
        wait,
        remaining(),
    )
}

/// One line describing what is about to be sent, shown above the device list.
fn summarize(payload: &Payload) -> String {
    match payload {
        Payload::Text(text) => format!("{} character(s) of text", text.chars().count()),
        Payload::Clipboard => "the clipboard".to_string(),
        Payload::Files(paths) => {
            let names: Vec<String> = paths
                .iter()
                .map(|path| {
                    path.file_name()
                        .map(|name| name.to_string_lossy().into_owned())
                        .unwrap_or_else(|| path.display().to_string())
                })
                .collect();
            format!("{} file(s): {}", paths.len(), names.join(", "))
        }
    }
}

/// Builds the single payload a request may carry, refusing every mixture
/// before any I/O happens.
pub fn payload_from_flags(
    text: Option<&str>,
    clipboard: bool,
    paths: &[PathBuf],
) -> CliResult<Payload> {
    let mut kinds: Vec<&'static str> = Vec::new();
    if !paths.is_empty() {
        kinds.push("file paths");
    }
    if text.is_some() {
        kinds.push("--text");
    }
    if clipboard {
        kinds.push("--clipboard");
    }

    if kinds.len() > 1 {
        return Err(invalid_payload(&format!(
            "{} are alternatives, but {} were given; pick exactly one. \
             To choose a device interactively, run `klardrop interactive`.",
            humanize(&kinds),
            kinds.len()
        )));
    }
    if kinds.is_empty() {
        return Err(invalid_payload(
            "nothing to share; pass one or more file paths, --text <TEXT> or --clipboard",
        ));
    }
    match text {
        Some(text) => Ok(Payload::Text(text.to_string())),
        None if clipboard => Ok(Payload::Clipboard),
        None => Ok(Payload::Files(paths.to_vec())),
    }
}

fn invalid_payload(reason: &str) -> CliError {
    CliError::new(ErrorCode::InvalidArgument, reason.to_string())
}

fn humanize(kinds: &[&str]) -> String {
    match kinds {
        [only] => (*only).to_string(),
        [first, second] => format!("{first} and {second}"),
        _ => format!("{kinds:?}"),
    }
}

/// An explicit command never prompts, so the target must be given and usable.
pub fn required_target(to: &str) -> CliResult<String> {
    let trimmed = to.trim();
    if trimmed.is_empty() {
        return Err(CliError::new(
            ErrorCode::InvalidArgument,
            "--to must name a device id (an unambiguous prefix is enough); \
             to pick one interactively, run `klardrop interactive`",
        ));
    }
    Ok(trimmed.to_string())
}

/// Checks every path locally and returns it in absolute form. A path the
/// client cannot even read is reported here — never skipped by the daemon and
/// reported as if it had gone through.
pub fn absolute_existing_paths(paths: &[PathBuf]) -> CliResult<Vec<String>> {
    let mut resolved = Vec::with_capacity(paths.len());
    for path in paths {
        let metadata = fs::metadata(path).map_err(|reason| {
            CliError::new(
                ErrorCode::InvalidPath,
                format!("cannot share {:?}: {reason}", path.display().to_string()),
            )
        })?;
        // A directory cannot be streamed: the engine would report the item as failed only
        // after a round trip, so the caller is told up front instead. Symlinks are followed,
        // because a symlink to a regular file is a regular file.
        if !metadata.is_file() {
            return Err(CliError::new(
                ErrorCode::InvalidPath,
                format!(
                    "cannot share {:?}: only regular files can be shared",
                    path.display().to_string()
                ),
            ));
        }
        // The daemon resolves paths against its own working directory, so it
        // only ever receives absolute ones.
        let absolute = fs::canonicalize(path).unwrap_or_else(|_| path.clone());
        match absolute.to_str() {
            Some(text) => resolved.push(text.to_string()),
            None => {
                return Err(CliError::new(
                    ErrorCode::InvalidPath,
                    format!(
                        "{:?} is not valid UTF-8 and cannot be shared",
                        path.display().to_string()
                    ),
                ))
            }
        }
    }
    Ok(resolved)
}

/// The one implementation behind both `share` and the compatibility `send`:
/// validate, resolve the device, submit, optionally wait, then report.
pub fn submit_and_report(
    cli: &Cli,
    out: &Output,
    command: &str,
    spec: ShareSpec,
    wait: bool,
    timeout_seconds: f64,
) -> CliResult<CommandOutcome> {
    let paths = match &spec.payload {
        Payload::Files(paths) => absolute_existing_paths(paths)?,
        Payload::Text(_) | Payload::Clipboard => Vec::new(),
    };

    let session = connect(cli, out, timeout_seconds)?;
    let device = resolve_device(&session, &spec.to)?;
    out.debug_log(&format!(
        "resolved {:?} to device {} ({})",
        spec.to, device.device_id, device.device_name
    ));

    let body = request_body(&device.device_id, &spec.payload, &paths);
    let value = session.client.post_json(SHARE_PATH, &body)?;
    let submitted = parse_share_response(&value)?;
    out.debug_log(&format!(
        "daemon accepted the request as {} with {} item(s)",
        submitted.status.as_str(),
        submitted.items.len()
    ));

    if wait {
        wait_for_terminal(out, command, &session, &submitted)
    } else {
        report_submission(out, command, &submitted)
    }
}

/// Without `--wait` the truth is "the daemon has it": `queued`, or whatever the
/// daemon already knows. A terminal failure it already reports is still a
/// failure — calling that queued would be its own kind of lie.
fn report_submission(
    out: &Output,
    command: &str,
    submitted: &SubmittedShare,
) -> CliResult<CommandOutcome> {
    out.progress(&format!(
        "{} request {} accepted for device {} ({} item(s))",
        submitted.kind.as_str(),
        submitted.request_id,
        submitted.device_id,
        submitted.items.len()
    ));
    describe_items(out, &submitted.items);

    if submitted.status.is_terminal() && submitted.status != RequestStatus::Completed {
        let error = transfer_error(&submitted.request_id, &submitted.items, submitted.status);
        out.fail_with_context(
            command,
            &error,
            &context(
                &submitted.request_id,
                &submitted.device_id,
                submitted.status.as_str(),
                &submitted.items,
            ),
        );
        return Ok(CommandOutcome::failed(&error));
    }

    if out.is_json() {
        out.print_json(&ShareEnvelope {
            schema_version: SCHEMA_VERSION,
            ok: true,
            command,
            request_id: &submitted.request_id,
            device_id: &submitted.device_id,
            status: submitted.status.as_str(),
            items: &submitted.items,
        })?;
    } else {
        out.print_line(&format!(
            "{} {} {} to {}",
            submitted.kind.as_str(),
            submitted.request_id,
            submitted.status.as_str(),
            submitted.device_id
        ));
    }
    Ok(CommandOutcome::Success)
}

/// Polls this request's own id until every item is terminal. Anything that
/// leaves the delivery state unknown — an expired deadline, a lost connection,
/// an id the daemon forgot — is reported as unknown, never as a failure and
/// never as a success.
fn wait_for_terminal(
    out: &Output,
    command: &str,
    session: &Session,
    submitted: &SubmittedShare,
) -> CliResult<CommandOutcome> {
    let path = format!("{TRANSFERS_PATH}?id={}", submitted.request_id);
    out.progress(&format!(
        "waiting for {} ({})",
        submitted.kind.as_str(),
        submitted.request_id
    ));
    describe_items(out, &submitted.items);

    let mut latest = submitted.clone();
    let mut reported = submitted.items.clone();

    loop {
        let polled = match session.client.get_json_optional(&path) {
            Ok(Some(value)) => parse_transfers_one(&value),
            Ok(None) => Err(forgotten(&latest.request_id)),
            Err(error) => Err(error),
        };
        let request = match polled {
            Ok(request) => request,
            // A dead connection or an expired deadline says nothing about the
            // transfer itself; the daemon keeps going either way.
            Err(error) if leaves_delivery_unknown(error.code) => {
                return report_unknown(out, command, &latest, error)
            }
            Err(error) => return Err(error),
        };

        report_changes(out, &reported, &request.items);
        reported.clone_from(&request.items);
        latest = request.into_submission();

        // Terminal per the daemon, or nothing left that can still change:
        // either way there is nothing more to learn from polling.
        if latest.status.is_terminal() || latest.items.iter().all(|item| item.status.is_terminal())
        {
            return finish_wait(out, command, &latest);
        }

        let remaining = session.deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return report_unknown(
                out,
                command,
                &latest,
                CliError::new(
                    ErrorCode::DaemonTimeout,
                    "the command deadline expired while waiting for delivery",
                ),
            );
        }
        // Ctrl-C during the wait. It cancels nothing — the daemon keeps sending —
        // so this leaves through the same door as an expired deadline: the
        // request id, an explicit "unknown", and exit 130.
        if crate::interrupt::was_interrupted() {
            return report_unknown(
                out,
                command,
                &latest,
                CliError::new(
                    ErrorCode::Cancelled,
                    "interrupted while waiting for delivery; the daemon was not told to stop, \
                     so this request may still complete",
                ),
            );
        }
        crate::interrupt::sleep_interruptibly(POLL_INTERVAL.min(remaining));
    }
}

/// The daemon forgot the id: evicted from its bounded registry, or gone with a
/// restart. Neither is a delivery result.
fn forgotten(request_id: &str) -> CliError {
    CliError::new(
        ErrorCode::TransferUnknown,
        format!(
            "the daemon no longer knows request {request_id} — it was evicted from the \
             recent-request list or the daemon restarted"
        ),
    )
}

/// Transport-level failures that say nothing about what the daemon did with
/// the transfer.
fn leaves_delivery_unknown(code: ErrorCode) -> bool {
    matches!(
        code,
        ErrorCode::DaemonTimeout
            | ErrorCode::DaemonUnreachable
            | ErrorCode::DaemonNotRunning
            | ErrorCode::DaemonControlInvalid
            // The daemon answered, but it can no longer tell us what became of this
            // request, so the wait must report it with the same context envelope as a
            // deadline — the caller keeps the request id it needs to look it up.
            | ErrorCode::TransferUnknown
    )
}

fn finish_wait(out: &Output, command: &str, latest: &SubmittedShare) -> CliResult<CommandOutcome> {
    let status = latest.status.as_str();
    // "Delivered" means every single item completed. A request the daemon
    // calls completed while one of its items is not is a contradiction, and the
    // items win: a partial multi-file share is never reported as success.
    let all_completed = latest
        .items
        .iter()
        .all(|item| item.status == TransferStatus::Completed);
    if latest.status == RequestStatus::Completed && all_completed {
        out.progress(&format!(
            "{status}: every item was delivered ({})",
            latest.request_id
        ));
        if out.is_json() {
            out.print_json(&ShareEnvelope {
                schema_version: SCHEMA_VERSION,
                ok: true,
                command,
                request_id: &latest.request_id,
                device_id: &latest.device_id,
                status,
                items: &latest.items,
            })?;
        } else {
            out.print_line(&format!(
                "Delivered {} item(s) to {} ({}).",
                latest.items.len(),
                latest.device_id,
                latest.request_id
            ));
        }
        return Ok(CommandOutcome::Success);
    }

    // A decline or a failure — including a partial multi-file result — is a
    // nonzero outcome that still names every item.
    let error = transfer_error(&latest.request_id, &latest.items, latest.status);
    out.fail_with_context(
        command,
        &error,
        &context(&latest.request_id, &latest.device_id, status, &latest.items),
    );
    Ok(CommandOutcome::failed(&error))
}

/// The delivery state could not be established: the deadline expired, the
/// connection dropped, or the daemon forgot the request. The daemon's transfer
/// is still its own — this client neither cancels it nor repeats it — so the
/// envelope says `unknown` and names the command that can still find out.
fn report_unknown(
    out: &Output,
    command: &str,
    latest: &SubmittedShare,
    error: CliError,
) -> CliResult<CommandOutcome> {
    let request_id = &latest.request_id;
    let message = format!(
        "{}; delivery state is unknown — inspect it with `klardrop transfers --id {request_id}`. \
         The transfer was neither cancelled nor retried.",
        error.message
    );
    let error = CliError::new(error.code, message);
    out.fail_with_context(
        command,
        &error,
        &context(request_id, &latest.device_id, "unknown", &latest.items),
    );
    Ok(CommandOutcome::failed(&error))
}

/// The message an operator sees for a request that ended badly. It names the
/// first item that did not go through, with the daemon's own reason.
fn transfer_error(request_id: &str, items: &[ShareItem], status: RequestStatus) -> CliError {
    let detail = items
        .iter()
        .find(|item| item.status != TransferStatus::Completed)
        .map(item_detail)
        .unwrap_or_else(|| "every item completed but the request did not".to_string());
    CliError::new(
        ErrorCode::TransferFailed,
        format!(
            "request {request_id} ended as {}: {detail}",
            status.as_str()
        ),
    )
}

fn item_detail(item: &ShareItem) -> String {
    let who = match item.file_name.as_deref() {
        Some(name) => format!("{name}: "),
        None => String::new(),
    };
    match item.error.as_deref() {
        Some(reason) => format!("{who}{reason}"),
        None => format!("{who}{}", item.status.as_str()),
    }
}

fn request_body(device_id: &str, payload: &Payload, paths: &[String]) -> serde_json::Value {
    let mut body = json!({ "deviceId": device_id });
    match payload {
        Payload::Files(_) => body["paths"] = json!(paths),
        Payload::Text(text) => body["text"] = json!(text),
        Payload::Clipboard => body["clipboard"] = json!(true),
    }
    body
}

/// Progress goes to stderr only: with `--json`, stdout keeps exactly one value.
fn describe_items(out: &Output, items: &[ShareItem]) {
    for item in items {
        out.progress(&format!("  {} {}", item_name(item), describe_item(item)));
    }
}

fn report_changes(out: &Output, previous: &[ShareItem], current: &[ShareItem]) {
    for item in current {
        let changed = match previous
            .iter()
            .find(|before| before.transfer_id == item.transfer_id)
        {
            None => true,
            Some(before) => {
                before.status != item.status || before.transferred_size != item.transferred_size
            }
        };
        if changed {
            out.progress(&format!("  {} {}", item_name(item), describe_item(item)));
        }
    }
}

fn item_name(item: &ShareItem) -> String {
    match item.file_name.as_deref() {
        Some(name) => name.to_string(),
        // An item the daemon could not prepare has no transfer id; name it by what it is
        // instead of inventing an identifier.
        None => match item.transfer_id.as_deref() {
            Some(id) => format!("item {id}"),
            None => "item".to_string(),
        },
    }
}

fn describe_item(item: &ShareItem) -> String {
    if item.total_size > 0 && item.status == TransferStatus::Transferring {
        let percent = item.transferred_size.saturating_mul(100) / item.total_size;
        return format!(
            "{percent}% ({} of {} bytes)",
            item.transferred_size, item.total_size
        );
    }
    match item.error.as_deref() {
        Some(reason) => format!("{}: {reason}", item.status.as_str()),
        None => item.status.as_str().to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn path(name: &str) -> PathBuf {
        PathBuf::from(name)
    }

    #[test]
    fn exactly_one_payload_kind_is_accepted() {
        assert_eq!(
            payload_from_flags(None, false, &[path("a.pdf")]).expect("files"),
            Payload::Files(vec![path("a.pdf")])
        );
        assert_eq!(
            payload_from_flags(Some("hi"), false, &[]).expect("text"),
            Payload::Text("hi".to_string())
        );
        assert_eq!(
            payload_from_flags(None, true, &[]).expect("clipboard"),
            Payload::Clipboard
        );
    }

    /// One way of passing two payload kinds at once.
    struct Mixture<'a> {
        label: &'a str,
        text: Option<&'a str>,
        clipboard: bool,
        paths: &'a [PathBuf],
        offenders: [&'a str; 2],
    }

    #[test]
    fn every_payload_mixture_is_refused_and_both_offenders_are_named() {
        let file = [PathBuf::from("a.pdf")];
        let cases = [
            Mixture {
                label: "paths plus text",
                text: Some("hi"),
                clipboard: false,
                paths: &file,
                offenders: ["file paths", "--text"],
            },
            Mixture {
                label: "text plus clipboard",
                text: Some("hi"),
                clipboard: true,
                paths: &[],
                offenders: ["--text", "--clipboard"],
            },
            Mixture {
                label: "paths plus clipboard",
                text: None,
                clipboard: true,
                paths: &file,
                offenders: ["file paths", "--clipboard"],
            },
        ];
        for case in cases {
            let error =
                payload_from_flags(case.text, case.clipboard, case.paths).expect_err(case.label);
            assert_eq!(error.code, ErrorCode::InvalidArgument, "{}", case.label);
            assert_eq!(error.code.exit_code(), 2, "{}", case.label);
            for offender in case.offenders {
                assert!(
                    error.message.contains(offender),
                    "{}: {offender} must be named, got {}",
                    case.label,
                    error.message
                );
            }
            assert!(
                error.message.contains("klardrop interactive"),
                "{}: must point at the interactive command, got {}",
                case.label,
                error.message
            );
        }
    }

    #[test]
    fn no_payload_at_all_is_refused() {
        let error = payload_from_flags(None, false, &[]).expect_err("empty");
        assert_eq!(error.code, ErrorCode::InvalidArgument);
        assert!(error.message.contains("--clipboard"), "{}", error.message);
    }

    #[test]
    fn an_empty_target_is_refused_without_touching_the_daemon() {
        for target in ["", "   "] {
            let error = required_target(target).expect_err("empty target");
            assert_eq!(error.code, ErrorCode::InvalidArgument);
            assert!(error.message.contains("klardrop interactive"));
        }
        assert_eq!(required_target(" 1111 ").expect("trimmed"), "1111");
    }

    #[test]
    fn a_missing_path_is_an_invalid_path_and_the_rest_is_never_reached() {
        let dir = std::env::temp_dir().join(format!("klardrop-share-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let present = dir.join("present.txt");
        std::fs::write(&present, b"x").expect("write fixture file");
        let missing = dir.join("missing.txt");

        let error =
            absolute_existing_paths(&[present.clone(), missing.clone()]).expect_err("missing path");
        assert_eq!(error.code, ErrorCode::InvalidPath);
        assert_eq!(error.code.exit_code(), 2);
        assert!(
            error.message.contains("missing.txt"),
            "the path must be named: {}",
            error.message
        );

        let resolved = absolute_existing_paths(&[present]).expect("present path");
        assert_eq!(resolved.len(), 1);
        assert!(
            PathBuf::from(&resolved[0]).is_absolute(),
            "the daemon only receives absolute paths: {resolved:?}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_request_body_carries_exactly_one_payload_kind() {
        let files = request_body(
            "11112222",
            &Payload::Files(vec![path("/tmp/a.pdf")]),
            &["/tmp/a.pdf".to_string()],
        );
        assert_eq!(files["deviceId"], json!("11112222"));
        assert_eq!(files["paths"], json!(["/tmp/a.pdf"]));
        assert!(files.get("text").is_none() && files.get("clipboard").is_none());

        let text = request_body("11112222", &Payload::Text("hi".to_string()), &[]);
        assert_eq!(text["text"], json!("hi"));
        assert!(text.get("paths").is_none() && text.get("clipboard").is_none());

        let clipboard = request_body("11112222", &Payload::Clipboard, &[]);
        assert_eq!(clipboard["clipboard"], json!(true));
        assert!(clipboard.get("paths").is_none() && clipboard.get("text").is_none());
    }

    #[test]
    fn a_partial_failure_names_the_item_that_did_not_go_through() {
        let items = vec![
            ShareItem {
                transfer_id: Some("1".to_string()),
                path: Some("/tmp/a.pdf".to_string()),
                file_name: Some("a.pdf".to_string()),
                total_size: 4,
                transferred_size: 4,
                status: TransferStatus::Completed,
                error: None,
            },
            ShareItem {
                transfer_id: Some("2".to_string()),
                path: Some("/tmp/b.pdf".to_string()),
                file_name: Some("b.pdf".to_string()),
                total_size: 4,
                transferred_size: 0,
                status: TransferStatus::Failed,
                error: Some("the peer closed the connection".to_string()),
            },
        ];
        let error = transfer_error("req-1", &items, RequestStatus::Failed);
        assert_eq!(error.code, ErrorCode::TransferFailed);
        assert_eq!(error.code.exit_code(), 1);
        assert!(
            error.message.contains("b.pdf") && error.message.contains("peer closed"),
            "the failing item must be named with the daemon's reason: {}",
            error.message
        );
    }

    #[test]
    fn the_failure_context_key_order_is_the_published_one() {
        let items = vec![ShareItem {
            transfer_id: Some("1".to_string()),
            path: None,
            file_name: None,
            total_size: 0,
            transferred_size: 0,
            status: TransferStatus::Declined,
            error: Some("recipient declined the transfer".to_string()),
        }];
        let keys: Vec<&str> = context("req-1", "11112222", "declined", &items)
            .iter()
            .map(|(key, _)| *key)
            .collect();
        assert_eq!(keys, vec!["requestId", "deviceId", "status", "items"]);
    }

    #[test]
    fn terminal_item_statuses_are_the_only_failures() {
        let declining = ShareItem {
            transfer_id: Some("1".to_string()),
            path: None,
            file_name: None,
            total_size: 0,
            transferred_size: 0,
            status: TransferStatus::Declined,
            error: None,
        };
        let error = transfer_error(
            "req-1",
            std::slice::from_ref(&declining),
            RequestStatus::Declined,
        );
        assert!(error.message.contains("declined"), "{}", error.message);

        let mut completed = declining.clone();
        completed.status = TransferStatus::Completed;
        let error = transfer_error("req-1", &[completed], RequestStatus::Completed);
        assert_eq!(error.code, ErrorCode::TransferFailed);
        assert!(error.message.contains("completed"), "{}", error.message);
    }
}
