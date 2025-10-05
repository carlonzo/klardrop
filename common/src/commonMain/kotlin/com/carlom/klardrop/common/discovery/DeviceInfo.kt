package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
  val deviceId: String,
  val name: String,
  val deviceType: DeviceType,
  val osType: OsType = OsType.UNKNOWN,
)

data class DiscoveryDevice(
  val deviceInfo: DeviceInfo,
  val deviceConnections: List<DeviceConnection> = emptyList(),
  val lastSeenTimestamp: Long
) {

  fun hasNearbyConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.NEARBY }
  }

  fun hasKlardropConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.KLARDROP }
  }

  fun getKlardropConnection(): List<DeviceConnection.KlardropConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.KlardropConnection>()
  }

  fun getNearbyConnection(): List<DeviceConnection.NearbyConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.NearbyConnection>()
  }
}

sealed interface DeviceConnection {

  val deviceConnectionType: DeviceConnectionType
  val address: String
  val port: Int

  data class NearbyConnection(
    override val address: String,
    override val port: Int
  ) : DeviceConnection {
    override val deviceConnectionType = DeviceConnectionType.NEARBY
  }

  data class KlardropConnection(
    override val address: String,
    override val port: Int
  ) : DeviceConnection {
    override val deviceConnectionType = DeviceConnectionType.KLARDROP
  }

  enum class DeviceConnectionType {
    NEARBY,
    KLARDROP
  }
}


