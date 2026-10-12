//! Discovery and validation of the daemon control file.
//!
//! Search order (identical to every other Klardrop client):
//!
//! 1. an explicit `--control-file <path>` override;
//! 2. `$XDG_RUNTIME_DIR/klardrop/control.json` (when set and non-empty);
//! 3. `$HOME/.cache/klardrop/control.json`.
//!
//! Unix only: the Rust client runs on Linux (native engine host) and macOS
//! (app companion). Windows desktop runs the JVM app and has no Rust CLI.
//!
//! Validation is defensive because the file is attacker-influenceable on a
//! shared machine and its contents become an `Authorization` header value:
//! regular file, 1..=4096 bytes, JSON object, integral port in 1..=65535,
//! non-empty token of at most 256 characters restricted to `[A-Za-z0-9_.~-]+`.
//!
//! On macOS the App Group container is tried FIRST, before the two unix paths above:
//! the shipped `Klardrop.app` is sandboxed and publishes its control file into
//! `$HOME/Library/Group Containers/<MACOS_APP_GROUP>/`, which is an ordinary directory under
//! the user's home and therefore the one location a sandboxed app and this unsandboxed CLI can
//! both read. The unix paths still apply, because the non-sandboxed JVM desktop host on macOS
//! keeps using `$HOME/.cache/klardrop` — so the group container is only preferred when it
//! actually holds a control file.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

use crate::envelope::{CliError, CliResult, ErrorCode};

/// Maximum accepted control-file size, in bytes (inclusive).
pub const MAX_CONTROL_FILE_BYTES: u64 = 4096;

/// Maximum accepted token length, in characters (inclusive).
pub const MAX_TOKEN_CHARS: usize = 256;

const START_HINT: &str =
    "start the Klardrop daemon first (the `klardrop daemon` command, or the desktop app)";

/// The macOS App Group shared by `Klardrop.app` and its share extensions.
///
/// MUST stay in lockstep with three other places in this repository — drift here means the CLI
/// looks somewhere the app never writes, and every macOS command fails with
/// "daemon unavailable":
///
/// - `control-plane/src/macosMain/kotlin/com/carlom/klardrop/control/ControlFile.macos.kt`
/// - `iosApp/Shared/ShareInbox.swift` (`ShareInbox.appGroupID`)
/// - `iosApp/iosApp/KlardropMac.entitlements`
///
/// `the_app_group_matches_the_repository` fails the build if any of them diverges.
pub const MACOS_APP_GROUP: &str = "D7T5425WSW.group.com.carlom.Klardrop";

/// `$HOME/Library/Group Containers/<MACOS_APP_GROUP>/control.json`, or None without a home.
fn macos_app_group_control_file(home: Option<&str>) -> Option<PathBuf> {
    let home = home.map(str::trim).filter(|h| !h.is_empty())?;
    Some(
        Path::new(home)
            .join("Library")
            .join("Group Containers")
            .join(MACOS_APP_GROUP)
            .join("control.json"),
    )
}

/// Validated connection metadata for a running daemon.
#[derive(Debug, Clone)]
pub struct ControlFile {
    pub path: PathBuf,
    pub port: u16,
    pub token: String,
}

impl ControlFile {
    /// The only authority this client ever dials. The literal comes from
    /// [`crate::client::LOOPBACK_HOST`] so there is exactly one source.
    pub fn authority(&self) -> String {
        format!("{}:{}", crate::client::LOOPBACK_HOST, self.port)
    }
}

/// Pure search-order resolution, unit-testable without mutating process env.
fn resolve_from_env(xdg_runtime_dir: Option<&str>, home: Option<&str>) -> CliResult<PathBuf> {
    fn non_empty(value: Option<&str>) -> Option<&str> {
        value.map(str::trim).filter(|trimmed| !trimmed.is_empty())
    }

    if let Some(base) = non_empty(xdg_runtime_dir) {
        return Ok(Path::new(base).join("klardrop").join("control.json"));
    }
    if let Some(base) = non_empty(home) {
        return Ok(Path::new(base)
            .join(".cache")
            .join("klardrop")
            .join("control.json"));
    }
    Err(CliError::new(
        ErrorCode::DaemonControlInvalid,
        "cannot locate the daemon control file: neither XDG_RUNTIME_DIR nor HOME is set",
    ))
}

