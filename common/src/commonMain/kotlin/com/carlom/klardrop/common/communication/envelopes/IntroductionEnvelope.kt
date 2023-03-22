package com.carlom.klardrop.common.communication.envelopes

import kotlinx.serialization.Serializable

@Serializable
data class IntroductionEnvelope(
  val deviceId: String,
) : Envelope.StaticEnvelope {
  override val type: EnvelopeType = EnvelopeType.INTRO
}
