package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

class NearbyClient(
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val fileManager: FileManager,
) {

  @Throws(IOException::class, CancellationException::class)
  suspend fun send(host: String, port: Int, sendRequests: List<SendMessageRequest>, sendFlow: MutableSharedFlow<MessengerSendProgress>) {

    val serverSocket = runCatching {
      val selectorManager = SelectorManager(coroutines.ioDispatcher)

      val socketAddress = InetSocketAddress(host, port)
      aSocket(selectorManager).tcp().connect(remoteAddress = socketAddress)
    }.onFailure {
      throw IOException("Failed to connect to $host:$port", it)
    }.getOrThrow()


    // client is stateful
    try {
      NearbyClientConnectionHandler(currentDeviceProvider, fileManager, sendRequests)
        .onConnection(serverSocket, sendFlow)
    } finally {
      serverSocket.close()
    }

  }


}