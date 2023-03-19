package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.IntroductionEnvelope
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf

interface Client {
  suspend fun connectTo(deviceId: String)
}

class ClientImpl(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val incomingMessagesRouter: IncomingMessagesRouter,
  private val localPropertiesRepository: LocalPropertiesRepository
) : Client {

  private val clientScope = CoroutineScope(coroutines.ioDispatcher)
  private val knownDevices =
    knownDevicesRepository.knownDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())
  private val currentDeviceId =
    localPropertiesRepository.properties.map { it.deviceId }.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = "")

  private val proto = ProtoBuf

  private val client by lazy {
    HttpClient(CIO) {
      install(WebSockets) {
        pingInterval = 20_000
        contentConverter = WebSocketEnvelopeContentConverted(proto)
      }
    }
  }

  override suspend fun connectTo(deviceId: String) {
    withContext(coroutines.ioDispatcher) {

      if (connectionsPool.isAvailable(deviceId)) {
        log("Client has already a connection with $deviceId. skipping")
        return@withContext
      }


      val deviceInfo = knownDevices.value[deviceId] ?: kotlin.run {
        log("Client cant connect. Device $deviceId cant be found")
        return@withContext
      }

      log("Client. Connecting to $deviceId, ${deviceInfo.lastAddress}")
      client.webSocket(
        method = HttpMethod.Get,
        host = deviceInfo.lastAddress,
        port = SERVER_PORT,
        path = "/connect"
      ) {
        log("Client. Connected to $deviceInfo. Sending greetings")
        val introEnvelope = IntroductionEnvelope(currentDeviceId.value)
        sendSerialized(introEnvelope)

        log("Client. Waiting for response greetings from $deviceId")
        val serverIntroEnvelope = receiveDeserialized<IntroductionEnvelope>()

        if (serverIntroEnvelope.deviceId == deviceId) {
          val connection = Connection(this, deviceId)
          val connectionMessenger = ConnectionMessenger(coroutines, connection, incomingMessagesRouter)

          connectionsPool.updateConnection(deviceId, connectionMessenger)
          log("Client. Connection established with ${serverIntroEnvelope.deviceId}")

          // suspends so the connection is kept alive
          log("Client: closing reason: ${closeReason.await()}")
        } else {
          log("Client cant connect. Device $deviceId found is wrong: ${introEnvelope.deviceId}")
        }

      }
    }

  }
}