//! Bounded, authenticated loopback HTTP client for the control plane.
//!
//! Guarantees:
//!   * the peer is always the literal `127.0.0.1:<port>` from the control file —
//!     never a hostname, never user-supplied;
//!   * one request per connection (`Connection: close`, no pooling);
//!   * `Authorization: Bearer <token>`, never logged;
//!   * no redirects, no proxy (in particular never environment/system proxy);
//!   * hard ceilings on the request target, each response header line, the
//!     response header block and the response body, so a hostile or broken peer
//!     can never drive an unbounded allocation;
//!   * chunked transfer encoding is rejected outright;
//!   * the whole exchange is bounded by the caller-supplied command deadline.

use std::io::Read;
use std::time::{Duration, Instant};

use crate::envelope::{CliError, CliResult, ErrorCode};

/// Request targets are fixed, short paths; this only guards against a
/// programming error turning one into something unbounded.
pub const MAX_REQUEST_TARGET_BYTES: usize = 2048;
/// Maximum length of a single response header line (`name: value\r\n`).
pub const MAX_RESPONSE_HEADER_LINE_BYTES: usize = 8192;
/// Maximum size of the whole response header block.
pub const MAX_RESPONSE_HEADER_BLOCK_BYTES: usize = 64 * 1024;
/// Maximum response body size (parity with the Qt client's `MAX_REPLY_BODY_BYTES`).
pub const MAX_RESPONSE_BODY_BYTES: usize = 4 * 1024 * 1024;
/// Maximum size of a request body this client will serialize and send. The
/// only request body is `POST /share` (a device id, paths and/or a short text
/// message), so this is generous; a larger one is a client bug and is refused
/// before a single byte reaches the socket.
pub const MAX_REQUEST_BODY_BYTES: usize = 65536;

/// Ceiling for any single socket operation.
///
/// `ureq`'s read/write/connect timeouts apply per call, so a peer that answers
/// with headers and then trickles a few bytes per interval could otherwise keep
/// a command alive far past its `--timeout`. Every individual operation is
/// therefore capped here *and* [`DeadlineReader`] enforces the absolute
/// deadline across the whole body read, bounding the overshoot to one cap.
const MAX_SINGLE_IO: Duration = Duration::from_secs(2);

/// A `Read` adapter that fails with a timeout once the command deadline passed,
/// so a slow-dripping peer cannot extend the command past `--timeout`.
struct DeadlineReader<R> {
    inner: R,
    deadline: Instant,
}

impl<R: Read> Read for DeadlineReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if Instant::now() >= self.deadline {
            return Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                "command deadline expired while reading the response body",
            ));
        }
        self.inner.read(buf)
    }
}

/// The one place the loopback literal is written down.
pub(crate) const LOOPBACK_HOST: &str = "127.0.0.1";

/// The HTTP methods this client ever uses. Both share one implementation so a
/// `POST` can never accidentally skip a ceiling, a redaction or the deadline.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Method {
    Get,
    Post,
}

impl Method {
    fn as_str(self) -> &'static str {
        match self {
            Self::Get => "GET",
            Self::Post => "POST",
        }
    }
}

pub struct Client {
    token: String,
    authority: String,
    deadline: Instant,
    debug: DebugLog,
}

/// Where `--debug` diagnostics go.
///
/// The distinction matters because a full-screen TUI session *is* stderr:
/// every line the client wrote while the TUI owned the terminal was drawn
/// into the middle of the panes and the footer, once per daemon request. So
/// the TUI passes [`DebugLog::ToFile`] and everything else passes
/// [`DebugLog::Stderr`], and the documented invariant — diagnostics to stderr,
/// never to the screen — becomes true rather than aspirational.
#[derive(Clone, Debug, Default)]
pub enum DebugLog {
    /// No diagnostics.
    #[default]
    Off,
    /// Straight to stderr. Correct for every command that does not own a
    /// screen.
    Stderr,
    /// Appended to a file, opened once per process. Used by the TUI session.
    ToFile(std::sync::Arc<std::sync::Mutex<Option<std::fs::File>>>),
}

impl DebugLog {
    /// `Stderr` when the flag is set, `Off` when it is not.
    pub fn stderr_if(enabled: bool) -> Self {
        if enabled {
            Self::Stderr
        } else {
            Self::Off
        }
    }

    /// A file-backed sink, writing to `path`.
    pub fn to_file(path: &std::path::Path) -> Self {
        Self::ToFile(std::sync::Arc::new(std::sync::Mutex::new(
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .ok(),
        )))
    }

    fn write(&self, message: &str) {
        use std::io::Write as _;
        match self {
            Self::Off => {}
            Self::Stderr => eprintln!("klardrop: debug: {message}"),
            Self::ToFile(file) => {
                let mut guard = match file.lock() {
                    Ok(guard) => guard,
                    Err(poisoned) => poisoned.into_inner(),
                };
                if let Some(handle) = guard.as_mut() {
                    let _ = writeln!(handle, "klardrop: debug: {message}");
                }
            }
        }
    }
}

