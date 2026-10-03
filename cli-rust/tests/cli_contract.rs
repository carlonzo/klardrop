//! End-to-end contract tests for the `klardrop` client.
//!
//! Every test drives the real binary against the fixture daemon
//! (`klardrop-fixture-daemon`) over loopback. Nothing here touches a real
//! daemon: each test gets its own temp directory, and the child processes get
//! an isolated `XDG_RUNTIME_DIR`/`HOME`/`LOCALAPPDATA` so the search order can
//! never resolve to the developer's real control file.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

const CLI: &str = env!("CARGO_BIN_EXE_klardrop");
const FIXTURE_DAEMON: &str = env!("CARGO_BIN_EXE_klardrop-fixture-daemon");
const FIXTURE_TOKEN: &str = "fixture-token-0123456789abcdef";

/// Generous upper bounds so a slow machine does not fail a "does not hang"
/// assertion, while still catching an unbounded wait.
const STALE_BOUND: Duration = Duration::from_secs(10);
const DEADLINE_BOUND: Duration = Duration::from_secs(10);
const HUGE_BODY_BOUND: Duration = Duration::from_secs(20);

// ---------------------------------------------------------------- utilities

static COUNTER: AtomicUsize = AtomicUsize::new(0);

struct TempDir {
    path: PathBuf,
}

