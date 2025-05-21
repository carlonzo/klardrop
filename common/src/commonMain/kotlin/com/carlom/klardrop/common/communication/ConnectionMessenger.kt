package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.errors.IOException // For more specific IO error handling
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive // To check if the coroutine scope is active
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.ClosedReceiveChannelException // To handle stream closures

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter
) {

  init {
    if (connection.isClosed()) {
      // TCP Sockets don't have a 'closeReason' object like WebSockets.
      // Log a generic message or handle the closed state as appropriate.
      throw IllegalStateException("Socket with ${connection.deviceId} at ${connection.remoteAddress} is already closed.")
    }
  }

  //  activates read from socket
  @OptIn(DelicateCoroutinesApi::class)
  suspend fun acceptIncomingMessages() {
    // Use coroutines.ioScope directly if this messenger is tied to that scope,
    // or ensure the passed coroutines.ioDispatcher is appropriate for long-running IO.
    withContext(coroutines.ioDispatcher) {
      // Loop while the coroutine scope is active and the connection itself isn't marked as closed.
      while (coroutines.ioScope.isActive && !connection.isClosed()) {
        log("ConnectionMessenger: Listening for new messages from ${connection.deviceId} on ${connection.remoteAddress}")

        try {
          // The actual reading and processing of messages is delegated to messagesRouter.
          // This router will now use connection.input (ByteReadChannel).
          messagesRouter.onMessageIncoming(connection.deviceId, connection.output, connection.input)
        } catch (e: ClosedReceiveChannelException) {
          log("ConnectionMessenger: Input channel closed by peer ${connection.deviceId} at ${connection.remoteAddress}.", e)
          close() // Ensure connection is marked as closed and resources are released.
          break // Exit loop as channel is closed.
        } catch (e: IOException) {
          log("ConnectionMessenger: IO Error while listening for messages from ${connection.deviceId} at ${connection.remoteAddress}. Closing connection.", e)
          close()
          break // Exit loop on IO errors.
        } catch (e: Exception) {
          log("ConnectionMessenger: Generic error while listening for messages from ${connection.deviceId} at ${connection.remoteAddress}. Closing connection.", e)
          close()
          break // Exit loop on other critical errors.
        }
        // If onMessageIncoming returns normally, it means it has processed one "message unit"
        // or has its own internal loop that was broken. This outer loop here is mainly to
        // re-check connection state and handle critical errors that might bring down the processing.
      }
      log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId} on ${connection.remoteAddress}. Connection closed: ${connection.isClosed()}")
    }
  }

  // No longer need these as properties, access directly via connection.output and connection.input
  // private val outgoing = connection.output
  // private val incoming = connection.input

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    if (isClosed()) {
      log("ConnectionMessenger: Attempted to send message to ${connection.deviceId} but connection is closed.")
      // Optionally throw an exception or notify the flow of the error
      // flow.tryEmit(MessengerSendProgress.Error(IOException("Connection closed")))
      return
    }
    // messagesRouter will now use connection.output (ByteWriteChannel)
    messagesRouter.onSendingMessage(connection.deviceId, sendRequest, connection.output, flow)
  }

  suspend fun close() {
    log("ConnectionMessenger: Closing connection with ${connection.deviceId} at ${connection.remoteAddress}")
    try {
      // Closing the socket should also close its read and write channels.
      connection.socket.close()
    } catch (e: Exception) {
      log("ConnectionMessenger: Error while closing socket for ${connection.deviceId}: ${e.message}", e)
    }
    log("ConnectionMessenger: Connection with ${connection.deviceId} at ${connection.remoteAddress} is now closed: ${connection.isClosed()}")
  }

  fun isClosed(): Boolean {
    return connection.isClosed()
  }
}
