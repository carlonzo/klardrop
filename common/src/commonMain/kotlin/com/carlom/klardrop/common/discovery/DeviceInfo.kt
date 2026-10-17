package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.serialization.Serializable

@Serializable
public data class DeviceInfo(
  public val deviceId: String,
  public val name: String,
  public val deviceType: DeviceType,
  public val osType: OsType = OsType.UNKNOWN,
)

public data class DiscoveryDevice(
  public val deviceInfo: DeviceInfo,
  public val deviceConnections: List<DeviceConnection> = emptyList(),
  public val lastSeenTimestamp: Long
) {

  public fun hasNearbyConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.NEARBY }
  }

  public fun hasKlardropConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.KLARDROP }
  }

  public fun hasBleConnection(): Boolean {
    return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.BLE }
  }

  public fun getKlardropConnection(): List<DeviceConnection.KlardropConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.KlardropConnection>()
  }

  public fun getNearbyConnection(): List<DeviceConnection.NearbyConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.NearbyConnection>()
  }

  public fun getBleConnection(): List<DeviceConnection.BleConnection> {
    return deviceConnections.filterIsInstance<DeviceConnection.BleConnection>()
  }
}

public sealed interface DeviceConnection {

  public val deviceConnectionType: DeviceConnectionType
  public val address: String
  public val port: Int

  public data class NearbyConnection(
    override val address: String,
    override val port: Int
  ) : DeviceConnection {
    override val deviceConnectionType: DeviceConnectionType = DeviceConnectionType.NEARBY
  }

  public data class KlardropConnection(
    override val address: String,
    override val port: Int
  ) : DeviceConnection {
    override val deviceConnectionType: DeviceConnectionType = DeviceConnectionType.KLARDROP
  }

  /**
   * Bluetooth Low Energy transport. [address] is the platform-specific peripheral identifier:
   * BluetoothDevice MAC on Android/Linux/Windows, CBPeripheral.identifier UUID on Apple.
   * [port] is unused (always 0) — BLE does not have ports.
   */
  public data class BleConnection(
    override val address: String,
  ) : DeviceConnection {
    override val port: Int = 0
    override val deviceConnectionType: DeviceConnectionType = DeviceConnectionType.BLE
  }

  public enum class DeviceConnectionType {
    NEARBY,
    KLARDROP,
    BLE,
  }
}


