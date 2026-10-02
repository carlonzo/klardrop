//! An interrupted wait.
//!
//! Ctrl-C cancels nothing: the daemon is never told to stop and keeps sending.
//! The contract is that the operator still learns *which* request is in flight,
//! so it can be inspected afterwards — not that the wait reports success or
//! failure, either of which would be a lie.
//!
//! Before this was handled, the client died on the default disposition: the
//! shell reported exit 130 and stdout was empty, so the request id was lost with
//! the process and the only way to find the transfer again was to guess. The
//! assertions below fail on that code.
//!
//! Both platforms run the same contract, because both deliver an interrupt in
//! their own way — `SIGINT` on Unix, a console control event on Windows — and
//! both must produce the same envelope. Only the delivery is platform-specific,
//! so only [`interrupted_run`] is duplicated; the assertions are written once
//! and both waits use them.

mod support;

use std::process::{Command, Output, Stdio};
use std::time::Duration;

use support::{Fixture, TempDir};

const CLI: &str = env!("CARGO_BIN_EXE_klardrop");

/// How long to let a command reach its wait before interrupting it. Long enough
/// that the wait — not the submission — is what gets interrupted; and if a slow
/// machine submitted late anyway, the interrupt flag is sticky, so the wait
/// would still end the way it is asserted to end.
#[cfg(windows)]
const SETTLE: Duration = Duration::from_millis(1000);
#[cfg(not(windows))]
const SETTLE: Duration = Duration::from_millis(400);

struct Run {
    code: Option<i32>,
    stdout: String,
    /// Raw status, so a test can ask whether the process exited or was killed.
    /// Exit code 130 comes from the client's own handler; a signal would mean
    /// the default disposition did it, which is the bug.
    #[cfg(unix)]
    status: std::process::ExitStatus,
    stderr: String,
}

impl From<Output> for Run {
    fn from(output: Output) -> Self {
        Self {
            code: output.status.code(),
            stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
            #[cfg(unix)]
            status: output.status,
            stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        }
    }
}

impl Run {
    fn json(&self) -> serde_json::Value {
        serde_json::from_str(&self.stdout).unwrap_or_else(|error| {
            panic!("stdout is not one JSON value ({error}): {:?}", self.stdout)
        })
    }
}

/// Runs the client to completion with an isolated environment.
fn run(control: &std::path::Path, args: &[&str], home: &TempDir) -> Run {
    let mut command = client_command(control, home, args);
    command.stdin(Stdio::null());
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    Run::from(command.output().expect("run klardrop"))
}

/// The client, isolated from any real daemon and with its output captured.
fn client_command(control: &std::path::Path, home: &TempDir, args: &[&str]) -> Command {
    let mut command = Command::new(CLI);
    command
        .arg("--control-file")
        .arg(control)
        .args(args)
        .env("HOME", home.path())
        .env("XDG_RUNTIME_DIR", home.path())
        .env("XDG_CACHE_HOME", home.path())
        .env("NO_COLOR", "1")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    command
}

/// A fixture with `behaviors`, an isolated `HOME`, and a `payload` to share.
struct Scene {
    fixture: Fixture,
    home: TempDir,
    payload: std::path::PathBuf,
}

fn scene(tag: &str, behaviors: &[&str]) -> Scene {
    let fixture = Fixture::start(tag, behaviors);
    let home = TempDir::new(&format!("{tag}-home"));
    let payload = fixture.dir().join("payload.bin");
    std::fs::write(&payload, vec![b'x'; 64]).expect("write payload");
    Scene {
        fixture,
        home,
        payload,
    }
}

/// The contract the two platforms below both assert: exit 130, an envelope that
/// says `unknown` and names the request, and a request the daemon still holds.
fn assert_interrupted_wait(fixture: &Fixture, home: &TempDir, finished: &Run) {
    // 130 is what separates "the operator stopped watching" from "the transfer
    // failed". It also happens to be what the default disposition produces, so
    // the exit code alone proves nothing — the envelope below does.
    assert_eq!(
        finished.code,
        Some(130),
        "interrupted wait must exit 130, stderr: {}",
        finished.stderr
    );

    let value = finished.json();
    assert_eq!(value["ok"], false, "an interrupted wait is not a success");
    assert_eq!(
        value["status"], "unknown",
        "delivery is unproven, so the client must say unknown rather than completed"
    );
    assert_eq!(value["error"]["code"], "cancelled");

    let request_id = value["requestId"]
        .as_str()
        .unwrap_or_else(|| panic!("the request id is the whole point; got {value}"));
    assert!(
        !request_id.is_empty(),
        "the interrupted envelope must carry the request id"
    );

    // And the id must actually work afterwards: the daemon was never told to
    // stop, so this is a live transfer the caller can still ask about.
    let follow_up = run(
        fixture.control(),
        &["transfers", "--id", request_id, "--json"],
        home,
    );
    assert_eq!(
        follow_up.code,
        Some(0),
        "the interrupted request must still be inspectable; stderr: {}",
        follow_up.stderr
    );
    assert_eq!(
        follow_up.json()["request"]["requestId"],
        request_id,
        "the daemon must still hold the request the client walked away from"
    );
}

#[test]
fn interrupting_a_share_wait_names_the_request_and_admits_it_is_unknown() {
    // `share_slow` holds the acknowledgement back long enough for the
    // interrupt to land while the client is genuinely still waiting.
    let scene = scene("interrupt-share", &["share_slow"]);
    let payload = scene.payload.to_str().expect("utf-8").to_string();
    let finished = interrupted_run(&mut client_command(
        scene.fixture.control(),
        &scene.home,
        &[
            "share",
            "--to",
            "11112222",
            &payload,
            "--wait",
            "--timeout",
            "60",
            "--json",
        ],
    ));

    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        assert_eq!(
            finished.status.signal(),
            None,
            "the client must handle SIGINT itself, not be killed by it"
        );
    }
    assert_interrupted_wait(&scene.fixture, &scene.home, &finished);
}

