package com.carlom.klardrop.common.communication.message

import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

enum class MessageType(val id: Byte) {

  HANDSHAKE(0),
  TEXT(1),
  FILE(2),

  ;

  companion object {
    fun fromId(id: Byte): MessageType {
      return values().first { it.id == id }
    }
  }

}

sealed interface Message {
  val type: MessageType
  val hasPayload: Boolean
}

sealed interface SendMessageRequest {
  val message: Message
}

fun Message.toSimpleSendRequest(): SendMessageRequest {
  if (hasPayload) {
    throw IllegalStateException("Message has payload. Cant use an empty send request")
  }

  return SimpleSendMessageRequest(this)
}

class SimpleSendMessageRequest(override val message: Message) : SendMessageRequest

interface MessageHandler<E : Message, R : SendMessageRequest> {

  suspend fun handleIncoming(message: E, receiveChannel: ReceiveChannel<Frame>)
  suspend fun handleOutgoing(request: R, sendChannel: SendChannel<Frame>)

}