/// Resolves the control-file path without touching the filesystem.
pub fn resolve_path(override_path: Option<&Path>) -> CliResult<PathBuf> {
    match override_path {
        Some(path) => {
            if path.as_os_str().is_empty() {
                return Err(CliError::new(
                    ErrorCode::InvalidArgument,
                    "--control-file requires a non-empty path",
                ));
            }
            Ok(path.to_path_buf())
        }
        None => {
            let home = std::env::var("HOME").ok();

            // macOS first: a running native Klardrop.app publishes its control file into the
            // App Group container. Probed first, and only when it really holds a file, so a
            // non-sandboxed JVM host on the same machine is still found below.
            if cfg!(target_os = "macos") {
                if let Some(path) = macos_app_group_control_file(home.as_deref()) {
                    if path.is_file() {
                        return Ok(path);
                    }
                }
            }

            let xdg = std::env::var("XDG_RUNTIME_DIR").ok();
            resolve_from_env(xdg.as_deref(), home.as_deref())
        }
    }
}

/// Resolves, reads and validates the control file.
pub fn load(override_path: Option<&Path>) -> CliResult<ControlFile> {
    let path = resolve_path(override_path)?;
    let metadata = match fs::metadata(&path) {
        Ok(metadata) => metadata,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(CliError::new(
                ErrorCode::DaemonNotRunning,
                format!(
                    "no daemon control file at {} ({START_HINT})",
                    path.display()
                ),
            ));
        }
        Err(e) => {
            return Err(CliError::new(
                ErrorCode::DaemonControlInvalid,
                format!("cannot stat control file {}: {e}", path.display()),
            ));
        }
    };

    if !metadata.is_file() {
        return Err(CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!("control file {} is not a regular file", path.display()),
        ));
    }
    if metadata.len() == 0 {
        return Err(CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!("control file {} is empty", path.display()),
        ));
    }
    if metadata.len() > MAX_CONTROL_FILE_BYTES {
        return Err(CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!(
                "control file {} exceeds {MAX_CONTROL_FILE_BYTES} bytes",
                path.display()
            ),
        ));
    }

    // Read at most one byte past the cap so a file that grew between stat and
    // read is still rejected without ever allocating more than the cap.
    let mut file = fs::File::open(&path).map_err(|e| {
        CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!("cannot open control file {}: {e}", path.display()),
        )
    })?;
    let mut buffer = Vec::with_capacity(MAX_CONTROL_FILE_BYTES as usize + 1);
    let read = file
        .by_ref()
        .take(MAX_CONTROL_FILE_BYTES + 1)
        .read_to_end(&mut buffer)
        .map_err(|e| {
            CliError::new(
                ErrorCode::DaemonControlInvalid,
                format!("cannot read control file {}: {e}", path.display()),
            )
        })?;
    if read == 0 {
        return Err(CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!("control file {} is empty", path.display()),
        ));
    }
    if buffer.len() as u64 > MAX_CONTROL_FILE_BYTES {
        return Err(CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!(
                "control file {} exceeds {MAX_CONTROL_FILE_BYTES} bytes",
                path.display()
            ),
        ));
    }

    let (port, token) = parse_and_validate(&buffer, &path)?;
    Ok(ControlFile { path, port, token })
}

/// Validates already-read control-file bytes.
pub fn parse_and_validate(bytes: &[u8], path: &Path) -> CliResult<(u16, String)> {
    let invalid = |reason: &str| -> CliError {
        CliError::new(
            ErrorCode::DaemonControlInvalid,
            format!("invalid control file {}: {reason}", path.display()),
        )
    };

    let value: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|e| invalid(&format!("invalid JSON ({e})")))?;
    let object = value
        .as_object()
        .ok_or_else(|| invalid("not a JSON object"))?;

    let port = object
        .get("port")
        .ok_or_else(|| invalid("missing port"))?
        .as_u64()
        .filter(|port| (1..=65_535).contains(port))
        .ok_or_else(|| invalid("port must be an integer in 1..=65535"))?;
    let port = u16::try_from(port).map_err(|_| invalid("port must be an integer in 1..=65535"))?;

    let token = object
        .get("token")
        .ok_or_else(|| invalid("missing token"))?
        .as_str()
        .ok_or_else(|| invalid("token must be a string"))?;
    validate_token(token).map_err(|reason| invalid(&reason))?;

    Ok((port, token.to_string()))
}

