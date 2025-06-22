package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke

interface MessagesRouter {
  suspend fun onMessageIncoming(
    fromDeviceId: String, 
    writeChannel: ByteWriteChannel, 
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit)? = null
  )
  suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>
  )
}

class MessagesRouterImpl(
  private val handlers: MessageHandlers,
  private val messageSerializer: MessageSerializer,
  private val coroutines: Coroutines,
  private val messengeReceiver: MessageReceiver,
) : MessagesRouter {
  override suspend fun onMessageIncoming(
    fromDeviceId: String, 
    writeChannel: ByteWriteChannel, 
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit)?
  ) = coroutines.ioDispatcher {

      val message = readChannel.readMessage(messageSerializer)
      log("MessagesRouter", "Received message from $fromDeviceId: $message")

      // Handle ACK messages specially - call the callback instead of normal processing
      if (message is MessageAcknowledgment) {
        log("MessagesRouter", "Received ACK message: ${message.ackType} for message ${message.messageId}")
        if (ackCallback != null) {
          log("MessagesRouter", "Calling ACK callback for message ${message.messageId}")
          ackCallback.invoke(message)
          log("MessagesRouter", "ACK callback completed for message ${message.messageId}")
        } else {
          log("MessagesRouter", "No ACK callback available for message ${message.messageId}")
        }
        return@ioDispatcher
      }

      // Skip ACK generation for ACK messages to prevent loops
      val isAckMessage = message.type == MessageType.ACK_READY || message.type == MessageType.ACK_RECEIVED
      
      val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

      if (message.hasPayload) {
        // Send ACK_READY for payload messages before processing
        if (!isAckMessage) {
          val ackReady = MessageAcknowledgment(AckType.READY, message.id)
          writeChannel.sendMessage(ackReady, messageSerializer)
          log("MessagesRouter", "Sent ACK_READY for message ${message.id} to $fromDeviceId")
        }

        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@ioDispatcher
        }

        messageHandler.handleIncoming(message, readChannel, receiveFlow)
      } else {
        // For messages without payload, process them through handler if available, otherwise directly
        val messageHandler = handlers[message.type]
        if (messageHandler != null) {
          messageHandler.handleIncoming(message, readChannel, receiveFlow)
        } else {
          receiveFlow.update {
            it.copy(
              messages = listOf(message),
              status = ReceiveMessageStatus.Completed
            )
          }
        }
      }

      // Send ACK_RECEIVED after successful processing (for all non-ACK messages)
      if (!isAckMessage) {
        val ackReceived = MessageAcknowledgment(AckType.RECEIVED, message.id)
        log("MessagesRouter", "About to send ACK_RECEIVED for message ${message.id} to $fromDeviceId")
        writeChannel.sendMessage(ackReceived, messageSerializer)
        log("MessagesRouter", "Successfully sent ACK_RECEIVED for message ${message.id} to $fromDeviceId")
      }
    }

  override suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>
  ) {
    coroutines.ioDispatcher {
      val message = sendMessageRequest.message

      if (message.hasPayload) {
        // message has extra payload. we need to handle it

        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@ioDispatcher
        }

        messageHandler.handleOutgoing(sendMessageRequest, writeChannel, progress)
      } else {
        // message has no payload. we can send it directly
        writeChannel.sendMessage(message, messageSerializer)
      }

    }
  }

}
