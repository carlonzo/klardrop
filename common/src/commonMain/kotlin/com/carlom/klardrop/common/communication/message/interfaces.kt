package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.random.Random

enum class MessageType(val id: Byte) {

  HANDSHAKE(0),
  TEXT(1),
  FILE(2),
  ACK_READY(3),
  ACK_RECEIVED(4),
  PAIRING(5),

  ;

  companion object {
    fun fromId(id: Byte): MessageType {
      return MessageType.entries.first { it.id == id }
    }
  }

}

sealed class Message {
  open val id: Int = Random.nextInt()

  abstract val type: MessageType
  abstract val hasPayload: Boolean
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

enum class AckType {
  READY,
  RECEIVED
}

@Serializable
data class MessageAcknowledgment(
  val ackType: AckType,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = when (ackType) {
    AckType.READY -> MessageType.ACK_READY
    AckType.RECEIVED -> MessageType.ACK_RECEIVED
  }
  override val hasPayload: Boolean = false
}

interface MessageHandler<E : Message, R : SendMessageRequest> {

  suspend fun handleIncoming(message: E, readChannel: ByteReadChannel, receiveFlow: MutableStateFlow<ReceiveMessageUpdate>)
  suspend fun handleOutgoing(toDeviceId: String, request: R, writeChannel: ByteWriteChannel, progressFlow: MutableSharedFlow<MessengerSendProgress>)

}
