package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class TextMessageHandler(
  private val serializer: MessageSerializer
) : MessageHandler<TextMessage, SimpleSendMessageRequest> {

  override suspend fun handleIncoming(
    message: TextMessage,
    readChannel: ByteReadChannel,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
  ) {
    log("TextMessageHandler", "Received text message: ${message.text}")
    
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