package com.carlom.klardrop.common.communication.message

import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import okio.BufferedSource

enum class MessageType(val id: Byte) {

  INTRO(0), TEXT(1), FILE(2);

  companion object {
    fun fromId(id: Byte): MessageType {
      return values().first { it.id == id }
    }
  }

}

sealed interface Message {
  val type: MessageType
}

sealed interface SendMessageRequest {
  val message: Message
}


interface EnvelopeHandler<E : Message, R: SendMessageRequest> {

  suspend fun handleIncoming(message: E, receiveChannel: ReceiveChannel<Frame>)
  suspend fun handleOutgoing(request: R, sendChannel: SendChannel<Frame>)

}

object NoopSendMessageRequest : SendMessageRequest {
  override val message: Message
    get() = throw NotImplementedError()
}