package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.flow.first
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CurrentDevice(
  private val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val osType: OsType,
){

  /**
   * Device id used during discovery
   */
  val shortDeviceId = deviceId.take(8)
}

@OptIn(ExperimentalUuidApi::class)
class CurrentDeviceProvider(
  private val localPropertiesRepository: LocalPropertiesRepository
) {
  suspend fun get(): CurrentDevice {
    val properties = localPropertiesRepository.properties.first()

    val deviceId = properties.deviceId.ifEmpty {
      val id = cleanDeviceId(Uuid.random().toString())
      localPropertiesRepository.save(properties.copy(deviceId = id))
      id
    }

    // Get system device name
    val systemDeviceName = CommonPlatformDependencies.getDeviceName()
    
    // Prioritize custom device name with fallback to system name
    val deviceName = properties.customDeviceName?.takeIf { it.isNotBlank() } ?: systemDeviceName
    
    val deviceType = CommonPlatformDependencies.deviceType()
    val osType = CommonPlatformDependencies.osType()

    return CurrentDevice(deviceId, deviceName, deviceType, osType)
  }

  /**
   * Updates the custom device name in local properties
   */
  suspend fun updateCustomDeviceName(customDeviceName: String?) {
    localPropertiesRepository.saveCustomDeviceName(customDeviceName)
  }

  /**
   * Cleans the device ID by removing any non-letter and non-digit characters,
   * and converts any uppercase letters to lowercase.
   *
   * @param deviceId The input device ID string to be cleaned.
   * @return The cleaned device ID string.
   */
  private fun cleanDeviceId(deviceId: String): String {
    return buildString {
      deviceId.forEach {
        if (it.isLetterOrDigit()) {
          append(it.lowercase())
        }
      }
    }
  }

}
