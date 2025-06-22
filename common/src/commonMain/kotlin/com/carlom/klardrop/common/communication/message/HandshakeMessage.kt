package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable

@Serializable
data class HandshakeMessage(
  val deviceId: String,
) : Message() {
  override val type: MessageType = MessageType.HANDSHAKE
  override val hasPayload: Boolean = false
}
