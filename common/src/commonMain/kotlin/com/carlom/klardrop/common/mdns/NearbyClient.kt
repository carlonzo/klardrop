package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.network.selector.*
import io.ktor.network.sockets.*

class NearbyClient(
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val internalPlatformDependencies: InternalPlatformDependencies,
  private val fileManager: FileManager,
) {

  suspend fun send(host: String, port: Int, sendRequests: List<SendMessageRequest>) {

    val selectorManager = SelectorManager(coroutines.ioDispatcher)

    val socketAddress = InetSocketAddress(host, port)
    val serverSocket = aSocket(selectorManager).tcp().connect(remoteAddress = socketAddress)

    // client is stateful
    NearbyClientConnectionHandler(currentDeviceProvider, internalPlatformDependencies, fileManager, sendRequests)
      .onConnection(serverSocket)
  }


}