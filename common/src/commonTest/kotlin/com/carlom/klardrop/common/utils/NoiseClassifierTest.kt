package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins Bugsnag noise filtering for lifecycle / expected transport failures that were
 * flooding the open-error list (CancellationException flood, ClosedWriteChannel mid-ACK,
 * dial-on-open with no transport).
 *
 * Real product bugs (ACK timeout, ClassCastException, IndexOutOfBounds) must remain
 * reportable.
 */
class NoiseClassifierTest {

  @Test
  fun cancellationException_isNoise() {
    assertTrue(
      CancellationException("StandaloneCoroutine was cancelled").isExpectedNetworkNoise(),
    )
  }

  @Test
  fun closedWriteChannelException_isNoise() {
    // Classifier matches by simpleName (common source set can't `is`-check Ktor types).
    // Local named class so simpleName == production Ktor ClosedWriteChannelException.
    class ClosedWriteChannelException(msg: String) : Exception(msg)
    assertTrue(ClosedWriteChannelException("channel closed").isExpectedNetworkNoise())
  }

  @Test
  fun noTransportAvailable_isNoise() {
    // Current wording (T5: distinguishes "no known route" from "connect failed: <cause>").
    assertTrue(
      IllegalArgumentException(
        "Cant connect to ef020564. No known route: device is visible but advertises no Klardrop TCP or BLE endpoint",
      ).isExpectedNetworkNoise(),
    )
    // Pre-T5 wording, still present in older on-device logs.
    assertTrue(
      IllegalArgumentException(
        "Cant connect to ef020564. No Klardrop TCP or BLE connection is available",
      ).isExpectedNetworkNoise(),
    )
  }

  @Test
  fun ackTimeout_isStillReported() {
    assertFalse(
      IllegalStateException(
        "ACK timeout: Expected RECEIVED for message 846910907 from ccf01ceb",
      ).isExpectedNetworkNoise(),
    )
  }

  @Test
  fun classCast_isStillReported() {
    assertFalse(
      ClassCastException(
        "TrustedMessage cannot be cast to TextMessage",
      ).isExpectedNetworkNoise(),
    )
  }

  @Test
  fun indexOutOfBounds_isStillReported() {
    assertFalse(
      IndexOutOfBoundsException("index: 3, size: 3").isExpectedNetworkNoise(),
    )
  }
}
