package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.envelopes.EnvelopeType
import com.carlom.klardrop.common.communication.envelopes.IntroductionEnvelope
import com.carlom.klardrop.common.communication.envelopes.TextEnvelope
import io.ktor.serialization.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.charsets.*
import io.ktor.websocket.*
import kotlinx.serialization.protobuf.ProtoBuf

class WebSocketEnvelopeContentConverted(
  private val proto: ProtoBuf
) : WebsocketContentConverter {
  override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: Frame): Envelope {
    val idType = content.data[0].toInt()

    val type = EnvelopeType.fromId(idType)
    val data = content.data.sliceArray(1 until content.data.size)

    return when (type) {
      EnvelopeType.INTRO -> IntroductionEnvelope.deserialize(data, charset)
      EnvelopeType.TEXT -> TextEnvelope.deserialize(data, charset)
      else -> throw IllegalArgumentException("Unknown envelope type $type. Cannot deserialize")
    }

  }

  override suspend fun serialize(charset: Charset, typeInfo: TypeInfo, value: Any): Frame {
    val envelope = value as Envelope
    val data = envelope.serialize(charset)

    val payload = ByteArray(data.size + 1)
    payload[0] = envelope.type.id.toByte()
    data.copyInto(payload, 1, 0, data.size)

    return Frame.Binary(true, payload)
  }

  override fun isApplicable(frame: Frame): Boolean {
    return frame.frameType == FrameType.BINARY
  }
}