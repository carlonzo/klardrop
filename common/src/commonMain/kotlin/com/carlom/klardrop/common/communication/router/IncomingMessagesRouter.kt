package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.EnvelopeSerializer
import com.carlom.klardrop.common.communication.message.EnvelopeHandlers
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface IncomingMessagesRouter {

  val onMessageReceived: Flow<Message>
  suspend fun onMessageIncoming(fromDeviceId: String, receiveChannel: ReceiveChannel<Frame>)
}

class IncomingMessagesRouterImpl(
  private val handlers: EnvelopeHandlers,
  private val envelopeSerializer: EnvelopeSerializer
) : IncomingMessagesRouter {

  private val messageReceivedSharedFlow = MutableSharedFlow<Message>()
  override val onMessageReceived: Flow<Message> = messageReceivedSharedFlow.asSharedFlow()

  override suspend fun onMessageIncoming(fromDeviceId: String, receiveChannel: ReceiveChannel<Frame>) {
    val firstFrame = receiveChannel.receive()
    val message = envelopeSerializer.deserialize(firstFrame)

    val messageHandler = handlers[message.type] ?: run {
      log("IncomingMessagesRouter", "No handler for message type ${message.type}")
      return
    }

    messageHandler.handleIncoming(message, receiveChannel)

    messageReceivedSharedFlow.emit(message)
  }

}
