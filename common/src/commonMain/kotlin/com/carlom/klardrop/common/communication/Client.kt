package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleChannelBridge
import com.carlom.klardrop.common.ble.BleRoleSelector
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
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
  private val visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
  private val bleTransport: BleTransport? = null,
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

    val tcpConnections = discoveryDevice.getKlardropConnection()
    val bleConnections = discoveryDevice.getBleConnection()

    require(tcpConnections.isNotEmpty() || bleConnections.isNotEmpty()) {
      "Cant connect to $deviceId. No Klardrop TCP or BLE connection is available"
    }

    val connectionJob = CompletableDeferred<Boolean>()

    // launch coroutine to connect and await for the connection to stay alive. TCP is
    // preferred (higher throughput); BLE is only tried if no TCP path worked, and only
    // when this device is the lex-smaller initiator per BleRoleSelector.
    launch {
      for (connection in tcpConnections) {
        log("Client", "Connecting to $deviceId with address ${connection.address} port ${connection.port}")
        establishConnection(connection.address, connection.port, deviceId, connectionJob)
          .onFailure { log("Client", "Failed TCP connect to $deviceId @ ${connection.address}", it) }
        if (connectionJob.isCompleted) return@launch
      }

      if (bleTransport != null && bleConnections.isNotEmpty()) {
        val selfId = currentDeviceProvider.get().shortDeviceId
        if (!BleRoleSelector.shouldInitiate(selfShortDeviceId = selfId, peerShortDeviceId = deviceId)) {
          log("Client", "Not the initiator for BLE to $deviceId (self=$selfId); awaiting inbound GATT")
          connectionJob.complete(false)
          return@launch
        }
        for (ble in bleConnections) {
          log("Client", "Connecting via BLE to $deviceId (address=${ble.address})")
          establishBleConnection(ble, deviceId, connectionJob)
            .onFailure { log("Client", "Failed BLE connect to $deviceId @ ${ble.address}", it) }
          if (connectionJob.isCompleted) return@launch
        }
      }

      if (!connectionJob.isCompleted) connectionJob.complete(false)
    }

    // await for the connection to be established and connectionpool to be updated
    val connectionResult = connectionJob.await()
    log("Client", "On client connection completed with $deviceId: result: $connectionResult")
  }

  private suspend fun establishConnection(address: String, port: Int, deviceId: String, connectionJob: CompletableDeferred<Boolean>) =
    runCatching {

    val socket = aSocket(selectorManager).tcp().connect(address, port) {
      // Coarse OS-level backstop. The application-level heartbeat is the
      // primary liveness mechanism; keep-alive only helps if the heartbeat
      // coroutine is itself wedged.
      keepAlive = true
    }
    log("Client", "Connected to $address:$port. Sending greetings")

    val self = currentDeviceProvider.get()
    val handshakeMessage = HandshakeMessage(
      deviceId = self.shortDeviceId,
      deviceName = self.deviceName,
      osType = self.osType,
      deviceType = self.deviceType,
    )
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
        val connection = Connection.Tcp(socket, deviceId)
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

  private suspend fun establishBleConnection(
    bleConnection: DeviceConnection.BleConnection,
    deviceId: String,
    connectionJob: CompletableDeferred<Boolean>,
  ) = runCatching {
    val transport = checkNotNull(bleTransport) { "No BLE transport injected" }
    val session = transport.connectCentral(bleConnection.address, deviceId)
    val bridge = BleChannelBridge(session, clientScope).start()

    val self = currentDeviceProvider.get()
    // Central speaks first — send the rich handshake so the server can enrich its
    // VisibleDevices entry. BLE advertisements only carry the bare shortDeviceId
    // for privacy; this is the first place the friendly name is revealed.
    bridge.writeChannel.sendMessage(
      HandshakeMessage(
        deviceId = self.shortDeviceId,
        deviceName = self.deviceName,
        osType = self.osType,
        deviceType = self.deviceType,
      ),
      serializer,
    )
    val serverHandshake = bridge.readChannel.readMessage(serializer) as HandshakeMessage

    if (serverHandshake.deviceId != deviceId) {
      log("Client", "BLE handshake id mismatch: expected $deviceId got ${serverHandshake.deviceId}")
      bridge.close()
      connectionJob.complete(false)
      return@runCatching
    }

    // Server's reply may carry rich identity — enrich our VisibleDevices entry so
    // the BLE peer shows up with friendly name + OS/device type instead of the
    // shortDeviceId placeholder.
    if (serverHandshake.deviceName.isNotEmpty()) {
      runCatching {
        visibleDevices.onNewDeviceVisible(
          com.carlom.klardrop.common.discovery.DeviceInfo(
            deviceId = serverHandshake.deviceId,
            name = serverHandshake.deviceName,
            deviceType = serverHandshake.deviceType,
            osType = serverHandshake.osType,
          ),
          bleConnection,
        )
      }
    }

    val connection = Connection.Ble(session, deviceId)
    val connectionMessenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = connection,
      messagesRouter = messagesRouter,
      readChannel = bridge.readChannel,
      writeChannel = bridge.writeChannel,
      ackTimeoutConfig = ackTimeoutConfig,
      heartbeatConfig = heartbeatConfig,
      messageSerializer = serializer,
    )
    connectionsPool.updateConnection(deviceId, connectionMessenger)
    clientScope.launch { connectionMessenger.acceptIncomingMessages() }
    connectionJob.complete(true)
  }
}
