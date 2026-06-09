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
 * [maxConsecutiveSkips] bounds how many back-to-back heartbeat ticks can be
 * suppressed by a held write lock before we treat the connection as dead.
 * Normally a write lock is held only while a chunk is in flight; a writer
 * that holds the lock across [maxConsecutiveSkips] full intervals is either
 * catastrophically slow or genuinely wedged — either way the link is unhealthy.
 *
 * With [interval] = 15 s and [maxConsecutiveSkips] = 12 the connection
 * survives 3 continuous minutes of in-flight writes before being torn down,
 * which comfortably covers multi-GB transfers on slow links. Set it higher
 * only if you expect significantly larger single-shot transfers.
 */
data class HeartbeatConfig(
  val interval: Duration = 15.seconds,
  val timeout: Duration = 5.seconds,
  val enabled: Boolean = true,
  /**
   * Maximum number of consecutive heartbeat ticks that may be skipped because
   * the write lock is held before the connection is forcibly closed.
   * A value ≤ 0 disables the bound (legacy behaviour, not recommended).
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
