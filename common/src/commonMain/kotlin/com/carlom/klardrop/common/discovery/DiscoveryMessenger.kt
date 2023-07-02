package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

internal class DiscoveryMessenger(
  coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
) {

  private val protoBuf = ProtoBuf
  private var introMessage = coroutines.appScope.async {
    val currentDevice = currentDeviceProvider.get()

    val introMessage = protoBuf.encodeToByteArray(
      DiscoveryMessage(currentDevice.deviceId, currentDevice.deviceName, currentDevice.deviceType)
    )

    if (introMessage.size > 65500) {
      // udp package fileSize must be below 65507 bytes
      throw IllegalArgumentException(
        "Discovery message is too big ${introMessage.size}: ${
          protoBuf.decodeFromByteArray<DiscoveryMessage>(
            introMessage
          )
        }"
      )
    }

    introMessage
  }


  suspend fun getIntroMessage(): ByteArray {
    return introMessage.await()
  }

  fun decodeDiscoveryMessage(message: ByteArray): DiscoveryMessage {
    return protoBuf.decodeFromByteArray(message)
  }

  @Serializable
  data class DiscoveryMessage(
    val deviceId: String,
    val name: String,
    val deviceType: DeviceType
  )

}