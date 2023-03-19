package com.carlom.klardrop.common.communication.envelopes

import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*


class TextEnvelope(
  val text: String
) : StaticEnvelope {
  override fun serialize(charset: Charset): ByteArray {
    return text.toByteArray(charset)
  }

  override val type = EnvelopeType.TEXT

  companion object {
    fun deserialize(payload: ByteArray, charset: Charset): TextEnvelope {
      return TextEnvelope(String(payload, charset = charset))
    }
  }
}