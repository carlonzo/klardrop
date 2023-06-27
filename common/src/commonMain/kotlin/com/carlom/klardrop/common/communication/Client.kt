package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface Client {
  suspend fun connectTo(deviceId: String)
}

class ClientImpl(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val serializer: MessageSerializer,
  private val visibleDevices: VisibleDevices
) : Client {

  private val clientScope = CoroutineScope(coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

  private val currentDeviceId =
    localPropertiesRepository.properties.map { it.deviceId }.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = "")


  private val client by lazy {
    HttpClient(CIO) {
      install(WebSockets) {
        pingInterval = 20_000


        extensions { install(FrameLoggerExtension) }
      }
    }
  }

  override suspend fun connectTo(deviceId: String) {
    withContext(coroutines.ioDispatcher) {

      if (connectionsPool.isAvailable(deviceId)) {
        log("Client", "has already a connection with $deviceId. skipping")
        return@withContext
      }


      val discoveryDevice = visibleDevicesFlow.value[deviceId] ?: kotlin.run {
        log("Client", "cant connect. Device $deviceId cant be found")
        return@withContext
      }

      val connections = discoveryDevice.getKlardropConnection()

      require(connections.isNotEmpty()) {
        "Cant connect to $deviceId. KLARDROP connection is not available"
      }


      val connectionJob = CompletableDeferred<Boolean>()

      coroutines.appScope.launch {

        connections.forEach { connection ->
          val address = connection.address

          log("Client", "Connecting to $deviceId with address $address")

          estabilishConnection(address, deviceId, connectionJob)
            .onSuccess {
              // if connected, return
              return@forEach
            }
            .onFailure {
              log("Client", "Failed to connect to $deviceId with address $address", it)
            }

        }

      }

      log("Client", "Awaiting for client to finish connection")
      val await = connectionJob.await()
      log("Client", "On client finished connection: $await")
    }

  }

  private suspend fun estabilishConnection(address: String, deviceId: String, connectionJob: CompletableDeferred<Boolean>) = runCatching {

    client.webSocket(
      method = HttpMethod.Get,
      host = address,
      port = SERVER_PORT,
      path = "/connect"
    ) {
      log("Client", "Connected to $address. Sending greetings")
      val introEnvelope = HandshakeMessage(currentDeviceId.value)

      send(serializer.serialize(introEnvelope))

      log("Client", "Waiting for response greetings from $deviceId")
      val serverIntroEnvelope = serializer.deserialize(incoming.receive()) as HandshakeMessage

      if (serverIntroEnvelope.deviceId == deviceId) {
        val connection = Connection(this, deviceId)
        val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)

        connectionsPool.updateConnection(deviceId, connectionMessenger)
        log("Client", "Connection established with ${serverIntroEnvelope.deviceId}")

        connectionJob.complete(true)

        connectionMessenger.acceptIncomingMessages()

        // suspends so the connection is kept alive
        log("Client", "closing reason: ${closeReason.await()}")
      } else {
        connectionJob.complete(false)
        log("Client", "cant connect. Device $deviceId found is wrong: ${introEnvelope.deviceId}")
      }

    }

  }
}