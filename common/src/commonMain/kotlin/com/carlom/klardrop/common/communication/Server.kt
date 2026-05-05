package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
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

    // Read the first message to detect protocol
    val firstMessage = readChannel.readByteArrayMessage()

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

    if (isAcceptedSender(request.deviceId, remoteAddress)) {
      val writeChannel = socket.openWriteChannel(autoFlush = true)
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
      )

      connectionsPool.updateConnection(request.deviceId, connectionMessenger)

      // Send back introduction
      val self = currentDeviceProvider.get()
      val intro = HandshakeMessage(
        deviceId = self.shortDeviceId,
        deviceName = self.deviceName,
        osType = self.osType,
        deviceType = self.deviceType,
      )
      log("Server", "Sending Klardrop greetings back to ${request.deviceId} on $remoteAddress")

      writeChannel.sendMessage(intro, serializer)

      log("Server", "Klardrop connection accepted from: $remoteAddress")

      // Start listening for incoming messages in a separate coroutine
      // This prevents blocking the connection establishment
      serverScope.launch {
        connectionMessenger.acceptIncomingMessages()
      }
    } else {
      log("Server", "Klardrop connection rejected from: $remoteAddress")
      socket.close()
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
      log("Server", "Received exception on Nearby Share connection from $remoteAddress", exception)
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

internal suspend fun ByteReadChannel.readByteArrayMessage(): ByteArray {
  // Read message length first (4 bytes)
  val lengthBytes = ByteArray(4)
  readFully(lengthBytes)

  val messageLength = (lengthBytes[0].toInt() and 0xFF shl 24) or
      (lengthBytes[1].toInt() and 0xFF shl 16) or
      (lengthBytes[2].toInt() and 0xFF shl 8) or
      (lengthBytes[3].toInt() and 0xFF)

  // Read the actual message
  val messageBytes = ByteArray(messageLength)
  readFully(messageBytes)

  return messageBytes
}

internal suspend fun ByteReadChannel.readMessage(serializer: MessageSerializer): Message {
  val messageBytes = readByteArrayMessage()
  com.carlom.klardrop.common.utils.log("ByteReadChannel", "[DEBUG] Read message: ${messageBytes.size} bytes")
  
  val message = serializer.deserialize(messageBytes)
  com.carlom.klardrop.common.utils.log("ByteReadChannel", "[DEBUG] Deserialized message: type=${message.type}, id=${message.id}, class=${message::class.simpleName}")
  return message
}

internal suspend fun ByteWriteChannel.sendMessage(message: Message, serializer: MessageSerializer) {
  com.carlom.klardrop.common.utils.log("ByteWriteChannel", "[DEBUG] Sending message: type=${message.type}, id=${message.id}, class=${message::class.simpleName}")
  val introBytes = serializer.serialize(message)
  val introLengthBytes = ByteArray(4)
  introLengthBytes[0] = (introBytes.size shr 24).toByte()
  introLengthBytes[1] = (introBytes.size shr 16).toByte()
  introLengthBytes[2] = (introBytes.size shr 8).toByte()
  introLengthBytes[3] = introBytes.size.toByte()

  writeByteArray(introLengthBytes)
  writeByteArray(introBytes)
  com.carlom.klardrop.common.utils.log("ByteWriteChannel", "[DEBUG] Message sent successfully: ${introBytes.size} bytes")
}
