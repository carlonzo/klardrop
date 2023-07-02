package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.tickerFlow
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Service that keeps emitting pings to announce availability and discover new devices or update info of the known ones
 */
class DiscoveryNetwork internal constructor(
  private val coroutines: Coroutines,
  private val discoveryMessenger: DiscoveryMessenger,
  private val visibleDevices: VisibleDevices,
  private val socketBroadcastUtility: SocketBroadcastUtility,
  private val clock: Clock,

  ) {

  private val discoveryScope = CoroutineScope(coroutines.ioDispatcher)
  private val timeLastSeen = mutableMapOf<String, Long>()

  init {
    discoveryScope.launch {
      tickerFlow(delayDuration = 10.seconds)
        .flowOn(coroutines.ioDispatcher)
        .collect {

          val currentTime = clock.currentTimeMillis()
          val devicesToRemove = timeLastSeen.filterValues { currentTime - it > TTL_VISIBLE_DEVICES }

          devicesToRemove.keys.forEach {
            visibleDevices.onDeviceLost(it)
          }

          log("VisibleDevices cleanup. removed: $devicesToRemove")
        }
    }
  }

  fun start(): Job = discoveryScope.launch {

    log("Start discovery")

    val visibilityJob = launchVisibilityJob()

    socketBroadcastUtility.listenToBroadcast(PORT)
      .cancellable()
      .collect { datagram ->
        val message = discoveryMessenger.decodeDiscoveryMessage(datagram.packet.readBytes())

        val isKnown = visibleDevices.isDeviceVisible(message.deviceId)
        onNewDeviceDiscovered(message, datagram.address)

        timeLastSeen[message.deviceId] = clock.currentTimeMillis()
        if (!isKnown) log("DiscoveryNetwork. discovered: ${datagram.address}")
      }


    log("cancelling discovery")
    visibilityJob.cancel()
  }

  private fun launchVisibilityJob() = discoveryScope.launch {

    val sendChannel = socketBroadcastUtility.sendMessageChannel(PORT, this)

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
          name = discoveryMessage.name,
          deviceType = discoveryMessage.deviceType
        ),
        DeviceConnection.Klardrop(address.cleanup())
      )
    }
  }


  private companion object {
    private const val PORT = 65321
    private val PING_TIME = 1500.milliseconds

    private val TTL_VISIBLE_DEVICES = 10.seconds.inWholeMilliseconds
  }

}

internal fun SocketAddress.cleanup(): String {
  return this.toString().removePrefix("/").substringBefore(":")
}