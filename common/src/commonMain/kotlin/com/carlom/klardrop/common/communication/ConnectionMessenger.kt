package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.ExperimentalTime

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter,
  private val readChannel: ByteReadChannel,
  private val writeChannel: ByteWriteChannel,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT
) {

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
      log("ConnectionMessenger: [DEBUG] Added pending ACK $ackType for message $messageId, total pending: ${pendingAcks.size}")
    }
    
    val timeout = when (ackType) {
      AckType.READY -> ackTimeoutConfig.readyAckTimeout
      AckType.RECEIVED -> ackTimeoutConfig.noPayloadAckTimeout
    }
    val timeoutMs = timeout.inWholeMilliseconds
    
    log("ConnectionMessenger: [DEBUG] Starting to wait for ACK $ackType for message $messageId from ${connection.deviceId}, timeout=${timeoutMs}ms")
    val startTime = kotlin.time.Clock.System.now()
    
    try {
      withTimeout(timeoutMs) {
        log("ConnectionMessenger: [DEBUG] Entering channel.receive() for ACK $ackType message $messageId")
        channel.receive()
        val elapsed = kotlin.time.Clock.System.now() - startTime
        log("ConnectionMessenger: [DEBUG] Successfully received ACK $ackType for message $messageId after ${elapsed.inWholeMilliseconds}ms")
      }
    } catch (e: Exception) {
      val elapsed = kotlin.time.Clock.System.now() - startTime
      log("ConnectionMessenger: [DEBUG] ACK timeout or error for $ackType message $messageId after ${elapsed.inWholeMilliseconds}ms (configured timeout: ${timeoutMs}ms): ${e::class.simpleName}: ${e.message}")
      log("ConnectionMessenger: [DEBUG] Connection state during timeout: isClosed=${isClosed()}, socket.isClosed=${connection.socket.isClosed}")
      
      // Cleanup pending ACK on timeout or error
      ackMutex.withLock {
        val removed = pendingAcks.remove(messageId)
        log("ConnectionMessenger: [DEBUG] Cleaned up pending ACK for message $messageId, was present: $removed, remaining pending: ${pendingAcks.size}")
      }
      channel.close()
      throw IllegalStateException("ACK timeout: Expected $ackType for message $messageId from ${connection.deviceId}")
    }
  }

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    val message = sendRequest.message
    log("ConnectionMessenger: [DEBUG] Starting send for message ${message.id} to ${connection.deviceId}, isClosed=${isClosed()}")
    
    if (isClosed()) {
      log("ConnectionMessenger: [DEBUG] Attempted to send message on closed connection to ${connection.deviceId}")
      flow.emit(MessengerSendProgress.Error("Connection is closed"))
      return
    }

    runCatching {
      coroutines.ioDispatcher {
        
        if (message.hasPayload) {
          // For payload messages (FILE): Send metadata → Wait for ACK_READY → Send payload → Wait for ACK_RECEIVED
          log("ConnectionMessenger: [DEBUG] Sending payload message ${message.id} to ${connection.deviceId}")
          
          // Send the message metadata through the router (this includes the payload sending)
          log("ConnectionMessenger: [DEBUG] Calling messagesRouter.onSendingMessage for payload message ${message.id}")
          messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)
          
          // Wait for ACK_RECEIVED since the message handler manages the payload flow internally
          log("ConnectionMessenger: [DEBUG] About to wait for ACK_RECEIVED for payload message ${message.id}, connection.isClosed=${isClosed()}")
          waitForAck(message.id, AckType.RECEIVED)
          log("ConnectionMessenger: [DEBUG] Received ACK_RECEIVED, payload message ${message.id} completed")
          
        } else {
          // For no-payload messages (TEXT): Send message → Wait for ACK_RECEIVED
          log("ConnectionMessenger: [DEBUG] Sending no-payload message ${message.id} to ${connection.deviceId}")
          
          // Send the message through the router
          log("ConnectionMessenger: [DEBUG] Calling messagesRouter.onSendingMessage for no-payload message ${message.id}")
          messagesRouter.onSendingMessage(connection.deviceId, sendRequest, writeChannel, readChannel, flow)
          log("ConnectionMessenger: [DEBUG] Completed messagesRouter.onSendingMessage for message ${message.id}")
          
          // Wait for ACK_RECEIVED
          log("ConnectionMessenger: [DEBUG] About to wait for ACK_RECEIVED for no-payload message ${message.id}, connection.isClosed=${isClosed()}")
          waitForAck(message.id, AckType.RECEIVED)
          log("ConnectionMessenger: [DEBUG] Successfully received ACK_RECEIVED for message ${message.id}")
        }
        
        log("ConnectionMessenger: [DEBUG] About to emit Completed for message ${message.id}")
        flow.emit(MessengerSendProgress.Completed)
        log("ConnectionMessenger: [DEBUG] Successfully completed send for message ${message.id}")
      }
    }.onFailure { exception: Throwable ->
      log("ConnectionMessenger: [DEBUG] Error sending message ${message.id} to ${connection.deviceId}: ${exception::class.simpleName}: ${exception.message}")
      log("ConnectionMessenger: [DEBUG] Full exception details", exception)
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
      log("ConnectionMessenger: [DEBUG] Detected remote closure for ${connection.deviceId} - readClosed=${readChannel.isClosedForRead}, writeClosed=${writeChannel.isClosedForWrite}")
      runCatching { connection.socket.close() }
        .onFailure { log("Failed closing the socket", it) }
      return true
    }
    
    return false
  }
}