impl TempDir {
    fn new(tag: &str) -> Self {
        let index = COUNTER.fetch_add(1, Ordering::SeqCst);
        let path = std::env::temp_dir().join(format!(
            "klardrop-cli-rust-{tag}-{}-{index}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).expect("create temp dir");
        Self { path }
    }

    fn join(&self, name: &str) -> PathBuf {
        self.path.join(name)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

/// A running fixture daemon plus the isolated directories it lives in.
struct Fixture {
    _dir: TempDir,
    child: Child,
    control: PathBuf,
}

impl Fixture {
    fn start(tag: &str, behaviors: &[&str]) -> Self {
        let dir = TempDir::new(tag);
        let control = dir.join("control.json");
        let child = Command::new(FIXTURE_DAEMON)
            .env("KLARDROP_FIXTURE_CONTROL_FILE", &control)
            .env("KLARDROP_FIXTURE_TOKEN", FIXTURE_TOKEN)
            .env("KLARDROP_FIXTURE_BEHAVIORS", behaviors.join(","))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn fixture daemon");

        let deadline = Instant::now() + Duration::from_secs(15);
        while Instant::now() < deadline {
            if control.exists() {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            control.exists(),
            "fixture daemon never wrote its control file"
        );
        Self {
            _dir: dir,
            child,
            control,
        }
    }

    fn control(&self) -> &Path {
        &self.control
    }

    /// The port the fixture actually bound.
    fn port(&self) -> u16 {
        let raw = fs::read_to_string(&self.control).expect("read fixture control file");
        let value: serde_json::Value = serde_json::from_str(&raw).expect("fixture control JSON");
        value["port"]
            .as_u64()
            .expect("fixture port")
            .try_into()
            .expect("fixture port fits u16")
    }

    fn dir(&self) -> &Path {
        &self._dir.path
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct CliRun {
    code: i32,
    stdout: String,
    stderr: String,
    elapsed: Duration,
}

impl CliRun {
    fn json(&self) -> serde_json::Value {
        serde_json::from_str(&self.stdout).unwrap_or_else(|e| {
            panic!(
                "stdout is not exactly one JSON value ({e}): {:?}",
                self.stdout
            )
        })
    }

    fn error_code(&self) -> String {
        self.json()["error"]["code"]
            .as_str()
            .expect("error.code is a string")
            .to_string()
    }

    fn assert_no_ansi(&self) {
        for (stream, text) in [("stdout", &self.stdout), ("stderr", &self.stderr)] {
            assert!(
                !text.contains('\u{1b}'),
                "{stream} contains an ANSI escape: {text:?}"
            );
        }
    }
}

/// Runs the CLI with an isolated environment. `control` of `None` exercises the
/// search order instead of the override.
fn run(control: Option<&Path>, args: &[&str], dir: &Path) -> CliRun {
    let mut command = Command::new(CLI);
    command.stdin(Stdio::null());
    // The control-file flag goes first: `share`'s `--` separator would
    // otherwise turn a trailing global flag into a file path.
    if let Some(control) = control {
        command.arg("--control-file").arg(control);
    }
    command.args(args);
    // Never let an ambient proxy or a real control file influence a test.
    command
        .env("XDG_RUNTIME_DIR", dir.join("xdg"))
        .env("HOME", dir.join("home"))
        .env("LOCALAPPDATA", dir.join("appdata"));

    let started = Instant::now();
    let output = command.output().expect("run klardrop CLI");
    CliRun {
        code: output.status.code().expect("process exited normally"),
        stdout: String::from_utf8(output.stdout).expect("stdout is utf-8"),
        stderr: String::from_utf8(output.stderr).expect("stderr is utf-8"),
        elapsed: started.elapsed(),
    }
}

fn write_control(path: &Path, contents: &str) {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).expect("create parent dir");
    }
    fs::write(path, contents).expect("write control file");
}

/// Reads the fixture's port so a hand-written control file can point at it.
fn control_with_token(path: &Path, port: u16, token: &str) {
    write_control(
        path,
        &format!(r#"{{"port":{port},"token":"{token}","apiVersion":1}}"#),
    );
}

/// Writes a payload file inside the fixture's isolated directory. Real file
/// contents matter: the fixture reports each item's real size, so a test can
/// tell two concurrent transfers apart by their own numbers.
fn payload(dir: &Path, name: &str, contents: &[u8]) -> PathBuf {
    let path = dir.join(name);
    fs::write(&path, contents).expect("write payload file");
    path
}

// -------------------------------------------------------------------- tests

#[test]
fn devices_json_is_exactly_one_clean_json_value() {
    let fixture = Fixture::start("devices-ok", &[]);
    let run = run(
        Some(fixture.control()),
        &["devices", "--json"],
        fixture.dir(),
    );

    assert_eq!(run.code, 0, "stderr: {}", run.stderr);
    run.assert_no_ansi();
    let json = run.json();
    assert_eq!(json["schemaVersion"], 1);
    assert_eq!(json["ok"], true);
    assert_eq!(json["command"], "devices");
    assert_eq!(json["daemon"]["apiVersion"], 1);
    assert_eq!(json["daemon"]["version"], "9.9.9-fixture");

    let devices = json["devices"].as_array().expect("devices array");
    assert_eq!(devices.len(), 3);
    assert_eq!(devices[0]["deviceId"], "11112222");
    assert_eq!(devices[0]["deviceName"], "Fixture Phone");
    assert_eq!(devices[0]["deviceType"], "ANDROID");
    assert_eq!(devices[0]["paired"], true);
    assert_eq!(devices[0]["reachable"], true);
    assert_eq!(devices[0]["trustStatus"], "trusted");
    assert_eq!(devices[0]["reachability"], "reachable");
    assert_eq!(devices[0]["connectionTypes"][0], "KLARDROP");
    assert_eq!(devices[0]["hasUnread"], true);
    assert_eq!(devices[0]["unreadCount"], 2);
    assert_eq!(devices[1]["paired"], false);
    assert_eq!(devices[1]["reachable"], false);
    assert_eq!(devices[2]["deviceId"], "3333bbbb");
    assert_eq!(devices[2]["reachable"], true);
}

#[test]
fn absent_control_file_reports_not_running() {
    let dir = TempDir::new("absent-control");
    let missing = dir.join("nope/control.json");
    let run = run(Some(&missing), &["devices", "--json"], &dir.path);

    assert_eq!(run.code, 3, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_not_running");
    assert_eq!(run.json()["ok"], false);
    assert!(
        run.stderr.contains("start the Klardrop daemon"),
        "message must say how to start the daemon: {}",
        run.stderr
    );
}

#[test]
fn malformed_control_files_are_rejected_with_exit_3() {
    let dir = TempDir::new("bad-control");
    let cases: Vec<(&str, String)> = vec![
        ("malformed", "{not json".to_string()),
        ("empty", String::new()),
        (
            "oversized",
            format!(r#"{{"port":1,"token":"t","pad":"{}"}}"#, "a".repeat(5000)),
        ),
        ("port-zero", r#"{"port":0,"token":"t"}"#.to_string()),
        (
            "port-too-large",
            r#"{"port":65536,"token":"t"}"#.to_string(),
        ),
        (
            "port-fractional",
            r#"{"port":80.5,"token":"t"}"#.to_string(),
        ),
        ("port-string", r#"{"port":"8080","token":"t"}"#.to_string()),
        ("port-missing", r#"{"token":"t"}"#.to_string()),
        ("token-missing", r#"{"port":1}"#.to_string()),
        ("token-empty", r#"{"port":1,"token":""}"#.to_string()),
        (
            "token-with-space",
            r#"{"port":1,"token":"a b"}"#.to_string(),
        ),
        (
            "token-with-newline",
            "{\"port\":1,\"token\":\"a\\nb\"}".to_string(),
        ),
        (
            "token-with-slash",
            r#"{"port":1,"token":"a/b"}"#.to_string(),
        ),
        (
            "token-too-long",
            format!(r#"{{"port":1,"token":"{}"}}"#, "a".repeat(257)),
        ),
        ("json-array", "[1,2,3]".to_string()),
    ];

    for (label, contents) in cases {
        let path = dir.join(&format!("{label}.json"));
        write_control(&path, &contents);
        let run = run(Some(&path), &["devices", "--json"], &dir.path);
        assert_eq!(run.code, 3, "{label}: stderr {}", run.stderr);
        assert_eq!(
            run.error_code(),
            "daemon_control_invalid",
            "{label} must be a control-file error"
        );
    }

    // A directory in place of the control file is not a regular file.
    let dir_path = dir.join("a-directory.json");
    fs::create_dir_all(&dir_path).expect("create directory");
    let run = run(Some(&dir_path), &["devices", "--json"], &dir.path);
    assert_eq!(run.code, 3);
    assert_eq!(run.error_code(), "daemon_control_invalid");
}

#[test]
fn stale_control_file_fails_fast_without_hanging() {
    let dir = TempDir::new("stale-control");
    // Bind and immediately drop a listener: the port is now closed and nothing will
    // ever answer on it. Whatever the client does with that must not be a hang.
    let port = {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        listener.local_addr().expect("addr").port()
    };
    let control = dir.join("control.json");
    control_with_token(&control, port, FIXTURE_TOKEN);

    let run = run(Some(&control), &["devices", "--json"], &dir.path);
    assert_ne!(run.code, 0, "a stale control file must not report success");
    let code = run.error_code();
    // Unix refuses the connect outright and says so. Windows surfaces the same dead
    // port as an unanswered request at the deadline instead — the identical fact about
    // the identical port, reported the only way that platform can — so the refusal is
    // asserted strictly only where it is a refusal, and this test keeps its actual
    // subject (does not hang) everywhere.
    if cfg!(windows) {
        assert!(
            matches!(code.as_str(), "daemon_unreachable" | "daemon_timeout"),
            "a dead port must be reported as unanswered, got {code}: {}",
            run.stderr,
        );
    } else {
        assert_eq!(code, "daemon_unreachable", "stderr: {}", run.stderr);
    }
    assert!(
        run.elapsed < STALE_BOUND,
        "stale control file must fail fast, took {:?}",
        run.elapsed
    );
}

#[test]
fn old_daemon_without_capabilities_still_answers() {
    let fixture = Fixture::start("no-capabilities", &["no_capabilities"]);

    let devices = run(
        Some(fixture.control()),
        &["devices", "--json"],
        fixture.dir(),
    );
    assert_eq!(devices.code, 0, "stderr: {}", devices.stderr);
    let json = devices.json();
    assert_eq!(json["ok"], true);
    assert_eq!(json["devices"].as_array().map(Vec::len), Some(3));
    assert!(
        json["daemon"]["apiVersion"].is_null(),
        "apiVersion must be unknown, got {}",
        json["daemon"]
    );

    let status = run(
        Some(fixture.control()),
        &["status", "--json"],
        fixture.dir(),
    );
    assert_eq!(status.code, 0, "stderr: {}", status.stderr);
    let json = status.json();
    assert_eq!(json["ok"], true);
    assert_eq!(json["daemon"]["apiVersion"], serde_json::Value::Null);
    assert_eq!(json["deviceCount"], 3);
    assert_eq!(json["pairedCount"], 1);
    assert_eq!(json["reachableCount"], 2);
}

#[test]
fn state_without_self_is_unsupported_not_zero_devices() {
    let fixture = Fixture::start("no-self", &["no_self"]);

    for args in [
        vec!["status", "--json"],
        vec!["devices", "--json"],
        vec!["discover", "--json", "--wait", "1"],
    ] {
        let run = run(Some(fixture.control()), &args, fixture.dir());
        assert_eq!(run.code, 3, "{args:?}: stderr {}", run.stderr);
        let json = run.json();
        assert_eq!(json["ok"], false, "{args:?}");
        assert_eq!(
            json["error"]["code"], "daemon_unsupported",
            "{args:?} must report an unsupported daemon"
        );
        assert!(
            json.get("devices").is_none(),
            "{args:?} must not fake an empty device list"
        );
    }
}

#[test]
fn oversized_response_body_is_a_protocol_error() {
    let fixture = Fixture::start("huge-body", &["huge_body"]);
    let run = run(
        Some(fixture.control()),
        &["devices", "--json", "--timeout", "5"],
        fixture.dir(),
    );

    assert_eq!(run.code, 1, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_protocol_error");
    assert!(
        run.elapsed < HUGE_BODY_BOUND,
        "oversized body must be rejected, not waited out ({:?})",
        run.elapsed
    );
}

#[test]
fn wedged_daemon_hits_the_command_deadline() {
    let fixture = Fixture::start("hang", &["hang"]);
    let started = Instant::now();
    let run = run(
        Some(fixture.control()),
        &["devices", "--json", "--timeout", "1"],
        fixture.dir(),
    );
    let elapsed = started.elapsed();

    assert_eq!(run.code, 4, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_timeout");
    assert!(
        elapsed >= Duration::from_millis(900),
        "the deadline must actually be waited for, took {elapsed:?}"
    );
    assert!(
        elapsed < DEADLINE_BOUND,
        "deadline must bound the wait, took {elapsed:?}"
    );
}

/// A peer that keeps answering — just one byte at a time — must not be able to
/// outlive `--timeout`. Per-operation socket timeouts alone would not stop it:
/// every individual read succeeds.
#[test]
fn slow_dripping_daemon_cannot_outlive_the_deadline() {
    let fixture = Fixture::start("slow-drip", &["slow_drip"]);
    let started = Instant::now();
    let run = run(
        Some(fixture.control()),
        &["devices", "--json", "--timeout", "2"],
        fixture.dir(),
    );
    let elapsed = started.elapsed();

    assert_eq!(run.code, 4, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_timeout");
    assert!(
        elapsed < DEADLINE_BOUND,
        "deadline must bound the wait, took {elapsed:?}"
    );
}

#[test]
fn invalid_timeouts_are_rejected_before_any_request() {
    let dir = TempDir::new("bad-timeouts");
    // No daemon and no control file: anything that resolves the control file or
    // opens a socket would report a different code, proving validation runs first.
    let missing = dir.join("nope/control.json");
    for value in ["0", "-1", "abc", "inf", "NaN", "3601"] {
        let run = run(
            Some(&missing),
            &["devices", "--json", "--timeout", value],
            &dir.path,
        );
        assert_eq!(run.code, 2, "--timeout {value}: stderr {}", run.stderr);
        assert_eq!(run.error_code(), "invalid_argument", "--timeout {value}");
    }

    // Against a live daemon, a rejected flag must not start polling either.
    let fixture = Fixture::start("bad-timeout-live", &["empty_state"]);
    let started = Instant::now();
    let live = run(
        Some(fixture.control()),
        &["discover", "--json", "--wait", "5", "--timeout", "0"],
        fixture.dir(),
    );
    assert_eq!(live.code, 2, "stderr: {}", live.stderr);
    assert_eq!(live.error_code(), "invalid_argument");
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "validation must precede the wait window"
    );

    let bad_wait = run(
        Some(fixture.control()),
        &["discover", "--json", "--wait", "-3"],
        fixture.dir(),
    );
    assert_eq!(bad_wait.code, 2);
    assert_eq!(bad_wait.error_code(), "invalid_argument");
}

#[test]
fn json_stdout_stays_parseable_with_debug_and_on_failure() {
    let fixture = Fixture::start("debug-json", &[]);
    let ok = run(
        Some(fixture.control()),
        &["devices", "--json", "--debug"],
        fixture.dir(),
    );
    assert_eq!(ok.code, 0);
    assert_eq!(ok.json()["ok"], true);
    assert!(
        ok.stderr.contains("klardrop: debug:"),
        "--debug must produce diagnostics on stderr: {}",
        ok.stderr
    );
    ok.assert_no_ansi();

    let dir = TempDir::new("debug-failure");
    let missing = dir.join("missing/control.json");
    let failed = run(Some(&missing), &["status", "--json", "--debug"], &dir.path);
    assert_eq!(failed.code, 3);
    let json = failed.json();
    assert_eq!(json["ok"], false);
    assert_eq!(json["command"], "status");
    assert_eq!(json["error"]["code"], "daemon_not_running");
    assert!(!failed.stderr.is_empty());
    failed.assert_no_ansi();
}

#[test]
fn http_500_is_reported_as_an_http_error() {
    let fixture = Fixture::start("http-500", &["http_500"]);
    let run = run(
        Some(fixture.control()),
        &["devices", "--json"],
        fixture.dir(),
    );
    assert_eq!(run.code, 1, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_http_error");
}

#[test]
fn invalid_json_body_is_reported_as_a_response_error() {
    let fixture = Fixture::start("invalid-json", &["invalid_json"]);
    let run = run(
        Some(fixture.control()),
        &["devices", "--json"],
        fixture.dir(),
    );
    assert_eq!(run.code, 1, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_response_invalid");
}

#[test]
fn a_wrong_token_is_a_control_file_error() {
    let fixture = Fixture::start("wrong-token", &[]);
    let control = fixture.dir().join("wrong-token.json");
    control_with_token(&control, fixture.port(), "not-the-fixture-token");

    let run = run(Some(&control), &["devices", "--json"], fixture.dir());
    assert_eq!(run.code, 3, "stderr: {}", run.stderr);
    assert_eq!(run.error_code(), "daemon_control_invalid");
}

#[test]
fn discover_json_keeps_the_legacy_array_shape() {
    let fixture = Fixture::start("discover-legacy", &[]);
    let run = run(
        Some(fixture.control()),
        &["discover", "--json"],
        fixture.dir(),
    );

    assert_eq!(run.code, 0, "stderr: {}", run.stderr);
    let json = run.json();
    let array = json.as_array().expect("legacy discover output is an array");
    assert_eq!(array.len(), 3);
    assert_eq!(array[0]["device_id"], "11112222");
    assert_eq!(array[0]["name"], "Fixture Phone");
    assert_eq!(array[0]["device_type"], "ANDROID");
    for entry in array {
        let object = entry.as_object().expect("object");
        let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["device_id", "device_type", "name"]);
        // Serialized field order is pinned by a unit test on LegacyDevice.
        assert!(entry.get("os_type").is_none());
        assert!(entry.get("connections").is_none());
    }
}

#[test]
fn discover_polls_until_the_wait_window_expires() {
    let fixture = Fixture::start("discover-empty", &["empty_state"]);
    let started = Instant::now();
    let run = run(
        Some(fixture.control()),
        &["discover", "--json", "--wait", "0.7"],
        fixture.dir(),
    );
    let elapsed = started.elapsed();

    assert_eq!(run.code, 0, "stderr: {}", run.stderr);
    assert_eq!(run.json(), serde_json::json!([]));
    assert!(
        elapsed >= Duration::from_millis(600),
        "discover must honour the wait window, took {elapsed:?}"
    );
}

#[test]
fn discover_stops_as_soon_as_a_device_appears() {
    let fixture = Fixture::start("discover-found", &[]);
    let started = Instant::now();
    let run = run(
        Some(fixture.control()),
        &["discover", "--json", "--wait", "30"],
        fixture.dir(),
    );
    assert_eq!(run.code, 0, "stderr: {}", run.stderr);
    assert_eq!(
        run.json().as_array().map(Vec::len),
        Some(3),
        "devices present on the first poll must end the wait"
    );
    assert!(
        started.elapsed() < Duration::from_secs(5),
        "must not wait out the window when devices are already visible"
    );
}

#[test]
fn human_output_is_readable_and_not_json() {
    let fixture = Fixture::start("human", &[]);
    let devices = run(Some(fixture.control()), &["devices"], fixture.dir());
    assert_eq!(devices.code, 0, "stderr: {}", devices.stderr);
    assert!(
        devices.stdout.contains("Devices: 3"),
        "unexpected stdout: {:?}",
        devices.stdout
    );
    assert!(devices.stdout.contains("11112222"));
    assert!(serde_json::from_str::<serde_json::Value>(&devices.stdout).is_err());

    let status = run(Some(fixture.control()), &["status"], fixture.dir());
    assert_eq!(status.code, 0, "stderr: {}", status.stderr);
    assert!(status.stdout.contains("Daemon:"));
    assert!(status
        .stdout
        .contains("Devices:   3 visible, 1 paired, 2 reachable"));
}

#[test]
fn control_file_search_order_uses_the_ambient_directory() {
    let fixture = Fixture::start("search-order", &[]);
    // The first ambient location is `$XDG_RUNTIME_DIR/klardrop` on unix and
    // `%LOCALAPPDATA%\Klardrop` on Windows, which has no POSIX fallback at all — so the
    // copy has to land where THIS platform looks first, or the search order is never
    // exercised. `run` below points both variables at the fixture's own directory.
    let ambient = if cfg!(windows) {
        fixture
            .dir()
            .join("appdata")
            .join("Klardrop")
            .join("control.json")
    } else {
        fixture
            .dir()
            .join("xdg")
            .join("klardrop")
            .join("control.json")
    };
    fs::create_dir_all(ambient.parent().expect("parent")).expect("create ambient dir");
    fs::copy(fixture.control(), &ambient).expect("copy control file");

    let run = run(None, &["status", "--json"], fixture.dir());
    assert_eq!(run.code, 0, "stderr: {}", run.stderr);
    assert_eq!(run.json()["deviceCount"], 3);
}

#[test]
fn an_ambient_proxy_is_never_used() {
    let fixture = Fixture::start("proxy", &[]);
    let started = Instant::now();
    let output = Command::new(CLI)
        .args(["status", "--json", "--control-file"])
        .arg(fixture.control())
        .env("XDG_RUNTIME_DIR", fixture.dir().join("xdg"))
        .env("HOME", fixture.dir().join("home"))
        .env("http_proxy", "http://127.0.0.1:1")
        .env("HTTP_PROXY", "http://127.0.0.1:1")
        .env("ALL_PROXY", "http://127.0.0.1:1")
        .env("all_proxy", "http://127.0.0.1:1")
        .stdin(Stdio::null())
        .output()
        .expect("run CLI");
    let run = CliRun {
        code: output.status.code().expect("exit code"),
        stdout: String::from_utf8(output.stdout).expect("utf-8"),
        stderr: String::from_utf8(output.stderr).expect("utf-8"),
        elapsed: started.elapsed(),
    };
    assert_eq!(run.code, 0, "stderr: {}", run.stderr);
    assert_eq!(run.json()["ok"], true);
}

#[test]
fn no_arguments_prints_help_and_exits_2() {
    let dir = TempDir::new("no-args");
    let run = run(None, &[], &dir.path);
    assert_eq!(run.code, 2);
    assert!(run.stdout.contains("Usage"), "stdout: {:?}", run.stdout);
    assert!(run.stdout.contains("devices"));
    assert!(run.stdout.contains("status"));
    assert!(run.stdout.contains("discover"));
    run.assert_no_ansi();
}

#[test]
fn version_flag_prints_the_crate_version() {
    let dir = TempDir::new("version");
    let run = run(None, &["--version"], &dir.path);
    assert_eq!(run.code, 0);
    assert!(
        run.stdout.starts_with("klardrop "),
        "unexpected version output: {:?}",
        run.stdout
    );
}

#[test]
fn unknown_arguments_are_rejected_before_any_work() {
    let dir = TempDir::new("bad-args");
    // `share` exists now, so the unknown-flag case needs a flag that really
    // does not exist, on an existing command.
    for args in [
        vec!["share", "--definitely-not-a-flag"],
        vec!["share", "--to", "11112222", "--definitely-not-a-flag"],
        vec!["transfers", "--definitely-not-a-flag"],
        vec!["send", "11112222", "--definitely-not-a-flag"],
        vec!["definitely-not-a-command"],
    ] {
        let run = run(None, &args, &dir.path);
        assert_eq!(
            run.code, 2,
            "{args:?} must be a usage error: stdout {} stderr {}",
            run.stdout, run.stderr
        );
    }
}

#[test]
fn mixed_payloads_are_refused_before_the_daemon_is_even_looked_up() {
    let dir = TempDir::new("mixed-payload");
    // A control file that does not exist would answer `daemon_not_running` if
    // anything were read: getting `invalid_argument` proves nothing was.
    let missing_control = dir.join("no-such-control.json");
    let file = payload(&dir.path, "report.pdf", b"pdf bytes");

    // `share` also points at the interactive command; legacy `send` keeps its
    // own wording.
    let cases: Vec<(Vec<&str>, bool)> = vec![
        (
            vec![
                "share",
                "--to",
                "11112222",
                file.to_str().expect("utf-8 path"),
                "--text",
                "hi",
                "--json",
            ],
            true,
        ),
        (
            vec![
                "share",
                "--to",
                "11112222",
                "--text",
                "hi",
                "--clipboard",
                "--json",
            ],
            true,
        ),
        (
            vec!["send", "11112222", "hello", "--text", "hi", "--json"],
            false,
        ),
        (
            vec![
                "send",
                "11112222",
                "--file",
                file.to_str().expect("utf-8 path"),
                "--text",
                "hi",
                "--json",
            ],
            false,
        ),
    ];
    for (args, expects_interactive_pointer) in cases {
        let result = run(Some(&missing_control), &args, &dir.path);
        assert_eq!(
            result.code, 2,
            "{args:?}: stdout {} stderr {}",
            result.stdout, result.stderr
        );
        assert_eq!(result.error_code(), "invalid_argument", "{args:?}");
        assert_eq!(
            result.stderr.contains("interactive"),
            expects_interactive_pointer,
            "{args:?}: stderr {}",
            result.stderr
        );
    }

    // No payload at all is the same class of mistake.
    let run = run(
        Some(&missing_control),
        &["share", "--to", "11112222", "--json"],
        &dir.path,
    );
    assert_eq!(run.code, 2);
    assert_eq!(run.error_code(), "invalid_argument");
}

#[test]
fn share_queues_without_wait_and_confirms_with_wait() {
    let fixture = Fixture::start("share-queue", &[]);
    let file = payload(fixture.dir(), "report.pdf", &[b'x'; 11]);

    let queued = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(queued.code, 0, "stderr: {}", queued.stderr);
    let json = queued.json();
    assert_eq!(json["schemaVersion"], 1);
    assert_eq!(json["ok"], true);
    assert_eq!(json["command"], "share");
    assert_eq!(json["deviceId"], "11112222");
    assert_eq!(
        json["status"], "queued",
        "without --wait the answer must be queued, never delivered"
    );
    let request_id = json["requestId"].as_str().expect("request id").to_string();
    assert!(request_id.starts_with("req-"), "unexpected id {request_id}");
    assert_eq!(json["items"][0]["status"], "queued");
    assert_eq!(json["items"][0]["fileName"], "report.pdf");
    assert_eq!(json["items"][0]["totalSize"], 11);

    let waited = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--wait",
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(waited.code, 0, "stderr: {}", waited.stderr);
    let json = waited.json();
    assert_eq!(json["ok"], true);
    assert_eq!(json["status"], "completed");
    assert_eq!(json["items"][0]["status"], "completed");
    assert_eq!(json["items"][0]["transferredSize"], 11);
    assert_ne!(
        json["requestId"], request_id,
        "each submission gets its own request id"
    );
}

#[test]
fn share_waits_for_a_delayed_acknowledgement() {
    let fixture = Fixture::start("share-slow", &["share_slow"]);
    let file = payload(fixture.dir(), "slow.bin", &[b'x'; 64]);
    let started = Instant::now();
    let run_result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--wait",
            "--timeout",
            "20",
            "--json",
        ],
        fixture.dir(),
    );
    let elapsed = started.elapsed();

    assert_eq!(run_result.code, 0, "stderr: {}", run_result.stderr);
    assert_eq!(run_result.json()["status"], "completed");
    assert!(
        elapsed >= Duration::from_millis(1100),
        "the wait must actually wait for the ack, took {elapsed:?}"
    );
    assert!(
        elapsed < Duration::from_secs(10),
        "the wait must end as soon as the ack arrives, took {elapsed:?}"
    );
    // Progress is reported on stderr only; stdout stays one JSON value.
    assert!(
        run_result.stderr.contains("transferring") || run_result.stderr.contains("%"),
        "waiting must report progress on stderr: {}",
        run_result.stderr
    );
}

#[test]
fn a_decline_is_a_transfer_failure_with_per_item_outcomes() {
    let fixture = Fixture::start("share-decline", &["share_decline"]);
    let file = payload(fixture.dir(), "secret.txt", b"nope");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--wait",
            "--json",
        ],
        fixture.dir(),
    );

    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    let json = result.json();
    assert_eq!(json["ok"], false);
    assert_eq!(json["command"], "share");
    assert_eq!(json["error"]["code"], "transfer_failed");
    assert_eq!(json["status"], "declined");
    assert_eq!(json["items"][0]["status"], "declined");
    assert_eq!(json["items"][0]["transferredSize"], 0);
    assert!(
        json["items"][0]["error"]
            .as_str()
            .expect("item error")
            .contains("declined"),
        "the item must carry the daemon's reason: {}",
        json["items"][0]
    );
    assert!(json["requestId"].as_str().is_some());
    assert!(json["deviceId"].as_str().is_some());
}

#[test]
fn a_failed_transfer_is_reported_as_such() {
    let fixture = Fixture::start("share-fail", &["share_fail"]);
    let file = payload(fixture.dir(), "broken.bin", b"0123456789");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--wait",
            "--json",
        ],
        fixture.dir(),
    );

    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    let json = result.json();
    assert_eq!(json["error"]["code"], "transfer_failed");
    assert_eq!(json["status"], "failed");
    assert_eq!(json["items"][0]["status"], "failed");
    assert!(
        json["items"][0]["error"].as_str().is_some(),
        "a failure must carry a reason"
    );
}

#[test]
fn a_wait_that_times_out_leaves_delivery_unknown() {
    let fixture = Fixture::start("share-never", &["share_never_completes"]);
    let file = payload(fixture.dir(), "big.bin", &[b'y'; 128]);
    let started = Instant::now();
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--wait",
            "--timeout",
            "1",
            "--json",
        ],
        fixture.dir(),
    );
    let elapsed = started.elapsed();

    assert_eq!(result.code, 4, "stderr: {}", result.stderr);
    let json = result.json();
    assert_eq!(json["ok"], false);
    assert_eq!(json["error"]["code"], "daemon_timeout");
    assert_eq!(
        json["status"], "unknown",
        "an expired deadline proves nothing about delivery"
    );
    let request_id = json["requestId"].as_str().expect("request id").to_string();
    assert!(request_id.starts_with("req-"));
    let message = json["error"]["message"].as_str().expect("message");
    assert!(
        message.contains(&request_id) && message.contains("unknown"),
        "the message must name the request and say it is unknown: {message}"
    );
    assert!(
        message.contains(&format!("klardrop transfers --id {request_id}")),
        "the message must say how to inspect it: {message}"
    );
    assert!(
        elapsed >= Duration::from_millis(900) && elapsed < DEADLINE_BOUND,
        "the deadline must be honoured, took {elapsed:?}"
    );

    // The daemon still knows it: an unknown state is not a lost request.
    let follow_up = run(
        Some(fixture.control()),
        &["transfers", "--id", &request_id, "--json"],
        fixture.dir(),
    );
    assert_eq!(follow_up.code, 0, "stderr: {}", follow_up.stderr);
    assert_eq!(follow_up.json()["request"]["requestId"], request_id);
}

#[test]
fn a_missing_file_is_an_invalid_path_and_nothing_is_sent() {
    let fixture = Fixture::start("share-missing", &[]);
    let missing = fixture.dir().join("not-here.pdf");

    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            missing.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "invalid_path");
    assert!(
        result.stderr.contains("not-here.pdf"),
        "the path must be named: {}",
        result.stderr
    );

    // Proof that nothing reached the daemon: the registry is still empty.
    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(registry.code, 0, "stderr: {}", registry.stderr);
    assert_eq!(
        registry.json()["requests"].as_array().map(Vec::len),
        Some(0),
        "a rejected path must not produce a request"
    );
}

#[test]
fn an_ambiguous_device_prefix_is_refused_and_never_resolved() {
    let fixture = Fixture::start("share-ambiguous", &[]);
    let file = payload(fixture.dir(), "a.pdf", b"a");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "3333",
            file.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );

    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "ambiguous_device");
    for candidate in ["3333aaaa", "3333bbbb"] {
        assert!(
            result.stderr.contains(candidate),
            "both candidates must be listed: {}",
            result.stderr
        );
    }
    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(
        registry.json()["requests"].as_array().map(Vec::len),
        Some(0)
    );
}

#[test]
fn an_unknown_device_is_refused_with_the_visible_ids() {
    let fixture = Fixture::start("share-unknown-device", &[]);
    let file = payload(fixture.dir(), "a.pdf", b"a");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "99999999",
            file.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );

    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "device_not_found");
    assert!(
        result.stderr.contains("11112222"),
        "the visible ids must be listed: {}",
        result.stderr
    );
}

#[test]
fn a_unique_device_prefix_is_accepted() {
    let fixture = Fixture::start("share-prefix", &[]);
    let file = payload(fixture.dir(), "a.pdf", b"a");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "1111",
            file.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(result.code, 0, "stderr: {}", result.stderr);
    assert_eq!(result.json()["deviceId"], "11112222");
}

#[test]
fn a_partial_multi_file_share_is_a_nonzero_result_naming_every_item() {
    let fixture = Fixture::start("share-partial", &["share_partial"]);
    let first = payload(fixture.dir(), "one.txt", b"one");
    let second = payload(fixture.dir(), "two.txt", b"two two");

    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            first.to_str().expect("utf-8"),
            second.to_str().expect("utf-8"),
            "--wait",
            "--json",
        ],
        fixture.dir(),
    );

    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    let json = result.json();
    assert_eq!(json["error"]["code"], "transfer_failed");
    assert_eq!(json["status"], "failed");
    let items = json["items"].as_array().expect("items");
    assert_eq!(items.len(), 2);
    assert_eq!(items[0]["fileName"], "one.txt");
    assert_eq!(items[0]["status"], "completed");
    assert_eq!(items[0]["transferredSize"], 3);
    assert_eq!(items[1]["fileName"], "two.txt");
    assert_eq!(items[1]["status"], "failed");
    assert_ne!(
        items[0]["transferId"], items[1]["transferId"],
        "each item needs its own transfer id"
    );
}

#[test]
fn two_concurrent_requests_each_follow_only_their_own_ids() {
    let fixture = Fixture::start("share-concurrent", &[]);
    let control = fixture.control().to_path_buf();
    let dir = fixture.dir().to_path_buf();
    let small = payload(fixture.dir(), "small.bin", &[b'a'; 11]);
    let large = payload(fixture.dir(), "large.bin", &[b'b'; 23]);

    let first = {
        let (control, dir) = (control.clone(), dir.clone());
        let file = small.to_str().expect("utf-8").to_string();
        std::thread::spawn(move || {
            run(
                Some(&control),
                &["share", "--to", "11112222", &file, "--wait", "--json"],
                &dir,
            )
        })
    };
    let second = {
        let (control, dir) = (control.clone(), dir.clone());
        let file = large.to_str().expect("utf-8").to_string();
        std::thread::spawn(move || {
            run(
                Some(&control),
                &["share", "--to", "11112222", &file, "--wait", "--json"],
                &dir,
            )
        })
    };
    let first = first.join().expect("first share thread");
    let second = second.join().expect("second share thread");

    assert_eq!(first.code, 0, "stderr: {}", first.stderr);
    assert_eq!(second.code, 0, "stderr: {}", second.stderr);
    let first_json = first.json();
    let second_json = second.json();
    assert_ne!(
        first_json["requestId"], second_json["requestId"],
        "concurrent submissions must not share a request id"
    );
    // Each envelope describes exactly its own payload.
    assert_eq!(first_json["items"][0]["fileName"], "small.bin");
    assert_eq!(first_json["items"][0]["totalSize"], 11);
    assert_eq!(first_json["items"][0]["transferredSize"], 11);
    assert_eq!(second_json["items"][0]["fileName"], "large.bin");
    assert_eq!(second_json["items"][0]["totalSize"], 23);
    assert_eq!(second_json["items"][0]["transferredSize"], 23);
    assert_ne!(
        first_json["items"][0]["transferId"], second_json["items"][0]["transferId"],
        "the two requests must not share a transfer id"
    );

    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    let listed = registry.json();
    let ids: Vec<&str> = listed["requests"]
        .as_array()
        .expect("requests")
        .iter()
        .map(|request| request["requestId"].as_str().expect("id"))
        .collect();
    assert!(ids.contains(&first_json["requestId"].as_str().expect("id")));
    assert!(ids.contains(&second_json["requestId"].as_str().expect("id")));
}

#[test]
fn transfers_round_trips_a_request_id_and_reports_an_unknown_one() {
    let fixture = Fixture::start("transfers-lookup", &[]);
    let file = payload(fixture.dir(), "note.txt", b"hello");
    let submitted = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );
    let request_id = submitted.json()["requestId"]
        .as_str()
        .expect("request id")
        .to_string();

    let one = run(
        Some(fixture.control()),
        &["transfers", "--id", &request_id, "--json"],
        fixture.dir(),
    );
    assert_eq!(one.code, 0, "stderr: {}", one.stderr);
    let json = one.json();
    assert_eq!(json["ok"], true);
    assert_eq!(json["command"], "transfers");
    assert_eq!(json["request"]["requestId"], request_id);
    assert_eq!(json["request"]["deviceId"], "11112222");
    assert_eq!(json["request"]["kind"], "files");
    assert!(json["request"]["createdAt"].is_i64());
    assert!(json["request"]["updatedAt"].is_i64());
    assert_eq!(json["request"]["items"][0]["fileName"], "note.txt");

    let list = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(list.code, 0, "stderr: {}", list.stderr);
    assert_eq!(
        list.json()["requests"][0]["requestId"],
        request_id,
        "the newest request comes first"
    );

    let unknown = run(
        Some(fixture.control()),
        &["transfers", "--id", "req-0000000000000000", "--json"],
        fixture.dir(),
    );
    assert_eq!(unknown.code, 3, "stderr: {}", unknown.stderr);
    assert_eq!(unknown.error_code(), "transfer_unknown");
    let message = unknown.stderr.clone();
    assert!(
        message.contains("not a delivery result"),
        "an unknown id must not read as a delivery failure: {message}"
    );
}

#[test]
fn a_daemon_without_the_share_route_is_unsupported_never_a_delivery() {
    let fixture = Fixture::start("no-share", &["no_share"]);
    let result = run(
        Some(fixture.control()),
        &["share", "--to", "11112222", "--text", "hi", "--json"],
        fixture.dir(),
    );

    assert_eq!(result.code, 3, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "daemon_unsupported");
    assert_eq!(result.json()["ok"], false);
    assert!(
        !result.stdout.contains("\"status\":\"completed\""),
        "an old daemon must never produce a delivery claim: {}",
        result.stdout
    );

    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(registry.code, 3, "stderr: {}", registry.stderr);
    assert_eq!(registry.error_code(), "daemon_unsupported");
}

#[test]
fn text_and_clipboard_shares_carry_no_file_fields() {
    let fixture = Fixture::start("share-text", &[]);
    let text = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            "--text",
            "hello there",
            "--wait",
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(text.code, 0, "stderr: {}", text.stderr);
    let json = text.json();
    assert_eq!(json["status"], "completed");
    assert_eq!(json["items"].as_array().map(Vec::len), Some(1));
    assert_eq!(json["items"][0]["path"], serde_json::Value::Null);
    assert_eq!(json["items"][0]["fileName"], serde_json::Value::Null);
    assert_eq!(json["items"][0]["totalSize"], 0);

    let clipboard = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            "--clipboard",
            "--wait",
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(clipboard.code, 0, "stderr: {}", clipboard.stderr);
    assert_eq!(clipboard.json()["status"], "completed");
    assert_eq!(
        clipboard.json()["items"][0]["path"],
        serde_json::Value::Null
    );

    let listed = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    let listed = listed.json();
    let kinds: Vec<&str> = listed["requests"]
        .as_array()
        .expect("requests")
        .iter()
        .map(|request| request["kind"].as_str().expect("kind"))
        .collect();
    assert_eq!(kinds, vec!["clipboard", "text"]);
}

#[test]
fn unicode_and_spaced_paths_are_shared_verbatim() {
    let fixture = Fixture::start("share-unicode", &[]);
    let file = payload(fixture.dir(), "παράδειγμα — notes.txt", b"unicode");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            "--json",
            "--",
            file.to_str().expect("utf-8"),
        ],
        fixture.dir(),
    );
    assert_eq!(result.code, 0, "stderr: {}", result.stderr);
    let item = &result.json()["items"][0];
    // Paths are resolved before they reach the daemon, so macOS's own
    // /var -> /private/var temp dir arrives resolved; what has to survive untouched
    // is the name the caller gave, unicode and spaces included.
    let resolved = fs::canonicalize(&file).expect("canonical payload path");
    assert_eq!(item["path"], resolved.to_str().expect("utf-8"));
    assert!(
        item["path"]
            .as_str()
            .expect("path")
            .ends_with("παράδειγμα — notes.txt"),
        "the spaced unicode name must reach the daemon verbatim: {item}"
    );
    assert_eq!(item["fileName"], "παράδειγμα — notes.txt");
}

