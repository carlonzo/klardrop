package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeVisibleDevices : VisibleDevices {

  val devices = mutableListOf<DiscoveryDevice>()
  private val _visibleDevices = MutableStateFlow<Map<String, DiscoveryDevice>>(emptyMap())

  init {
    devices.add(
      DiscoveryDevice(
        deviceInfo = DeviceInfo(
          "deviceid-123",
          "fake-device-name",
          deviceType = DeviceType.MOBILE,
          osType = OsType.ANDROID,
        ),
        lastSeenTimestamp = 0L
        )
    )
  }

  override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? {
    val hostname = address.hostname

    return devices.firstOrNull { device -> device.deviceConnections.any { it.address == hostname } }
  }

  override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>>
    get() = _visibleDevices

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
    error("not required to be implemented for this test")
  }

  override fun isDeviceVisible(deviceId: String): Boolean {
    return devices.any { it.deviceInfo.deviceId == deviceId }
  }

  override fun getDevice(deviceId: String): DiscoveryDevice? {
    return devices.firstOrNull { it.deviceInfo.deviceId == deviceId }
  }

  override fun cachedNameFor(deviceId: String): String? {
    val info = devices.firstOrNull { it.deviceInfo.deviceId == deviceId }?.deviceInfo
    return info?.name?.takeIf { it.isNotBlank() && it != info.deviceId }
  }

  override fun onDeviceLost(deviceId: String) {
    devices.removeAll { it.deviceInfo.deviceId == deviceId }
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {
    error("not required to be implemented for this test")
  }

  fun addKlardropDevice(deviceId: String, address: String, port: Int) {
    val deviceInfo = DeviceInfo(
      deviceId = deviceId,
      name = "Test Klardrop Device",
      deviceType = DeviceType.DESKTOP,
      osType = OsType.LINUX
    )

    val klardropConnection = DeviceConnection.KlardropConnection(address, port)
    val discoveryDevice = DiscoveryDevice(deviceInfo, listOf(klardropConnection), lastSeenTimestamp = 0L)

    devices.add(discoveryDevice)

    // Update the flow as well
    val currentDevices = _visibleDevices.value.toMutableMap()
    currentDevices[deviceId] = discoveryDevice
    _visibleDevices.value = currentDevices
  }

  fun addNearbyDevice(deviceId: String, address: String, port: Int) {
    val deviceInfo = DeviceInfo(
      deviceId = deviceId,
      name = "Test Nearby Device",
      deviceType = DeviceType.MOBILE,
      osType = OsType.ANDROID
    )

    val nearbyConnection = DeviceConnection.NearbyConnection(address, port)
    val discoveryDevice = DiscoveryDevice(deviceInfo, listOf(nearbyConnection), lastSeenTimestamp = 0L)

    devices.add(discoveryDevice)

    // Update the flow as well
    val currentDevices = _visibleDevices.value.toMutableMap()
    currentDevices[deviceId] = discoveryDevice
    _visibleDevices.value = currentDevices
  }

}