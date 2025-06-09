package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * Messenger used to send messages
 */
interface Messenger {
  fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress>

  fun receive(): Flow<Flow<ReceiveMessageUpdate>>
}

// TODO: Update DI (e.g. CommunicationModule.kt) because MessagesRouterImpl now needs AckDelegate, and MessengerImpl provides it.
class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  private val coroutines: Coroutines, // Made it a val to use for messengerScope
  private val nearbyClient: NearbyClient,
  private val messageReceiver: MessageReceiver
) : Messenger, AckDelegate { // Added AckDelegate

  private val messengerScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  // TODO: Consider using a ConcurrentHashMap if available and thread safety becomes a concern.
  private val pendingAckMessages = mutableMapOf<String, MutableSharedFlow<MessengerSendProgress>>()

  override fun onAckReceived(originalMessageId: String, fromDeviceId: String) {
    messengerScope.launch {
        pendingAckMessages.remove(originalMessageId)?.let { flow -> // Remove first
            log("MessengerImpl", "ACK received for messageId: $originalMessageId from $fromDeviceId")
            flow.emit(MessengerSendProgress.Acknowledged(originalMessageId))
        } ?: run {
            log("MessengerImpl", "Received ACK for unknown or already timed out/handled messageId: $originalMessageId")
        }
    }
  }

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {

    val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    messengerScope.launch {

      flow.emit(MessengerSendProgress.Pending)

      // --- ACK Logic Setup ---
      val message = messageRequest.message
      val messageId = message.messageId // Use the generalized messageId from the interface

      if (messageId != null) {
          pendingAckMessages[messageId] = flow // Store the flow for ACK handling

          // Launch timeout coroutine
          val currentMessageId = messageId // Capture for use in coroutine
          messengerScope.launch {
              delay(30000L) // 30-second timeout (configurable)
              // Check if ACK was received (i.e., if it's still in pendingAckMessages)
              pendingAckMessages.remove(currentMessageId)?.let { stillPendingFlow ->
                  log("MessengerImpl", "Timeout waiting for ACK for messageId: $currentMessageId")
                  stillPendingFlow.emit(MessengerSendProgress.Error("Timeout waiting for ACK for $currentMessageId"))
              }
          }
      }
      // --- End ACK Logic Setup ---

      val device = visibleDevices.getDevice(deviceId)

      //    skip if not visible
      if (device == null) {
        log("Messenger", "Wanted to send a message to $deviceId but it is not visible")
        flow.emit(MessengerSendProgress.Error("$deviceId but it is not visible"))
        // Clean up if messageId was added to pendingAckMessages
        if (messageId != null) pendingAckMessages.remove(messageId)
        return@launch
      }

      val transferHandlerResult = if (device.hasKlardropConnection()) {
        handleKlardropTransfer(deviceId, messageRequest, flow)
      } else if (device.hasNearbyConnection()) {
        handleNearbyTransfer(deviceId, messageRequest, flow)
      } else {
        log("Messenger", "Wanted to send a message to $deviceId but it has no connection")
        flow.emit(MessengerSendProgress.Error("$deviceId but it has no connection"))
        // Clean up if messageId was added to pendingAckMessages
        if (messageId != null) pendingAckMessages.remove(messageId)
        return@launch false // Indicate failure to prevent emitting Completed
      }

      // Emit Completed only if transfer was initiated successfully and no ACK is expected,
      // or if ACK is expected, this 'Completed' signifies data sent, ACK is separate.
      // The current structure implies 'Completed' is for the whole operation.
      // If an ACK is expected, 'Completed' should ideally be emitted AFTER 'Acknowledged'
      // or if the message type doesn't need an ACK.
      // For now, let's assume handleKlardropTransfer/handleNearbyTransfer emit their own 'Completed' if they finish payload.
      // And the ACK logic will separately emit 'Acknowledged'.
      // The original 'if (transferCompleted) flow.emit(Completed)' is problematic if ACK is pending.
      // Let's remove it for messages expecting an ACK.
      // If a message expects an ACK, its 'Completed' state will be part of the ACK process or a final success state.
      // If it does NOT expect an ACK, then the original logic is fine.

      if (messageId == null && transferHandlerResult) { // No ACK expected, and transfer was successful
          flow.emit(MessengerSendProgress.Completed)
      } else if (!transferHandlerResult) {
          // If transferHandler failed, it should have emitted an Error.
          // Clean up pending ACK if it was added.
          if (messageId != null) pendingAckMessages.remove(messageId)
      }
      // If messageId is not null (ACK expected) and transferHandlerResult is true,
      // we wait for ACK or timeout. 'handleKlardropTransfer' or 'handleNearbyTransfer'
      // might emit 'Completed' for data transfer, then 'onAckReceived' emits 'Acknowledged'.
    }

    return flow
  }

  override fun receive(): Flow<Flow<ReceiveMessageUpdate>> {
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
        nearbyClient.send(it.address, it.port, listOf(messageRequest), sendFlow)
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
    // if there is no connection, create one
    if (!connectionsPool.isAvailable(deviceId)) {
      client.connectTo(deviceId)
    } else {
      log("Messenger", "Client has already a connection with $deviceId. skipping")
    }

    log("Messenger", "Client sending message to $deviceId: ${messageRequest.message}")

    val connectionMessenger = connectionsPool.getConnection(deviceId) ?: run {
      log("Messenger", "No connection available for $deviceId")
      return false
    }

    connectionMessenger.send(messageRequest, flow)
    return true
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
  // 'Completed' now means the message data has been successfully sent.
  // If an ACK was expected, 'Acknowledged' will follow.
  // If no ACK was expected, 'Completed' is the final state.
  data object Completed : MessengerSendProgress
  data class Error(val message: String = "") : MessengerSendProgress
  data class Acknowledged(val ackedMessageId: String) : MessengerSendProgress

  fun isCompleted(): Boolean = this is Completed || this is Error
  // Acknowledged is a progress state, not necessarily a terminal 'completed' state
  // for the entire send operation from the user's perspective, though it completes the ACK wait.
}

