package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.log
import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DefaultExecutor.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface

private const val SERVER_PORT = 65221
class SocketServer(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val flow: Flow<Set<DeviceInfo>>,
) {

  private val serverScope = CoroutineScope(coroutines.ioDispatcher)
  private val knownDevices = flow.stateIn(serverScope, started = SharingStarted.Eagerly, initialValue = emptySet())
  private fun isAcceptedSender(receiverAddress: String): Boolean {
    // TODO should notify the user if wants to accept the connection
    return true
  }

  fun startServer(): Job {
    val selectorManager = ActorSelectorManager(coroutines.ioDispatcher)
    val socketAddress: SocketAddress = InetSocketAddress(InetAddress.getLocalHost().hostName, SERVER_PORT)

    val serverSocket = aSocket(selectorManager).tcp().bind(localAddress = socketAddress)

    log("Starting server on ${serverSocket.localAddress}")

    return serverScope.launch {

      while (isActive && !serverSocket.isClosed) {
        val socket = serverSocket.accept()

        log("Server received connection from: ${socket.remoteAddress}")
        onStartedConnectionWith(socket)
      }

      serverSocket.awaitClosed()
      connectionsPool.closeAllConnections()
    }
  }

  private fun onStartedConnectionWith(socket: Socket) {
    serverScope.launch {

      val readChannel = socket.openReadChannel()

      readChannel.cancel()




    }

  }

  suspend fun sendMessage(text: String, port: Int) {
    val sendSockets = getSendSocket(port)

    log("Sending packet to: ${sendSockets.remoteAddress}")
    val datagram = Datagram(
      ByteReadPacket(text.encodeToByteArray()),
      sendSockets.remoteAddress
    )

    sendSockets.use {
      it.send(datagram)
    }
  }

  fun sendMessageChannel(port: Int, coroutineScope: CoroutineScope = GlobalScope): SendChannel<ByteArray> =
    Channel<ByteArray>(Channel.UNLIMITED).apply {
      val thisChannel = this
      val sendSockets = getSendSocket(port)

      val listenerJob = coroutineScope.launch {
        for (message in thisChannel) {
          sendSockets.send(
            Datagram(
              ByteReadPacket(message),
              sendSockets.remoteAddress
            )
          )
        }
      }

      invokeOnClose {
        sendSockets.close()
        listenerJob.cancel()
      }
    }



  private fun getLocalAddresses(): Set<String> {
    return NetworkInterface.getNetworkInterfaces().asSequence()
      .filterNot { it.isLoopback || it.isVirtual }
      .flatMap { networkInterface ->
        networkInterface.inetAddresses.asSequence()
          .map { inet -> inet.hostAddress }.filterNot { address -> address.contains(char = ':') }
      }.toSet()
  }

  private fun SocketAddress.cleanup(): String{
    return toJavaAddress().toString().substringAfter('/').substringBefore(':')
  }
}