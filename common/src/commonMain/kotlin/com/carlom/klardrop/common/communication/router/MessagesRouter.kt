package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.receiver.TransferReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke

interface MessagesRouter {
  suspend fun onMessageIncoming(fromDeviceId: String, sendChannel: SendChannel<Frame>, receiveChannel: ReceiveChannel<Frame>)
  suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    webSocketSession: WebSocketSession,
    progress: MutableSharedFlow<MessengerSendProgress>
  )
}

class MessagesRouterImpl(
  private val handlers: MessageHandlers,
  private val messageSerializer: MessageSerializer,
  private val coroutines: Coroutines,
  private val messengeReceiver: TransferReceiver,
) : MessagesRouter {
  override suspend fun onMessageIncoming(fromDeviceId: String, sendChannel: SendChannel<Frame>, receiveChannel: ReceiveChannel<Frame>) =
    coroutines.ioDispatcher {
      val firstFrame = receiveChannel.receive()

      val message = messageSerializer.deserialize(firstFrame)
      log("MessagesRouter", "Received message from $fromDeviceId: $message")

      val receiveFlow = messengeReceiver.onReceiveTransfer(fromDeviceId)


      if (message.hasPayload) {
        // message has extra payload. we need to handle it

        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@ioDispatcher
        }

        messageHandler.handleIncoming(message, receiveChannel, receiveFlow)
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
    webSocketSession: WebSocketSession,
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


        messageHandler.handleOutgoing(sendMessageRequest, webSocketSession, progress)
      } else {
        // message has no payload. we can send it directly
        webSocketSession.send(messageSerializer.serialize(message))
      }

    }
  }

}
