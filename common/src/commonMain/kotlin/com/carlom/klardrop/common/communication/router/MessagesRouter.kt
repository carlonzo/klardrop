package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.SendMessageRequest
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
      // Read message length first (4 bytes)
      val lengthBytes = ByteArray(4)
      readChannel.readFully(lengthBytes)
      val messageLength = (lengthBytes[0].toInt() and 0xFF shl 24) or
                        (lengthBytes[1].toInt() and 0xFF shl 16) or 
                        (lengthBytes[2].toInt() and 0xFF shl 8) or
                        (lengthBytes[3].toInt() and 0xFF)

      // Read the actual message
      val messageBytes = ByteArray(messageLength)
      readChannel.readFully(messageBytes)
      
      val message = messageSerializer.deserialize(messageBytes)
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

        messageHandler.handleOutgoing(sendMessageRequest, writeChannel, progress)
      } else {
        // message has no payload. we can send it directly
        val messageBytes = messageSerializer.serialize(message)
        
        // Send message with length prefix
        val lengthBytes = ByteArray(4)
        lengthBytes[0] = (messageBytes.size shr 24).toByte()
        lengthBytes[1] = (messageBytes.size shr 16).toByte()
        lengthBytes[2] = (messageBytes.size shr 8).toByte()
        lengthBytes[3] = messageBytes.size.toByte()
        
        writeChannel.writeFully(lengthBytes)
        writeChannel.writeFully(messageBytes)
      }

    }
  }

}
