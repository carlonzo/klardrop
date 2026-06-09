package com.carlom.klardrop.common.communication

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Application-level liveness probe configuration.
 *
 * Every [interval] the local end sends a PING to the peer and waits up to
 * [timeout] for a PONG. If the PONG does not arrive, the connection is closed.
 * The next send() will then trigger reconnection via the existing retry path.
 *
 * Intended as a fast complement to OS-level TCP keep-alive, which on Linux
 * defaults to 2 hours of idle before probing.
 *
 * [maxConsecutiveSkips] bounds how many back-to-back heartbeat ticks may be
 * skipped because the write lock is *continuously* held before we treat the
 * connection as dead. A healthy chunked transfer releases the write lock between
 * every framed chunk, so the heartbeat's per-tick probe (see
 * [ConnectionMessenger]) observes a release and resets the counter — a counted
 * skip therefore requires the lock to stay held for an entire probe window, i.e.
 * a genuinely wedged writer or a peer that silently vanished (socket writes block
 * because they never drain). This bounds liveness-detection latency WITHOUT
 * false-closing healthy-but-saturated transfers.
 *
 * With [interval] = 15 s and [maxConsecutiveSkips] = 12 a wedged link is torn
 * down after at most ~12 ticks (≈3 min worst case). This is a backstop for a
 * stuck writer, NOT a per-transfer budget: a transfer that keeps making progress
 * resets the counter every tick and is never closed regardless of its duration.
 */
data class HeartbeatConfig(
  val interval: Duration = 15.seconds,
  val timeout: Duration = 5.seconds,
  val enabled: Boolean = true,
  /**
   * Maximum number of consecutive heartbeat ticks during which the write lock is
   * held *continuously* (through the whole per-tick probe window) before the
   * connection is forcibly closed as wedged. A value ≤ 0 disables the bound
   * (legacy behaviour, not recommended).
   */
  val maxConsecutiveSkips: Int = 12,
) {
  companion object {
    val DEFAULT = HeartbeatConfig()

    /** Test helper for fast deterministic heartbeat behavior. */
    fun forTest(
      intervalMs: Long,
      timeoutMs: Long,
      maxConsecutiveSkips: Int = 12,
    ): HeartbeatConfig = HeartbeatConfig(
      interval = intervalMs.milliseconds,
      timeout = timeoutMs.milliseconds,
      enabled = true,
      maxConsecutiveSkips = maxConsecutiveSkips,
    )
  }
}
