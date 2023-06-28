package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NearbyShareServer constructor(
  private val coroutines: Coroutines,
  private val nearbyConnectionHandler: NearbyConnectionHandler,
) {

  private val nearbyShareScope = CoroutineScope(coroutines.ioDispatcher)

  val status = MutableStateFlow(
    NearbyShareServerStatus(
      port = 0,
      isRunning = false
    )
  )

  fun start(): Job = nearbyShareScope.launch {

    val selectorManager = SelectorManager(coroutines.ioDispatcher)

    val socketAddress = InetSocketAddress("0.0.0.0", 0)
    val serverSocket = aSocket(selectorManager).tcp().bind(localAddress = socketAddress)

    log("NearbyShareServer", "Starting server ${serverSocket.localAddress}")

    status.update { it.copy(port = (serverSocket.localAddress as InetSocketAddress).port, isRunning = true) }

    while (isActive) {
      val receive = serverSocket.accept()
      log("NearbyShareServer", "started receiving from: ${receive.remoteAddress}")

      nearbyConnectionHandler.onConnection(receive)
    }

    log("NearbyShareServer", "Closing NearbyShareServer")
    serverSocket.close()
    status.update { it.copy(isRunning = false, port = 0) }
  }

}

data class NearbyShareServerStatus(
  val port: Int,
  val isRunning: Boolean
)