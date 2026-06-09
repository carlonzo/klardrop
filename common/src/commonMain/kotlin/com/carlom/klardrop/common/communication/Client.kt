package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleChannelBridge
import com.carlom.klardrop.common.ble.BleRoleSelector
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.logLocal
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch

/**
 * Result of a [Client.connectTo] call. The connector uses this to distinguish a
 * genuine dial failure from a deliberate decision not to initiate (e.g. BLE
 * role-selection means the peer will dial *us*), so reachability is not
 * incorrectly forced to [Unreachable] for inbound-only peers.
 */
sealed interface ConnectOutcome {
  /** A connection was successfully established and added to the pool. */
  data object Connected : ConnectOutcome

  /**
   * We deliberately did not initiate — e.g. BLE role-selection says the peer
   * dials us, or we are already connected. The probe is inconclusive; the peer
   * may still reach us via an inbound connection.
   */
  data object NotInitiated : ConnectOutcome

  /** Every dial attempt failed with an error. The peer is genuinely unreachable. */
  data object Failed : ConnectOutcome
}

interface Client {
  suspend fun connectTo(deviceId: String): ConnectOutcome
}

class ClientImpl(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  private val visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val trustManager: TrustManager,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
  private val bleTransport: BleTransport? = null,
) : Client {

  private val clientScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

  private val selectorManager = SelectorManager(coroutines.ioDispatcher)

  override suspend fun connectTo(deviceId: String): ConnectOutcome = coroutines.ioDispatcher {

    if (connectionsPool.isAvailable(deviceId)) {
      log("Client", "has already a connection with $deviceId. skipping")
      return@ioDispatcher ConnectOutcome.Connected
    }

    val discoveryDevice = visibleDevicesFlow.value[deviceId] ?: kotlin.run {
      log("Client", "cant connect. Device $deviceId cant be found")
      return@ioDispatcher ConnectOutcome.Failed
    }

    val tcpConnections = discoveryDevice.getKlardropConnection()
    val bleConnections = discoveryDevice.getBleConnection()

    require(tcpConnections.isNotEmpty() || bleConnections.isNotEmpty()) {
      "Cant connect to $deviceId. No Klardrop TCP or BLE connection is available"
    }

    val connectionJob = CompletableDeferred<ConnectOutcome>()

    // launch coroutine to connect and await for the connection to stay alive. TCP is
    // preferred (higher throughput); BLE is only tried if no TCP path worked, and only
    // when this device is the lex-smaller initiator per BleRoleSelector.
    launch {
      for (connection in tcpConnections) {
        log("Client", "Connecting to $deviceId with address ${connection.address} port ${connection.port}")
        establishConnection(connection.address, connection.port, deviceId, connectionJob)
          // TCP dial failures (peer not listening, connection refused, peer closed
          // mid-handshake) are routine on a flaky LAN. Keep the on-device log,
          // skip Bugsnag.
          .onFailure { logLocal("Client", "Failed TCP connect to $deviceId @ ${connection.address}", it) }
        if (connectionJob.isCompleted) return@launch
      }

      if (bleTransport != null && bleConnections.isNotEmpty()) {
        val selfId = currentDeviceProvider.get().shortDeviceId
        if (!BleRoleSelector.shouldInitiate(selfShortDeviceId = selfId, peerShortDeviceId = deviceId)) {
          log("Client", "Not the initiator for BLE to $deviceId (self=$selfId); awaiting inbound GATT")
          // Deliberately not initiating — the peer will dial us. This is not a failure;
          // leave reachability as Probing so we don't mark the peer Unreachable.
          connectionJob.complete(ConnectOutcome.NotInitiated)
          return@launch
        }
        for (ble in bleConnections) {
          log("Client", "Connecting via BLE to $deviceId (address=${ble.address})")
          establishBleConnection(ble, deviceId, connectionJob)
            .onFailure { logLocal("Client", "Failed BLE connect to $deviceId @ ${ble.address}", it) }
          if (connectionJob.isCompleted) return@launch
        }
      }

      // All TCP and BLE attempts were exhausted without success — genuine failure.
      if (!connectionJob.isCompleted) connectionJob.complete(ConnectOutcome.Failed)
    }

    // await for the connection to be established and connectionpool to be updated
    val outcome = connectionJob.await()
    log("Client", "On client connection completed with $deviceId: outcome: $outcome")
    outcome
  }

  private suspend fun establishConnection(address: String, port: Int, deviceId: String, connectionJob: CompletableDeferred<ConnectOutcome>) =
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
      supportsEncryption = true,
    )
    val writeChannel = socket.openWriteChannel(autoFlush = true)
    writeChannel.sendMessage(handshakeMessage, serializer)

    log("Client", "Waiting for response greetings from $deviceId")

    val readChannel = socket.openReadChannel()
    val serverHandshakeMessage = readChannel.readMessage(serializer) as HandshakeMessage

    if (serverHandshakeMessage.deviceId != deviceId) {
      log("Client", "cant connect. Device $deviceId found is wrong: ${serverHandshakeMessage.deviceId}")
      connectionJob.complete(ConnectOutcome.Failed)
      socket.close()
      return@runCatching
    }

    // Encryption is required: refuse peers (e.g. older builds) that don't advertise it rather
    // than silently falling back to cleartext.
    if (!serverHandshakeMessage.supportsEncryption) {
      log("Client", "Device $deviceId does not support encrypted transport; refusing (encryption required)")
      connectionJob.complete(ConnectOutcome.Failed)
      socket.close()
      return@runCatching
    }

    log("Client", "Connection established with ${serverHandshakeMessage.deviceId}; starting UKEY2 handshake")

    // Run the UKEY2 handshake (initiator role) over the same socket and bind it to the peer's
    // device identity. Done before any ConnectionMessenger exists so every subsequent frame is
    // encrypted.
    val cipher = KlardropEncryptedTransport.runInitiatorHandshake(
      readChannel = readChannel,
      writeChannel = writeChannel,
      selfDeviceId = self.shortDeviceId,
      peerDeviceId = deviceId,
      trustManager = trustManager,
    )

    // Check if client and server have the same device ID (test scenario). The UKEY2 handshake
    // above still ran so the server side completes; we just don't create a competing
    // client-side messenger — the server already manages this socket.
    val clientDeviceId = self.shortDeviceId
    if (clientDeviceId == deviceId) {
      log("Client", "Client and server have same device ID - server will manage the connection")
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
        cipher = cipher,
        initiatedByUs = true,
      )

      // Store the connection in the client's pool keyed by the server's device ID
      connectionsPool.updateConnection(deviceId, connectionMessenger)

      // Start listening for incoming messages (including ACKs) in a separate coroutine
      clientScope.launch {
        connectionMessenger.acceptIncomingMessages()
      }
    }

    connectionJob.complete(ConnectOutcome.Connected)
  }

  private suspend fun establishBleConnection(
    bleConnection: DeviceConnection.BleConnection,
    deviceId: String,
    connectionJob: CompletableDeferred<ConnectOutcome>,
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
        supportsEncryption = true,
      ),
      serializer,
    )
    val serverHandshake = bridge.readChannel.readMessage(serializer) as HandshakeMessage

    if (serverHandshake.deviceId != deviceId) {
      log("Client", "BLE handshake id mismatch: expected $deviceId got ${serverHandshake.deviceId}")
      bridge.close()
      connectionJob.complete(ConnectOutcome.Failed)
      return@runCatching
    }

    // Encryption is required: refuse peers (e.g. older builds) that don't advertise it.
    if (!serverHandshake.supportsEncryption) {
      log("Client", "BLE peer $deviceId does not support encrypted transport; refusing (encryption required)")
      bridge.close()
      connectionJob.complete(ConnectOutcome.Failed)
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

    // We (the central) spoke first, so we are the UKEY2 initiator — same role mapping as the
    // TCP client. Runs over the BLE bridge channels before any messenger exists.
    val cipher = KlardropEncryptedTransport.runInitiatorHandshake(
      readChannel = bridge.readChannel,
      writeChannel = bridge.writeChannel,
      selfDeviceId = self.shortDeviceId,
      peerDeviceId = deviceId,
      trustManager = trustManager,
    )

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
      cipher = cipher,
      initiatedByUs = true,
    )
    connectionsPool.updateConnection(deviceId, connectionMessenger)
    clientScope.launch { connectionMessenger.acceptIncomingMessages() }
    connectionJob.complete(ConnectOutcome.Connected)
  }
}
