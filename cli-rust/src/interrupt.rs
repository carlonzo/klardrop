//! Ctrl-C during an unattended command.
//!
//! The plan gives an interrupted wait its own meaning: it does **not** prove
//! the transfer failed, and it does not cancel anything — the daemon keeps
//! sending. What it must do is leave the caller holding the request id, so the
//! delivery state can still be asked for afterwards with `transfers --id`.
//!
//! Without a handler the process dies on the default disposition: the shell
//! reports exit 130 and that is all the operator ever learns, which is the one
//! thing the contract says must not happen.
//!
//! The handler only sets a flag. Doing work in a signal handler is unsafe, and
//! the work here is a single atomic store. The poll loops that wait for a
//! result are what notice — `share --wait` and `discover --wait` — and they
//! leave through the same "delivery unknown" door an expired deadline takes. A
//! single request already in flight is deliberately left alone: abandoning a
//! `POST /share` mid-flight would lose the very request id the interrupt exists
//! to preserve.
//!
//! The client targets Unix only (Linux native engine host, macOS app companion):
//! one `signal(2)` handler, one flag, one outcome. Windows desktop runs the JVM
//! app and has no Rust CLI, so there is no console-control branch to keep.
//!
//! Registration can fail, and it is reported rather than swallowed: a client
//! that could not register the handler cannot honour the interrupted-wait
//! contract, and quietly running anyway is how the request id gets lost in the
//! first place.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use crate::envelope::{CliError, ErrorCode};

static INTERRUPTED: AtomicBool = AtomicBool::new(false);

/// Installs the console-control handler.
///
/// Idempotent: the first outcome is reused, so a second call neither registers
/// a second handler nor hides an earlier failure.
pub fn install() -> Result<(), CliError> {
    platform::install().map_err(|source| {
        CliError::new(
            ErrorCode::InternalError,
            format!(
                "could not register the Ctrl-C handler, so an interrupted wait would lose \
                 the request id: {source}"
            ),
        )
    })
}

/// `true` once Ctrl-C has been seen. The caller decides what that means.
pub fn was_interrupted() -> bool {
    INTERRUPTED.load(Ordering::SeqCst)
}

/// Sleeps in short slices so an interrupt is noticed promptly instead of after a
/// whole poll interval. Bounded by `total` either way.
pub fn sleep_interruptibly(total: Duration) {
    const SLICE: Duration = Duration::from_millis(50);
    let mut left = total;
    while !left.is_zero() && !was_interrupted() {
        let slice = SLICE.min(left);
        std::thread::sleep(slice);
        left -= slice;
    }
}

/// Console control: `signal(2)` and `SIGINT`.
///
/// `SIGINT` is the only signal handled here, because it is the only one that
/// means "a person wants this to stop". `SIGTERM` is how a supervisor or an
/// operator ends a process and keeps its default disposition.
mod platform {
    use std::io;
    use std::sync::LazyLock;

    use super::{Ordering, INTERRUPTED};

    extern "C" fn on_sigint(_signal: libc::c_int) {
        INTERRUPTED.store(true, Ordering::SeqCst);
    }

    /// The registration, run exactly once: `LazyLock`'s own `Once` is what makes
    /// `install` idempotent, and it keeps the outcome so a failure reaches every
    /// caller instead of being retried behind their backs.
    static REGISTRATION: LazyLock<Result<(), i32>> = LazyLock::new(|| {
        // SAFETY: `on_sigint` is `extern "C"`, does nothing but an atomic
        // store, and has a stable address for the whole program. `signal` is
        // used rather than `sigaction` only because a handler that does no work
        // is exactly the case where the weaker call is right.
        let previous =
            unsafe { libc::signal(libc::SIGINT, on_sigint as *const () as libc::sighandler_t) };
        if previous == libc::SIG_ERR {
            Err(io::Error::last_os_error().raw_os_error().unwrap_or(0))
        } else {
            Ok(())
        }
    });

    pub(super) fn install() -> Result<(), io::Error> {
        match *REGISTRATION {
            Ok(()) => Ok(()),
            Err(code) => Err(io::Error::from_raw_os_error(code)),
        }
    }
}
