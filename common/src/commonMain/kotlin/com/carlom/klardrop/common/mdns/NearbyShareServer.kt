package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NearbyShareServer(
  private val coroutines: Coroutines,
  private val nearbyReceiverConnectionHandlerFactory: NearbyReceiverConnectionHandlerFactory,
  private val visibleDevices: VisibleDevices,
  private val messageReceiver: MessageReceiver
) {

  private val nearbyShareScope = coroutines.newScope(coroutines.ioDispatcher + SupervisorJob())

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

      val device = visibleDevices.findDeviceByAddress(receive.remoteAddress as InetSocketAddress)

      val receiveFlow = messageReceiver.onReceiveMessage(device?.deviceInfo?.deviceId ?: "")

      val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        log("NearbyShareServer", "Received exception on connection", exception)
        receive.dispose()
      }

      nearbyShareScope.launch(exceptionHandler) {
        nearbyReceiverConnectionHandlerFactory.get().onConnection(receive, receiveFlow)
      }

    }

    log("NearbyShareServer", "Closing NearbyShareServer")
    serverSocket.close()
    selectorManager.close()
    status.update { it.copy(isRunning = false, port = 0) }

  }

  fun cancel() {
    nearbyShareScope.cancel()
  }

}

data class NearbyShareServerStatus(
  val port: Int,
  val isRunning: Boolean
)