#[test]
fn legacy_send_waits_for_delivery_unless_told_otherwise() {
    let fixture = Fixture::start("send-wait", &[]);
    let file = payload(fixture.dir(), "sent.txt", b"sent");

    let delivered = run(
        Some(fixture.control()),
        &[
            "send",
            "11112222",
            "--file",
            file.to_str().expect("utf-8"),
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(delivered.code, 0, "stderr: {}", delivered.stderr);
    assert_eq!(
        delivered.json()["status"],
        "completed",
        "legacy send means delivered, not queued"
    );
    assert_eq!(delivered.json()["command"], "send");

    let queued = run(
        Some(fixture.control()),
        &["send", "11112222", "--text", "hello", "--no-wait", "--json"],
        fixture.dir(),
    );
    assert_eq!(queued.code, 0, "stderr: {}", queued.stderr);
    assert_eq!(queued.json()["status"], "queued");

    // The legacy positional spelling still works: a separator means a file.
    let positional = run(
        Some(fixture.control()),
        &["send", "11112222", file.to_str().expect("utf-8"), "--json"],
        fixture.dir(),
    );
    assert_eq!(positional.code, 0, "stderr: {}", positional.stderr);
    assert_eq!(positional.json()["status"], "completed");
    assert_eq!(positional.json()["items"][0]["fileName"], "sent.txt");
}

#[test]
fn legacy_send_reports_a_decline_as_a_failure() {
    let fixture = Fixture::start("send-decline", &["share_decline"]);
    let result = run(
        Some(fixture.control()),
        &["send", "11112222", "--text", "hello", "--json"],
        fixture.dir(),
    );
    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "transfer_failed");
    assert_eq!(result.json()["status"], "declined");
}

#[test]
fn share_honours_an_invalid_timeout_before_sending_anything() {
    let fixture = Fixture::start("share-bad-timeout", &[]);
    let file = payload(fixture.dir(), "a.pdf", b"a");
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            file.to_str().expect("utf-8"),
            "--timeout",
            "0",
            "--json",
        ],
        fixture.dir(),
    );
    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "invalid_argument");
    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(
        registry.json()["requests"].as_array().map(Vec::len),
        Some(0)
    );
}

