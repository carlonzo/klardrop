package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.network.NetworkAddressUtil
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SocketBroadcastUtility(
  private val coroutines: Coroutines,
  private val networkAddressUtil: NetworkAddressUtil
) {

  fun listenToBroadcast(port: Int): Flow<Datagram> = callbackFlow {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val socketAddress: SocketAddress = InetSocketAddress("0.0.0.0", port)

    val localAddresses = networkAddressUtil.getLocalAddresses()

    val serverSocket = aSocket(selectorManager).udp().bind(localAddress = socketAddress)

    log("Listening on ${serverSocket.localAddress}")

    while (isActive) {

      val receive = serverSocket.receive()
      val cleanedReceiverAddress = receive.address.toString()

      if (cleanedReceiverAddress in localAddresses) {
        continue
      }

      send(receive)
    }

    awaitClose {
      log("Closing broadcast listener flow on $socketAddress")
      serverSocket.close()
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

  private fun getSendSocket(port: Int): ConnectedDatagramSocket {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val socketAddress: SocketAddress = InetSocketAddress("255.255.255.255", port)

    return aSocket(selectorManager).udp().connect(socketAddress) {
      broadcast = true
    }
  }

}