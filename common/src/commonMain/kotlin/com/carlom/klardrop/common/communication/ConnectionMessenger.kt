package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.isClosed
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.ExperimentalTime

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter,
  private val readChannel: ByteReadChannel,
  private val writeChannel: ByteWriteChannel,
  private val ackTimeoutMs: Long = ACK_TIMEOUT_MS
) {

  // ACK correlation system
  private data class PendingAck(val type: AckType, val channel: Channel<Unit>)

  private val pendingAcks = mutableMapOf<Int, PendingAck>()
  private val ackMutex = Mutex()

  companion object {
    private const val ACK_TIMEOUT_MS = 2_000L
  }

  init {
    if (connection.socket.isClosed) {
      throw IllegalStateException("Socket with ${connection.deviceId} is closed.")
    }
  }

  //  activates read from socket
  suspend fun acceptIncomingMessages() = coroutines.ioDispatcher {
    while (!readChannel.isClosedForRead) {
      log("ConnectionMessenger: Listening for new messages from ${connection.deviceId}")

      runCatching {
        // Use the existing router but register ourselves for ACK handling
        messagesRouter.onMessageIncoming(connection.deviceId, writeChannel, readChannel) { ack ->
          log("ConnectionMessenger: Received ACK callback for message ${ack.messageId}, ackType: ${ack.ackType}")
          handleAckMessage(ack)
        }
      }.onFailure {
        log("ConnectionMessenger: Error while listening for messages from ${connection.deviceId}. Closing connection.", it)
        close()
      }
    }

    log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
  }


  // Public method for ACK message handling - called by MessagesRouter or AckMessageHandler
  suspend fun handleAckMessage(ack: MessageAcknowledgment) {
    log("ConnectionMessenger: Received ACK ${ack.ackType} for message ${ack.messageId} from ${connection.deviceId}")

    ackMutex.withLock {
      val pendingAck = pendingAcks[ack.messageId]
      if (pendingAck != null && pendingAck.type == ack.ackType) {
        // Signal the waiting sender
        val sendResult = pendingAck.channel.trySend(Unit)
        if (sendResult.isSuccess) {
          pendingAcks.remove(ack.messageId)
          log("ConnectionMessenger: Successfully signaled ACK ${ack.ackType} for message ${ack.messageId}")
        } else {
          log("ConnectionMessenger: Failed to signal ACK ${ack.ackType} for message ${ack.messageId}: ${sendResult.exceptionOrNull()}")
        }
      } else {
        log("ConnectionMessenger: Unexpected ACK ${ack.ackType} for message ${ack.messageId} - no matching pending request")
      }
    }
  }

  @OptIn(ExperimentalTime::class)
  private suspend fun waitForAck(messageId: Int, ackType: AckType) {
    val channel = Channel<Unit>(capacity = 1)

    ackMutex.withLock {
      pendingAcks[messageId] = PendingAck(ackType, channel)
    }

    val timeoutMs = ackTimeoutMs
    try {
      withContext(coroutines.mainDispatcher) {
        withTimeout(timeoutMs) {
          channel.receive()
        }
      }
    } catch (e: Exception) {
      // Cleanup pending ACK on timeout or error
      ackMutex.withLock {
        pendingAcks.remove(messageId)
      }
      channel.close()
      throw IllegalStateException("ACK timeout: Expected $ackType for message $messageId from ${connection.deviceId}")
    }
  }

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    val message = sendRequest.message

    if (isClosed()) {
      flow.emit(MessengerSendProgress.Error("Connection is closed"))
      return
    }

    runCatching {
      coroutines.ioDispatcher {

        if (message.hasPayload) {
          // For payload messages (FILE): Send metadata → Wait for ACK_READY → Send payload → Wait for ACK_RECEIVED

          // Send the message metadata through the router (this includes the payload sending)
          messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)

          // Wait for ACK_RECEIVED since the message handler manages the payload flow internally
          waitForAck(message.id, AckType.RECEIVED)

        } else {
          // For no-payload messages (TEXT): Send message → Wait for ACK_RECEIVED

          // Send the message through the router
          messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)

          // Wait for ACK_RECEIVED
          waitForAck(message.id, AckType.RECEIVED)
        }

        flow.emit(MessengerSendProgress.Completed)
      }
    }.onFailure { exception: Throwable ->
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
    // Check if socket is explicitly closed
    if (connection.socket.isClosed) {
      return true
    }

    // Check if read/write channels are closed (indicates remote closure)
    if (readChannel.isClosedForRead || writeChannel.isClosedForWrite) {
      runCatching { connection.socket.close() }
        .onFailure { log("Failed closing the socket", it) }
      return true
    }

    return false
  }
}
