package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.AckType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for ACK timeout handling
 */
data class AckTimeoutConfig(
  /**
   * Timeout for ACK_RECEIVED when sending messages without payload (TextMessage)
   */
  val noPayloadAckTimeout: Duration = 5.seconds,

  /**
   * Timeout for ACK_READY when sending message headers with payload (FileMessage)
   */
  val readyAckTimeout: Duration = 5.seconds,

  /**
   * Timeout for ACK_RECEIVED when sending payload completion
   */
  val receivedAckTimeout: Duration = 10.seconds,

  /**
   * Maximum number of retry attempts for no-payload messages and payload headers
   */
  val maxRetries: Int = 2,

  /**
   * Backoff multiplier for retry delays
   */
  val retryBackoffMultiplier: Double = 1.5
) {
  fun timeoutFor(ackType: AckType, hasPayload: Boolean): Duration = when (ackType) {
    AckType.READY -> readyAckTimeout
    AckType.RECEIVED -> if (hasPayload) receivedAckTimeout else noPayloadAckTimeout
    // REJECTED is registered alongside RECEIVED/READY and races them in the same
    // withTimeout block — its independent timeout is never directly waited on.
    AckType.REJECTED -> if (hasPayload) receivedAckTimeout else noPayloadAckTimeout
  }

  companion object {
    val DEFAULT = AckTimeoutConfig()

    /**
     * Test helper that uses the same timeout for every ACK kind.
     */
    fun uniform(timeoutMs: Long): AckTimeoutConfig = AckTimeoutConfig(
      noPayloadAckTimeout = timeoutMs.milliseconds,
      readyAckTimeout = timeoutMs.milliseconds,
      receivedAckTimeout = timeoutMs.milliseconds,
    )
  }
}