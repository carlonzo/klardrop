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
import kotlinx.coroutines.withTimeout

/**
 * Per-address TCP connect timeout (milliseconds), enforced via [withTimeout].
 *
 * Ktor's NIO-based connect suspends until the OS completes the 3-way
 * handshake.  When a peer advertises a stale/black-holed address (SYN
 * packets silently dropped rather than RST'd) there is no OS-level upper
 * bound on that wait — it can block for the full OS retransmit cycle (tens
 * of seconds).  One such address therefore consumes the entire 15 s
 * CONNECTION_WAIT_TIMEOUT before any other advertised address is tried.
 *
 * 3 s is generous for any reachable LAN peer and leaves room for 4+ stale
 * addresses to be tried sequentially within the 15 s budget.
 */
internal const val TCP_CONNECT_TIMEOUT_MS = 3_000L

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

/**
 * Returns true when this exception (or any cause in its chain) represents a hard connection
 * refusal — ECONNREFUSED, i.e. the remote port is not listening. Used to distinguish
 * "peer is gone / restarted" from transient network glitches. Works across platforms by
 * inspecting class simpleName and message text rather than JVM-only types:
 *  - JVM / Android: `java.net.ConnectException`.
 *  - Apple-native (Ktor over POSIX sockets): `PosixException.ConnectionRefusedException`
 *    (simpleName "ConnectionRefusedException") and/or the strerror text "Connection refused".
 *
 * We deliberately match ONLY the refusal-specific class name and the canonical refusal text —
 * NOT the broad `PosixException` base, which also covers ECONNRESET / ETIMEDOUT /
 * EHOSTUNREACH. Treating those as "refused" would wrongly invalidate a still-valid endpoint
 * on a transient error.
 */
internal fun Throwable.isConnectionRefused(): Boolean {
  var current: Throwable? = this
  var depth = 0
  while (current != null && depth < 8) {
    val name = current::class.simpleName ?: ""
    val msg = current.message.orEmpty()
    if (name == "ConnectException" || name == "ConnectionRefusedException") return true
    if (msg.contains("ECONNREFUSED", ignoreCase = true) ||
      msg.contains("Connection refused", ignoreCase = true)
    ) return true
    current = current.cause?.takeIf { it !== current }
    depth++
  }
  return false
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
          .onFailure { cause ->
            logLocal("Client", "Failed TCP connect to $deviceId @ ${connection.address}", cause)
            // If the dial was actively refused (peer's port is dead — e.g. peer restarted
            // on a new ephemeral port), remove the stale endpoint from the visible-device
            // cache immediately. This prevents every subsequent send attempt from retrying
            // the dead address+port until mDNS delivers a fresh SRV record.
            if (cause.isConnectionRefused()) {
              log("Client", "Connection refused to $deviceId @ ${connection.address}:${connection.port} — invalidating stale endpoint")
              visibleDevices.invalidateKlardropEndpoint(deviceId, connection.address, connection.port)
            }
          }
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

    // withTimeout caps the per-address connect phase.  Ktor's NIO-based
    // connect waits for the OS to complete the TCP 3-way handshake; if the
    // remote address is black-holed (SYN packets silently dropped — not
    // refused) this wait has no OS-level upper bound and can block for tens
    // of seconds, consuming the entire CONNECTION_WAIT_TIMEOUT budget before
    // any other advertised address is tried.  socketTimeout only applies to
    // read/write I/O, not to connect, so withTimeout is the correct mechanism.
    val socket = withTimeout(TCP_CONNECT_TIMEOUT_MS) {
      aSocket(selectorManager).tcp().connect(address, port) {
        // Coarse OS-level backstop. The application-level heartbeat is the
        // primary liveness mechanism; keep-alive only helps if the heartbeat
        // coroutine is itself wedged.
        keepAlive = true
      }
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
    // Bound the wait for the peer's greeting too. A peer can complete the TCP
    // 3-way handshake — satisfying the connect withTimeout above — yet never send
    // its HandshakeMessage: e.g. a connection the peer's kernel queued but the app
    // never accepted (backlog-stalled), a half-open/black-holed socket, or a peer
    // that died right after accept. socketTimeout only covers post-handshake I/O on
    // an established channel and would not fire here, so without this explicit bound
    // that silent peer stalls the whole dial indefinitely — the same black-hole
    // symptom we already cap at the connect phase. Reuse the connect budget: a real
    // peer sends its greeting immediately after accept, well inside this window.
    val serverHandshakeMessage = withTimeout(TCP_CONNECT_TIMEOUT_MS) {
      readChannel.readMessage(serializer) as HandshakeMessage
    }

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
