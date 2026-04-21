package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch

interface Client {
  suspend fun connectTo(deviceId: String)
}

class ClientImpl(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
) : Client {

  private val clientScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

  private val selectorManager = SelectorManager(coroutines.ioDispatcher)

  override suspend fun connectTo(deviceId: String) = coroutines.ioDispatcher {

    if (connectionsPool.isAvailable(deviceId)) {
      log("Client", "has already a connection with $deviceId. skipping")
      return@ioDispatcher
    }

    val discoveryDevice = visibleDevicesFlow.value[deviceId] ?: kotlin.run {
      log("Client", "cant connect. Device $deviceId cant be found")
      return@ioDispatcher
    }

    val connections = discoveryDevice.getKlardropConnection()

    require(connections.isNotEmpty()) {
      "Cant connect to $deviceId. Klardrop connection is not available"
    }

    val connectionJob = CompletableDeferred<Boolean>()

    // launch coroutine to connect and await for the connection to stay alive
    launch {
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

    // await for the connection to be established and connectionpool to be updated
    val connectionResult = connectionJob.await()
    log("Client", "On client connection completed with $deviceId: result: $connectionResult")
  }

  private suspend fun establishConnection(address: String, port: Int, deviceId: String, connectionJob: CompletableDeferred<Boolean>) =
    runCatching {

    val socket = aSocket(selectorManager).tcp().connect(address, port) {
      // Enable kernel-level TCP keep-alive so the OS reaps a half-open connection
      // (peer crashed / off-network without sending FIN). The application-level
      // ACK timeout is the fast-path recovery; keep-alive is a coarse backstop.
      keepAlive = true
    }
    log("Client", "Connected to $address:$port. Sending greetings")

    val handshakeMessage = HandshakeMessage(currentDeviceProvider.get().shortDeviceId)
    val writeChannel = socket.openWriteChannel(autoFlush = true)
    writeChannel.sendMessage(handshakeMessage, serializer)

    log("Client", "Waiting for response greetings from $deviceId")

    val readChannel = socket.openReadChannel()
    val serverHandshakeMessage = readChannel.readMessage(serializer) as HandshakeMessage

    if (serverHandshakeMessage.deviceId == deviceId) {
      log("Client", "Connection established with ${serverHandshakeMessage.deviceId}")

      // Check if client and server have the same device ID (test scenario)
      val clientDeviceId = currentDeviceProvider.get().shortDeviceId
      if (clientDeviceId == deviceId) {
        log("Client", "Client and server have same device ID - server will manage the connection")
        // Don't create a client-side ConnectionMessenger to avoid conflicts
        // The server has already created one and stored it with the same key
      } else {
        // Create a ConnectionMessenger for the client side to send messages to the server
        val connection = Connection(socket, deviceId)
        val connectionMessenger = ConnectionMessenger(
          coroutines = coroutines,
          connection = connection,
          messagesRouter = messagesRouter,
          readChannel = readChannel,
          writeChannel = writeChannel,
          ackTimeoutConfig = ackTimeoutConfig,
          heartbeatConfig = heartbeatConfig,
          messageSerializer = serializer,
        )
        
        // Store the connection in the client's pool keyed by the server's device ID
        connectionsPool.updateConnection(deviceId, connectionMessenger)
        
        // Start listening for incoming messages (including ACKs) in a separate coroutine
        clientScope.launch {
          connectionMessenger.acceptIncomingMessages()
        }
      }
      
      connectionJob.complete(true)
    } else {
      log("Client", "cant connect. Device $deviceId found is wrong: ${serverHandshakeMessage.deviceId}")
      connectionJob.complete(false)
      socket.close()
    }

  }
}