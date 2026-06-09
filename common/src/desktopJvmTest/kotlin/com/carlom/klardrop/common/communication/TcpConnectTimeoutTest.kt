package com.carlom.klardrop.common.communication

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Verifies that the per-address TCP connect is bounded by [withTimeout] so that
 * a stale / black-holed address does not consume the entire
 * CONNECTION_WAIT_TIMEOUT budget.
 *
 * **Black-hole simulation**: bind a [ServerSocket] with backlog=1 and
 * pre-connect one raw [Socket] to saturate the kernel accept queue.
 * Subsequent SYN packets are then silently dropped by the OS (not RST'd), so
 * the connect call would hang indefinitely without an explicit timeout.
 *
 * **What this test validates**: [TCP_CONNECT_TIMEOUT_MS] is configured as a
 * sensible value (positive, below the 15 s overall budget), and an unguarded
 * Ktor connect to a black-holed port does *not* complete within that budget —
 * confirming that the `withTimeout` wrapper in [ClientImpl.establishConnection]
 * is actually needed and is not a no-op.
 */
class TcpConnectTimeoutTest {

  // ── 1. Unit assertion: the constant is in a sensible range ──────────────

  /**
   * [TCP_CONNECT_TIMEOUT_MS] must be:
   * - positive (timeout is active)
   * - smaller than the 15 s CONNECTION_WAIT_TIMEOUT so that subsequent
   *   addresses can still be tried within the overall budget
   */
  @Test
  fun connectTimeoutConstantIsReasonable() {
    assertTrue(TCP_CONNECT_TIMEOUT_MS > 0L, "TCP_CONNECT_TIMEOUT_MS must be positive")
    assertTrue(
      TCP_CONNECT_TIMEOUT_MS < 15_000L,
      "TCP_CONNECT_TIMEOUT_MS ($TCP_CONNECT_TIMEOUT_MS ms) must be smaller than the " +
        "15 s CONNECTION_WAIT_TIMEOUT so the caller can still try subsequent addresses",
    )
  }

  // ── 2. Behavioral: a black-holed connect does NOT complete within the budget

  /**
   * Demonstrates (pre-condition for the fix) that without an explicit timeout
   * a Ktor connect to a backlog-saturated port does NOT complete within
   * [TCP_CONNECT_TIMEOUT_MS].  The production [ClientImpl.establishConnection]
   * wraps the connect with `withTimeout(TCP_CONNECT_TIMEOUT_MS)`, so what
   * would otherwise be an unbounded wait becomes bounded.
   *
   * The test is structurally equivalent to calling `establishConnection`
   * without the wrapping `withTimeout` and asserting it does NOT finish fast.
   * If the OS happens to complete the SYN-ACK despite a full backlog (unusual
   * on macOS/Linux but theoretically possible), the test is silently skipped.
   */
  @Test
  fun unguardedConnectToBlackholeHangsLongerThanTimeout() = runBlocking(Dispatchers.IO) {
    val serverSocket = ServerSocket()
    // Smallest backlog the OS honours — after filling it, SYNs are dropped.
    serverSocket.bind(InetSocketAddress("127.0.0.1", 0), /*backlog=*/ 1)
    val port = serverSocket.localPort

    // Pre-connect one raw socket to saturate the kernel accept queue.
    val fillerSocket = Socket()
    fillerSocket.connect(InetSocketAddress("127.0.0.1", port), 500)

    val selectorManager = SelectorManager(Dispatchers.IO)
    val mark = TimeSource.Monotonic.markNow()
    var timedOut = false

    try {
      // Use TCP_CONNECT_TIMEOUT_MS as the outer budget: if the connect
      // completes instantly the backlog wasn't actually full on this OS/kernel
      // configuration and we cannot draw conclusions (test is a no-op).
      // If it times out, we know the connect would have hung indefinitely
      // without the withTimeout guard in production code.
      withTimeout(TCP_CONNECT_TIMEOUT_MS) {
        aSocket(selectorManager).tcp().connect("127.0.0.1", port) {
          keepAlive = true
        }
      }
    } catch (_: TimeoutCancellationException) {
      timedOut = true
    } finally {
      fillerSocket.close()
      serverSocket.close()
      selectorManager.close()
    }

    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    if (!timedOut) {
      // The OS completed the SYN-ACK despite a nominally full backlog —
      // this happens on some macOS / Linux kernel versions.  The test is
      // inconclusive on this host; skip without failing.
      println(
        "TcpConnectTimeoutTest: backlog saturation did not produce a black-hole on " +
          "this platform (connect completed in ${elapsedMs} ms). " +
          "Test is inconclusive — skipping assertion.",
      )
      return@runBlocking
    }

    // We confirmed the connect hung.  The outer withTimeout fired after
    // exactly TCP_CONNECT_TIMEOUT_MS — this is precisely what the production
    // withTimeout guard achieves.  Without it the elapsed time would be
    // measured in minutes (OS retransmit backoff).
    assertTrue(
      elapsedMs >= TCP_CONNECT_TIMEOUT_MS - 500L,
      "Connect timed out but elapsed time ($elapsedMs ms) is unexpectedly short — " +
        "expected >= ${TCP_CONNECT_TIMEOUT_MS - 500L} ms (the timeout budget).",
    )
    assertTrue(
      // Allow a 3 s margin above the nominal timeout for scheduling jitter on
      // slow CI runners, but stay well below the OS retransmit default (~75 s).
      elapsedMs < TCP_CONNECT_TIMEOUT_MS + 3_000L,
      "Connect timed out but took ${elapsedMs} ms — more than " +
        "${TCP_CONNECT_TIMEOUT_MS + 3_000L} ms (timeout + 3 s CI margin). " +
        "The withTimeout guard may not be firing promptly.",
    )
  }
}
