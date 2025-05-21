package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
}

class ClientImpl(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider
) : Client {

  private val clientScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

  private val selectorManager by lazy { SelectorManager(Dispatchers.IO) }

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

  private suspend fun establishConnection(address: String, port: Int, deviceId: String, connectionJob: CompletableDeferred<Boolean>) =
    runCatching {
      var socket: Socket? = null
      try {
        socket = aSocket(selectorManager).tcp().connect(address, port)
        log("Client", "Connected to $address:$port. Sending greetings")

        val output = socket.openWriteChannel(autoFlush = true)
        val input = socket.openReadChannel()

        // Send handshake
        val clientHandshakeMessage = HandshakeMessage(currentDeviceProvider.get().shortDeviceId)
        val serializedRequest = serializer.serialize(clientHandshakeMessage)
        // Assuming serializer returns ByteArray, convert to String for writeStringUtf8
        // Add newline for server's readUTF8Line
        output.writeStringUtf8(String(serializedRequest) + "\n")
        log("Client", "Sent handshake to $deviceId at $address:$port")

        // Receive handshake response
        log("Client", "Waiting for response greetings from $deviceId")
        val responseJson = input.readUTF8Line()

        if (responseJson == null) {
          log("Client", "Connection to $deviceId ($address:$port) closed by server before receiving handshake response.")
          connectionJob.complete(false)
          socket.close() // Ensure socket is closed
          return@runCatching // Exit runCatching block
        }

        val serverHandshakeMessage = serializer.deserialize(responseJson.decodeToByteArray()) as HandshakeMessage
        log("Client", "Received handshake response from $deviceId: ${serverHandshakeMessage.deviceId}")

        if (serverHandshakeMessage.deviceId == deviceId) {
          // val connection = Connection(this, deviceId) // 'this' would be wrong here, needs socket
          // val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)
          // connectionsPool.updateConnection(deviceId, connectionMessenger)

          log("Client", "Connection established with ${serverHandshakeMessage.deviceId} ($address:$port). Socket will be kept open.")
          connectionJob.complete(true)

          // The socket is intentionally left open here.
          // The ConnectionManager (or equivalent) will be responsible for managing it,
          // including reading incoming messages and handling closure.
          // For this subtask, we just establish the connection and complete the job.
          // No equivalent of "acceptIncomingMessages" or "closeReason.await()" here as that logic
          // will be part of the refactored Connection/ConnectionMessenger.

        } else {
          log("Client", "Handshake failed with $deviceId ($address:$port). Expected ${deviceId} but got ${serverHandshakeMessage.deviceId}")
          connectionJob.complete(false)
          socket.close() // Close socket on failed handshake
        }
      } catch (e: Exception) {
        log("Client", "Error connecting to $deviceId ($address:$port): ${e.message}", e)
        connectionJob.complete(false)
        socket?.close() // Ensure socket is closed on exception
        throw e // Re-throw to be caught by onFailure in connectTo
      }
      // Note: If connectionJob.complete(true), the socket is intentionally left open.
      // It's the responsibility of the calling code or a subsequent component to manage it.
    }
}