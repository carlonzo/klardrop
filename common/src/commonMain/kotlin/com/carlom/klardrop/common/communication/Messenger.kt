package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.MessengerSendProgress.Pending
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.receiver.TransferReceiver
import com.carlom.klardrop.common.receiver.ReceiveTransferUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * Messenger used to send messages
 */
interface Messenger {
  fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress>
  fun receive(): Flow<Flow<ReceiveTransferUpdate>>
}

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  coroutines: Coroutines,
  private val nearbyClient: NearbyClient,
  private val transferReceiver: TransferReceiver
) : Messenger {

  private val messengerScope = coroutines.newScope(coroutines.ioDispatcher)

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {

    val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    messengerScope.launch {

      flow.emit(Pending)

      val device = visibleDevices.getDevice(deviceId)

      //    skip if not visible
      if (device == null) {
        log("Messenger", "Wanted to send a message to $deviceId but it is not visible")
        flow.emit(Error("$deviceId but it is not visible"))
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

      if (transferCompleted) {
        flow.emit(Completed)
      }
    }

    return flow
  }

  override fun receive(): Flow<Flow<ReceiveTransferUpdate>> {
    return transferReceiver.notifier
  }

  private suspend fun handleNearbyTransfer(
    deviceId: String,
    messageRequest: SendMessageRequest,
    sendFlow: MutableSharedFlow<MessengerSendProgress>
  ): Boolean {

    val device = visibleDevices.getDevice(deviceId) ?: throw IllegalStateException("Device $deviceId is not found!")
    val nearbyConnections = device.getNearbyConnection()

    require(nearbyConnections.isNotEmpty()) { "Device $deviceId has no nearby connections" }

    nearbyConnections.forEach {
      log("Messenger", "Client sending message to $deviceId: ${it.address} ${it.port}")

      runCatching {
        nearbyClient.send(it.address, it.port, listOf(messageRequest), sendFlow)
      }.onSuccess {
        return@forEach
      }.onFailure { failure ->
        throw failure
      }

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
  data object Completed : MessengerSendProgress
  data class Error(val message: String = "") : MessengerSendProgress

  fun isCompleted(): Boolean = this is Completed || this is Error
}

