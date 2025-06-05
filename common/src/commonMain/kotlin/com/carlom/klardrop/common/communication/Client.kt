package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

  private val clientScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

  private val selectorManager = SelectorManager(Dispatchers.Default)

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

      val socket = aSocket(selectorManager).tcp().connect(address, port)
      log("Client", "Connected to $address:$port. Sending greetings")

      val readChannel = socket.openReadChannel()
      val writeChannel = socket.openWriteChannel(autoFlush = true)

      val handshakeMessage = HandshakeMessage(currentDeviceProvider.get().shortDeviceId)
      val handshakeBytes = serializer.serialize(handshakeMessage)
      
      // Send message with length prefix
      val lengthBytes = ByteArray(4)
      lengthBytes[0] = (handshakeBytes.size shr 24).toByte()
      lengthBytes[1] = (handshakeBytes.size shr 16).toByte()
      lengthBytes[2] = (handshakeBytes.size shr 8).toByte()
      lengthBytes[3] = handshakeBytes.size.toByte()
      
      writeChannel.writeFully(lengthBytes)
      writeChannel.writeFully(handshakeBytes)

      log("Client", "Waiting for response greetings from $deviceId")
      
      // Read response message length
      val responseLengthBytes = ByteArray(4)
      readChannel.readFully(responseLengthBytes)
      val responseLength = (responseLengthBytes[0].toInt() and 0xFF shl 24) or
                         (responseLengthBytes[1].toInt() and 0xFF shl 16) or 
                         (responseLengthBytes[2].toInt() and 0xFF shl 8) or
                         (responseLengthBytes[3].toInt() and 0xFF)

      // Read the actual response message
      val responseBytes = ByteArray(responseLength)
      readChannel.readFully(responseBytes)
      
      val serverHandshakeMessage = serializer.deserialize(responseBytes) as HandshakeMessage

      if (serverHandshakeMessage.deviceId == deviceId) {
        val connection = Connection(socket, deviceId)
        val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)

        connectionsPool.updateConnection(deviceId, connectionMessenger)
        log("Client", "Connection established with ${serverHandshakeMessage.deviceId}")

        connectionJob.complete(true)

        connectionMessenger.acceptIncomingMessages()
      } else {
        connectionJob.complete(false)
        log("Client", "cant connect. Device $deviceId found is wrong: ${serverHandshakeMessage.deviceId}")
        socket.close()
      }

    }
}