#[test]
fn a_redirect_is_a_protocol_error_and_never_the_daemons_answer() {
    let fixture = Fixture::start("redirect", &["redirect"]);
    let result = run(
        Some(fixture.control()),
        &["devices", "--json"],
        fixture.dir(),
    );
    assert_ne!(result.code, 0, "a 3xx must never read as success");
    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "daemon_protocol_error");
    assert!(
        result.stderr.contains("redirect"),
        "stderr should say a redirect was refused: {}",
        result.stderr
    );
}

#[test]
fn chunked_transfer_encoding_is_rejected_outright() {
    let fixture = Fixture::start("chunked", &["chunked"]);
    let result = run(
        Some(fixture.control()),
        &["devices", "--json"],
        fixture.dir(),
    );
    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "daemon_protocol_error");
    assert!(
        result.stderr.contains("chunked"),
        "stderr: {}",
        result.stderr
    );
}

#[test]
fn an_item_the_daemon_cannot_prepare_keeps_the_rest_of_the_answer_usable() {
    // The second path is refused at submission time, exactly as the real daemon refuses a
    // path that is not a readable regular file. The client must survive the explicit
    // `"transferId": null` and keep the whole registry listing parseable.
    let fixture = Fixture::start("unsendable", &["share_unsendable"]);
    let dir = fixture.dir();
    let first = dir.join("first.txt");
    fs::write(&first, "first").expect("write first");
    let second = dir.join("second.txt");
    fs::write(&second, "second").expect("write second");

    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            first.to_str().expect("utf-8"),
            second.to_str().expect("utf-8"),
            "--json",
        ],
        dir,
    );
    assert_eq!(result.code, 1, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "transfer_failed");
    let json = result.json();
    let items = json["items"].as_array().expect("items array");
    assert_eq!(items.len(), 2);
    assert_eq!(items[0]["status"], "queued");
    assert!(items[0]["transferId"].is_string());
    assert_eq!(items[1]["status"], "failed");
    assert!(
        items[1]["transferId"].is_null(),
        "an item that was never sent has no transfer id: {items:?}"
    );
    assert!(items[1]["error"]
        .as_str()
        .is_some_and(|e| e.contains("no readable file")));

    // One poisoned item must not make the listing unreadable.
    let listing = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(listing.code, 0, "stderr: {}", listing.stderr);
    assert_eq!(
        listing.json()["requests"][0]["items"][1]["status"],
        "failed"
    );
}

