package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
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
  private val serializer: MessageSerializer,
  visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider
) : Client {

  private val clientScope = coroutines.newScope(coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())


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
          val port = connection.port

          log("Client", "Connecting to $deviceId with address $address port $port")

          establishConnection(address, port, deviceId, connectionJob)
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

  private suspend fun establishConnection(address: String, port: Int, deviceId: String, connectionJob: CompletableDeferred<Boolean>) = runCatching {

    client.webSocket(
      method = HttpMethod.Get,
      host = address,
      port = port,
      path = "/connect"
    ) {
      log("Client", "Connected to $address. Sending greetings")

      val handshakeMessage = HandshakeMessage(currentDeviceProvider.get().shortDeviceId)

      send(serializer.serialize(handshakeMessage))

      log("Client", "Waiting for response greetings from $deviceId")
      val serverHandshakeMessage = serializer.deserialize(incoming.receive()) as HandshakeMessage

      if (serverHandshakeMessage.deviceId == deviceId) {
        val connection = Connection(this, deviceId)
        val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)

        connectionsPool.updateConnection(deviceId, connectionMessenger)
        log("Client", "Connection established with ${serverHandshakeMessage.deviceId}")

        connectionJob.complete(true)

        connectionMessenger.acceptIncomingMessages()

        // suspends so the connection is kept alive
        log("Client", "closing reason: ${closeReason.await()}")
      } else {
        connectionJob.complete(false)
        log("Client", "cant connect. Device $deviceId found is wrong: ${handshakeMessage.deviceId}")
      }

    }

  }
}