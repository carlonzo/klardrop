package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.tickerFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

interface VisibleDevices {

  val visibleDevices: Flow<Map<String, DeviceInfo>>

  fun onNewDeviceVisible(deviceInfo: DeviceInfo)

  suspend fun isDeviceVisible(deviceId: String): Boolean

  suspend fun getDeviceInfo(deviceId: String): DeviceInfo?

}

internal class VisibleDevicesImpl(
  private val coroutines: Coroutines,
  private val clock: Clock,
) : VisibleDevices {

  private val currentVisibleDevices = mutableMapOf<String, DeviceInfo>()
  private val visibleDevicesFlow = MutableStateFlow(emptyMap<String, DeviceInfo>())

  private val timeLastSeen = mutableMapOf<String, Long>()
  private val mutex = Mutex(locked = false)

  @Suppress("PrivatePropertyName")
  private val TTL_VISIBLE_DEVICES = 10.seconds.inWholeMilliseconds

  init {
//    Ticker to clean the visible devices every [TTL_VISIBLE_DEVICES]
    coroutines.appScope.launch {
      tickerFlow(delayDuration = 10.seconds)
        .onEach { }
        .flowOn(coroutines.ioDispatcher)
        .collect {
          mutex.withLock {
            val currentTime = clock.currentTimeMillis()
            val devicesToRemove = timeLastSeen.filterValues { currentTime - it > TTL_VISIBLE_DEVICES }

            if (devicesToRemove.isNotEmpty()) {
              devicesToRemove.forEach {
                currentVisibleDevices.remove(it.key)
                timeLastSeen.remove(it.key)
              }

              visibleDevicesFlow.emit(currentVisibleDevices)
              log("VisibleDevices cleanup. removed: $devicesToRemove")
            }
          }

        }
    }
  }

  override val visibleDevices: Flow<Map<String, DeviceInfo>> = visibleDevicesFlow.asStateFlow()
    .onEach { log("VisibleDevices flow. emitting: $it") }

  override fun onNewDeviceVisible(deviceInfo: DeviceInfo) {
    coroutines.appScope.launch {
      val isNew = addDevice(deviceInfo)

      if (isNew) log("VisibleDevices. new device: $currentVisibleDevices")
      visibleDevicesFlow.emit(currentVisibleDevices)
    }
  }

  override suspend fun isDeviceVisible(deviceId: String): Boolean {
    return mutex.withLock { currentVisibleDevices.containsKey(deviceId) }
  }

  override suspend fun getDeviceInfo(deviceId: String): DeviceInfo? {
    return mutex.withLock { currentVisibleDevices[deviceId] }
  }

  /**
   * @return true if the device was never seen before
   */
  private suspend fun addDevice(deviceInfo: DeviceInfo): Boolean {
    return coroutines.ioDispatcher {
      mutex.withLock {
        val containsAlready = currentVisibleDevices.containsKey(deviceInfo.deviceId)

        currentVisibleDevices[deviceInfo.deviceId] = deviceInfo
        timeLastSeen[deviceInfo.deviceId] = clock.currentTimeMillis()

        !containsAlready
      }
    }
  }

}