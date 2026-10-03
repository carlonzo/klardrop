//! `klardrop send` — the compatibility spelling of `share`.
//!
//! It runs on exactly the same implementation and the same daemon routes, so
//! there is no second code path to keep honest. The one difference is the
//! promise: legacy `send` meant *delivered*, so it waits for a terminal
//! outcome by default and exits nonzero unless every item completed.
//!
//! Accepted syntaxes (unchanged from the previous CLI):
//!
//! ```text
//! klardrop send DEVICE_ID --file PATH [--file PATH …]
//! klardrop send DEVICE_ID --text TEXT
//! klardrop send DEVICE_ID CONTENT        # a path separator means file, else text
//! ```

use std::path::PathBuf;

use crate::cli::{Cli, Command};
use crate::commands::share::{required_target, submit_and_report, Payload, ShareSpec};
use crate::commands::{parse_seconds, timeout_of, CommandOutcome};
use crate::envelope::{CliError, CliResult, ErrorCode, Output};

pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<CommandOutcome> {
    let Command::Send {
        device_id,
        content,
        file,
        text,
        no_wait,
        timeout,
        ..
    } = command
    else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "send::run called for another subcommand",
        ));
    };

    let payload = legacy_payload(content.as_deref(), file, text.as_deref())?;
    let to = required_target(device_id)?;
    let timeout = parse_seconds(timeout.as_ref(), timeout_of(cli)?, "--timeout")?;

    // Legacy `send` never claimed success for a merely queued transfer.
    submit_and_report(
        cli,
        out,
        "send",
        ShareSpec { to, payload },
        !no_wait,
        timeout,
    )
}

/// Maps the legacy flags onto exactly one payload kind, refusing every
/// mixture and inventing no new heuristics.
fn legacy_payload(
    content: Option<&str>,
    files: &[PathBuf],
    text: Option<&str>,
) -> CliResult<Payload> {
    let mut kinds: Vec<&'static str> = Vec::new();
    if !files.is_empty() {
        kinds.push("--file");
    }
    if text.is_some() {
        kinds.push("--text");
    }
    if content.is_some() {
        kinds.push("CONTENT");
    }
    if kinds.len() > 1 {
        return Err(CliError::new(
            ErrorCode::InvalidArgument,
            format!(
                "{} are alternatives, but {} were given; pass only one.",
                describe_kinds(&kinds),
                kinds.len()
            ),
        ));
    }

    if let Some(text) = text {
        return Ok(Payload::Text(text.to_string()));
    }
    if !files.is_empty() {
        return Ok(Payload::Files(files.to_vec()));
    }
    match content {
        // The legacy rule, unchanged: a positional value carrying a path
        // separator is a file, anything else is text.
        Some(content) if content.contains('/') || content.contains('\\') => {
            Ok(Payload::Files(vec![PathBuf::from(content)]))
        }
        Some(content) => Ok(Payload::Text(content.to_string())),
        None => Err(CliError::new(
            ErrorCode::InvalidArgument,
            "no content specified; pass --file <PATH>, --text <TEXT>, or CONTENT",
        )),
    }
}

fn describe_kinds(kinds: &[&str]) -> String {
    match kinds {
        [only] => (*only).to_string(),
        [first, second] => format!("{first} and {second}"),
        _ => format!("{kinds:?}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn files(names: &[&str]) -> Vec<PathBuf> {
        names.iter().map(PathBuf::from).collect()
    }

    #[test]
    fn every_legacy_spelling_maps_to_one_payload() {
        assert_eq!(
            legacy_payload(None, &files(&["/tmp/a.pdf"]), None).expect("--file"),
            Payload::Files(files(&["/tmp/a.pdf"]))
        );
        assert_eq!(
            legacy_payload(None, &files(&["/tmp/a.pdf", "/tmp/b.pdf"]), None).expect("two files"),
            Payload::Files(files(&["/tmp/a.pdf", "/tmp/b.pdf"]))
        );
        assert_eq!(
            legacy_payload(None, &[], Some("hello")).expect("--text"),
            Payload::Text("hello".to_string())
        );
        assert_eq!(
            legacy_payload(Some("Hello World"), &[], None).expect("text content"),
            Payload::Text("Hello World".to_string())
        );
    }

    #[test]
    fn a_positional_containing_a_separator_is_the_legacy_file_case() {
        assert_eq!(
            legacy_payload(Some("/tmp/report.pdf"), &[], None).expect("path"),
            Payload::Files(files(&["/tmp/report.pdf"]))
        );
        assert_eq!(
            legacy_payload(Some(r"C:\Users\me\report.pdf"), &[], None).expect("windows path"),
            Payload::Files(files(&[r"C:\Users\me\report.pdf"]))
        );
        // Prose that happens to contain a separator is still a file under the
        // legacy rule. That is deliberate: old scripts keep working.
        assert_eq!(
            legacy_payload(Some("and/or"), &[], None).expect("legacy rule"),
            Payload::Files(files(&["and/or"]))
        );
    }

    #[test]
    fn no_content_is_a_usage_error() {
        let error = legacy_payload(None, &[], None).expect_err("nothing to send");
        assert_eq!(error.code, ErrorCode::InvalidArgument);
        assert_eq!(error.code.exit_code(), 2);
        assert!(error.message.contains("--file"), "{}", error.message);
    }

    /// One way of passing two payload kinds at once.
    struct Mixture<'a> {
        content: Option<&'a str>,
        files: Vec<PathBuf>,
        text: Option<&'a str>,
        offenders: [&'a str; 2],
    }

    #[test]
    fn legacy_mixtures_are_refused_with_both_offenders_named() {
        let cases = [
            Mixture {
                content: None,
                files: files(&["/tmp/a.pdf"]),
                text: Some("hi"),
                offenders: ["--file", "--text"],
            },
            Mixture {
                content: Some("hi"),
                files: files(&["/tmp/a.pdf"]),
                text: None,
                offenders: ["--file", "CONTENT"],
            },
            Mixture {
                content: Some("hi"),
                files: vec![],
                text: Some("hi"),
                offenders: ["--text", "CONTENT"],
            },
        ];
        for case in cases {
            let error = legacy_payload(case.content, &case.files, case.text).expect_err("mixture");
            assert_eq!(error.code, ErrorCode::InvalidArgument);
            for offender in case.offenders {
                assert!(
                    error.message.contains(offender),
                    "{offender} must be named: {}",
                    error.message
                );
            }
        }
    }
}
