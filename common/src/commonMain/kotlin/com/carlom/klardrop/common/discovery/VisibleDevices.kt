package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

interface VisibleDevices {

  val visibleDevices: StateFlow<Map<String, DiscoveryDevice>>

  suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection)

  fun isDeviceVisible(deviceId: String): Boolean

  fun getDevice(deviceId: String): DiscoveryDevice?

  fun onDeviceLost(deviceId: String)
  fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection)

  fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice?

}

internal class VisibleDevicesImpl(
  private val coroutines: Coroutines,
  private val clock: Clock
) : VisibleDevices {

  private companion object {
    val deviceTTLVisibility = 90.seconds
  }

  private val visibleDevicesFlow = MutableStateFlow(emptyMap<String, DiscoveryDevice>())

  override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = visibleDevicesFlow.asStateFlow()

  private val cleanupScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  init {
    // Start periodic cleanup of stale devices
    cleanupScope.launch {
      while (isActive) {
        delay(30.seconds)
        cleanupStaleDevices()
      }
    }
  }

  private fun cleanupStaleDevices() {
    val currentTime = clock.currentTimeMillis()

    val staleDevices = visibleDevicesFlow.value.filter { (deviceId, device) ->
      val isStale = (currentTime - device.lastSeenTimestamp) > deviceTTLVisibility.inWholeMilliseconds
      if (isStale) {
        log(
          "VisibleDevices",
          "Removing stale device: ${device.deviceInfo.name} (id: $deviceId), last seen ${(currentTime - device.lastSeenTimestamp) / 1000}s ago"
        )
      }
      isStale
    }

    if (staleDevices.isNotEmpty()) {
      visibleDevicesFlow.update { currentMap ->
        currentMap.toMutableMap().apply {
          staleDevices.keys.forEach { remove(it) }
        }
      }
    }
  }

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {

    val isNew = addDevice(deviceInfo, deviceConnection)

    if (isNew)
      log("VisibleDevices", "new device: $deviceInfo isNew: $isNew connections: ${deviceConnection.deviceConnectionType}")
  }

  override fun isDeviceVisible(deviceId: String): Boolean {
    return visibleDevicesFlow.value.containsKey(deviceId)
  }

  override fun getDevice(deviceId: String): DiscoveryDevice? {
    return visibleDevicesFlow.value[deviceId]
  }

  override fun onDeviceLost(deviceId: String) {
    visibleDevicesFlow.update {
      it.toMutableMap().also { map -> map.remove(deviceId) }
    }
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {

    if (deviceConnectionToRemove is DeviceConnection.NearbyConnection && deviceConnectionToRemove.port == 0 && deviceConnectionToRemove.address.isEmpty()) {
      onDeviceLost(deviceId)
      return
    }

    visibleDevicesFlow.update { currentMap ->
      currentMap.toMutableMap().also { map ->

        val device = map[deviceId] ?: return@also

        val newConnections = device.deviceConnections.filterNot { it == deviceConnectionToRemove }

        if (newConnections.isEmpty()) {
          map.remove(deviceId)
        } else {
          map[deviceId] = device.copy(deviceConnections = newConnections)
        }

      }
    }
  }

  override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? {
    val hostname = address.hostname

    return visibleDevicesFlow.value.values.firstOrNull { device -> device.deviceConnections.any { it.address == hostname } }
  }

  /**
   * @return true if the device was never seen before
   */
  private suspend fun addDevice(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection): Boolean {
    return coroutines.ioDispatcher {
      val containsAlready = visibleDevicesFlow.value.containsKey(deviceInfo.deviceId)

      if (containsAlready) {

        if (visibleDevicesFlow.value.getValue(deviceInfo.deviceId).deviceConnections.contains(deviceConnection)) {
          return@ioDispatcher false
        }

      }

      visibleDevicesFlow.update {
        val now = clock.currentTimeMillis()
        val storedDiscoveryDevice = (it[deviceInfo.deviceId] ?: DiscoveryDevice(deviceInfo, lastSeenTimestamp = now))

        val newConnections = storedDiscoveryDevice.deviceConnections
          // removes connections same connection type and address. Probably new connection with new port that did not expire yet from mdns
          .filterNot { it.deviceConnectionType == deviceConnection.deviceConnectionType && it.address == deviceConnection.address }
          .toMutableList().also { it.add(deviceConnection) }

        it.toMutableMap().apply {

          put(
            deviceInfo.deviceId, storedDiscoveryDevice.copy(
              deviceConnections = newConnections,
              lastSeenTimestamp = now
            )
          )
        }

      }

      !containsAlready
    }

  }

}