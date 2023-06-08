package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.NonCancellable.isActive

class NearbyShareServer constructor(
  private val coroutines: Coroutines,
  private val nearbyConnectionHandler: NearbyConnectionHandler,
) {


  suspend fun start() {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)

    val socketAddress: InetSocketAddress = InetSocketAddress("0.0.0.0", 0)
    val serverSocket = aSocket(selectorManager).tcp().bind(localAddress = socketAddress)

    log("NearbyShareServer", "Starting server ${serverSocket.localAddress}")

    while (isActive) {
      val receive = serverSocket.accept()
      log("NearbyShareServer", "started receiving from: ${receive.remoteAddress}")

      nearbyConnectionHandler.onConnection(receive)
    }

    log("NearbyShareServer","Closing NearbyShareServer")
    serverSocket.close()
  }
}