package com.carlom.klardrop.common.communication

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for ACK timeout handling
 */
data class AckTimeoutConfig(
  /**
   * Timeout for ACK_RECEIVED when sending messages without payload (TextMessage)
   */
  val noPayloadAckTimeout: Duration = 10.seconds,
  
  /**
   * Timeout for ACK_READY when sending message headers with payload (FileMessage)
   */
  val readyAckTimeout: Duration = 10.seconds,
  
  /**
   * Timeout for ACK_RECEIVED when sending payload completion
   */
  val receivedAckTimeout: Duration = 30.seconds,
  
  /**
   * Maximum number of retry attempts for no-payload messages and payload headers
   */
  val maxRetries: Int = 2,
  
  /**
   * Backoff multiplier for retry delays
   */
  val retryBackoffMultiplier: Double = 1.5
) {
  companion object {
    val DEFAULT = AckTimeoutConfig()
  }
}