impl Client {
    /// Builds a client whose every socket operation is bounded by `deadline`.
    pub fn new(port: u16, token: &str, deadline: Instant, debug: DebugLog) -> CliResult<Self> {
        // Fail fast when the command has no budget left.
        remaining_until(deadline)?;
        Ok(Self {
            token: token.to_string(),
            authority: format!("{LOOPBACK_HOST}:{port}"),
            deadline,
            debug,
        })
    }

    /// A fresh agent per request: one request per connection (no keep-alive
    /// reuse, hence no retry of an aborted pooled request), no redirects, and
    /// never the environment/system proxy — the bearer token must never leave
    /// the loopback socket.
    fn agent(remaining: Duration) -> ureq::Agent {
        // Per-operation ceilings, so one blocking call cannot eat the whole budget.
        let io = remaining.min(MAX_SINGLE_IO);
        ureq::AgentBuilder::new()
            .max_idle_connections_per_host(0)
            .redirects(0)
            .try_proxy_from_env(false)
            .timeout_connect(io)
            .timeout_read(io)
            .timeout_write(io)
            .build()
    }

    fn debug_log(&self, message: &str) {
        self.debug.write(message);
    }

    /// Performs one bounded `GET` and returns the parsed JSON body.
    pub fn get_json(&self, path: &str) -> CliResult<serde_json::Value> {
        let body = self.get_bytes(path)?;
        self.debug_log(&format!("{path} -> {} body bytes", body.len()));
        serde_json::from_slice(&body).map_err(|e| {
            CliError::new(
                ErrorCode::DaemonResponseInvalid,
                format!("daemon returned a body that is not valid JSON ({e})"),
            )
        })
    }

    /// Performs one bounded `GET`, returning the raw (size-checked) body bytes.
    pub fn get_bytes(&self, path: &str) -> CliResult<Vec<u8>> {
        self.get_raw(path, false)?
            .ok_or_else(|| internal_error(format!("{path} answered without a body")))
    }

    /// Performs one bounded request carrying a JSON body and returns the
    /// parsed JSON answer.
    ///
    /// The body is never logged: it can contain user content (file paths, the
    /// text being shared). Only its byte length is.
    pub fn post_json(&self, path: &str, body: &serde_json::Value) -> CliResult<serde_json::Value> {
        let encoded = serde_json::to_vec(body).map_err(|e| {
            internal_error(format!("failed to serialize the {path} request body: {e}"))
        })?;
        if encoded.len() > MAX_REQUEST_BODY_BYTES {
            return Err(internal_error(format!(
                "{path} request body exceeds {MAX_REQUEST_BODY_BYTES} bytes ({} bytes)",
                encoded.len()
            )));
        }
        let response = self
            .request(Method::Post, path, Some(&encoded), false)?
            .ok_or_else(|| internal_error(format!("{path} answered without a body")))?;
        serde_json::from_slice(&response).map_err(|e| {
            CliError::new(
                ErrorCode::DaemonResponseInvalid,
                format!("daemon returned a body that is not valid JSON ({e})"),
            )
        })
    }

    /// Performs one bounded `GET`. `Ok(None)` means the daemon does not
    /// implement this route (HTTP 404) — the caller decides what an absent
    /// route means. Every other failure (including timeouts) is propagated.
    pub fn get_json_optional(&self, path: &str) -> CliResult<Option<serde_json::Value>> {
        let Some(body) = self.get_raw(path, true)? else {
            return Ok(None);
        };
        serde_json::from_slice(&body).map(Some).map_err(|e| {
            CliError::new(
                ErrorCode::DaemonResponseInvalid,
                format!("daemon returned a body that is not valid JSON ({e})"),
            )
        })
    }

    /// One bounded `GET`. `Ok(None)` only when `allow_missing_route` and the
    /// daemon answered 404.
    fn get_raw(&self, path: &str, allow_missing_route: bool) -> CliResult<Option<Vec<u8>>> {
        self.request(Method::Get, path, None, allow_missing_route)
    }

