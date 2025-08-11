package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class TextMessageHandler(
  private val serializer: MessageSerializer,
  private val messageRepository: MessageRepository
) : MessageHandler<TextMessage, SimpleSendMessageRequest> {

  override suspend fun handleIncoming(
    message: TextMessage,
    readChannel: ByteReadChannel,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
  ) {
    log("TextMessageHandler", "Received text message: ${message.text}")
    
    // Insert the received message into the database
    val fromDeviceId = receiveFlow.value.device?.deviceId ?: "unknown"
    messageRepository.insertMessage(
      remoteDeviceId = fromDeviceId,
      content = message.text,
      isSender = false,
      messageType = PersistenceMessageType.TEXT,
      isRead = false // Incoming messages are unread initially
    )
    
    // Update receive flow with completed status
    receiveFlow.update {
      it.copy(
        messages = listOf(message),
        status = ReceiveMessageStatus.Completed
      )
    }
  }

  override suspend fun handleOutgoing(
    toDeviceId: String,
    request: SimpleSendMessageRequest,
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    val textMessage = request.message as TextMessage
    log("TextMessageHandler", "Sending text message: ${textMessage.text}")
    
    // Send the message directly since it has no payload
    writeChannel.sendMessage(textMessage, serializer)
  }
}