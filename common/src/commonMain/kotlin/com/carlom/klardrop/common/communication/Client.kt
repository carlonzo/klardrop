package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.IntroductionEnvelope
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.log
import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class Client(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val flowKnownDevices: Flow<Set<DeviceInfo>>,
  private val incomingMessagesRouter: IncomingMessagesRouter
) {

  private val clientScope = CoroutineScope(coroutines.ioDispatcher)
  private val knownDevices = flowKnownDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptySet())

  private val client by lazy {
    HttpClient(CIO) {
      install(WebSockets) {
        pingInterval = 20_000
      }
    }
  }

  suspend fun connectTo(deviceId: String) {
    withContext(coroutines.ioDispatcher) {

      if (connectionsPool.isAvailable(deviceId)) {
        log("Client has already a connection with $deviceId. skipping")
        return@withContext
      }


      val deviceInfo = knownDevices.value.find { it.deviceId == deviceId } ?: kotlin.run {
        log("Client cant connect. Device $deviceId cant be found")
        return@withContext
      }

      client.webSocket(method = HttpMethod.Get, host = deviceInfo.lastAddress, port = SERVER_PORT, path = "/connect") {
        val introEnvelope = receiveDeserialized<IntroductionEnvelope>()

        if (introEnvelope.deviceId == deviceId) {
          val connection = Connection(this, deviceId)
          val connectionMessenger = ConnectionMessenger(connection, incomingMessagesRouter)

          connectionsPool.updateConnection(deviceId, connectionMessenger)
        } else {
          log("Client cant connect. Device $deviceId found is wrong: ${introEnvelope.deviceId}")
        }

      }
    }

  }
}