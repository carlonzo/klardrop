package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.withContext

interface Messenger {
  suspend fun send(deviceId: String, envelope: Envelope)
}

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  private val coroutines: Coroutines
) : Messenger {

  override suspend fun send(deviceId: String, envelope: Envelope) {

//    skip if not visible
    if (!visibleDevices.isDeviceVisible(deviceId)) {
      log("Wanted to send a message to $deviceId but it is not visible")
      return
    }

    withContext(coroutines.ioDispatcher) {
      // if there is no connection, create one
      if (!connectionsPool.isAvailable(deviceId)) {
        client.connectTo(deviceId)
      } else {
        log("Client has already a connection with $deviceId. skipping")
      }

      log("Client sending message to $deviceId: $envelope")
      connectionsPool.getConnection(deviceId)?.send(envelope)
    }

  }


}