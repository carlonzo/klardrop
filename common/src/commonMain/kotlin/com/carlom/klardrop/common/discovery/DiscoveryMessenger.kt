package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.DeviceTypesSerializer
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

class DiscoveryMessenger(
  private val coroutines: Coroutines,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val deviceName: String,
  private val deviceType: DeviceType
) {

  private val protoBuf = ProtoBuf
  private var introMessage: ByteArray = byteArrayOf()

  init {
    coroutines.appScope.launch {
      localPropertiesRepository.properties.mapLatest { it.deviceId }
        .collect { deviceId ->
          introMessage = protoBuf.encodeToByteArray(
            DiscoveryMessage(deviceId, deviceName, deviceType)
          )

          if (introMessage.size > 65500) {
            // udp package size must be below 65507 bytes
            throw IllegalArgumentException("Discovery message is too big ${introMessage.size}: ${protoBuf.decodeFromByteArray<DiscoveryMessage>(introMessage)}")
          }
        }
    }
  }

  fun getIntroMessage(): ByteArray {
    return introMessage
  }

  fun decodeMessage(message: ByteArray): DiscoveryMessage {
    return protoBuf.decodeFromByteArray(message)
  }

  @Serializable
  data class DiscoveryMessage(
    val deviceId: String,
    val name: String,
    val deviceType: DeviceType
  )

}