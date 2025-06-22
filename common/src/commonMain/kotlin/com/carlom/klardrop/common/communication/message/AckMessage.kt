package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class AckMessageHandler : MessageHandler<MessageAcknowledgment, SimpleSendMessageRequest> {

  override suspend fun handleIncoming(
    message: MessageAcknowledgment,
    readChannel: ByteReadChannel,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
  ) {
    log("AckMessageHandler", "Received ACK: ${message.ackType} for message ${message.messageId}")
    // ACKs are handled by the sender waiting for them, not through the normal receive flow
    // This method exists to satisfy the interface but doesn't need to do anything
  }

  override suspend fun handleOutgoing(
    request: SimpleSendMessageRequest,
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    log("AckMessageHandler", "Sending ACK: ${(request.message as MessageAcknowledgment).ackType} for message ${request.message.messageId}")
    // ACKs are sent directly, no additional handling needed
    // The message itself is already serialized by the connection layer
  }
}