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
 */
data class HeartbeatConfig(
  val interval: Duration = 15.seconds,
  val timeout: Duration = 5.seconds,
  val enabled: Boolean = true,
) {
  companion object {
    val DEFAULT = HeartbeatConfig()

    /** Test helper for fast deterministic heartbeat behavior. */
    fun forTest(intervalMs: Long, timeoutMs: Long): HeartbeatConfig = HeartbeatConfig(
      interval = intervalMs.milliseconds,
      timeout = timeoutMs.milliseconds,
      enabled = true,
    )
  }
}
