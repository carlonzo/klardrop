package com.carlom.klardrop.common.utils

/**
 * Installs the process-wide net for coroutine failures that no [nonFatalCoroutineExceptionHandler]
 * saw.
 *
 * We own the exception handler on every scope we create, but not on the scopes inside our
 * dependencies — Ktor's native `SelectorManager`, for one, runs its `pselect` loop in a
 * `SupervisorJob` scope with no handler of its own. On Kotlin/Native a failure there lands in
 * `processUnhandledException`, which terminates the process with `abort()`/SIGABRT; a transient
 * selector error after the Mac wakes from sleep is enough to take the whole app down.
 *
 * The Apple implementation installs a hook so those exceptions are reported to Sentry and the
 * process keeps running. Idempotent, and a no-op on the JVM targets, where the platform default is
 * already "log and carry on".
 */
expect fun installUnhandledExceptionGuard()
