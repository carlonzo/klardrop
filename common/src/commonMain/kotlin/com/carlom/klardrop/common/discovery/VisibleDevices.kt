package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch

interface VisibleDevices {

  val visibleDevices: Flow<Map<String, DiscoveryDevice>>

  fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection)

  suspend fun isDeviceVisible(deviceId: String): Boolean

  suspend fun getDevice(deviceId: String): DiscoveryDevice?

  suspend fun onDeviceLost(deviceId: String)
  suspend fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection)

}

internal class VisibleDevicesImpl(
  private val coroutines: Coroutines,
) : VisibleDevices {

  private val visibleDevicesFlow = MutableStateFlow(emptyMap<String, DiscoveryDevice>())


  override val visibleDevices: Flow<Map<String, DiscoveryDevice>> = visibleDevicesFlow.asStateFlow()
    .onEach { log("VisibleDevices flow. emitting: $it") }

  override fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
    coroutines.appScope.launch {
      val isNew = addDevice(deviceInfo, deviceConnection)

      if (isNew) log("VisibleDevices. new device: $deviceInfo")
    }
  }

  override suspend fun isDeviceVisible(deviceId: String): Boolean {
    return visibleDevicesFlow.value.containsKey(deviceId)
  }

  override suspend fun getDevice(deviceId: String): DiscoveryDevice? {
    return visibleDevicesFlow.value[deviceId]
  }

  override suspend fun onDeviceLost(deviceId: String) {
    visibleDevicesFlow.update {
      it.toMutableMap().also { map -> map.remove(deviceId) }
    }
  }

  override suspend fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {

    if (deviceConnectionToRemove is DeviceConnection.Nearby && deviceConnectionToRemove.port == 0 && deviceConnectionToRemove.address.isEmpty()) {
      onDeviceLost(deviceId)
      return
    }

    visibleDevicesFlow.update { currentMap ->
      currentMap.toMutableMap().also { map ->

        val device = map[deviceId] ?: return@also

        val newConnections = device.deviceConnections.filterNot { it == deviceConnectionToRemove }.toSet()

        if (newConnections.isEmpty()) {
          map.remove(deviceId)
        } else {
          map[deviceId] = device.copy(deviceConnections = newConnections)
        }

      }
    }
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

      val storedDiscoveryDevice = (visibleDevicesFlow.value[deviceInfo.deviceId] ?: DiscoveryDevice(deviceInfo))

      val newConnections = storedDiscoveryDevice.deviceConnections.toMutableSet().also { it.add(deviceConnection) }

      visibleDevicesFlow.update {
        it.toMutableMap().apply {

          put(
            deviceInfo.deviceId, storedDiscoveryDevice.copy(
              deviceConnections = newConnections
            )
          )
        }

      }

      !containsAlready
    }

  }

}