    /// The single request path: fresh agent per request (one request per
    /// connection, so an aborted request is never silently retried), no
    /// redirects, never the environment proxy, `Authorization` never logged,
    /// and every operation bounded by the command deadline.
    fn request(
        &self,
        method: Method,
        path: &str,
        body: Option<&[u8]>,
        allow_missing_route: bool,
    ) -> CliResult<Option<Vec<u8>>> {
        if path.len() > MAX_REQUEST_TARGET_BYTES {
            // Reachable from a user-supplied `--id`, so it is an invalid argument rather than a
            // violated internal invariant.
            return Err(CliError::new(
                ErrorCode::InvalidArgument,
                format!("request target exceeds {MAX_REQUEST_TARGET_BYTES} bytes"),
            ));
        }
        let remaining = remaining_until(self.deadline)?;
        let agent = Self::agent(remaining);

        let url = format!("http://{}{path}", self.authority);
        self.debug_log(&format!(
            "{} {url} (budget {remaining:?}, authorization header redacted{})",
            method.as_str(),
            match body {
                Some(body) => format!(", {}-byte body not logged", body.len()),
                None => String::new(),
            }
        ));

        let request = match method {
            Method::Get => agent.get(&url),
            Method::Post => agent.post(&url),
        };
        let request = request
            .set("Authorization", &format!("Bearer {}", self.token))
            .set("Connection", "close")
            .set("Accept", "application/json");

        let outcome = match body {
            Some(body) => request
                .set("Content-Type", "application/json")
                .set("Content-Length", &body.len().to_string())
                .send_bytes(body),
            None => request.call(),
        };

        match outcome {
            Ok(response) => self.read_body(response, path).map(Some),
            Err(ureq::Error::Status(404, _)) if allow_missing_route => {
                self.debug_log(&format!(
                    "GET {path} is not implemented by this daemon (404)"
                ));
                Ok(None)
            }
            Err(ureq::Error::Status(status, response)) => {
                Err(self.map_status_error(method, status, response, path))
            }
            Err(e) => Err(self.map_transport_error(&e, path)),
        }
    }

    /// Status handling that needs the request method or the error body. A 404
    /// on a `POST` means the daemon predates the route (too old to share at
    /// all — never "nothing was sent successfully"); a 400 means the daemon
    /// refused the payload, which is an argument error, not a daemon fault.
    fn map_status_error(
        &self,
        method: Method,
        status: u16,
        response: ureq::Response,
        path: &str,
    ) -> CliError {
        if method == Method::Post && status == 404 {
            return CliError::new(
                ErrorCode::DaemonUnsupported,
                format!(
                    "the daemon at {} does not implement POST {path}; it is too old — upgrade it",
                    self.authority
                ),
            );
        }
        if method == Method::Post && status == 400 {
            return CliError::new(
                ErrorCode::InvalidArgument,
                format!(
                    "the daemon rejected the request: {}",
                    self.error_detail(response, path)
                ),
            );
        }
        if status == 503 {
            // The host is up but has not bound its engine yet. That is a daemon-side condition
            // the caller can retry, never a claim that anything was or was not delivered.
            return CliError::new(
                ErrorCode::DaemonUnsupported,
                format!(
                    "the daemon at {} is not ready yet (HTTP {status} on {path}); retry in a moment",
                    self.authority
                ),
            );
        }
        self.map_status(status, path)
    }

    /// Best-effort human message from an error body. Falls back to a generic
    /// description rather than failing the whole command over cosmetics.
    fn error_detail(&self, response: ureq::Response, path: &str) -> String {
        match self.read_body(response, path) {
            Ok(body) => serde_json::from_slice::<serde_json::Value>(&body)
                .ok()
                .and_then(|value| {
                    value
                        .get("error")
                        .and_then(|error| error.as_str().map(String::from))
                })
                .unwrap_or_else(|| "the daemon gave no reason".to_string()),
            Err(_) => "the daemon gave no reason".to_string(),
        }
    }

