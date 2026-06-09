package com.carlom.klardrop.common.communication

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform unit tests for [isConnectionRefused], which decides whether a failed dial
 * should invalidate the cached endpoint (review finding 1.3). This logic is platform-agnostic
 * but the exception *shapes* differ per platform, so these tests synthesize each shape:
 *  - JVM / Android: `java.net.ConnectException` (matched by simpleName).
 *  - Apple-native (Ktor over POSIX): `PosixException.ConnectionRefusedException` (simpleName
 *    "ConnectionRefusedException") and/or strerror text "Connection refused".
 *
 * It is critical that NON-refusal errors (reset / timeout / host-unreachable) return false:
 * those are transient and must not evict a still-valid endpoint.
 *
 * Local classes are used purely so their `simpleName` matches the real platform exception
 * names the production heuristic keys on.
 */
class IsConnectionRefusedTest {

  // Names chosen so ::class.simpleName matches the real platform exceptions.
  private class ConnectException(message: String?) : Exception(message)
  private class ConnectionRefusedException(message: String?) : Exception(message)
  private class ConnectionResetException(message: String?) : Exception(message)
  private class PosixException(message: String?) : Exception(message)
  private class GenericIo(message: String?) : Exception(message)

  @Test
  fun jvmConnectExceptionIsRefused() {
    assertTrue(ConnectException("Connection refused").isConnectionRefused())
  }

  @Test
  fun appleConnectionRefusedExceptionIsRefused() {
    // Darwin strerror(ECONNREFUSED) == "Connection refused"; class name also matches.
    assertTrue(ConnectionRefusedException("Connection refused").isConnectionRefused())
  }

  @Test
  fun canonicalEconnrefusedTextIsRefused_regardlessOfClass() {
    assertTrue(GenericIo("connect() failed: ECONNREFUSED").isConnectionRefused())
  }

  @Test
  fun connectionRefusedTextIsRefused_regardlessOfClass() {
    assertTrue(GenericIo("Connection refused").isConnectionRefused())
  }

  @Test
  fun wrappedRefusalInCauseChainIsRefused() {
    val wrapped = GenericIo("dial failed").initCausedBy(ConnectException("Connection refused"))
    assertTrue(wrapped.isConnectionRefused())
  }

  @Test
  fun connectionResetIsNotRefused() {
    assertFalse(ConnectionResetException("Connection reset by peer").isConnectionRefused())
  }

  @Test
  fun timeoutIsNotRefused() {
    assertFalse(GenericIo("Operation timed out (ETIMEDOUT)").isConnectionRefused())
  }

  @Test
  fun hostUnreachableIsNotRefused() {
    assertFalse(GenericIo("No route to host (EHOSTUNREACH)").isConnectionRefused())
  }

  @Test
  fun barePosixExceptionBaseIsNotRefused() {
    // The broad PosixException base (used for reset/timeout/unreachable) must NOT be
    // treated as a refusal when its message isn't refusal-specific.
    assertFalse(PosixException("posix error 54").isConnectionRefused())
  }

  @Test
  fun unrelatedExceptionIsNotRefused() {
    assertFalse(IllegalStateException("something else").isConnectionRefused())
  }

  // Helper: attach a cause without relying on Throwable.initCause (not available in common).
  private fun Throwable.initCausedBy(cause: Throwable): Throwable = WrappedWithCause(this, cause)

  private class WrappedWithCause(original: Throwable, override val cause: Throwable) :
    Exception(original.message)
}
