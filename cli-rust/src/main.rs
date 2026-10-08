//! Entry point: parse, dispatch, map a [`CliError`] onto the documented exit
//! code.
//!
//! Output discipline lives in [`envelope::Output`]: with `--json`, stdout holds
//! exactly one JSON value; every diagnostic goes to stderr.

mod cli;
mod client;
mod commands;
mod control_file;
mod envelope;
mod interrupt;
mod state;
mod tui;

use clap::{CommandFactory, Parser};

use cli::{Cli, Command, ThemeArg};

use envelope::Output;

/// Bare `klardrop` outside a terminal prints help and exits 2. Inside a
/// terminal it opens the TUI, because that is what a person typing the bare
/// command means; an agent or a pipe still gets help on stdout and exit 2.
const NO_COMMAND_EXIT: i32 = 2;

fn main() {
    std::process::exit(run());
}

fn run() -> i32 {
    let mut cli = Cli::parse();
    if cli.command.is_none() {
        if !tui::terminal::is_interactive_terminal() {
            let help = <Cli as CommandFactory>::command().render_help();
            println!("{help}");
            return NO_COMMAND_EXIT;
        }
        cli.command = Some(bare_interactive());
    }

    let Some(command) = cli.command.as_ref() else {
        let help = <Cli as CommandFactory>::command().render_help();
        println!("{help}");
        return NO_COMMAND_EXIT;
    };

    let out = Output::new(command.json(), cli.debug);

    // Before anything that can block, and after argument parsing so that
    // `--help`, `--version` and a usage error keep working even on a host where
    // the handler cannot be registered. The TUI reads Ctrl-C as a key and never
    // needs this; the unattended commands do, because a killed wait would
    // otherwise take the request id with it. A registration failure is reported
    // rather than ignored: carrying on without it is how the request id is
    // lost in the first place.
    if let Err(error) = interrupt::install() {
        return out.fail(command.name(), error);
    }

    let result =
        match command {
            Command::Devices { .. } => commands::devices::run(&cli, command, &out)
                .map(|()| commands::CommandOutcome::Success),
            Command::Status { .. } => commands::status::run(&cli, command, &out)
                .map(|()| commands::CommandOutcome::Success),
            Command::Discover { .. } => commands::discover::run(&cli, command, &out)
                .map(|()| commands::CommandOutcome::Success),
            Command::Share { .. } => commands::share::run(&cli, command, &out),
            Command::Transfers { .. } => commands::transfers::run(&cli, command, &out),
            Command::Send { .. } => commands::send::run(&cli, command, &out),
            Command::Interactive { .. } => commands::interactive::run(&cli, command, &out)
                .map(|()| commands::CommandOutcome::Success),
            Command::Daemon { .. } => commands::daemon::run(&cli, command, &out),
        }
        .map(commands::CommandOutcome::exit_code);

    match result {
        Ok(code) => code,
        Err(error) => out.fail(command.name(), error),
    }
}

/// What a bare `klardrop` runs when there is a terminal: every display flag at
/// its documented default.
fn bare_interactive() -> Command {
    Command::Interactive {
        theme: ThemeArg::Auto,
        no_motion: false,
        no_mouse: false,
        no_color: false,
        line_mode: false,
        timeout: None,
    }
}