/// `true` when the token is non-empty, at most 256 chars and made only of
/// `[A-Za-z0-9_.~-]` (the set that is safe in an `Authorization` header value).
fn validate_token(token: &str) -> Result<(), String> {
    if token.is_empty() {
        return Err("token must not be empty".to_string());
    }
    if token.chars().count() > MAX_TOKEN_CHARS {
        return Err(format!("token exceeds {MAX_TOKEN_CHARS} characters"));
    }
    if !token.bytes().all(is_token_byte) {
        return Err("token contains characters outside [A-Za-z0-9_.~-]".to_string());
    }
    Ok(())
}

fn is_token_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.' | b'~' | b'-')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid(path: &str, bytes: &[u8]) -> CliResult<(u16, String)> {
        parse_and_validate(bytes, Path::new(path))
    }

    #[test]
    fn accepts_a_minimal_control_file() {
        let (port, token) = valid("/c.json", br#"{"port":8765,"token":"abc-123_x.y~z"}"#).unwrap();
        assert_eq!(port, 8765);
        assert_eq!(token, "abc-123_x.y~z");
    }

    #[test]
    fn accepts_api_version_and_capabilities_fields() {
        let bytes = br#"{"port":1,"token":"t","apiVersion":1,"capabilities":["state","health"]}"#;
        assert_eq!(valid("/c.json", bytes).unwrap().0, 1);
    }

    #[test]
    fn rejects_invalid_documents() {
        let cases: &[(&str, &[u8])] = &[
            ("", b""),
            ("{", b"{"),
            ("[]", b"[]"),
            ("null", b"null"),
            ("{}", b"{}"),
            (r#"{"token":"t"}"#, br#"{"token":"t"}"#),
            (r#"{"port":8765}"#, br#"{"port":8765}"#),
            (r#"{"port":0,"token":"t"}"#, br#"{"port":0,"token":"t"}"#),
            (
                r#"{"port":65536,"token":"t"}"#,
                br#"{"port":65536,"token":"t"}"#,
            ),
            (r#"{"port":-1,"token":"t"}"#, br#"{"port":-1,"token":"t"}"#),
            (
                r#"{"port":8080.5,"token":"t"}"#,
                br#"{"port":8080.5,"token":"t"}"#,
            ),
            (
                r#"{"port":"8080","token":"t"}"#,
                br#"{"port":"8080","token":"t"}"#,
            ),
            (
                r#"{"port":null,"token":"t"}"#,
                br#"{"port":null,"token":"t"}"#,
            ),
            (r#"{"port":1,"token":""}"#, br#"{"port":1,"token":""}"#),
            (r#"{"port":1,"token":123}"#, br#"{"port":1,"token":123}"#),
            ("token with a space", br#"{"port":1,"token":"a b"}"#),
            ("token with a newline", b"{\"port\":1,\"token\":\"a\\nb\"}"),
            ("token with a quote", br#"{"port":1,"token":"a\"b"}"#),
            ("token with a slash", br#"{"port":1,"token":"a/b"}"#),
        ];
        for (label, bytes) in cases {
            let error =
                valid("/c.json", bytes).expect_err(&format!("expected rejection for {label}"));
            assert_eq!(
                error.code,
                ErrorCode::DaemonControlInvalid,
                "case {label}: {}",
                error.message
            );
            assert_eq!(error.code.exit_code(), 3);
        }
    }

    #[test]
    fn rejects_oversized_token() {
        let token = "a".repeat(MAX_TOKEN_CHARS + 1);
        let bytes = format!(r#"{{"port":1,"token":"{token}"}}"#);
        let error = valid("/c.json", bytes.as_bytes()).expect_err("257-char token must fail");
        assert_eq!(error.code, ErrorCode::DaemonControlInvalid);
    }

    #[test]
    fn accepts_maximum_length_token() {
        let token = "a".repeat(MAX_TOKEN_CHARS);
        let bytes = format!(r#"{{"port":1,"token":"{token}"}}"#);
        assert_eq!(valid("/c.json", bytes.as_bytes()).unwrap().0, 1);
    }

    #[test]
    fn search_order_prefers_xdg_runtime_dir() {
        let path = resolve_from_env(Some("/run/user/1000"), Some("/home/u")).unwrap();
        assert_eq!(path, PathBuf::from("/run/user/1000/klardrop/control.json"));
    }

    #[test]
    fn search_order_falls_back_to_home_cache() {
        let path = resolve_from_env(None, Some("/home/u")).unwrap();
        assert_eq!(path, PathBuf::from("/home/u/.cache/klardrop/control.json"));
        let path = resolve_from_env(Some(""), Some("/home/u")).unwrap();
        assert_eq!(path, PathBuf::from("/home/u/.cache/klardrop/control.json"));
    }

    #[test]
    fn search_order_without_any_env_var_fails() {
        let error = resolve_from_env(None, None).expect_err("no env vars");
        assert_eq!(error.code, ErrorCode::DaemonControlInvalid);
    }

    #[test]
    fn empty_override_path_is_an_argument_error() {
        let error = resolve_path(Some(Path::new(""))).expect_err("empty override");
        assert_eq!(error.code, ErrorCode::InvalidArgument);
        assert_eq!(error.code.exit_code(), 2);
    }

    #[test]
    fn macos_app_group_path_is_the_native_app_container() {
        assert_eq!(
            macos_app_group_control_file(Some("/Users/carlo")).unwrap(),
            PathBuf::from("/Users/carlo")
                .join("Library")
                .join("Group Containers")
                .join(MACOS_APP_GROUP)
                .join("control.json"),
        );
        // A blank or missing HOME must not produce a relative path the CLI would then
        // resolve against whatever directory it happens to be started in.
        assert_eq!(macos_app_group_control_file(Some("   ")), None);
        assert_eq!(macos_app_group_control_file(None), None);
    }

    /// The macOS CLI only works if this constant, the Kotlin actual that writes the file and
    /// the Swift/entitlement copies all name the same App Group. There is no compiler to catch
    /// that drift — it would ship as "every macOS command says the daemon is unavailable" — so
    /// it is checked here, on every platform, as part of the ordinary Rust test run.
    ///
    /// Two checks, not one. The constant agreement is the point of the test. The second check
    /// is what keeps the first honest: a `contains()` scan proves only that the string appears
    /// *somewhere*. If the Kotlin actual stopped routing its path through the App Group container
    /// but kept the literal in a comment, the app would write somewhere the client never looks
    /// and this test would still be green. So the Kotlin file must actually derive its directory
    /// from that identifier.
    #[test]
    fn the_app_group_matches_the_repository() {
        let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
        let sources = [
            (
                "control-plane macOS actual",
                repo.join(
                    "control-plane/src/macosMain/kotlin/com/carlom/klardrop/control/ControlFile.macos.kt",
                ),
            ),
            (
                "Swift ShareInbox",
                repo.join("iosApp/Shared/ShareInbox.swift"),
            ),
            (
                "KlardropMac entitlements",
                repo.join("iosApp/iosApp/KlardropMac.entitlements"),
            ),
        ];
        for (label, path) in &sources {
            let text = std::fs::read_to_string(path)
                .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
            assert!(
                text.contains(MACOS_APP_GROUP),
                "{label} ({}) does not name the app group {MACOS_APP_GROUP}; the macOS client \
                 would look somewhere the app never writes",
                path.display(),
            );
        }

        // The App Group identifier must be what the Kotlin actual actually asks
        // the OS for, not a literal that survived in a comment. `file` is a
        // compiled constant and cannot be introspected from here, so this is the
        // closest honest check: the identifier is used as the argument to the
        // container lookup that produces the directory.
        let kotlin = std::fs::read_to_string(&sources[0].1).expect("the macOS actual is readable");
        assert!(
            kotlin.contains(&format!("\"{MACOS_APP_GROUP}\"")),
            "the macOS actual must declare the App Group as a quoted constant, not only mention it"
        );
        assert!(
            kotlin.contains("containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID)"),
            "the macOS actual no longer derives its directory from the App Group identifier; the \
             app would write a control file somewhere the Rust client never looks"
        );
        assert!(
            kotlin.contains("unixWriteControlFile(resolveControlFilePath()"),
            "the macOS actual no longer writes the control file through the shared resolver"
        );
    }
}
