//! `klardrop daemon <args…>` — run the engine.
//!
//! The client is a client: it never links an engine in, never embeds one, and
//! never starts one on its own initiative. This subcommand is the single
//! exception, and it is deliberately the *thinnest* possible exception:
//!
//!   * it forwards the argument vector it was given, verbatim, as an argv —
//!     never joined into a string, never handed to a shell, so nothing this
//!     client passes on can become a command someone else runs;
//!   * it looks for `klardrop-engine` next to its own binary first and on
//!     `PATH` second, so a matched pair installed together is the pair that
//!     runs, and an isolated client still finds a system-wide engine;
//!   * it gives the engine this process's stdin, stdout and stderr and then
//!     reports the engine's own exit code, because the engine is the thing
//!     that knows what went wrong.
//!
//! The systemd user unit and the Qt launcher both start the engine through this
//! path, so its argument handling and its exit code are a published contract.

use std::path::{Path, PathBuf};
use std::process::Command as Process;

use crate::cli::{Cli, Command};
use crate::commands::CommandOutcome;
use crate::envelope::{CliError, CliResult, ErrorCode, Output};

/// The engine binary's name. Distinct from this client's on purpose: a user
/// who types `klardrop` gets a client, never a second daemon.
pub const ENGINE_BIN: &str = "klardrop-engine";

/// Forwards to the engine and reports its exit code.
pub fn run(_cli: &Cli, command: &Command, out: &Output) -> CliResult<CommandOutcome> {
    let Command::Daemon { args } = command else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "daemon::run called for another subcommand",
        ));
    };
    let Some(engine) = locate_engine() else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            not_installed_message(),
        ));
    };
    out.debug_log(&format!(
        "forwarding {} argument(s) to {}",
        args.len(),
        engine.display()
    ));

    let status = Process::new(&engine)
        .args(args)
        .status()
        .map_err(|reason| spawn_error(&engine, &reason.to_string()))?;

    let code = exit_code(&status);
    if code != 0 {
        return Ok(CommandOutcome::Failure(code));
    }
    Ok(CommandOutcome::Success)
}

/// The exit code to report for a finished child.
fn exit_code(status: &std::process::ExitStatus) -> i32 {
    if let Some(code) = status.code() {
        return code;
    }
    use std::os::unix::process::ExitStatusExt;
    if let Some(signal) = status.signal() {
        return 128 + signal;
    }
    // No code and no signal means the platform has nothing to say; anything
    // but 0 here would report a success that did not happen.
    1
}

/// The engine, or `None` when neither place holds one.
///
/// The order is part of the contract: the sibling binary first, because that
/// is the pair an installer put on disk together, then `PATH`, because that is
/// where a system package puts it.
pub fn locate_engine() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok();
    locate_in(exe.as_deref(), &search_path())
}

/// The search itself, over injected inputs so it is testable without touching
/// the real filesystem or the caller's `PATH`.
fn locate_in(exe: Option<&Path>, path_entries: &[PathBuf]) -> Option<PathBuf> {
    if let Some(sibling) = exe.and_then(Path::parent).map(|dir| dir.join(ENGINE_BIN)) {
        if sibling.is_file() {
            return Some(sibling);
        }
    }
    path_entries
        .iter()
        .map(|dir| dir.join(ENGINE_BIN))
        .find(|candidate| candidate.is_file())
}

/// `PATH`, split into entries. Empty entries mean the current directory to
/// every other `PATH` consumer on this platform, and are dropped here rather
/// than resolved against whatever this process happens to be doing.
fn search_path() -> Vec<PathBuf> {
    std::env::var_os("PATH")
        .map(|value| {
            std::env::split_paths(&value)
                .filter(|entry| !entry.as_os_str().is_empty())
                .collect()
        })
        .unwrap_or_default()
}

fn spawn_error(engine: &Path, reason: &str) -> CliError {
    CliError::new(
        ErrorCode::InternalError,
        format!(
            "cannot run the Klardrop engine at {}: {reason}",
            engine.display()
        ),
    )
}

/// The message for an engine that is not installed anywhere this client can
/// see. It names both places, because "klardrop-engine not found" is the one
/// error a user cannot act on without knowing where it looked.
pub fn not_installed_message() -> String {
    format!(
        "the Klardrop engine ({ENGINE_BIN}) was not found. It is looked for \
         first next to this binary, then on PATH. Install the native package, \
         or start the engine yourself."
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn touch(path: &Path) {
        std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
        std::fs::write(path, b"#!/bin/sh\nexit 0\n").expect("write");
    }

    #[test]
    fn the_sibling_binary_wins_over_path() {
        let root =
            std::env::temp_dir().join(format!("klardrop-engine-search-{}", std::process::id()));
        let bindir = root.join("bin");
        let otherdir = root.join("other");
        touch(&bindir.join(ENGINE_BIN));
        touch(&otherdir.join(ENGINE_BIN));
        let exe = bindir.join("klardrop");

        assert_eq!(
            locate_in(Some(&exe), std::slice::from_ref(&otherdir)),
            Some(bindir.join(ENGINE_BIN))
        );
        // With no sibling beside this client, PATH is what is left.
        assert_eq!(
            locate_in(
                Some(&root.join("elsewhere/klardrop")),
                std::slice::from_ref(&otherdir)
            ),
            Some(otherdir.join(ENGINE_BIN))
        );
        // With no exe to look beside, PATH is still searched.
        assert_eq!(
            locate_in(None, std::slice::from_ref(&otherdir)),
            Some(otherdir.join(ENGINE_BIN))
        );
        // Nothing anywhere is a miss, not a guess.
        assert_eq!(locate_in(None, &[]), None);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_missing_engine_message_names_both_places_it_looked() {
        let message = not_installed_message();
        assert!(message.contains("next to this binary"), "{message}");
        assert!(message.contains("on PATH"), "{message}");
    }
}
