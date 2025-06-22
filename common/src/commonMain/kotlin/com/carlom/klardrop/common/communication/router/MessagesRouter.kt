package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageHandlers
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
  suspend fun onMessageIncoming(fromDeviceId: String, writeChannel: ByteWriteChannel, readChannel: ByteReadChannel)
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
  override suspend fun onMessageIncoming(fromDeviceId: String, writeChannel: ByteWriteChannel, readChannel: ByteReadChannel) =
    coroutines.ioDispatcher {

      val message = readChannel.readMessage(messageSerializer)
      log("MessagesRouter", "Received message from $fromDeviceId: $message")

      // Skip ACK processing for ACK messages themselves to avoid infinite loops
      if (message is MessageAcknowledgment) {
        // ACKs are handled by the sender waiting for them, not through normal receive flow
        log("MessagesRouter", "Received ACK ${message.ackType} for message ${message.messageId}")
        return@ioDispatcher
      }

      val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

      if (message.hasPayload) {
        // Send ACK_READY first for payload messages
        val readyAck = MessageAcknowledgment(AckType.READY, message.id)
        writeChannel.sendMessage(readyAck, messageSerializer)
        log("MessagesRouter", "Sent ACK_READY for message ${message.id}")

        // Process the message with payload
        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@ioDispatcher
        }

        messageHandler.handleIncoming(message, readChannel, receiveFlow)

        // Send ACK_RECEIVED after processing payload
        val receivedAck = MessageAcknowledgment(AckType.RECEIVED, message.id)
        writeChannel.sendMessage(receivedAck, messageSerializer)
        log("MessagesRouter", "Sent ACK_RECEIVED for message ${message.id}")
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

        // Send ACK_RECEIVED for no-payload messages
        val receivedAck = MessageAcknowledgment(AckType.RECEIVED, message.id)
        writeChannel.sendMessage(receivedAck, messageSerializer)
        log("MessagesRouter", "Sent ACK_RECEIVED for no-payload message ${message.id}")
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
