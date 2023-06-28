package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.UUIDGenerator
import kotlinx.coroutines.flow.first

data class CurrentDevice(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
)

class CurrentDeviceProvider(
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val internalPlatformDependency: InternalPlatformDependencies
) {
  suspend fun get(): CurrentDevice {
    val properties = localPropertiesRepository.properties.first()

    val deviceId = properties.deviceId.ifEmpty {
      val id = UUIDGenerator().generate().lowercase()
      localPropertiesRepository.save(properties.copy(deviceId = id))
      id
    }

    val deviceName = internalPlatformDependency.getDeviceName()
    val deviceType = internalPlatformDependency.deviceType()

    return CurrentDevice(deviceId, deviceName, deviceType)
  }

}
