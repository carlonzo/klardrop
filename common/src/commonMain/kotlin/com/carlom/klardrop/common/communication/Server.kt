package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.errors.IOException // For specific IO error handling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException // For specific channel closed errors
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive


class Server(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  private val currentDeviceProvider: CurrentDeviceProvider
) {

  private fun isAcceptedSender(deviceId: String, receiverAddress: String): Boolean {
    return true // always accept for now. should only accept if known? or just hold the connection if known?
  }

  /**
   * Starts the TCP server and returns the host and port it's listening on.
   *
   * @return A Pair containing the host (String) and port (Int).
   */
  suspend fun startServer(): Pair<String, Int> {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val serverSocket = aSocket(selectorManager).tcp()
      .bind("0.0.0.0", 0) // Bind to all interfaces, OS assigns port

    val localAddress = serverSocket.localAddress as InetSocketAddress
    val host = localAddress.hostname
    val port = localAddress.port

    log("Server", "Server starting on $host:$port")

    coroutines.ioScope.launch {
      try {
        while (isActive) {
          log("Server", "Waiting for incoming connections...")
          val clientSocket = serverSocket.accept()
          val remoteAddress = clientSocket.remoteAddress.toString()
          log("Server", "New connection from: $remoteAddress")
          // Launch a new coroutine for each client to handle connection request
          // This prevents blocking the accept loop
          launch {
            onConnectionRequest(clientSocket, remoteAddress)
          }
        }
      } catch (e: Exception) {
        if (e is io.ktor.network.sockets.SocketClosedException || e is kotlinx.coroutines.CancellationException) {
          log("Server", "Server socket closed or coroutine cancelled, stopping accept loop.")
        } else {
          log("Server", "Error in server accept loop: ${e.message}", e)
        }
      } finally {
        if (!serverSocket.isClosed) {
          serverSocket.close()
        }
        selectorManager.close() // Close selectorManager when server stops
        log("Server", "Server stopped.")
      }
    }

    log("Server", "Server started and listening on $host:$port")
    return Pair(host, port)
  }

  private suspend fun onConnectionRequest(socket: Socket, remoteAddress: String) {
    val input = socket.openReadChannel()
    val output = socket.openWriteChannel(autoFlush = true) // autoFlush is important

    try {
      // Receive Handshake
      log("Server", "Awaiting handshake from $remoteAddress")
      val requestLength = input.readInt()
      if (requestLength <= 0 || requestLength > 1024 * 1024) { // Basic sanity check for length
          log("Server", "Invalid handshake length $requestLength from $remoteAddress. Closing.")
          socket.close()
          return
      }
      val requestBytes = ByteArray(requestLength)
      input.readFully(requestBytes, 0, requestLength)

      val receivedMessage = serializer.deserialize(requestBytes)

      if (receivedMessage !is HandshakeMessage) {
        log("Server", "Received non-Handshake message during handshake from $remoteAddress. Type: ${receivedMessage.type}. Closing.")
        socket.close()
        return
      }
      val request = receivedMessage // Smart cast
      log("Server", "Handshake received from: $remoteAddress - ${request.deviceId}")

      if (isAcceptedSender(request.deviceId, remoteAddress)) {
        // Send Handshake Response
        val currentDeviceId = currentDeviceProvider.get().shortDeviceId
        val handshakeResponse = HandshakeMessage(currentDeviceId)
        val serializedResponse = serializer.serialize(handshakeResponse)

        output.writeInt(serializedResponse.size)
        output.writeFully(serializedResponse)
        // output.flush() // autoFlush=true should handle this, but explicit flush can be added if needed.

        log("Server", "Sent handshake response to ${request.deviceId} on $remoteAddress. Connection accepted.")

        // Instantiate Connection and ConnectionMessenger, then start listening
        val connection = Connection(socket, request.deviceId)
        val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)
        connectionsPool.updateConnection(request.deviceId, connectionMessenger)

        log("Server", "Handing off connection for ${request.deviceId} to ConnectionMessenger.")
        // This is a suspending call and will keep the coroutine alive, processing messages.
        connectionMessenger.acceptIncomingMessages() 

        log("Server", "Connection for ${request.deviceId} on $remoteAddress finished or closed by messenger.")

      } else {
        log("Server", "Connection rejected for ${request.deviceId} from $remoteAddress.")
        socket.close()
      }
    } catch (e: ClosedReceiveChannelException) {
      log("Server", "Connection closed by peer $remoteAddress during handshake or early communication: ${e.message}", e)
      if (!socket.isClosed) socket.close()
    } catch (e: IOException) {
      log("Server", "IO error during handshake/communication with $remoteAddress: ${e.message}", e)
      if (!socket.isClosed) socket.close()
    } catch (e: SerializationException) { // Assuming MessageSerializer throws this or a custom one
        log("Server", "Serialization/Deserialization error with $remoteAddress: ${e.message}", e)
        if (!socket.isClosed) socket.close()
    } catch (e: Exception) {
      log("Server", "Unexpected error during connection request from $remoteAddress: ${e.message}", e)
      if (!socket.isClosed) socket.close()
    } finally {
      // If acceptIncomingMessages() finishes (e.g. connection closed), this block will execute.
      // We need to ensure the socket is closed if it hasn't been already by one of the error handlers
      // or by ConnectionMessenger itself.
      if (!socket.isClosed && !connectionsPool.isAvailable(socket.remoteAddress.toString())) { // A bit tricky to get deviceId here if handshake failed early
         // log("Server", "Ensuring socket for $remoteAddress is closed in finally block.")
         // socket.close() // Potentially redundant if ConnectionMessenger closes it, but can be a safeguard.
      }
    }
    // If acceptIncomingMessages is running, the socket is managed by ConnectionMessenger.
    // If handshake failed, socket is closed in catch blocks.
  }
}

// Helper for MessageSerializer if it throws a specific exception.
// For now, using a generic Exception in the catch block for serialization.
class SerializationException(message: String, cause: Throwable? = null) : Exception(message, cause)