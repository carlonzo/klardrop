package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter
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

        log("ConnectionMessenger: Listening for new messages from ${connection.deviceId}")

        // suspension for messages in within the messagesRouter
        runCatching {
          messagesRouter.onMessageIncoming(connection.deviceId, outgoing, incoming)
        }.onFailure {
          log("ConnectionMessenger: Error while listening for messages from ${connection.deviceId}. Closing connection", it)
          close()
        }

      }

      log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
    }

  }

  private val outgoing = connection.session.outgoing
  private val incoming = connection.session.incoming

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    messagesRouter.onSendingMessage(connection.deviceId, sendRequest, connection.session, flow)
  }

  suspend fun close() {
    log("ConnectionMessenger: Closing connection with ${connection.deviceId}")
    connection.session.close()
  }

  fun isClosed(): Boolean {
    return connection.session.isClosed()
  }
}
