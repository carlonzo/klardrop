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

  fun hasBleConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.BLE }
  }

  fun getKlardropConnection(): List<DeviceConnection.KlardropConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.KlardropConnection>()
  }

  fun getNearbyConnection(): List<DeviceConnection.NearbyConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.NearbyConnection>()
  }

  fun getBleConnection(): List<DeviceConnection.BleConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.BleConnection>()
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

  /**
   * Bluetooth Low Energy transport. [address] is the platform-specific peripheral identifier:
   * BluetoothDevice MAC on Android/Linux/Windows, CBPeripheral.identifier UUID on Apple.
   * [port] is unused (always 0) — BLE does not have ports.
   */
  data class BleConnection(
    override val address: String,
  ) : DeviceConnection {
    override val port: Int = 0
    override val deviceConnectionType = DeviceConnectionType.BLE
  }

  enum class DeviceConnectionType {
    NEARBY,
    KLARDROP,
    BLE,
  }
}


