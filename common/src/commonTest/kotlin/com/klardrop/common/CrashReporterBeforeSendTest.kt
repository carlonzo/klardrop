package com.klardrop.common

import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.SentryException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the `beforeSend` backstop for native-SDK auto-capture (Sentry KLARDROP-JW/JY):
 * uncaught coroutine cancellations reach the thread's uncaught-exception handler and are
 * uploaded without ever passing through `CrashReporter.notify`, so the event-level
 * decision must re-apply the noise table. Native SDKs report the fully qualified name.
 */
class CrashReporterBeforeSendTest {

  private fun eventWith(type: String?, value: String?): SentryEvent =
    SentryEvent().apply {
      exceptions = mutableListOf(SentryException(type = type, value = value))
    }

  @Test
  fun qualifiedJobCancellation_isDropped() {
    assertTrue(
      shouldDropEvent(eventWith("kotlinx.coroutines.JobCancellationException", "by2 was cancelled")),
    )
  }

  @Test
  fun qualifiedCancellation_isDropped() {
    assertTrue(
      shouldDropEvent(eventWith("kotlinx.coroutines.CancellationException", "StandaloneCoroutine was cancelled")),
    )
  }

  @Test
  fun ackTimeout_isKept() {
    assertFalse(
      shouldDropEvent(
        eventWith(
          "kotlin.IllegalStateException",
          "ACK timeout: Expected RECEIVED for message 846910907 from ccf01ceb",
        ),
      ),
    )
  }

  @Test
  fun messageOnlyEvent_isKept() {
    // User reports and captureMessage events carry no exceptions — never drop them.
    assertFalse(shouldDropEvent(SentryEvent()))
  }
}
