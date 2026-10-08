//! Command-line surface. Kept free of I/O so that argument validation can be
//! unit-tested and so that `--version`/`--help` cost nothing.

use std::path::PathBuf;

use clap::{Parser, Subcommand, ValueEnum};

/// Read-only client for a running Klardrop daemon.
///
/// The client never starts, spawns or embeds an engine: it reads the daemon's
/// control file and speaks authenticated loopback HTTP to it.
#[derive(Debug, Parser)]
#[command(
    name = "klardrop",
    version,
    about = "Read-only CLI client for a running Klardrop daemon",
    long_about = None,
    disable_colored_help = true,
    subcommand_required = false,
    arg_required_else_help = false
)]
pub struct Cli {
    /// Path to the daemon control.json (overrides the search path).
    #[arg(long, global = true, value_name = "PATH")]
    pub control_file: Option<PathBuf>,

    /// Write diagnostics to stderr (never to stdout).
    #[arg(long, global = true)]
    pub debug: bool,

    #[command(subcommand)]
    pub command: Option<Command>,
}

/// `--theme` choices. Declared here, with no UI code attached, so argument
/// parsing stays testable on its own and `cli` keeps no dependency on the TUI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum ThemeArg {
    /// Resolve from what the terminal actually offers.
    Auto,
    /// Use the host's system palette when one is readable.
    System,
    /// Always use Tokyo Night.
    TokyoNight,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// List devices the daemon currently sees.
    Devices {
        /// Emit the versioned JSON envelope on stdout.
        #[arg(long)]
        json: bool,
        /// Whole-command deadline in seconds (default: 15).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
    },

    /// Show daemon, self device, protocol and update status.
    Status {
        /// Emit the versioned JSON envelope on stdout.
        #[arg(long)]
        json: bool,
        /// Whole-command deadline in seconds (default: 15).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
    },

    /// Poll for devices until one appears or the wait window expires.
    ///
    /// Compatibility command: kept because the previous CLI shipped it. Without
    /// --json it is a convenience wrapper around `devices`; with --json it
    /// preserves the legacy array shape.
    Discover {
        /// Emit the legacy device array on stdout (not the versioned envelope).
        #[arg(long)]
        json: bool,
        /// Discovery window in seconds (default: 5).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        wait: Option<String>,
        /// Whole-command deadline in seconds (default: 20).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
    },

    /// Send files, text or the clipboard to a device.
    ///
    /// The payload kinds are alternatives: pass paths, `--text` or
    /// `--clipboard`, never a mixture. Use `--` before a dash-prefixed file
    /// name. Without `--wait` the command reports `queued` — never "delivered".
    Share {
        /// Target device id, or an unambiguous prefix of one. Required unless
        /// `--pick`: an explicit command never opens a device picker.
        ///
        /// Not marked `required_unless_present` on purpose. Clap's own
        /// "required arguments were not provided" error prints the argument
        /// names and nothing else, so it could never point a person at the
        /// interactive entry point — and the alternative, letting clap reject
        /// it first, makes this command's own guidance unreachable. `share::run`
        /// checks for it and answers with the message that says what to do.
        #[arg(long, value_name = "DEVICE_ID")]
        to: Option<String>,
        /// Choose the device interactively in the TUI's device picker.
        ///
        /// The entry point for callers that cannot supply a device id — the
        /// desktop entry, the Nautilus extension, the Omarchy menu helper.
        /// Ordinary automation never needs it: without `--pick` a target is
        /// mandatory, so a script can never be left waiting on a prompt.
        #[arg(long)]
        pick: bool,
        /// Share this text instead of files.
        #[arg(long, value_name = "TEXT")]
        text: Option<String>,
        /// Share the daemon's current clipboard content instead of files.
        #[arg(long)]
        clipboard: bool,
        /// Files or directories to share.
        #[arg(value_name = "PATH")]
        paths: Vec<PathBuf>,
        /// Wait until every submitted item reaches a terminal outcome.
        #[arg(long)]
        wait: bool,
        /// Whole-command deadline in seconds (default: 120).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
        /// Emit the versioned JSON envelope on stdout.
        #[arg(long)]
        json: bool,
    },

    /// Show what the daemon knows about recent share requests.
    Transfers {
        /// Show exactly this request id instead of the whole registry.
        #[arg(long, value_name = "REQUEST_ID")]
        id: Option<String>,
        /// Whole-command deadline in seconds (default: 15).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
        /// Emit the versioned JSON envelope on stdout.
        #[arg(long)]
        json: bool,
    },

    /// Run the Klardrop engine, forwarding every argument to `klardrop-engine`.
    ///
    /// This is the one and only way a client starts an engine, and it does not
    /// start one itself: it runs the sibling `klardrop-engine` binary with the
    /// argument vector it was given, never through a shell. The systemd unit
    /// and the Qt launcher both rely on it.
    Daemon {
        /// Arguments for the engine, forwarded verbatim.
        #[arg(
            trailing_var_arg = true,
            allow_hyphen_values = true,
            value_name = "ARGS"
        )]
        args: Vec<String>,
    },
    ///
    /// The legacy syntaxes still work: `send DEVICE_ID --file PATH`,
    /// `send DEVICE_ID --text TEXT`, and `send DEVICE_ID CONTENT`, where a
    /// positional value containing a path separator is treated as a file and
    /// anything else as text.
    Send {
        /// Target device id, or an unambiguous prefix of one.
        #[arg(value_name = "DEVICE_ID")]
        device_id: String,
        /// Legacy positional content: a file path, or text without a separator.
        #[arg(value_name = "CONTENT")]
        content: Option<String>,
        /// Send this file (repeatable).
        #[arg(long = "file", short = 'f', value_name = "PATH")]
        file: Vec<PathBuf>,
        /// Send this text instead of a file.
        #[arg(long = "text", short = 't', value_name = "TEXT")]
        text: Option<String>,
        /// Do not wait for delivery; report `queued` instead.
        #[arg(long)]
        no_wait: bool,
        /// Whole-command deadline in seconds (default: 120).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
        /// Emit the versioned JSON envelope on stdout.
        #[arg(long)]
        json: bool,
    },

    /// Open the two-pane interactive TUI against a running daemon.
    ///
    /// Requires a real terminal on both stdin and stdout: redirected input is
    /// not a keyboard, and this command never reads it. A bare `klardrop` with
    /// no subcommand opens the same TUI when, and only when, both ends are
    /// terminals — otherwise it prints help and exits 2.
    Interactive {
        /// Colour palette. `auto` resolves from what the terminal offers.
        #[arg(long, value_name = "THEME", value_enum, default_value_t = ThemeArg::Auto)]
        theme: ThemeArg,
        /// Do not animate the connection and transfer indicators.
        #[arg(long)]
        no_motion: bool,
        /// Do not capture the mouse; terminal selection keeps working.
        #[arg(long)]
        no_mouse: bool,
        /// Draw without any colour at all.
        #[arg(long)]
        no_color: bool,
        /// Print the daemon state as lines and exit, without taking the
        /// terminal. This is also what happens on its own when the terminal
        /// cannot host a screen: `TERM` is `dumb` or unset, or the window is
        /// smaller than 20x8.
        #[arg(long)]
        line_mode: bool,
        /// Deadline for the initial daemon connection, in seconds (default: 15).
        #[arg(long, value_name = "SECS", allow_hyphen_values = true)]
        timeout: Option<String>,
    },
}

