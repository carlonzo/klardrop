package com.carlom.klardrop.common.communication.envelopes

import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*

class IntroductionEnvelope(
  val deviceId: String,
): StaticEnvelope {
  override fun serialize(charset: Charset) = deviceId.toByteArray(Charsets.UTF_8)

  override val type = EnvelopeType.INTRO

  companion object {
    fun deserialize(payload: ByteArray, charset: Charset): IntroductionEnvelope {
      return IntroductionEnvelope(String(payload, charset = charset))
    }
  }
}