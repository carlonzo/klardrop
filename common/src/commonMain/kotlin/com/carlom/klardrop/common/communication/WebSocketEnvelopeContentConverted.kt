package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.envelopes.EnvelopeType
import com.carlom.klardrop.common.communication.envelopes.FileEnvelope
import com.carlom.klardrop.common.communication.envelopes.IntroductionEnvelope
import com.carlom.klardrop.common.communication.envelopes.TextEnvelope
import io.ktor.serialization.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.charsets.*
import io.ktor.websocket.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf

class WebSocketEnvelopeContentConverted(
  private val proto: ProtoBuf
) : WebsocketContentConverter {


  override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: Frame): Envelope {
    val idType = content.data[0]
    val type = EnvelopeType.fromId(idType)

    val data = content.data.sliceArray(1 until content.data.size)
    val serializer = serializer<Envelope>(type)

    return proto.decodeFromByteArray(serializer, data)
  }

  override suspend fun serialize(charset: Charset, typeInfo: TypeInfo, value: Any): Frame {

    val envelope = value as Envelope
    val type = envelope.type
    val serializer = serializer<Envelope>(type)

    val encodedEnvelope = proto.encodeToByteArray(serializer, envelope)

    val payload = ByteArray(encodedEnvelope.size + 1)
    payload[0] = envelope.type.id

    encodedEnvelope.copyInto(payload, 1, 0, encodedEnvelope.size)

    return Frame.Binary(true, payload)
  }

  override fun isApplicable(frame: Frame): Boolean {
    return frame.frameType == FrameType.BINARY
  }

  private fun <E : Envelope> serializer(envelopeType: EnvelopeType): KSerializer<E> {
    return when (envelopeType) {
      EnvelopeType.INTRO -> IntroductionEnvelope.serializer() as KSerializer<E>
      EnvelopeType.TEXT -> TextEnvelope.serializer() as KSerializer<E>
      EnvelopeType.FILE -> FileEnvelope.serializer() as KSerializer<E>
    }
  }
}