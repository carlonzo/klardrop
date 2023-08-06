package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

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

  suspend fun handleIncoming(message: E, receiveChannel: ReceiveChannel<Frame>, receiveFlow: MutableStateFlow<ReceiveMessageUpdate>)
  suspend fun handleOutgoing(request: R, webSocketSession: WebSocketSession, progressFlow: MutableSharedFlow<MessengerSendProgress>)

}
