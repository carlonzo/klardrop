package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.SocketBroadcastUtility
import com.carlom.klardrop.common.log
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service that keeps emitting pings to announce availability and discover new devices or update info of the known ones
 */
class DiscoveryNetwork(
  private val coroutines: Coroutines,
  private val localPropertiesRepository: LocalPropertiesRepository
) {

  private val discoveryScope = CoroutineScope(coroutines.ioDispatcher)

  fun start(): Flow<String> = flow {

    log("Start discovery")

    val visibilityJob = launchVisibilityJob()
    val currentFlowJob = currentCoroutineContext().job

    val receiveChannel = SocketBroadcastUtility.listenToBroadcast(PORT).produceIn(discoveryScope)

    whileSelect {

      receiveChannel.onReceive {
        emit(it)
        !receiveChannel.isClosedForReceive
      }

      currentFlowJob.onJoin {
        false
      }

    }

    log("cancelling discovery")
    visibilityJob.cancel()
    receiveChannel.cancel()
  }

  private fun launchVisibilityJob() = discoveryScope.launch {

    val sendChannel = SocketBroadcastUtility.sendMessageChannel(PORT, this)


    localPropertiesRepository.properties.mapLatest { it.deviceId }
      .combine(tickerFlow(PING_TIME)) { deviceId, _ -> deviceId }
      .cancellable()
      .collect { deviceId ->

        log("discovery sending $deviceId")
        sendChannel.send(deviceId)
      }


    log("closed visivility")
    sendChannel.close()
  }

  private fun tickerFlow(delayDuration: Duration = 500.milliseconds) = flow {

    while (currentCoroutineContext().isActive) {
      emit(Unit)

      delay(delayDuration)
    }

  }

  private companion object {
    private const val PORT = 65321
    private val PING_TIME = 1500.milliseconds
  }

}
