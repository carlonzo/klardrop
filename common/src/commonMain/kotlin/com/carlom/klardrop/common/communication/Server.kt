package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


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

  data class ServerConfig(val host: String, val port: Int)

  /**
   * Starts the server and returns the server configuration.
   *
   * @return The server configuration containing host and port.
   */
  suspend fun startServer(): ServerConfig {
    val selectorManager = SelectorManager(Dispatchers.Default)
    val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", 0)

    val localAddress = serverSocket.localAddress as InetSocketAddress
    val actualPort = localAddress.port
    val host = localAddress.hostname

    log("Server", "Server started on $host:$actualPort")

    coroutines.appScope.launch {
      while (true) {
        val socket = serverSocket.accept()
        val remoteAddress = socket.remoteAddress.toString()
        log("Server", "New connection from: $remoteAddress")

        coroutines.appScope.launch {
          try {
            onConnectionRequest(socket, remoteAddress)
          } catch (e: Exception) {
            log("Server", "Error handling connection from $remoteAddress", e)
            socket.close()
          }
        }
      }
    }

    return ServerConfig(host, actualPort)
  }

  private suspend fun onConnectionRequest(socket: Socket, remoteAddress: String) {
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel(autoFlush = true)

    val request = readChannel.readMessage(serializer) as HandshakeMessage

    log("Server", "Connection request from: $remoteAddress - ${request.deviceId}")

    if (isAcceptedSender(request.deviceId, remoteAddress)) {
      val connection = Connection(socket, request.deviceId)
      val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter, readChannel, writeChannel)

      connectionsPool.updateConnection(request.deviceId, connectionMessenger)

      // Send back introduction
      val deviceId = currentDeviceProvider.get().shortDeviceId
      val intro = HandshakeMessage(deviceId)
      log("Server", "Sending greetings back to ${request.deviceId} on $remoteAddress")

      writeChannel.sendMessage(intro, serializer)

      log("Server", "Connection accepted from: $remoteAddress")

      connectionMessenger.acceptIncomingMessages()
    } else {
      log("Server", "Connection rejected from: $remoteAddress")
      socket.close()
    }
  }

}

internal suspend fun ByteWriteChannel.sendMessage(message: Message, serializer: MessageSerializer) {
  val introBytes = serializer.serialize(message)
  val introLengthBytes = ByteArray(4)
  introLengthBytes[0] = (introBytes.size shr 24).toByte()
  introLengthBytes[1] = (introBytes.size shr 16).toByte()
  introLengthBytes[2] = (introBytes.size shr 8).toByte()
  introLengthBytes[3] = introBytes.size.toByte()

  writeFully(introLengthBytes)
  writeFully(introBytes)
}

internal suspend fun ByteReadChannel.readMessage(serializer: MessageSerializer): Message {
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

  return serializer.deserialize(messageBytes)
}