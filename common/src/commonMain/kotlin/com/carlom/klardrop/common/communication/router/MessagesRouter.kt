package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.AckDelegate
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckMessage
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
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
  private val ackDelegate: AckDelegate // New dependency
) : MessagesRouter {
  override suspend fun onMessageIncoming(fromDeviceId: String, writeChannel: ByteWriteChannel, readChannel: ByteReadChannel) =
    coroutines.ioDispatcher {

      val incomingMessage =  readChannel.readMessage(messageSerializer)
      log("MessagesRouter", "Received message from $fromDeviceId: $incomingMessage")

      val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

      // --- ACK Logic Start ---
      var messageIdToAck: String? = null
      if (incomingMessage is TextMessage) {
          messageIdToAck = incomingMessage.messageId
      } else if (incomingMessage is FileMessage) {
          // For FileMessage, ACK should be sent by its handler after full reception.
          // We'll need to pass necessary things to the handler or use a callback.
          // TODO: Ensure FileMessageHandler sends an ACK for messageId: incomingMessage.messageId
      }
      // --- ACK Logic End ---

      if (incomingMessage.hasPayload) {
        // message has extra payload. we need to handle it

        val messageHandler = handlers[incomingMessage.type] ?: run {
          log("MessagesRouter", "No handler for message type ${incomingMessage.type}")
          // If no handler, and we have a messageIdToAck for a non-payload message (that somehow ended up here)
          // or a payload message that we've decided to ACK immediately (not FileMessage).
          if (messageIdToAck != null && incomingMessage !is FileMessage) {
              val ack = AckMessage(ackedMessageId = messageIdToAck)
              writeChannel.sendMessage(ack, messageSerializer)
              log("MessagesRouter", "Sent ACK for $messageIdToAck to $fromDeviceId for ${incomingMessage.type} (no handler)")
          }
          return@ioDispatcher
        }

        // Pass 'writeChannel' and 'messageSerializer' to messageHandler.handleIncoming
        // as it's now responsible for sending ACKs for payloaded messages.
        messageHandler.handleIncoming(incomingMessage, readChannel, receiveFlow, writeChannel, messageSerializer)
      } else {
        // Message has no payload
        if (incomingMessage is AckMessage) {
            log("MessagesRouter", "Received ACK for messageId: ${incomingMessage.ackedMessageId} from $fromDeviceId")
            ackDelegate.onAckReceived(incomingMessage.ackedMessageId, fromDeviceId)
            // TODO: Connect this to MessengerImpl to update the MessengerSendProgress flow for the original message.
        } else {
            // Original logic for other non-payloaded messages
            receiveFlow.update {
                it.copy(
                    messages = listOf(incomingMessage),
                    status = ReceiveMessageStatus.Completed
                )
            }
            // Send ACK for non-payloaded messages (like TextMessage if it's configured as such)
            // This check should be specific that incomingMessage is NOT an AckMessage itself.
            if (messageIdToAck != null && incomingMessage !is AckMessage) { // Ensure we don't try to ACK an ACK
                val ack = AckMessage(ackedMessageId = messageIdToAck)
                writeChannel.sendMessage(ack, messageSerializer)
                log("MessagesRouter", "Sent ACK for $messageIdToAck to $fromDeviceId for ${incomingMessage.type}")
            }
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
        writeChannel.sendMessage(message, messageSerializer)
      }

    }
  }

}
