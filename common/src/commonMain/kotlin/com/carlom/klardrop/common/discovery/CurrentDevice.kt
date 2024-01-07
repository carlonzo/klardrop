package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CurrentDevice(
  private val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val osType: OsType,
) {

  /**
   * Device id used during discovery
   */
  val shortDeviceId = deviceId.take(8)
}

@OptIn(ExperimentalUuidApi::class)
class CurrentDeviceProvider(
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val internalPlatformDependency: InternalPlatformDependencies,
  private val coroutines: Coroutines
) {
  private val initialDevice = CurrentDevice("", "", DeviceType.UNKNOWN, OsType.UNKNOWN)
  private val stateFlow: MutableStateFlow<CurrentDevice> = MutableStateFlow(initialDevice)

  init {
    coroutines.appScope.launch {
      val currentDevice = init()
      stateFlow.emit(currentDevice)
    }
  }

  suspend fun get(): CurrentDevice {
    return stateFlow.filter { it != initialDevice }.first()
  }

  val flow: StateFlow<CurrentDevice>
    get() = stateFlow.asStateFlow()

  private suspend fun init(): CurrentDevice {
    val properties = localPropertiesRepository.properties.first()

    val deviceId = properties.deviceId.ifEmpty {
      val id = cleanDeviceId(Uuid.random().toString())
      localPropertiesRepository.save(properties.copy(deviceId = id))
      id
    }

    val deviceName = internalPlatformDependency.getDeviceName()
    val deviceType = internalPlatformDependency.deviceType()
    val osType = internalPlatformDependency.osType()

    return CurrentDevice(deviceId, deviceName, deviceType, osType)
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