#[test]
fn a_daemon_that_has_not_bound_its_engine_is_reported_as_not_ready() {
    let fixture = Fixture::start("not-ready", &["daemon_not_ready"]);
    let share = run(
        Some(fixture.control()),
        &["share", "--to", "11112222", "--text", "hi", "--json"],
        fixture.dir(),
    );
    assert_eq!(share.code, 3, "stderr: {}", share.stderr);
    assert_eq!(share.error_code(), "daemon_unsupported");
    assert!(
        share.stderr.contains("not ready"),
        "stderr: {}",
        share.stderr
    );

    let transfers = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(transfers.code, 3, "stderr: {}", transfers.stderr);
    assert_eq!(transfers.error_code(), "daemon_unsupported");
}

#[test]
fn a_daemon_refuses_an_oversized_path_list_before_submitting_anything() {
    let fixture = Fixture::start("too-many", &[]);
    let dir = fixture.dir();
    let file = dir.join("payload.txt");
    fs::write(&file, "payload").expect("write payload");
    let path = file.to_str().expect("utf-8").to_string();

    let mut args: Vec<String> = vec!["share".into(), "--to".into(), "11112222".into()];
    args.extend(std::iter::repeat_n(path.clone(), 65));
    args.push("--json".into());

    let borrowed: Vec<&str> = args.iter().map(String::as_str).collect();
    let result = run(Some(fixture.control()), &borrowed, dir);
    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "invalid_argument");
    assert!(
        result.stderr.contains("at most 64"),
        "stderr: {}",
        result.stderr
    );

    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(
        registry.json()["requests"].as_array().map(Vec::len),
        Some(0),
        "nothing may have been submitted"
    );
}