    fn read_body(&self, response: ureq::Response, path: &str) -> CliResult<Vec<u8>> {
        let status = response.status();
        let protocol_error = |reason: &str| {
            CliError::new(
                ErrorCode::DaemonProtocolError,
                format!("daemon violated the loopback HTTP contract on {path}: {reason}"),
            )
        };
        // ureq only surfaces `Err(Error::Status(..))` for status >= 400, so a 3xx arrives here
        // looking like an ordinary success. Redirects are never followed and a loopback control
        // plane has no reason to answer one; treating its body as the daemon's answer would let a
        // stranger's page masquerade as Klardrop state.
        if (300..400).contains(&status) {
            return Err(protocol_error(&format!(
                "answered {status}; redirects are never followed"
            )));
        }
        if let Some(encoding) = response.header("transfer-encoding") {
            if encoding.to_ascii_lowercase().contains("chunked") {
                return Err(protocol_error("chunked transfer encoding is not supported"));
            }
        }

        // Enforce the header ceilings before touching the body.
        let mut block_bytes = 0usize;
        for name in response.headers_names() {
            for value in response.all(&name) {
                let line_bytes = name.len() + value.len() + 4; // ": " + CRLF
                block_bytes = block_bytes.saturating_add(line_bytes);
                if line_bytes > MAX_RESPONSE_HEADER_LINE_BYTES {
                    return Err(protocol_error(&format!(
                        "response header line exceeds {MAX_RESPONSE_HEADER_LINE_BYTES} bytes"
                    )));
                }
            }
        }
        if block_bytes > MAX_RESPONSE_HEADER_BLOCK_BYTES {
            return Err(protocol_error(&format!(
                "response header block exceeds {MAX_RESPONSE_HEADER_BLOCK_BYTES} bytes"
            )));
        }

        if let Some(length) = response.header("content-length") {
            let declared: u64 = length
                .trim()
                .parse()
                .map_err(|_| protocol_error("malformed Content-Length header"))?;
            if declared > MAX_RESPONSE_BODY_BYTES as u64 {
                return Err(protocol_error(&format!(
                    "response body exceeds {MAX_RESPONSE_BODY_BYTES} bytes"
                )));
            }
        }

        let mut body = Vec::new();
        DeadlineReader {
            inner: response.into_reader(),
            deadline: self.deadline,
        }
        .take((MAX_RESPONSE_BODY_BYTES + 1) as u64)
        .read_to_end(&mut body)
        .map_err(|e| self.map_io_error(&e, path))?;
        if body.len() > MAX_RESPONSE_BODY_BYTES {
            return Err(protocol_error(&format!(
                "response body exceeds {MAX_RESPONSE_BODY_BYTES} bytes"
            )));
        }

        self.debug_log(&format!(
            "{path} status {status}, body {} bytes",
            body.len()
        ));
        Ok(body)
    }

    fn map_transport_error(&self, error: &ureq::Error, path: &str) -> CliError {
        match error {
            ureq::Error::Status(status, _) => self.map_status(*status, path),
            ureq::Error::Transport(transport) => {
                self.debug_log(&format!("transport error on {path}: {transport}"));
                // ureq reports socket timeouts as `ErrorKind::Io` carrying the
                // original `std::io::Error`, so classify on that.
                match self.io_source(transport) {
                    Some(io) => self.map_io_error(&io, path),
                    None => self.unreachable(path, &transport.to_string()),
                }
            }
        }
    }

    fn io_source(&self, transport: &ureq::Transport) -> Option<std::io::Error> {
        std::error::Error::source(transport)
            .and_then(|source| source.downcast_ref::<std::io::Error>())
            .map(|io| std::io::Error::new(io.kind(), io.to_string()))
    }

    fn map_io_error(&self, error: &std::io::Error, path: &str) -> CliError {
        match error.kind() {
            std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock => self.timeout_error(),
            _ => self.unreachable(path, &error.to_string()),
        }
    }

    fn timeout_error(&self) -> CliError {
        CliError::new(
            ErrorCode::DaemonTimeout,
            format!(
                "daemon at {} did not answer before the command deadline",
                self.authority
            ),
        )
    }

    fn unreachable(&self, path: &str, detail: &str) -> CliError {
        CliError::new(
            ErrorCode::DaemonUnreachable,
            format!(
                "cannot reach the Klardrop daemon at {} ({path}: {detail}); \
                 the control file may be stale — restart the daemon or pass --control-file",
                self.authority
            ),
        )
    }

    fn map_status(&self, status: u16, path: &str) -> CliError {
        match status {
            // The daemon rejected our token: the control file is not the one this
            // daemon wrote, or another daemon replaced it in the meantime.
            401 | 403 => CliError::new(
                ErrorCode::DaemonControlInvalid,
                format!(
                    "daemon at {} rejected the control-file credentials ({status} on {path}); \
                     the control file is stale — restart the daemon",
                    self.authority
                ),
            ),
            300..=399 => CliError::new(
                ErrorCode::DaemonProtocolError,
                format!("daemon answered {status} on {path}; redirects are never followed"),
            ),
            _ => CliError::new(
                ErrorCode::DaemonHttpError,
                format!("daemon answered HTTP {status} on {path}"),
            ),
        }
    }
}

fn internal_error(message: String) -> CliError {
    CliError::new(ErrorCode::InternalError, message)
}

fn remaining_until(deadline: Instant) -> CliResult<Duration> {
    let now = Instant::now();
    if now >= deadline {
        return Err(CliError::new(
            ErrorCode::DaemonTimeout,
            "command deadline expired before the request could be sent",
        ));
    }
    Ok(deadline - now)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expired_deadline_is_a_timeout() {
        let deadline = Instant::now() - Duration::from_millis(1);
        let error = remaining_until(deadline).expect_err("already expired");
        assert_eq!(error.code, ErrorCode::DaemonTimeout);
        assert_eq!(error.code.exit_code(), 4);
    }

    #[test]
    fn request_target_ceiling_covers_the_documented_paths() {
        for path in ["/state", "/capabilities", "/health"] {
            assert!(path.len() <= MAX_REQUEST_TARGET_BYTES);
        }
    }
}
