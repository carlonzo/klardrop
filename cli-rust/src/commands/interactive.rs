//! `klardrop interactive` — the two-pane TUI.
//!
//! This is deliberately the thinnest command in the crate. It resolves the
//! control file with the same code every other command uses, refuses to run
//! without a terminal, and hands everything else to [`crate::tui`]. No engine
//! is ever started: the TUI observes a daemon that is already running.
//!
//! Order matters. The terminal is checked *before* the control file is read, so
//! an automation that pipes into `klardrop interactive` is told exactly why it
//! cannot work instead of waiting for a keypress that will never come.

use std::time::Duration;

use crate::cli::{Cli, Command, ThemeArg};
use crate::commands::{parse_seconds, timeout_of};
use crate::control_file;
use crate::envelope::{CliError, CliResult, ErrorCode, Output};
use crate::tui;

/// Runs the TUI to completion.
///
/// Returns `Ok(())` on an ordinary quit — including `q`, `Ctrl-C` and the user
/// closing the terminal connection — because leaving the TUI is not a failure
/// and never cancels a transfer: the daemon owns it.
pub fn run(cli: &Cli, command: &Command, out: &Output) -> CliResult<()> {
    let Command::Interactive {
        theme,
        no_motion,
        no_mouse,
        no_color,
        line_mode,
        timeout,
    } = command
    else {
        return Err(CliError::new(
            ErrorCode::InternalError,
            "interactive::run called for another subcommand",
        ));
    };

    if !tui::terminal::is_interactive_terminal() {
        return Err(CliError::new(
            ErrorCode::TerminalRequired,
            "klardrop interactive needs a real terminal on both stdin and stdout; \
             this invocation has at least one of them redirected",
        ));
    }

    let timeout_seconds = parse_seconds(timeout.as_ref(), timeout_of(cli)?, "--timeout")?;
    let control = control_file::load(cli.control_file.as_deref())?;
    out.debug_log(&format!(
        "control file {} -> {}",
        control.path.display(),
        control.authority()
    ));

    let options = tui::Options {
        theme: theme_choice(*theme),
        motion: !no_motion,
        mouse: !no_mouse,
        color: !no_color,
        line_mode: *line_mode,
        connect_timeout: Duration::from_secs_f64(timeout_seconds),
        debug: cli.debug,
        picker: None,
    };

    // The full client has no answer to give back: only a picker picks.
    tui::run(control, options).map(|_| ())
}

/// Maps the flag onto the renderer's theme selector.
fn theme_choice(arg: ThemeArg) -> tui::theme::ThemeChoice {
    use tui::theme::ThemeChoice;
    match arg {
        ThemeArg::Auto => ThemeChoice::Auto,
        ThemeArg::System => ThemeChoice::System,
        ThemeArg::TokyoNight => ThemeChoice::TokyoNight,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    /// Parses an invocation. `Command` is deliberately not `Clone`, so the
    /// parsed `Cli` is returned whole and borrowed from.
    fn parse(args: &[&str]) -> Cli {
        Cli::try_parse_from(args).expect("valid invocation")
    }

    fn interactive_of(cli: &Cli) -> &Command {
        cli.command.as_ref().expect("a subcommand was given")
    }

    #[test]
    fn every_theme_flag_resolves_to_a_theme() {
        for (flag, expected) in [
            ("--theme=auto", tui::theme::ThemeChoice::Auto),
            ("--theme=system", tui::theme::ThemeChoice::System),
            ("--theme=tokyo-night", tui::theme::ThemeChoice::TokyoNight),
        ] {
            let cli = parse(&["klardrop", "interactive", flag]);
            let Command::Interactive { theme, .. } = interactive_of(&cli) else {
                panic!("expected the interactive command");
            };
            assert_eq!(theme_choice(*theme), expected, "{flag}");
        }
    }

    #[test]
    fn an_unknown_theme_is_refused_by_the_parser() {
        let error =
            Cli::try_parse_from(["klardrop", "interactive", "--theme=nord"]).expect_err("no");
        assert_eq!(error.exit_code(), 2, "an invalid choice is a usage error");
    }

    #[test]
    fn the_display_flags_default_to_the_documented_values() {
        let cli = parse(&["klardrop", "interactive"]);
        let command = interactive_of(&cli);
        let Command::Interactive {
            theme,
            no_motion,
            no_mouse,
            no_color,
            line_mode,
            timeout,
        } = command
        else {
            panic!("expected the interactive command");
        };
        assert_eq!(*theme, ThemeArg::Auto);
        assert!(!*no_motion && !*no_mouse && !*no_color, "all default off");
        assert!(
            !*line_mode,
            "line mode is a fallback, not the default: a capable terminal gets the TUI"
        );
        assert!(timeout.is_none(), "the timeout has a default, not a flag");
    }

    #[test]
    fn the_display_flags_can_all_be_set_together() {
        let cli = parse(&[
            "klardrop",
            "interactive",
            "--no-motion",
            "--no-mouse",
            "--no-color",
            "--line-mode",
            "--timeout",
            "5",
        ]);
        let Command::Interactive {
            no_motion,
            no_mouse,
            no_color,
            line_mode,
            timeout,
            ..
        } = interactive_of(&cli)
        else {
            panic!("expected the interactive command");
        };
        assert!(*no_motion && *no_mouse && *no_color && *line_mode);
        assert_eq!(timeout.as_deref(), Some("5"));
    }

    #[test]
    fn an_interactive_run_without_a_terminal_fails_with_terminal_required() {
        // `cargo test` gives this process piped stdio, so the TUI must refuse.
        let cli = parse(&["klardrop", "interactive"]);
        let command = interactive_of(&cli);
        let out = Output::new(false, false);
        let error = run(&cli, command, &out).expect_err("no terminal here");
        assert_eq!(error.code, ErrorCode::TerminalRequired);
        assert_eq!(error.code.exit_code(), 2);
        assert!(
            error.message.contains("stdin and stdout"),
            "the message must say what is missing: {}",
            error.message
        );
    }
}
