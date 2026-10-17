package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public data class CurrentDevice(
  public val deviceId: String,
  public val deviceName: String,
  public val deviceType: DeviceType,
  public val osType: OsType,
) {

  /**
   * Device id used during discovery
   */
  public val shortDeviceId: String = deviceId.take(8)
}

@OptIn(ExperimentalUuidApi::class)
public class CurrentDeviceProvider(
  private val localPropertiesRepository: LocalPropertiesRepository
) {

  private val mutex = Mutex()
  private var inMemoryDeviceId: String? = null

  /**
   * Flow that emits CurrentDevice whenever device properties change
   */
  public val deviceInfoFlow: Flow<CurrentDevice> = localPropertiesRepository.properties.map { properties ->
    val deviceId = if (properties.deviceId.isNotBlank()) {
      mutex.withLock {
        inMemoryDeviceId = properties.deviceId
        properties.deviceId
      }
    } else {
      mutex.withLock {
        inMemoryDeviceId ?: run {
          val id = cleanDeviceId(Uuid.random().toString())
          inMemoryDeviceId = id
          localPropertiesRepository.save(properties.copy(deviceId = id))
          id
        }
      }
    }

    // Prioritize custom device name with fallback to system name
    val deviceName = properties.customDeviceName?.takeIf { it.isNotBlank() } ?: CommonPlatformDependencies.getDeviceName()

    val deviceType = CommonPlatformDependencies.deviceType()
    val osType = CommonPlatformDependencies.osType()

    CurrentDevice(deviceId, deviceName, deviceType, osType)
  }

  public suspend fun get(): CurrentDevice {
    return deviceInfoFlow.first()
  }

  /**
   * Updates the custom device name in local properties
   */
  public suspend fun updateCustomDeviceName(customDeviceName: String?): Unit {
    localPropertiesRepository.saveCustomDeviceName(customDeviceName)
  }

  /**
   * Replace this device's persisted id with a fresh UUID (simulates uninstall/reinstall).
   * Discovery republishes automatically via [deviceInfoFlow]. Returns the new 8-char short id.
   */
  public suspend fun rotateDeviceId(): String {
    val id = cleanDeviceId(Uuid.random().toString())
    mutex.withLock {
      inMemoryDeviceId = id
      val properties = localPropertiesRepository.getProperty()
      localPropertiesRepository.save(properties.copy(deviceId = id))
    }
    return id.take(8)
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
