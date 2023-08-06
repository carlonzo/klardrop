package com.carlom.klardrop.common.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = DeviceTypesSerializer::class)
enum class DeviceType(val id: Byte) {
  MOBILE(1), DESKTOP(2), UNKNOWN(3);

  companion object {
    fun fromId(id: Byte): DeviceType {
      return DeviceType.values().first { it.id == id }
    }

    fun fromId(id: Int): DeviceType {
      val b = id.toByte()
      return DeviceType.values().first { it.id == b }
    }
  }
}

internal class DeviceTypesSerializer : KSerializer<DeviceType> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DeviceType", PrimitiveKind.BYTE)

  override fun deserialize(decoder: Decoder): DeviceType {
    return DeviceType.fromId(decoder.decodeByte())
  }

  override fun serialize(encoder: Encoder, value: DeviceType) {
    encoder.encodeByte(value.id)
  }

}