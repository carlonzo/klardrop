package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
  val deviceId: String,
  val name: String,
  val deviceType: DeviceType,
)

data class DiscoveryDevice(
  val deviceInfo: DeviceInfo,
  val deviceConnections: Set<DeviceConnection> = emptySet()
) {

  fun hasNearbyConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.NEARBY }
  }

  fun hasKlardropConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.KLARDROP }
  }

  fun getKlardropConnection(): List<DeviceConnection.Klardrop> {
    return deviceConnections.filterIsInstance<DeviceConnection.Klardrop>()
  }

  fun getNearbyConnection(): List<DeviceConnection.Nearby> {
    return deviceConnections.filterIsInstance<DeviceConnection.Nearby>()
  }
}

sealed interface DeviceConnection {

  val deviceConnectionType: DeviceConnectionType

  data class Nearby(
    val address: String,
    val port: Int
  ) : DeviceConnection {
    override val deviceConnectionType = DeviceConnectionType.NEARBY
  }

  data class Klardrop(
    val address: String
  ) : DeviceConnection {
    override val deviceConnectionType = DeviceConnectionType.KLARDROP
  }

  enum class DeviceConnectionType {
    NEARBY,
    KLARDROP
  }
}


