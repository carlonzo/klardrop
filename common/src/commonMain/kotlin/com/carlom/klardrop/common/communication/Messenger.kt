package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.withContext

/**
 * Messenger used to send envelops
 */
interface Messenger {
  suspend fun send(deviceId: String, messageRequest: SendMessageRequest)
}

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  private val coroutines: Coroutines,
) : Messenger {

  override suspend fun send(deviceId: String, messageRequest: SendMessageRequest) {

//    skip if not visible
    if (!visibleDevices.isDeviceVisible(deviceId)) {
      log("Messenger", "Wanted to send a message to $deviceId but it is not visible")
      return
    }

    withContext(coroutines.ioDispatcher) {
      // if there is no connection, create one
      if (!connectionsPool.isAvailable(deviceId)) {
        client.connectTo(deviceId)
      } else {
        log("Messenger", "Client has already a connection with $deviceId. skipping")
      }

      log("Messenger", "Client sending message to $deviceId: ${messageRequest.message}")

      val connectionMessenger = connectionsPool.getConnection(deviceId) ?: run {
        log("Messenger", "No connection available for $deviceId")
        return@withContext
      }

      connectionMessenger.send(messageRequest)
    }

  }


}