impl Command {
    /// Stable command name used in the JSON envelope.
    pub fn name(&self) -> &'static str {
        match self {
            Self::Devices { .. } => "devices",
            Self::Status { .. } => "status",
            Self::Discover { .. } => "discover",
            Self::Share { .. } => "share",
            Self::Transfers { .. } => "transfers",
            Self::Send { .. } => "send",
            Self::Interactive { .. } => "interactive",
            Self::Daemon { .. } => "daemon",
        }
    }

    /// Whether this command was asked for machine-readable output.
    pub fn json(&self) -> bool {
        match self {
            Self::Devices { json, .. }
            | Self::Status { json, .. }
            | Self::Discover { json, .. }
            | Self::Share { json, .. }
            | Self::Transfers { json, .. }
            | Self::Send { json, .. } => *json,
            // The TUI owns the screen, so it never produces a stdout envelope.
            Self::Interactive { .. } => false,
            // A forwarded engine owns the terminal and reports for itself.
            Self::Daemon { .. } => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_global_flags_with_a_subcommand() {
        let cli = Cli::try_parse_from([
            "klardrop",
            "--control-file",
            "/tmp/control.json",
            "--debug",
            "devices",
            "--json",
            "--timeout",
            "3",
        ])
        .expect("valid invocation");
        assert_eq!(cli.control_file, Some(PathBuf::from("/tmp/control.json")));
        assert!(cli.debug);
        let command = cli.command.expect("subcommand present");
        assert_eq!(command.name(), "devices");
        assert!(command.json());
    }

    #[test]
    fn accepts_no_subcommand() {
        let cli = Cli::try_parse_from(["klardrop"]).expect("bare invocation is allowed");
        assert!(cli.command.is_none());
    }

    #[test]
    fn discover_takes_wait_and_timeout() {
        let cli = Cli::try_parse_from(["klardrop", "discover", "--wait", "2.5", "--timeout", "9"])
            .expect("valid invocation");
        match cli.command.expect("subcommand present") {
            Command::Discover { wait, timeout, .. } => {
                assert_eq!(wait.as_deref(), Some("2.5"));
                assert_eq!(timeout.as_deref(), Some("9"));
            }
            other => panic!("expected discover, got {other:?}"),
        }
    }

    #[test]
    fn global_flag_is_accepted_after_the_subcommand() {
        let cli = Cli::try_parse_from(["klardrop", "status", "--control-file", "/c.json"])
            .expect("valid invocation");
        assert_eq!(cli.control_file, Some(PathBuf::from("/c.json")));
    }

    #[test]
    fn share_requires_a_target_and_one_payload_kind() {
        let cli = Cli::try_parse_from(["klardrop", "share", "--to", "11112222", "a.pdf", "b.txt"])
            .expect("valid invocation");
        match cli.command.expect("subcommand present") {
            Command::Share {
                to,
                pick,
                paths,
                wait,
                json,
                clipboard,
                text,
                ..
            } => {
                assert_eq!(to.as_deref(), Some("11112222"));
                assert!(!pick);
                assert_eq!(paths, vec![PathBuf::from("a.pdf"), PathBuf::from("b.txt")]);
                assert!(!wait && !json && !clipboard);
                assert_eq!(text, None);
            }
            other => panic!("expected share, got {other:?}"),
        }

        // A missing --to is still a usage error, but the parser must let it
        // through: clap's own "required arguments were not provided" text names
        // the flag and nothing else, so it could never point a person at
        // `interactive` / `--pick`. `share::run` owns that message instead —
        // `share_without_a_target_names_the_interactive_entry_point` pins it.
        let cli = Cli::try_parse_from(["klardrop", "share", "a.pdf"])
            .expect("parsing must succeed so the guidance is reachable");
        match cli.command.expect("subcommand present") {
            Command::Share { to, pick, .. } => {
                assert_eq!(to, None);
                assert!(!pick, "an imperative share must never pick for you");
            }
            other => panic!("expected share, got {other:?}"),
        }
    }

    #[test]
    fn pick_is_the_only_way_to_leave_the_device_undecided() {
        let cli = Cli::try_parse_from(["klardrop", "share", "--pick", "--clipboard"])
            .expect("--pick alone is enough to defer the device choice");
        match cli.command.expect("subcommand present") {
            Command::Share {
                to,
                pick,
                clipboard,
                ..
            } => {
                assert_eq!(to, None);
                assert!(pick && clipboard);
            }
            other => panic!("expected share, got {other:?}"),
        }

        // The desktop entry's exact shape: a payload and no target.
        let cli = Cli::try_parse_from(["klardrop", "share", "--pick", "/tmp/report.pdf"])
            .expect("valid invocation");
        match cli.command.expect("subcommand present") {
            Command::Share {
                to, pick, paths, ..
            } => {
                assert_eq!(to, None);
                assert!(pick);
                assert_eq!(paths, vec![PathBuf::from("/tmp/report.pdf")]);
            }
            other => panic!("expected share, got {other:?}"),
        }
    }

    #[test]
    fn daemon_forwards_its_arguments_verbatim() {
        let cli = Cli::try_parse_from([
            "klardrop",
            "daemon",
            "--data-dir",
            "/tmp/x",
            "--verbose",
            "not-a-flag",
        ])
        .expect("valid invocation");
        match cli.command.expect("subcommand present") {
            Command::Daemon { args } => {
                // Everything after `daemon` is the engine's, including things that
                // look like this client's own flags.
                assert_eq!(
                    args,
                    vec!["--data-dir", "/tmp/x", "--verbose", "not-a-flag"]
                );
            }
            other => panic!("expected daemon, got {other:?}"),
        }
        let bare = Cli::try_parse_from(["klardrop", "daemon"]).expect("bare daemon");
        assert!(!bare.command.expect("daemon").json());
    }

    #[test]
    fn share_keeps_dash_prefixed_paths_and_flag_like_names() {
        let cli = Cli::try_parse_from([
            "klardrop",
            "share",
            "--to",
            "11112222",
            "--",
            "-weird-name.pdf",
            "--not-a-flag",
            "notes.txt",
        ])
        .expect("valid invocation");
        match cli.command.expect("subcommand present") {
            Command::Share { paths, .. } => assert_eq!(
                paths,
                vec![
                    PathBuf::from("-weird-name.pdf"),
                    PathBuf::from("--not-a-flag"),
                    PathBuf::from("notes.txt"),
                ]
            ),
            other => panic!("expected share, got {other:?}"),
        }
    }

    #[test]
    fn flags_after_paths_still_parse_and_dash_names_need_a_separator() {
        // Documented usage: flags may follow the paths.
        let cli = Cli::try_parse_from([
            "klardrop",
            "share",
            "--to",
            "11112222",
            "a.pdf",
            "b.txt",
            "--wait",
            "--timeout",
            "120",
            "--json",
        ])
        .expect("valid invocation");
        match cli.command.expect("subcommand present") {
            Command::Share {
                paths,
                wait,
                json,
                timeout,
                ..
            } => {
                assert_eq!(paths, vec![PathBuf::from("a.pdf"), PathBuf::from("b.txt")]);
                assert!(wait && json);
                assert_eq!(timeout.as_deref(), Some("120"));
            }
            other => panic!("expected share, got {other:?}"),
        }

        // A dash-prefixed file name needs the `--` separator; without it the
        // token really is a flag, and an unknown one is a usage error.
        assert!(Cli::try_parse_from(["klardrop", "share", "--to", "d", "-weird.pdf"]).is_err());
        assert!(
            Cli::try_parse_from(["klardrop", "share", "--to", "d", "--definitely-not-a-flag"])
                .is_err()
        );
    }

    #[test]
    fn share_takes_text_or_clipboard_with_wait_and_timeout() {
        let cli = Cli::try_parse_from([
            "klardrop",
            "share",
            "--to",
            "11112222",
            "--text",
            "hello world",
            "--wait",
            "--timeout",
            "30",
            "--json",
        ])
        .expect("valid invocation");
        let command = cli.command.expect("subcommand present");
        match &command {
            Command::Share {
                text,
                wait,
                json,
                timeout,
                paths,
                ..
            } => {
                assert_eq!(text.as_deref(), Some("hello world"));
                assert!(*wait && *json);
                assert_eq!(timeout.as_deref(), Some("30"));
                assert!(paths.is_empty());
            }
            other => panic!("expected share, got {other:?}"),
        }
        assert!(command.json());
    }

    #[test]
    fn transfers_id_is_optional() {
        let cli = Cli::try_parse_from(["klardrop", "transfers", "--json"]).expect("valid");
        match cli.command.expect("subcommand present") {
            Command::Transfers { id, json, .. } => {
                assert_eq!(id, None);
                assert!(json);
            }
            other => panic!("expected transfers, got {other:?}"),
        }

        let cli = Cli::try_parse_from(["klardrop", "transfers", "--id", "req-1"]).expect("valid");
        match cli.command.expect("subcommand present") {
            Command::Transfers { id, .. } => assert_eq!(id.as_deref(), Some("req-1")),
            other => panic!("expected transfers, got {other:?}"),
        }
    }

    #[test]
    fn send_keeps_every_legacy_syntax() {
        for args in [
            vec!["klardrop", "send", "11112222", "--file", "/tmp/a.pdf"],
            vec![
                "klardrop",
                "send",
                "11112222",
                "-f",
                "/tmp/a.pdf",
                "-f",
                "/tmp/b.pdf",
            ],
            vec!["klardrop", "send", "11112222", "--text", "hello"],
            vec!["klardrop", "send", "11112222", "Hello World"],
            vec![
                "klardrop",
                "send",
                "11112222",
                "/tmp/report.pdf",
                "--no-wait",
            ],
        ] {
            let cli = Cli::try_parse_from(args.clone()).unwrap_or_else(|e| panic!("{args:?}: {e}"));
            match cli.command.expect("subcommand present") {
                Command::Send { device_id, .. } => assert_eq!(device_id, "11112222"),
                other => panic!("{args:?}: expected send, got {other:?}"),
            }
        }
    }

    #[test]
    fn send_content_is_a_single_positional_value() {
        let cli =
            Cli::try_parse_from(["klardrop", "send", "11112222", "Hello World"]).expect("valid");
        match cli.command.expect("subcommand present") {
            Command::Send { content, file, .. } => {
                assert_eq!(content.as_deref(), Some("Hello World"));
                assert!(file.is_empty());
            }
            other => panic!("expected send, got {other:?}"),
        }
    }

    #[test]
    fn every_subcommand_reports_its_stable_name() {
        for (args, expected) in [
            (vec!["klardrop", "devices"], "devices"),
            (vec!["klardrop", "status"], "status"),
            (vec!["klardrop", "discover"], "discover"),
            (vec!["klardrop", "share", "--to", "d"], "share"),
            (vec!["klardrop", "transfers"], "transfers"),
            (vec!["klardrop", "send", "d"], "send"),
        ] {
            let cli = Cli::try_parse_from(args.clone()).unwrap_or_else(|e| panic!("{args:?}: {e}"));
            assert_eq!(cli.command.expect("subcommand present").name(), expected);
        }
    }
}