#[test]
fn interrupting_a_discover_wait_stops_instead_of_polling_to_the_window_end() {
    // `empty_state` never lists a device, so this one keeps polling for the
    // whole `--wait` window unless the interrupt ends it. Thirty seconds is far
    // more than the interrupt needs and far less than a person should wait for
    // a test to fail: ignoring the flag costs half a minute, not a hang.
    const WINDOW_SECONDS: u64 = 30;

    let scene = scene("interrupt-discover", &["empty_state"]);
    let window = WINDOW_SECONDS.to_string();
    let started = std::time::Instant::now();
    let finished = interrupted_run(&mut client_command(
        scene.fixture.control(),
        &scene.home,
        &[
            "discover",
            "--wait",
            &window,
            "--timeout",
            &window,
            "--json",
        ],
    ));
    let elapsed = started.elapsed();

    assert!(
        elapsed < Duration::from_secs(WINDOW_SECONDS),
        "the client ignored the interrupt and polled for the whole {WINDOW_SECONDS}s \
         window ({elapsed:?})"
    );

    assert_eq!(
        finished.code,
        Some(130),
        "an interrupted discovery wait must exit 130, stderr: {}",
        finished.stderr
    );
    let value = finished.json();
    assert!(
        value.is_object(),
        "a completed discovery prints the legacy device array; this one was \
         interrupted, so stdout must be the failure envelope, not `[]`: {value}"
    );
    assert_eq!(value["ok"], false);
    assert_eq!(value["error"]["code"], "cancelled");

    // Nothing was sent, so there is no delivery state to report — and the
    // message must not imply that a transfer exists to inspect.
    let message = value["error"]["message"].as_str().unwrap_or_default();
    assert!(
        message.contains("nothing was sent"),
        "the cancelled discovery must say it sent nothing: {message}"
    );
}

// ------------------------------------------------------------- delivery

/// Runs `command`, waits until it has settled into its wait, delivers the
/// platform's interrupt to it alone, and returns how it finished.
#[cfg(unix)]
fn interrupted_run(command: &mut Command) -> Run {
    let child = command.spawn().expect("spawn klardrop");
    let submitted = std::time::Instant::now();
    loop {
        std::thread::sleep(Duration::from_millis(50));
        // SAFETY: the pid comes from a Child this test owns and has not reaped.
        let alive = unsafe { libc::kill(child.id() as libc::pid_t, 0) } == 0;
        assert!(alive, "the client exited before it could be interrupted");
        if submitted.elapsed() >= SETTLE {
            break;
        }
    }

    // SAFETY: same pid, and it is still this test's unreaped child.
    let signalled = unsafe { libc::kill(child.id() as libc::pid_t, libc::SIGINT) };
    assert_eq!(signalled, 0, "could not deliver SIGINT to the client");

    Run::from(child.wait_with_output().expect("wait for klardrop"))
}

/// `CTRL_BREAK_EVENT` rather than `CTRL_C_EVENT`, and a new process group so
/// the event is aimed at this child alone: Ctrl-C is broadcast to every process
/// sharing the console — which would take the test and the fixture daemon with
/// it — and `CREATE_NEW_PROCESS_GROUP` also switches Ctrl-C off for the group,
/// leaving Ctrl-Break as the event that can be delivered to one process. The
/// client answers both, which is why this still tests the handler.
#[cfg(windows)]
fn interrupted_run(command: &mut Command) -> Run {
    use std::os::windows::process::CommandExt;
    use windows_sys::Win32::System::Console::{GenerateConsoleCtrlEvent, CTRL_BREAK_EVENT};
    use windows_sys::Win32::System::Threading::CREATE_NEW_PROCESS_GROUP;

    ensure_console();
    command.creation_flags(CREATE_NEW_PROCESS_GROUP);
    let child = command.spawn().expect("spawn klardrop");

    std::thread::sleep(SETTLE);

    // SAFETY: a plain console call whose only argument this test owns — the
    // group id of the child spawned into its own process group above.
    let delivered = unsafe { GenerateConsoleCtrlEvent(CTRL_BREAK_EVENT, child.id()) };
    assert_ne!(
        delivered, 0,
        "could not deliver CTRL_BREAK_EVENT to the client; it may have exited early"
    );

    let finished = child.wait_with_output().expect("wait for klardrop");
    // Unlike a Unix signal there is no separate "was it a signal" flag to read.
    // Windows ends a process on the default disposition with
    // `STATUS_CONTROL_C_EXIT` (0xC000013A), and `ExitStatus` on Windows is a
    // plain `u32`, so `code()` reports that as `Some(-1073741766)` rather than
    // as no code at all. `Some(130)` is therefore what rules the default
    // disposition out, because only the client's own envelope produces 130.
    Run::from(finished)
}

/// A console event can only be delivered to a process that has a console. A
/// Windows CI step normally does; a service-hosted one does not. Attaching one
/// when there is none is harmless, and without it the assertions above fail on
/// the client having been killed by the default disposition — which is a real
/// defect worth failing on, not something to skip around.
#[cfg(windows)]
fn ensure_console() {
    use windows_sys::Win32::System::Console::{AllocConsole, GetConsoleCP};

    // SAFETY: neither call takes an argument or retains anything;
    // `GetConsoleCP` returns 0 exactly when this process has no console.
    if unsafe { GetConsoleCP() } == 0 {
        assert_ne!(
            unsafe { AllocConsole() },
            0,
            "the Windows interrupted-wait tests need a console to deliver a control event"
        );
    }
}
