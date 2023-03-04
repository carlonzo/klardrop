package com.carlom.klardrop.common.communication.envelopes

class IntroductionEnvelope(private val deviceId: String): StaticEnvelope {
  override val payload: ByteArray
    get() = deviceId.toByteArray()

  override val type = EnvelopeTypes.INTRO
}