package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.TrustMessage
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.invoke
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf

class MessageSerializer(
  private val proto: ProtoBuf,
  private val coroutines: Coroutines
) {

  suspend fun deserialize(content: ByteArray): Message = coroutines.cpuDispatcher {
    val idType = content[0]
    val type = MessageType.fromId(idType)

    val data = content.sliceArray(1 until content.size)
    val serializer = serializer<Message>(type)

    proto.decodeFromByteArray(serializer, data)
  }

  suspend fun serialize(message: Message): ByteArray = coroutines.cpuDispatcher {
    val type = message.type
    val serializer = serializer<Message>(type)

    val encodedMessage = proto.encodeToByteArray(serializer, message)

    val payload = ByteArray(encodedMessage.size + 1)
    payload[0] = message.type.id

    encodedMessage.copyInto(payload, 1, 0, encodedMessage.size)

    payload
  }

  @Suppress("UNCHECKED_CAST")
  private fun <E : Message> serializer(messageType: MessageType): KSerializer<E> {
    return when (messageType) {
      MessageType.HANDSHAKE -> HandshakeMessage.serializer() as KSerializer<E>
      MessageType.TEXT -> TextMessage.serializer() as KSerializer<E>
      MessageType.FILE -> FileMessage.serializer() as KSerializer<E>
      MessageType.ACK_READY -> MessageAcknowledgment.serializer() as KSerializer<E>
      MessageType.ACK_RECEIVED -> MessageAcknowledgment.serializer() as KSerializer<E>
      MessageType.PAIRING -> TrustMessage.serializer() as KSerializer<E>
    }
  }
}