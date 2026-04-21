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
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
) {

  // Test-only constructor that lets a test override every ACK timeout with a single value.
  internal constructor(
    coroutines: Coroutines,
    connection: Connection,
    messagesRouter: MessagesRouter,
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    ackTimeoutMs: Long,
  ) : this(
    coroutines = coroutines,
    connection = connection,
    messagesRouter = messagesRouter,
    readChannel = readChannel,
    writeChannel = writeChannel,
    ackTimeoutConfig = AckTimeoutConfig.uniform(ackTimeoutMs),
  )

  // ACK correlation system
  private data class PendingAck(val type: AckType, val channel: Channel<Unit>)

  private val pendingAcks = mutableMapOf<Int, PendingAck>()
  private val ackMutex = Mutex()

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
          log("ConnectionMessenger: Received ACK callback for message ${ack.id}, ackType: ${ack.ackType}")
          handleAckMessage(ack)
        }
      }.onFailure {
        log("ConnectionMessenger: Exception in acceptIncomingMessages loop for ${connection.deviceId}: ${it::class.simpleName}: ${it.message}")
        log("ConnectionMessenger: Error while listening for messages from ${connection.deviceId}. Closing connection.", it)
        close()
      }
    }

    log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
  }


  // Public method for ACK message handling - called by MessagesRouter or AckMessageHandler
  suspend fun handleAckMessage(ack: MessageAcknowledgment) {
    log("ConnectionMessenger: Received ACK ${ack.ackType} for message ${ack.id} from ${connection.deviceId}")

    ackMutex.withLock {
      val pendingAck = pendingAcks[ack.id]
      if (pendingAck != null && pendingAck.type == ack.ackType) {
        // Signal the waiting sender
        val sendResult = pendingAck.channel.trySend(Unit)
        if (sendResult.isSuccess) {
          pendingAcks.remove(ack.id)
          log("ConnectionMessenger: Successfully signaled ACK ${ack.ackType} for message ${ack.id}")
        } else {
          log("ConnectionMessenger: Failed to signal ACK ${ack.ackType} for message ${ack.id}: ${sendResult.exceptionOrNull()}")
        }
      } else {
        log("ConnectionMessenger: Unexpected ACK ${ack.ackType} for message ${ack.id} - no matching pending request")
      }
    }
  }

  /**
   * Registers a pending ACK request BEFORE sending the message.
   * This prevents race conditions where ACK arrives before registration.
   */
  private suspend fun registerPendingAck(messageId: Int, ackType: AckType): Channel<Unit> {
    val channel = Channel<Unit>(capacity = 1)
    log("ConnectionMessenger: [DEBUG] Registering pending ACK $ackType for message $messageId to ${connection.deviceId}")
    
    ackMutex.withLock {
      pendingAcks[messageId] = PendingAck(ackType, channel)
    }
    
    return channel
  }

  /**
   * Waits for a previously registered ACK to arrive.
   */
  @OptIn(ExperimentalTime::class)
  private suspend fun awaitRegisteredAck(
    messageId: Int,
    ackType: AckType,
    channel: Channel<Unit>,
    hasPayload: Boolean,
  ) {
    val timeoutMs = ackTimeoutConfig.timeoutFor(ackType, hasPayload).inWholeMilliseconds
    log("ConnectionMessenger: [DEBUG] Awaiting ACK $ackType for message $messageId from ${connection.deviceId} (timeout: ${timeoutMs}ms)")
    
    try {
      withContext(coroutines.mainDispatcher) {
        withTimeout(timeoutMs) {
          channel.receive()
        }
      }
      log("ConnectionMessenger: [DEBUG] Successfully received ACK $ackType for message $messageId from ${connection.deviceId}")
    } catch (e: Exception) {
      log("ConnectionMessenger: [DEBUG] ACK timeout for message $messageId, cleaning up pending request")
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
      throw IllegalStateException("Connection with ${connection.deviceId} is closed")
    }

    try {
      coroutines.ioDispatcher {
        // Register pending ACK BEFORE sending message to prevent race condition.
        // For both payload (FILE) and no-payload (TEXT) messages we currently only
        // await ACK_RECEIVED here; ACK_READY handshake for payload messages is owned
        // by the per-message handler and is wired separately.
        val ackChannel = registerPendingAck(message.id, AckType.RECEIVED)

        messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)

        awaitRegisteredAck(message.id, AckType.RECEIVED, ackChannel, hasPayload = message.hasPayload)
      }
    } catch (exception: Throwable) {
      log("ConnectionMessenger: Exception while sending message ${message.id} to ${connection.deviceId}", exception)
      // Close the socket so the next send forces a fresh connection and so the
      // pool's isClosed() check evicts this entry. Do NOT emit a terminal flow
      // event here - retry/terminal is owned by Messenger.handleKlardropTransfer.
      close()
      throw exception
    }
  }


  fun close() = runCatching {
    if (!connection.socket.isClosed) {
      log("ConnectionMessenger: [DEBUG] Explicitly closing connection with ${connection.deviceId}")
      connection.socket.close()
      log("ConnectionMessenger: [DEBUG] Socket closed for ${connection.deviceId}")
    } else {
      log("ConnectionMessenger: [DEBUG] close() called but socket already closed for ${connection.deviceId}")
    }
  }

  fun isClosed(): Boolean {
    // Check if socket is explicitly closed
    if (connection.socket.isClosed) {
      log("ConnectionMessenger: [DEBUG] isClosed() = true - socket is explicitly closed for ${connection.deviceId}")
      return true
    }

    // Check if read/write channels are closed (indicates remote closure)
    val readClosed = readChannel.isClosedForRead
    val writeClosed = writeChannel.isClosedForWrite
    
    log("ConnectionMessenger: [DEBUG] isClosed() check for ${connection.deviceId}: readClosed=$readClosed, writeClosed=$writeClosed")
    
    if (readClosed || writeClosed) {
      log("ConnectionMessenger: [DEBUG] Detected channel closure for ${connection.deviceId}, closing socket (readClosed=$readClosed, writeClosed=$writeClosed)")
      runCatching { connection.socket.close() }
        .onFailure { log("Failed closing the socket", it) }
      return true
    }

    log("ConnectionMessenger: [DEBUG] isClosed() = false for ${connection.deviceId}")
    return false
  }
}
