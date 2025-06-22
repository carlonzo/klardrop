package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter,
  private val readChannel: ByteReadChannel,
  private val writeChannel: ByteWriteChannel
) {

  init {
    if (connection.socket.isClosed) {
      throw IllegalStateException("Socket with ${connection.deviceId} is closed.")
    }
  }

  //  activates read from socket
  suspend fun acceptIncomingMessages() = coroutines.ioDispatcher {
    while (!readChannel.isClosedForRead) {
      log("ConnectionMessenger: Listening for new messages from ${connection.deviceId}")

      // suspension for messages in within the messagesRouter
      runCatching {
        messagesRouter.onMessageIncoming(connection.deviceId, writeChannel, readChannel)
      }.onFailure {
        log("ConnectionMessenger: Error while listening for messages from ${connection.deviceId}. Closing connection.", it)
        close()
      }

    }

    log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
  }

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    if (isClosed()) {
      log("ConnectionMessenger: Attempted to send message on closed connection to ${connection.deviceId}")
      flow.emit(MessengerSendProgress.Error("Connection is closed"))
      return
    }

    runCatching {
      messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)
    }.onFailure { exception ->
      log("ConnectionMessenger: Error sending message to ${connection.deviceId}", exception)
      flow.emit(MessengerSendProgress.Error("Send failed: ${exception.message}"))
      close() // Close the connection on error
    }
  }

  fun close() = runCatching {
    if (!connection.socket.isClosed) {
      log("ConnectionMessenger: Closing connection with ${connection.deviceId}")
      connection.socket.close()
    }
  }

  fun isClosed(): Boolean {
    return connection.socket.isClosed
  }
}
