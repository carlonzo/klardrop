package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class HandshakeMessage(
  val deviceId: String,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = MessageType.HANDSHAKE
  override val hasPayload: Boolean = false
}

