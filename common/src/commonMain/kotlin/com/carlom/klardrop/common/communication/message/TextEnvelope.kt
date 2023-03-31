package com.carlom.klardrop.common.communication.message

import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.Serializable

@Serializable
data class TextEnvelope(
  val text: String
) : Message {



  override val type = MessageType.TEXT
}

class TextEnvelopeHandler : EnvelopeHandler<TextEnvelope, NoopSendMessageRequest> {

  override suspend fun handleIncoming(message: TextEnvelope, receiveChannel: ReceiveChannel<Frame>) {
    // noop
  }

  override suspend fun handleOutgoing(request: NoopSendMessageRequest, sendChannel: SendChannel<Frame>) {
    // noop
  }

}