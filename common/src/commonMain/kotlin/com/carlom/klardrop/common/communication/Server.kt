package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.logLocal
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.V1Frame
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
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
  private val nearbyReceiverConnectionHandlerFactory: NearbyReceiverConnectionHandlerFactory,
  private val visibleDevices: VisibleDevices,
  private val messageReceiver: MessageReceiver,
  private val protoBuf: ProtoBuf,
  private val trustManager: TrustManager,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
) {
  data class ServerConfig(val host: String, val port: Int)

  internal enum class Protocol {
    KLARDROP,
    NEARBY_SHARE
  }

  private val serverScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  /**
   * Starts the unified server that handles both protocols.
   *
   * @return ServerConfig containing the host and port the server is listening on
   */
  suspend fun startServer(): ServerConfig {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", 0)

    val localAddress = serverSocket.localAddress as InetSocketAddress
    val actualPort = localAddress.port
    val host = localAddress.hostname

    log("Server", "Unified server started on $host:$actualPort")

    serverScope.launch {
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

      log("Server", "Closing the server connection")
      serverSocket.close()
      selectorManager.close()
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
   * @param payload The complete first message including the 4-byte length prefix
   * @return The detected protocol
   * @throws IllegalArgumentException if the protocol cannot be determined
   */
  internal fun detectProtocol(payload: ByteArray): Protocol {
    if (payload.isEmpty()) {
      throw IllegalArgumentException("Message too short: ${payload.size} bytes")
    }

    // Check if the first payload byte matches Klardrop message types
    val potentialMessageType = payload[0]
    if (potentialMessageType in MessageType.entries.map { it.id }) {
      // Try to parse as Klardrop HandshakeMessage
      val handshakePayload = payload.sliceArray(1 until payload.size)
      protoBuf.decodeFromByteArray(HandshakeMessage.serializer(), handshakePayload)
      return Protocol.KLARDROP
    }

    // Try to parse as Nearby Share OfflineFrame

    val offlineFrame = OfflineFrame.ADAPTER.decode(payload)
    if (offlineFrame.v1?.type == V1Frame.FrameType.CONNECTION_REQUEST) {
      return Protocol.NEARBY_SHARE
    }

    throw IllegalArgumentException("Unable to detect protocol - message doesn't match Klardrop or Nearby Share format")
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

    if (!isAcceptedSender(request.deviceId, remoteAddress)) {
      log("Server", "Klardrop connection rejected from: $remoteAddress")
      socket.close()
      return
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
    )
    log("Server", "Sending Klardrop greetings back to ${request.deviceId} on $remoteAddress")
    writeChannel.sendMessage(intro, serializer)

    // Run the UKEY2 handshake (responder role) and bind it to the peer's device identity before
    // any ConnectionMessenger exists, so every subsequent frame is encrypted. Bounded (F9) so a
    // peer that stalls mid-handshake can't hold this connection/coroutine open indefinitely —
    // mirrors the protocol-detection read timeout above and the client-side initiator bound.
    val cipher = try {
      withTimeout(60_000L) { // DIAGKD: was TCP_CONNECT_TIMEOUT_MS — measure real native UKEY2 duration
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

    log("Server", "Klardrop connection accepted from: $remoteAddress")

    // Start listening for incoming messages in a separate coroutine
    // This prevents blocking the connection establishment
    serverScope.launch {
      connectionMessenger.acceptIncomingMessages()
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
      // locally; surprises still go to Bugsnag.
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

      val handler = nearbyReceiverConnectionHandlerFactory.get()
      // We'll need to modify the handler to accept a pre-parsed connection request
      handler.onConnection(socket, receiveFlow, connectionRequest, readChannel)
    }
  }

  /**
   * Connection-level admission control. The TCP handshake itself is open to anyone on
   * the local network — per-message authorization (trusted vs. prompt-the-user) lives
   * in the router and IncomingAuthorizer, not here, so we always let the connection
   * establish. This stays as a hook in case we ever want to block specific peers
   * (e.g. user-blocklist) before the handshake even completes.
   */
  @Suppress("UNUSED_PARAMETER")
  private fun isAcceptedSender(deviceId: String, receiverAddress: String): Boolean {
    return true
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

internal suspend fun ByteReadChannel.readMessage(serializer: MessageSerializer, cipher: FrameCipher = FrameCipher.Plain): Message {
  val messageBytes = readByteArrayMessage(cipher)
  com.carlom.klardrop.common.utils.log("ByteReadChannel", "[DEBUG] Read message: ${messageBytes.size} bytes")

  val message = serializer.deserialize(messageBytes)
  com.carlom.klardrop.common.utils.log("ByteReadChannel", "[DEBUG] Deserialized message: type=${message.type}, id=${message.id}, class=${message::class.simpleName}")
  return message
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
  com.carlom.klardrop.common.utils.log("ByteWriteChannel", "[DEBUG] Sending message: type=${message.type}, id=${message.id}, class=${message::class.simpleName}")
  val wireBytes = cipher.encode(serializer.serialize(message))
  val introLengthBytes = ByteArray(4)
  introLengthBytes[0] = (wireBytes.size shr 24).toByte()
  introLengthBytes[1] = (wireBytes.size shr 16).toByte()
  introLengthBytes[2] = (wireBytes.size shr 8).toByte()
  introLengthBytes[3] = wireBytes.size.toByte()

  writeByteArray(introLengthBytes)
  writeByteArray(wireBytes)
  com.carlom.klardrop.common.utils.log("ByteWriteChannel", "[DEBUG] Message sent successfully: ${wireBytes.size} bytes")
}
