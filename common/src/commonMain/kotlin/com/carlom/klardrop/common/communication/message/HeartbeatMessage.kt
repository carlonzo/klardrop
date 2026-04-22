package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Application-level liveness probe.
 *
 * The sender periodically emits a [PingMessage] with a random id and waits
 * for the receiver to send back a [PongMessage] carrying the same id as
 * [PongMessage.pingId]. If the pong does not arrive within
 * [com.carlom.klardrop.common.communication.HeartbeatConfig.timeout], the
 * connection is considered dead and torn down. This catches half-open TCP
 * connections far faster than the OS keep-alive default.
 */
@Serializable
data class PingMessage(
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = MessageType.PING
  override val hasPayload: Boolean = false
}

@Serializable
data class PongMessage(
  val pingId: Int,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = MessageType.PONG
  override val hasPayload: Boolean = false
}
