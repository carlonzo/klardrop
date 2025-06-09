package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class MessageType(val id: Byte) {

  HANDSHAKE(0),
  TEXT(1),
  FILE(2),
  ACK(3),
  ;

  companion object {
    fun fromId(id: Byte): MessageType {
      return MessageType.entries.first { it.id == id }
    }
  }

}

sealed interface Message {
  val type: MessageType
  val hasPayload: Boolean
  val messageId: String? // Add this nullable field
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

import com.carlom.klardrop.common.communication.MessageSerializer // Added import

interface MessageHandler<E : Message, R : SendMessageRequest> {

  suspend fun handleIncoming(
    message: E,
    readChannel: ByteReadChannel,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
    // Added for ACK sending capability by handlers
    writeChannel: ByteWriteChannel,
    messageSerializer: MessageSerializer
  )
  suspend fun handleOutgoing(request: R, writeChannel: ByteWriteChannel, progressFlow: MutableSharedFlow<MessengerSendProgress>)

}
