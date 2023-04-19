package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.MessengerSendProgress.Closed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Messenger used to send envelops
 */
interface Messenger {
  fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress>
}

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  private val coroutines: Coroutines,
) : Messenger {

  private val sendScope = CoroutineScope(coroutines.ioDispatcher)

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {

    val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    sendScope.launch {

      flow.emit(MessengerSendProgress.Pending)

      //    skip if not visible
      if (!visibleDevices.isDeviceVisible(deviceId)) {
        log("Messenger", "Wanted to send a message to $deviceId but it is not visible")
        flow.emit(Error("$deviceId but it is not visible"))
        return@launch
      }

      // if there is no connection, create one
      if (!connectionsPool.isAvailable(deviceId)) {
        client.connectTo(deviceId)
      } else {
        log("Messenger", "Client has already a connection with $deviceId. skipping")
      }

      log("Messenger", "Client sending message to $deviceId: ${messageRequest.message}")

      val connectionMessenger = connectionsPool.getConnection(deviceId) ?: run {
        log("Messenger", "No connection available for $deviceId")
        flow.emit(Error("No connection available for $deviceId"))
        return@launch
      }

      connectionMessenger.send(messageRequest, flow)

      flow.emit(Completed)
    }

    return flow
  }

}

fun Flow<MessengerSendProgress>.untilCompleted(): Flow<MessengerSendProgress> {
  return this
    // send a closed after completed or error. this is to close the collection
    .flatMapConcat { if (it is Completed || it is Error) flowOf(it, Closed) else flowOf(it) }
    .takeWhile { it !is Closed }
}

sealed interface MessengerSendProgress {
  object Pending : MessengerSendProgress
  data class InProgress(val percentage: Float) : MessengerSendProgress
  object Completed : MessengerSendProgress
  data class Error(val message: String = "") : MessengerSendProgress

  object Closed : MessengerSendProgress
}