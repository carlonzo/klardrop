package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.SocketBroadcastUtility
import com.carlom.klardrop.common.log
import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.tickerFlow
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service that keeps emitting pings to announce availability and discover new devices or update info of the known ones
 */
class DiscoveryNetwork(
  private val coroutines: Coroutines,
  private val discoveryMessenger: DiscoveryMessenger,
  private val visibleDevices: VisibleDevices
) {

  private val discoveryScope = CoroutineScope(coroutines.ioDispatcher)

  fun start(): Job = discoveryScope.launch {

    log("Start discovery")

    val visibilityJob = launchVisibilityJob()

    SocketBroadcastUtility.listenToBroadcast(PORT)
      .cancellable()
      .collect { datagram ->
        val message = discoveryMessenger.decodeMessage(datagram.packet.readBytes())
        onNewDeviceDiscovered(message, datagram.address)
      }


    log("cancelling discovery")
    visibilityJob.cancel()
  }

  private fun launchVisibilityJob() = discoveryScope.launch {

    val sendChannel = SocketBroadcastUtility.sendMessageChannel(PORT, this)

    tickerFlow(PING_TIME)
      .cancellable()
      .collect {
        val message = discoveryMessenger.getIntroMessage()
        if (message.isNotEmpty()) sendChannel.send((message))
      }


    log("closed visibility job")
    sendChannel.close()
  }

  private suspend fun onNewDeviceDiscovered(discoveryMessage: DiscoveryMessenger.DiscoveryMessage, address: SocketAddress) {
    withContext(coroutines.ioDispatcher) {
      visibleDevices.onNewDeviceVisible(
        DeviceInfo(
          deviceId = discoveryMessage.deviceId,
          lastAddress = address.toString(),
          name = discoveryMessage.name,
          deviceType = discoveryMessage.deviceType
        )
      )
    }
  }


  private companion object {
    private const val PORT = 65321
    private val PING_TIME = 1500.milliseconds
  }

}
