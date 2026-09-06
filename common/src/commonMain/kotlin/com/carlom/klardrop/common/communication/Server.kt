package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandler
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.dropSupersededTrust
import com.carlom.klardrop.common.trust.revocationIfPeerStale
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.logLocal
import com.carlom.klardrop.common.utils.transferLog
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.V1Frame
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Server - A single server that handles both Klardrop and Nearby Share protocols
 *
 * This server listens on a single TCP socket and automatically detects which protocol
 * a client is using based on the structure of the first message received. It then routes
 * the connection to the appropriate protocol handler.
 *
 * ## Protocol Detection Strategy
 *
 * Both protocols use the same transport layer:
 * - Raw TCP sockets with 4-byte length-prefixed messages
 * - Big-endian encoding for message lengths
 *
 * ### Message Format Differences:
 *
 * **Klardrop Protocol:**
 * ```
 * [4-byte length][1-byte message type][protobuf payload]
 * ```
 * - First payload byte is message type [MessageType.id]
 * - HandshakeMessage contains simple device ID string
 *
 * **Nearby Share Protocol:**
 * ```
 * [4-byte length][protobuf OfflineFrame]
 * ```
 * - First payload bytes are Protocol Buffer field tags
 * - OfflineFrame contains complex nested structures with version and frame types
 *
 * ### Detection Algorithm:
 *
 * 1. Read the length-prefixed message
 * 2. Check if first payload byte matches Klardrop message types (0-2)
 * 3. Try to parse as Klardrop HandshakeMessage
 * 4. If that fails, try to parse as Nearby Share OfflineFrame
 * 5. Route connection to appropriate handler based on successful parsing
 *
 * ## Benefits:
 *
 * - **Resource Efficiency**: Single server socket instead of two separate servers
 * - **Simplified Discovery**: Only one mDNS service advertisement per protocol
 * - **Automatic Detection**: No manual protocol selection required
 * - **Backward Compatibility**: Existing clients work unchanged
 * - **Port Consolidation**: Both protocols share the same listening port
 *
 * ## Usage:
 *
 * ```kotlin
 * val server = Server(...)
 * val config = server.startServer()
 * // Server now accepts both Klardrop and Nearby Share connections on config.port
 * ```
 */
