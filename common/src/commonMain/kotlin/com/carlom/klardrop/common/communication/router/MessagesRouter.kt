package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
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
  private val messageRepository: MessageRepository // Added
) : MessagesRouter {
  override suspend fun onMessageIncoming(fromDeviceId: String, writeChannel: ByteWriteChannel, readChannel: ByteReadChannel) =
    coroutines.ioDispatcher {

      val message =  readChannel.readMessage(messageSerializer)
      log("MessagesRouter", "Received message from $fromDeviceId: $message")

      val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

      if (message.hasPayload) {
        // message has extra payload. we need to handle it
        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@ioDispatcher
        }
        messageHandler.handleIncoming(message, readChannel, receiveFlow)
      } else {
        // No payload, likely a text message
        if (message is TextMessage) {
            messageRepository.insertMessage(
                remoteDeviceId = fromDeviceId,
                content = message.text, // Assuming TextMessage has a 'text' field
                isSender = false,
                messageType = PersistenceMessageType.TEXT
            )
        }
        receiveFlow.update {
          it.copy(
            messages = listOf(message),
            status = ReceiveMessageStatus.Completed
          )
        }
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
        messageHandler.handleOutgoing(toDeviceId, sendMessageRequest, writeChannel, progress) // Passed toDeviceId
      } else {
        // No payload, likely a text message
        if (message is TextMessage) {
            messageRepository.insertMessage(
                remoteDeviceId = toDeviceId,
                content = message.text, // Assuming TextMessage has a 'text' field
                isSender = true,
                messageType = PersistenceMessageType.TEXT
            )
        }
        // message has no payload. we can send it directly
        writeChannel.sendMessage(message, messageSerializer)
      }
    }
  }
}