#[test]
fn an_over_long_request_id_is_an_invalid_argument_not_a_client_bug() {
    let fixture = Fixture::start("long-id", &[]);
    let id = "r".repeat(3000);
    let result = run(
        Some(fixture.control()),
        &["transfers", "--id", &id, "--json"],
        fixture.dir(),
    );
    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "invalid_argument");
}

#[test]
fn a_directory_is_refused_before_anything_is_submitted() {
    let fixture = Fixture::start("dir", &[]);
    let dir = fixture.dir();
    let result = run(
        Some(fixture.control()),
        &[
            "share",
            "--to",
            "11112222",
            dir.to_str().expect("utf-8"),
            "--json",
        ],
        dir,
    );
    assert_eq!(result.code, 2, "stderr: {}", result.stderr);
    assert_eq!(result.error_code(), "invalid_path");
    let registry = run(
        Some(fixture.control()),
        &["transfers", "--json"],
        fixture.dir(),
    );
    assert_eq!(
        registry.json()["requests"].as_array().map(Vec::len),
        Some(0)
    );
}
#[test]
fn share_without_a_target_names_the_interactive_entry_point() {
    // The plan requires the missing-`--to` error to be a usage error *with
    // guidance to `interactive`*. Clap's own "required arguments were not
    // provided" text names the flag and nothing else, so `share` deliberately
    // does not declare `--to` as `required_unless_present`: it has to reach
    // `run` to answer with something that says what to do instead.
    let fixture = Fixture::start("share-no-target", &[]);
    let dir = fixture.dir();
    let file = payload(dir, "report.pdf", b"a report");

    let human = run(
        Some(fixture.control()),
        &["share", file.to_str().expect("utf-8")],
        dir,
    );
    assert_eq!(human.code, 2, "stderr: {}", human.stderr);
    for needle in ["--pick", "interactive"] {
        assert!(
            human.stderr.contains(needle),
            "the usage error must mention {needle}: {}",
            human.stderr
        );
    }

    // The same answer under --json, still exactly one value and no prompting.
    let machine = run(
        Some(fixture.control()),
        &["share", "--json", file.to_str().expect("utf-8")],
        dir,
    );
    assert_eq!(machine.code, 2, "stderr: {}", machine.stderr);
    assert_eq!(machine.error_code(), "invalid_argument");
    assert!(
        machine.json()["error"]["message"]
            .as_str()
            .expect("message")
            .contains("interactive"),
        "the JSON message must carry the guidance too: {}",
        machine.stdout
    );
    machine.assert_no_ansi();
}
