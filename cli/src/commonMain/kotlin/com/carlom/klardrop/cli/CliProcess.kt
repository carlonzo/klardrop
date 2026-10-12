package com.carlom.klardrop.cli

/**
 * Runs [argv] (no shell) with no stdin, waiting up to [timeoutMillis].
 * Returns (exitCode, trimmed stdout), or null if the process could not be started/timed out.
 */
internal expect fun runProcessCaptureStdout(argv: List<String>, timeoutMillis: Long): Pair<Int, String>?

/**
 * Registers the OS-level trigger for a graceful `daemon` shutdown: a JVM shutdown hook on JVM,
 * or a SIGINT/SIGTERM handler on native. The native handler only does an async-signal-safe
 * self-pipe write, so it's safe regardless of how many threads exist or which one the signal
 * lands on.
 */
internal expect fun installTerminationSignalHandler()

/**
 * Suspends with zero ongoing cost until termination has been requested (SIGINT/SIGTERM on
 * native, the JVM shutdown hook firing on JVM) — no polling.
 */
internal expect suspend fun awaitTerminationRequest()

/**
 * Called after daemon cleanup (ControlPlane.stop()) has finished. On JVM this releases the
 * shutdown hook so the JVM can actually exit; a no-op on native, which exits normally once
 * main() returns.
 */
internal expect fun onCleanupComplete()
