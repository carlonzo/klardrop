package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.EnvelopeHandler
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.withContext

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val incomingMessagesRouter: IncomingMessagesRouter
) {

  init {

    if (connection.session.isClosed()) {
      throw IllegalStateException("Socket with ${connection.deviceId} is closed. Reason: ${connection.session.closeReason.getCompleted()}")
    }

  }

  //  activates read from socket
  @OptIn(DelicateCoroutinesApi::class)
  suspend fun acceptIncomingMessages() {
    withContext(coroutines.ioDispatcher) {
      while (!connection.session.incoming.isClosedForReceive) {

        incomingMessagesRouter.onMessageIncoming(connection.deviceId, connection.session.incoming)
      }

      log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
    }

  }

  private val outgoing = connection.session.outgoing

  suspend fun <M: Message, S : SendMessageRequest> send(sendRequest: S, envelopeHandler: EnvelopeHandler<M, S>) {
    envelopeHandler.handleOutgoing(sendRequest, outgoing)
  }

  suspend fun close() {
    log("ConnectionMessenger: Closing connection with ${connection.deviceId}")
    connection.session.close()
  }
}
