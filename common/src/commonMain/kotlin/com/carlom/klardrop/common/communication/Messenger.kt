package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.MessengerSendProgress.Pending
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.SupervisorJob
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

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  coroutines: Coroutines,
  private val nearbyClient: NearbyClient,
  private val messageReceiver: MessageReceiver
) : Messenger {

  private val messengerScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {

    val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    messengerScope.launch {

      flow.emit(Pending)

      val device = visibleDevices.getDevice(deviceId)

      //    skip if not visible
      if (device == null) {
        log("Messenger", "Wanted to send a message to $deviceId but it is not visible")
        flow.emit(Error("$deviceId it is not visible"))
        return@launch
      }

      val transferCompleted = if (device.hasKlardropConnection()) {
        handleKlardropTransfer(deviceId, messageRequest, flow)
      } else if (device.hasNearbyConnection()) {
        handleNearbyTransfer(deviceId, messageRequest, flow)
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
    return runCatching {
      // Get or establish connection
      val connectionMessenger = getOrEstablishConnection(deviceId)

      if (connectionMessenger == null) {
        log("Messenger", "Failed to establish connection to $deviceId")
        flow.emit(Error("Failed to establish connection"))
        return false
      }

      log("Messenger", "Client sending message to $deviceId: ${messageRequest.message}")

      // Send the message - this will emit progress updates to the flow
      connectionMessenger.send(messageRequest, flow)
      true
    }.getOrElse { exception ->
      log("Messenger", "Error in Klardrop transfer to $deviceId", exception)
      flow.emit(Error("Transfer failed: ${exception.message}"))
      false
    }
  }

  private suspend fun getOrEstablishConnection(deviceId: String): ConnectionMessenger? {
    // First, check if we have a valid existing connection
    val existingConnection = connectionsPool.getConnection(deviceId)
    if (existingConnection != null && !existingConnection.isClosed()) {
      log("Messenger", "Using existing connection for $deviceId")
      return existingConnection
    }

    // If we have a closed connection, clean it up
    if (existingConnection?.isClosed() == true) {
      log("Messenger", "Removing closed connection for $deviceId")
      connectionsPool.closeConnection(deviceId)
    }

    // Establish a new connection
    log("Messenger", "Establishing new connection for $deviceId")
    client.connectTo(deviceId)

    // Verify the connection was established
    val newConnection = connectionsPool.getConnection(deviceId)
    if (newConnection == null || newConnection.isClosed()) {
      log("Messenger", "Failed to establish connection for $deviceId")
      return null
    }

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

