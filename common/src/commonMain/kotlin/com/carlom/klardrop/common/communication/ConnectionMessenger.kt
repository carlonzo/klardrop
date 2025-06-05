package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter
) {

  private val readChannel = connection.socket.openReadChannel()
  private val writeChannel = connection.socket.openWriteChannel(autoFlush = true)

  init {
    if (connection.socket.isClosed) {
      throw IllegalStateException("Socket with ${connection.deviceId} is closed.")
    }
  }

  //  activates read from socket
  @OptIn(DelicateCoroutinesApi::class)
  suspend fun acceptIncomingMessages() {
    withContext(coroutines.ioDispatcher) {
      while (!readChannel.isClosedForRead) {

        log("ConnectionMessenger: Listening for new messages from ${connection.deviceId}")

        // suspension for messages in within the messagesRouter
        runCatching {
          messagesRouter.onMessageIncoming(connection.deviceId, writeChannel, readChannel)
        }.onFailure {
          log("ConnectionMessenger: Error while listening for messages from ${connection.deviceId}. Closing connection", it)
          close()
        }

      }

      log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
    }

  }

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)
  }

  suspend fun close() {
    log("ConnectionMessenger: Closing connection with ${connection.deviceId}")
    connection.socket.close()
  }

  fun isClosed(): Boolean {
    return connection.socket.isClosed
  }
}
