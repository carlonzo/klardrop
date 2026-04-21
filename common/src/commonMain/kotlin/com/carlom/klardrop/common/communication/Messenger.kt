package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.MessengerSendProgress.Pending
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.TrustChecker
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

/**
 * Messenger used to send messages
 */
interface Messenger {
  fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress>

  fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>>
}

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  private val coroutines: Coroutines,
  private val nearbyClient: Lazy<NearbyClient>,
  private val messageReceiver: MessageReceiver,
  private val trustChecker: Lazy<TrustChecker>,
  private val trustManager: com.carlom.klardrop.common.trust.TrustManager,
  private val messageSerializer: MessageSerializer,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
) : Messenger {

  private val messengerScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {
    log(
      "Messenger",
      "send() called: deviceId=$deviceId, messageType=${messageRequest.message.type}, messageId=${messageRequest.message.id}"
    )

    val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    messengerScope.launch {

      flow.emit(Pending)
      log("Messenger", "Emitted Pending status for $deviceId")

      val device = visibleDevices.getDevice(deviceId)

      //    skip if not visible
      if (device == null) {
        log("Messenger", "❌ Device $deviceId is not visible in device list")
        log("Messenger", "Wanted to send a message to $deviceId but it is not visible")
        flow.emit(Error("$deviceId it is not visible"))
        return@launch
      }

      log("Messenger", "✅ Device $deviceId found in visible devices")

      // Check if device is trusted and wrap message in TrustedMessage if needed
      val finalMessageRequest = try {
        val message = messageRequest.message
        val isPairingMessage = message is TrustPairingRequest || message is TrustPairingResponse

        if (!isPairingMessage && trustChecker.value.isTrusted(deviceId)) {
          log("Messenger", "Device $deviceId is trusted, creating TrustedMessage")

          // Serialize the original message
          val messageBytes = messageSerializer.serialize(message)

          // Sign the message using TrustManager
          val trustedMessage = trustManager.signMessage(messageBytes)

          if (trustedMessage != null) {
            log("Messenger", "Successfully created TrustedMessage for device $deviceId")
            // Create a new request with the TrustedMessage
            trustedMessage.toSimpleSendRequest()
          } else {
            log("Messenger", "Failed to create TrustedMessage for device $deviceId, sending unsigned")
            messageRequest
          }
        } else {
          if (isPairingMessage) {
            log("Messenger", "Device $deviceId: Pairing message detected, sending unsigned for protocol handshake")
          } else {
            log("Messenger", "Device $deviceId is not trusted, sending unsigned request")
          }
          messageRequest
        }
      } catch (e: Exception) {
        log("Messenger", "Error creating TrustedMessage for $deviceId: ${e.message}", e)
        messageRequest // fallback to original message
      }

      val transferCompleted = if (device.hasKlardropConnection()) {
        handleKlardropTransfer(deviceId, finalMessageRequest, flow)
      } else if (device.hasNearbyConnection()) {
        handleNearbyTransfer(deviceId, finalMessageRequest, flow)
      } else {
        log("Messenger", "Wanted to send a message to $deviceId but it has no connection")
        flow.emit(Error("$deviceId but it has no connection"))
        return@launch
      }

      if (transferCompleted)
        flow.emit(Completed)
    }

    return flow
  }

  override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> { // Changed
    return messageReceiver.notifier
  }

  private suspend fun handleNearbyTransfer(
    deviceId: String,
    messageRequest: SendMessageRequest,
    sendFlow: MutableSharedFlow<MessengerSendProgress>
  ): Boolean {

    val device = visibleDevices.getDevice(deviceId) ?: throw IllegalStateException("Device $deviceId is not found!")
    val nearbyConnections = device.getNearbyConnection()

    require(nearbyConnections.isNotEmpty()) { "Device $deviceId has no nearby connections" }

    nearbyConnections.first {
      log("Messenger", "Client sending message to $deviceId: ${it.address} ${it.port}")

      runCatching {
        nearbyClient.value.send(it.address, it.port, listOf(messageRequest), sendFlow)
      }.onFailure { exception ->
        log("Messenger", "Error sending message to $deviceId", exception)
      }.isSuccess

    }

    return true
  }

  private suspend fun handleKlardropTransfer(
    deviceId: String,
    messageRequest: SendMessageRequest,
    flow: MutableSharedFlow<MessengerSendProgress>
  ): Boolean {
    val config = ackTimeoutConfig
    val maxRetries = config.maxRetries
    var attempt = 0

    log(
      "Messenger",
      "[DEBUG] Starting handleKlardropTransfer for $deviceId, message: ${messageRequest.message.id}, maxRetries: $maxRetries"
    )

    while (attempt <= maxRetries) {
      attempt++
      log("Messenger", "[DEBUG] Attempt $attempt/$maxRetries for $deviceId, message: ${messageRequest.message.id}")

      val result = runCatching {
        // Get or establish connection
        log("Messenger", "[DEBUG] Getting or establishing connection to $deviceId (attempt $attempt)")
        val connectionMessenger = getOrEstablishConnection(deviceId)

        if (connectionMessenger == null) {
          log("Messenger", "[DEBUG] Failed to establish connection to $deviceId (attempt $attempt)")
          if (attempt <= maxRetries) {
            // Don't emit error yet, we'll retry
            return@runCatching false
          } else {
            log("Messenger", "[DEBUG] All connection attempts exhausted for $deviceId")
            flow.emit(Error("Failed to establish connection after $maxRetries attempts"))
            return false
          }
        }

        log(
          "Messenger",
          "[DEBUG] Successfully got connection to $deviceId, sending message: ${messageRequest.message.id} (attempt $attempt)"
        )

        // Send the message - this will emit progress updates to the flow and wait for ACKs
        connectionMessenger.send(messageRequest, flow)
        log("Messenger", "[DEBUG] Successfully sent message ${messageRequest.message.id} to $deviceId (attempt $attempt)")
        true
      }.getOrElse { exception ->
        log(
          "Messenger",
          "[DEBUG] Error in Klardrop transfer to $deviceId (attempt $attempt): ${exception::class.simpleName}: ${exception.message}"
        )
        log("Messenger", "[DEBUG] Full exception for attempt $attempt", exception)

        // Check if this is an ACK timeout (connection lost)
        val exceptionMessage = exception.message ?: ""
        val isAckTimeout = exceptionMessage.contains("ACK timeout")
        log("Messenger", "[DEBUG] Is ACK timeout: $isAckTimeout, exception message: '$exceptionMessage'")

        if (isAckTimeout && attempt <= maxRetries) {
          log("Messenger", "[DEBUG] ACK timeout detected, will retry connection to $deviceId (attempt $attempt)")
          // Force cleanup of the connection
          connectionsPool.closeConnection(deviceId)
          log("Messenger", "[DEBUG] Closed connection to $deviceId, starting backoff delay")

          // Wait before retry with exponential backoff
          val delay = (1.seconds * config.retryBackoffMultiplier.pow(attempt - 1))
          log("Messenger", "[DEBUG] Waiting ${delay.inWholeMilliseconds}ms before retry (attempt $attempt)")
          withContext(coroutines.mainDispatcher) {
            kotlinx.coroutines.delay(delay)
          }

          return@getOrElse false // Signal to retry
        } else {
          // Final failure or non-timeout error
          log("Messenger", "[DEBUG] Final failure for $deviceId: not ACK timeout or max retries exceeded")
          val errorMessage = exception.message ?: "Unknown connection error"
          flow.emit(Error("Transfer failed: $errorMessage"))
          return false
        }
      }

      if (result) {
        // Success
        log("Messenger", "[DEBUG] Successfully completed transfer to $deviceId (attempt $attempt)")
        return true
      }

      log("Messenger", "[DEBUG] Attempt $attempt failed, will retry if attempts remaining")
      // If we get here, it was a retryable failure and we should try again
    }

    // All retries exhausted
    log("Messenger", "[DEBUG] All retries exhausted for $deviceId after $maxRetries attempts")
    flow.emit(Error("Transfer failed after $maxRetries retry attempts"))
    return false
  }


  private suspend fun getOrEstablishConnection(deviceId: String): ConnectionMessenger? {
    log("Messenger", "[DEBUG] getOrEstablishConnection() called for $deviceId")

    // First, check if we have a valid existing connection
    val existingConnection = connectionsPool.getConnection(deviceId)
    if (existingConnection != null) {
      val isConnectionClosed = existingConnection.isClosed()
      log("Messenger", "[DEBUG] Found existing connection for $deviceId, isClosed=$isConnectionClosed")

      if (!isConnectionClosed) {
        log("Messenger", "[DEBUG] Using existing active connection for $deviceId")
        return existingConnection
      } else {
        log("Messenger", "[DEBUG] Existing connection is closed, removing and establishing new one for $deviceId")
        connectionsPool.closeConnection(deviceId)
      }
    } else {
      log("Messenger", "[DEBUG] No existing connection found for $deviceId")
    }

    // Establish a new connection
    log("Messenger", "Establishing new connection for $deviceId")
    val connectResult = runCatching {
      client.connectTo(deviceId)
    }

    if (connectResult.isFailure) {
      val exception = connectResult.exceptionOrNull()
      log("Messenger", "Failed to connect to $deviceId: ${exception?.message}")
      return null
    }

    // Verify the connection was established
    log("Messenger", "[DEBUG] Verifying new connection for $deviceId")
    val newConnection = connectionsPool.getConnection(deviceId)
    if (newConnection == null) {
      log("Messenger", "[DEBUG] Failed to establish connection for $deviceId - connection not found in pool")
      return null
    } else if (newConnection.isClosed()) {
      log("Messenger", "[DEBUG] Failed to establish connection for $deviceId - connection is closed")
      return null
    }

    log("Messenger", "[DEBUG] Successfully established new connection for $deviceId")
    return newConnection
  }

}

fun Flow<MessengerSendProgress>.untilCompleted(): Flow<MessengerSendProgress> {
  return this
    // send a closed after completed or error. this is to close the collection
    .transformWhile {
      emit(it)
      !it.isCompleted()
    }
}

sealed interface MessengerSendProgress {
  data object Pending : MessengerSendProgress
  data class InProgress(val percentage: Int) : MessengerSendProgress
  data object Completed : MessengerSendProgress
  data class Error(val message: String = "") : MessengerSendProgress

  fun isCompleted(): Boolean = this is Completed || this is Error
}

