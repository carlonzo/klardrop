package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable

@Serializable
data class Handshake(
  val deviceId: String,
) : Message {
  override val type: MessageType = MessageType.INTRO
}
