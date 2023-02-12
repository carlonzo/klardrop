package com.carlom.klardrop.common

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.cio.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.use
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow

object SocketBroadcastUtility {

  fun listenToBroadcast(port: Int): Flow<String> = callbackFlow {
    val selectorManager = ActorSelectorManager(Dispatchers.IO)
    val socketAddress: SocketAddress = InetSocketAddress("0.0.0.0", port)

    log("Registering to listen on ${socketAddress}")

    val serverSocket = aSocket(selectorManager).udp().bind(localAddress = socketAddress)

    log("Listening on ${serverSocket.localAddress}")

    while (isActive) {
      val receive = serverSocket.receive()

      val text = receive.packet.readText()
      log("received: $text from: ${receive.address}")
      send(text)
    }

    awaitClose {
      log("Closing broadcast listener flow on ${socketAddress}")
      serverSocket.close()
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

  fun sendMessageChannel(port: Int): SendChannel<String> = Channel<String>(Channel.UNLIMITED).apply {
    val thisChannel = this
    val sendSockets = getSendSocket(port)

    val listenerJob = GlobalScope.launch {
      for (message in thisChannel) {
        sendSockets.send(
          Datagram(
            ByteReadPacket(message.encodeToByteArray()),
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

  private fun getSendSocket(port: Int): ConnectedDatagramSocket {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val socketAddress: SocketAddress = InetSocketAddress("255.255.255.255", port)

    return aSocket(selectorManager).udp().connect(socketAddress) {
      broadcast = true
    }
  }
}