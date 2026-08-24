package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.AckType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
   * Timeout for ACK_RECEIVED when sending payload completion.
   *
   * Measured from the moment the sender finishes *writing* the last chunk, which — with TCP
   * send buffers and a 38 MB/s link — can be several megabytes ahead of what the receiver has
   * drained, hashed and finalized. 10s was tight enough to fire on a slow receiver and trigger
   * a pointless retry of an already-delivered file.
   */
  val receivedAckTimeout: Duration = 30.seconds,

  /**
   * Time to wait for ACK_READY / ACK_RECEIVED / ACK_REJECTED after the receiver has
   * signalled ACK_AWAITING_USER (i.e. is blocking on a human accept/reject prompt).
   * Long enough to give the user time to glance at their phone; short enough that a
   * dropped peer eventually surfaces as a transfer failure instead of hanging forever.
   */
  val userResponseTimeout: Duration = 5.minutes,

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
    // REJECTED and AWAITING_USER are registered alongside RECEIVED/READY and race them
    // in the same withTimeout block — their independent timeouts are never directly
    // waited on.
    AckType.REJECTED -> if (hasPayload) receivedAckTimeout else noPayloadAckTimeout
    AckType.AWAITING_USER -> if (hasPayload) receivedAckTimeout else noPayloadAckTimeout
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
      userResponseTimeout = timeoutMs.milliseconds,
    )
  }
}