class Server(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val createNearbyReceiver: () -> NearbyReceiverConnectionHandler,
  private val visibleDevices: VisibleDevices,
  private val messageReceiver: MessageReceiver,
  private val protoBuf: ProtoBuf,
  private val trustManager: TrustManager,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
  /**
   * Published with the bound port as soon as the listener is up (0 while unknown). The
   * client's T10 punch-through dial reads it to bind its sockets to our listening port.
   */
  private val serverPort: MutableStateFlow<Int>? = null,
  private val preferredPort: Int = 0,
) {
  data class ServerConfig(val host: String, val port: Int)

  internal enum class Protocol {
    KLARDROP,
    NEARBY_SHARE
  }

  private val serverScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  /**
   * Binds the listening socket, preferring SO_REUSEPORT.
   *
   * T10: the punch-through dial socket co-binds this port (on a specific local address)
   * while the listener holds it. Linux refuses that co-bind with SO_REUSEADDR alone when
   * the existing socket is LISTENing — SO_REUSEPORT on BOTH sockets is what permits it
   * (the dial socket never listens, so SYNs are still dispatched only to the listener).
   *
   * SO_REUSEPORT is not universally available, and ktor resolves it reflectively from
   * `java.net.StandardSocketOptions` — a field Android only gained in API 33 (we ship
   * minSdk 24) and which Windows does not support at all. Without the fallback below the
   * option's absence throws out of bind() and the whole server never starts, which reads
   * to the user as "discovery works but nothing ever connects". Punch-through is a
   * best-effort optimisation; the server is not, so an unavailable option degrades to a
   * plain bind instead of taking the listener down with it.
   */
  private suspend fun bindListener(selectorManager: SelectorManager): ServerSocket {
    val portsToTry = if (preferredPort > 0) listOf(preferredPort, 0) else listOf(0)
    for (port in portsToTry) {
      val socket = try {
        aSocket(selectorManager).tcp().bind("0.0.0.0", port) {
          reuseAddress = true
          reusePort = true
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        try {
          aSocket(selectorManager).tcp().bind("0.0.0.0", port) {
            reuseAddress = true
          }
        } catch (e2: kotlinx.coroutines.CancellationException) {
          throw e2
        } catch (e2: Exception) {
          log("Server", "Failed binding to port $port (${e2.message}); trying next")
          null
        }
      }
      if (socket != null) return socket
    }
    error("Failed to bind server socket to any port")
  }

  /**
   * Starts the unified server that handles both protocols.
   *
   * @return ServerConfig containing the host and port the server is listening on
   */
  suspend fun startServer(): ServerConfig {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val serverSocket = bindListener(selectorManager)

    val localAddress = serverSocket.localAddress as InetSocketAddress
    val actualPort = localAddress.port
    val host = localAddress.hostname
    serverPort?.value = actualPort

    log("Server", "Unified server started on $host:$actualPort")

    serverScope.launch {
      try {
        while (isActive) {
          val socket = serverSocket.accept()
          val remoteAddress = socket.remoteAddress.toString()
          log("Server", "New connection from: $remoteAddress")

          launch {
            try {
              handleConnection(socket, remoteAddress)
            } catch (e: Exception) {
              log("Server", "Error handling connection from $remoteAddress", e)
              socket.close()
            }
          }
        }
      } finally {
        // MUST be a finally: stopServer() cancels serverScope, which cancels this very coroutine,
        // so cleanup placed after the loop never ran and every accept loop leaked its selector.
        // On Apple targets that matters — ktor's selector loop blocks in `pselect` and holds one
        // of Dispatchers.IO's 64 parallelism slots until closed, so leaked selectors eventually
        // starve the pool and every subsequent socket operation hangs forever.
        log("Server", "Closing the server connection")
        serverSocket.close()
        selectorManager.close()
      }
    }

    return ServerConfig(host, actualPort)
  }

  fun stopServer() {
    serverScope.cancel()
    log("Server", "Unified server stopped")
  }

  /**
   * Handles an incoming connection by detecting the protocol and routing appropriately.
   */
  private suspend fun handleConnection(socket: Socket, remoteAddress: String) {
    val readChannel = socket.openReadChannel()

    // Read the first message to detect protocol. Bound by TCP_CONNECT_TIMEOUT_MS so a peer that
    // completes the TCP handshake but then sends nothing cannot hold the FD + coroutine forever.
    val firstMessage = try {
      withTimeout(TCP_CONNECT_TIMEOUT_MS) {
        readChannel.readByteArrayMessage()
      }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      log("Server", "Protocol-detection read timed out for $remoteAddress — closing silent peer")
      socket.close()
      return
    }

    val protocol = detectProtocol(firstMessage)
    log("Server", "Detected protocol: $protocol for connection from $remoteAddress")

    when (protocol) {
      Protocol.KLARDROP -> handleKlardropConnection(socket, firstMessage, remoteAddress, readChannel)
      Protocol.NEARBY_SHARE -> handleNearbyShareConnection(socket, firstMessage, remoteAddress, readChannel)
    }

  }

  /**
   * Detects which protocol is being used based on the first message structure.
   *
   * Detection order:
   * 1. A first byte of 0x0A that parses as a Nearby CONNECTION_REQUEST OfflineFrame is
   *    NEARBY_SHARE — the byte collides with [MessageType.TRUST_PAIRING_REQUEST]'s id, and
   *    Klardrop never opens a connection with it (its first message is always a Handshake).
   * 2. Any other first byte in the Klardrop MessageType range means Klardrop only if the rest
   *    parses as a [HandshakeMessage]; a failed parse falls through to Nearby detection.
   * 3. A Nearby Share OfflineFrame is accepted only when it carries a CONNECTION_REQUEST.
   *
   * @param payload The complete first message including the 4-byte length prefix
   * @return The detected protocol
   * @throws IllegalArgumentException if the protocol cannot be determined
   */
  internal fun detectProtocol(payload: ByteArray): Protocol {
    if (payload.isEmpty()) {
      throw IllegalArgumentException("Message too short: ${payload.size} bytes")
    }

    val firstByte = payload[0]

    // Collision guard: Nearby OfflineFrames from real peers start 0x0A (protobuf field 1,
    // wire-type 2), which equals TRUST_PAIRING_REQUEST's id, and a crafted Version block can
    // accidentally parse as a HandshakeMessage. Preferring Nearby for this byte is safe because
    // Klardrop's first message on a fresh connection is always a Handshake (id 0).
    if (firstByte == 0x0A.toByte() && isValidNearbyConnectionRequest(payload)) {
      return Protocol.NEARBY_SHARE
    }

    // Klardrop frames start with a MessageType id followed by a protobuf HandshakeMessage.
    // A first byte in range is only Klardrop if that parse succeeds — on failure fall through
    // to Nearby Share detection instead of dropping the connection.
    if (firstByte in MessageType.entries.map { it.id }) {
      val handshakePayload = payload.sliceArray(1 until payload.size)
      val handshake = try {
        protoBuf.decodeFromByteArray(HandshakeMessage.serializer(), handshakePayload)
      } catch (_: Exception) {
        null
      }
      if (handshake != null) {
        return Protocol.KLARDROP
      }
    }

    // Try to parse as Nearby Share OfflineFrame
    if (isValidNearbyConnectionRequest(payload)) {
      return Protocol.NEARBY_SHARE
    }

    val firstByteHex = (firstByte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    throw IllegalArgumentException("Unrecognized protocol: first byte 0x$firstByteHex")
  }

  private fun isValidNearbyConnectionRequest(payload: ByteArray): Boolean {
    val offlineFrame = try {
      OfflineFrame.ADAPTER.decode(payload)
    } catch (_: Exception) {
      return false
    }
    return offlineFrame.v1?.type == V1Frame.FrameType.CONNECTION_REQUEST
  }

  /**
   * Handles a Klardrop protocol connection.
   */
  private suspend fun handleKlardropConnection(
    socket: Socket,
    firstMessage: ByteArray,
    remoteAddress: String,
    readChannel: ByteReadChannel
  ) {
    val handshakePayload = firstMessage.sliceArray(1 until firstMessage.size)
    val request = protoBuf.decodeFromByteArray(HandshakeMessage.serializer(), handshakePayload)

    log("Server", "Klardrop connection request from: $remoteAddress - ${request.deviceId}")

    // Log-only diagnostic: a claimed id absent from the visible map usually means mDNS loss
    // or a regenerated id on the peer. Legitimate cases exist (BLE-only visibility, races
    // between discovery and connect), so the connection proceeds regardless.
    if (!visibleDevices.isDeviceVisible(request.deviceId)) {
      log("Server", "Inbound connection claims deviceId ${request.deviceId} which is not in visible devices (mDNS loss or id change)")
    }

    // Encryption is required: refuse peers (e.g. older builds) that don't advertise it rather
    // than silently falling back to cleartext.
    if (!request.supportsEncryption) {
      log("Server", "Peer ${request.deviceId} does not support encrypted transport; refusing (encryption required)")
      socket.close()
      return
    }

    val writeChannel = socket.openWriteChannel(autoFlush = true)

    // Send back introduction (cleartext, like the request) advertising encryption support.
    val self = currentDeviceProvider.get()
    val intro = HandshakeMessage(
      deviceId = self.shortDeviceId,
      deviceName = self.deviceName,
      osType = self.osType,
      deviceType = self.deviceType,
      supportsEncryption = true,
      listenPort = serverPort?.value ?: 0,
      claimsTrust = trustManager.isTrusted(request.deviceId),
    )
    log("Server", "Sending Klardrop greetings back to ${request.deviceId} on $remoteAddress")
    writeChannel.sendMessage(intro, serializer)

    // Run the UKEY2 handshake (responder role) and bind it to the peer's device identity before
    // any ConnectionMessenger exists, so every subsequent frame is encrypted. Bounded (F9) so a
    // peer that stalls mid-handshake can't hold this connection/coroutine open indefinitely —
    // mirrors the protocol-detection read timeout above and the client-side initiator bound.
    val cipher = try {
      withTimeout(UKEY2_HANDSHAKE_TIMEOUT_MS) {
        KlardropEncryptedTransport.runResponderHandshake(
          readChannel = readChannel,
          writeChannel = writeChannel,
          selfDeviceId = self.shortDeviceId,
          peerDeviceId = request.deviceId,
          trustManager = trustManager,
        )
      }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      log("Server", "UKEY2 responder handshake with ${request.deviceId} timed out — closing stalled peer")
      socket.close()
      return
    }

    val connection = Connection.Tcp(socket, request.deviceId)
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
    )

    connectionsPool.updateConnection(request.deviceId, connectionMessenger)
    recordInboundPeer(request, socket)

    log("Server", "Klardrop connection accepted from: $remoteAddress")

    // Start listening for incoming messages in a separate coroutine
    // This prevents blocking the connection establishment
    serverScope.launch {
      connectionMessenger.acceptIncomingMessages()
    }
    serverScope.launchStaleTrustRevocation(
      messenger = connectionMessenger,
      trustManager = trustManager,
      peerId = request.deviceId,
      peerClaimsTrust = request.claimsTrust,
    )
  }

  private suspend fun recordInboundPeer(handshake: HandshakeMessage, socket: Socket) {
    val address = socket.remoteAddress as? InetSocketAddress ?: return
    val host = address.extractNumericHost()
    if (host.isBlank()) return
    val port = handshake.listenPort
    if (port <= 0) {
      log("Server", "Inbound ${handshake.deviceId} from $host has no listenPort; not adding a dialable endpoint")
      return
    }
    runCatching {
      val existingDevice = visibleDevices.getDevice(handshake.deviceId)
      existingDevice?.deviceConnections?.filter {
        (it is DeviceConnection.KlardropConnection && it.address == host && it.port != port) ||
          (it is DeviceConnection.NearbyConnection && it.address == host && it.port != port)
      }?.forEach { stale ->
        visibleDevices.onDeviceLost(handshake.deviceId, stale)
      }
      visibleDevices.onNewDeviceVisible(
        DeviceInfo(
          deviceId = handshake.deviceId,
          name = handshake.deviceName.ifBlank { handshake.deviceId },
          deviceType = handshake.deviceType,
          osType = handshake.osType,
        ),
        DeviceConnection.KlardropConnection(host, port),
      )
      dropSupersededTrust(handshake.deviceId, host, visibleDevices, trustManager)
      log("Server", "Recorded inbound peer ${handshake.deviceId} @ $host:$port")
    }.onFailure {
      log("Server", "Failed recording inbound peer ${handshake.deviceId} @ $host:$port", it)
    }
  }

  /**
   * Handles a Nearby Share protocol connection.
   */
  private fun handleNearbyShareConnection(socket: Socket, firstMessage: ByteArray, remoteAddress: String, readChannel: ByteReadChannel) {
    log("Server", "Handling Nearby Share connection from: $remoteAddress")

    // Find the device info for this connection
    val device = visibleDevices.findDeviceByAddress(socket.remoteAddress as InetSocketAddress)
    val receiveFlow = messageReceiver.onReceiveMessage(device?.deviceInfo?.deviceId ?: "")

    val exceptionHandler = CoroutineExceptionHandler { _, exception ->
      // Nearby Share peers routinely close mid-frame (network drops, app
      // backgrounding, user cancel). Anything in the noise classifier is logged
      // locally; surprises still go to Sentry.
      if (exception.isExpectedNetworkNoise()) {
        logLocal("Server", "Nearby Share connection from $remoteAddress ended", exception)
      } else {
        log("Server", "Received exception on Nearby Share connection from $remoteAddress", exception)
      }
      socket.dispose()
      receiveFlow.update {
        it.copy(status = com.carlom.klardrop.common.receiver.ReceiveMessageStatus.Failed(exception.message ?: "Unknown error"))
      }
    }

    serverScope.launch(exceptionHandler) {
      val connectionRequest = OfflineFrame.ADAPTER.decode(firstMessage)

      val handler = createNearbyReceiver()
      // We'll need to modify the handler to accept a pre-parsed connection request
      handler.onConnection(socket, receiveFlow, connectionRequest, readChannel)
    }
  }

}

/**
 * Reads one length-prefixed frame and returns the (decrypted) payload bytes. With
 * [FrameCipher.Plain] this is the cleartext payload; with [FrameCipher.Encrypted] the on-wire
 * bytes are the ciphertext and [FrameCipher.decode] recovers the original payload. Called only
 * from the single read loop, so the cipher's receive sequence stays ordered.
 */
internal suspend fun ByteReadChannel.readByteArrayMessage(cipher: FrameCipher = FrameCipher.Plain): ByteArray {
  // Read message length first (4 bytes)
  val lengthBytes = ByteArray(4)
  readFully(lengthBytes)

  val messageLength = (lengthBytes[0].toInt() and 0xFF shl 24) or
      (lengthBytes[1].toInt() and 0xFF shl 16) or
      (lengthBytes[2].toInt() and 0xFF shl 8) or
      (lengthBytes[3].toInt() and 0xFF)

  // Read the actual (possibly encrypted) frame
  val wireBytes = ByteArray(messageLength)
  readFully(wireBytes)

  return cipher.decode(wireBytes)
}

/**
 * Marks a frame as a [BulkCipher] chunk rather than a normal `[type][protobuf]` message, set in
 * the top bit of the length prefix. Lengths are bounded far below 2^31, so the bit is free — and
 * putting the discriminator OUTSIDE the ciphertext is what lets the read loop pick an envelope
 * before it decrypts, without spending a whole extra byte on every frame.
 */
internal const val BULK_FRAME_FLAG: Int = 1 shl 31

internal suspend fun ByteReadChannel.readMessage(serializer: MessageSerializer, cipher: FrameCipher = FrameCipher.Plain): Message {
  val lengthBytes = ByteArray(4)
  readFully(lengthBytes)
  val prefix = (lengthBytes[0].toInt() and 0xFF shl 24) or
      (lengthBytes[1].toInt() and 0xFF shl 16) or
      (lengthBytes[2].toInt() and 0xFF shl 8) or
      (lengthBytes[3].toInt() and 0xFF)

  val wireBytes = ByteArray(prefix and BULK_FRAME_FLAG.inv())
  readFully(wireBytes)

  if (prefix and BULK_FRAME_FLAG != 0) {
    // Runs once per 256 KB chunk, so it only ever counts into [transferLog] — the two lines the
    // normal path used to emit per frame were themselves a measurable slice of a transfer's budget.
    val bulk = checkNotNull(cipher.bulk) { "Bulk frame received on a link with no bulk cipher" }
    transferLog.received(wireBytes.size)
    return bulk.open(wireBytes)
  }

  val messageBytes = cipher.decode(wireBytes)
  val message = serializer.deserialize(messageBytes)
  // Chunks are counted, not logged; everything else is rare enough to name individually.
  if (message.type == MessageType.FILE_CHUNK) {
    transferLog.received(messageBytes.size)
  } else {
    com.carlom.klardrop.common.utils.log(
      "ByteReadChannel",
      "[DEBUG] Read ${message.type} id=${message.id} class=${message::class.simpleName} (${messageBytes.size} bytes)",
    )
  }
  return message
}

/**
 * Writes one [BulkCipher] chunk frame: `[4-byte length | BULK_FRAME_FLAG][17-byte header][ciphertext]`.
 *
 * The header and ciphertext go out as two writes rather than being spliced into one array — the
 * whole point of this path is to touch the payload as few times as possible.
 *
 * THREADING: [BulkCipher.seal] advances the frame counter, so this must be called under the
 * connection's write mutex, same as [sendMessage].
 */
internal suspend fun ByteWriteChannel.sendBulkChunk(
  bulk: BulkCipher,
  fileMessageId: Int,
  seq: Int,
  isLast: Boolean,
  body: ByteArray,
  bodyLength: Int,
) {
  val sealed = bulk.seal(fileMessageId, seq, isLast, body, bodyLength)
  val prefix = sealed.wireSize or BULK_FRAME_FLAG
  val lengthBytes = ByteArray(4)
  lengthBytes[0] = (prefix ushr 24).toByte()
  lengthBytes[1] = (prefix ushr 16).toByte()
  lengthBytes[2] = (prefix ushr 8).toByte()
  lengthBytes[3] = prefix.toByte()

  writeByteArray(lengthBytes)
  writeByteArray(sealed.header)
  writeByteArray(sealed.ciphertext)
  transferLog.sent(sealed.wireSize)
}

/**
 * Serializes [message] to the `[1-byte type][protobuf]` payload, applies [cipher] (identity for
 * cleartext, AES-GCM for an encrypted link), and writes it length-prefixed.
 *
 * THREADING: with [FrameCipher.Encrypted] the encode step advances the context's send sequence
 * number, so this call MUST happen under the connection's write mutex together with the write —
 * callers already hold [ConnectionMessenger.writeLock] on every write path.
 */
internal suspend fun ByteWriteChannel.sendMessage(message: Message, serializer: MessageSerializer, cipher: FrameCipher = FrameCipher.Plain) {
  // Logged BEFORE the write, so a wire that wedges mid-send still names the message that did it.
  if (message.type != MessageType.FILE_CHUNK) {
    com.carlom.klardrop.common.utils.log(
      "ByteWriteChannel",
      "[DEBUG] Sending ${message.type} id=${message.id} class=${message::class.simpleName}",
    )
  }
  val wireBytes = cipher.encode(serializer.serialize(message))
  val introLengthBytes = ByteArray(4)
  introLengthBytes[0] = (wireBytes.size shr 24).toByte()
  introLengthBytes[1] = (wireBytes.size shr 16).toByte()
  introLengthBytes[2] = (wireBytes.size shr 8).toByte()
  introLengthBytes[3] = wireBytes.size.toByte()

  writeByteArray(introLengthBytes)
  writeByteArray(wireBytes)
  if (message.type == MessageType.FILE_CHUNK) transferLog.sent(wireBytes.size)
}

internal fun CoroutineScope.launchStaleTrustRevocation(
  messenger: ConnectionMessenger,
  trustManager: TrustManager,
  peerId: String,
  peerClaimsTrust: Boolean,
) {
  launch {
    val revocation = runCatching {
      revocationIfPeerStale(trustManager, peerId, peerClaimsTrust)
    }.onFailure {
      log("TrustHeal", "Failed building revocation for $peerId", it)
    }.getOrNull() ?: return@launch
    runCatching {
      val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 8)
      messenger.send(revocation.toSimpleSendRequest(), flow)
    }.onFailure {
      log("TrustHeal", "Failed sending connect-time revocation to $peerId", it)
    }
  }
}
