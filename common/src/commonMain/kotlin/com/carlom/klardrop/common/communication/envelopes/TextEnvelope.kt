package com.carlom.klardrop.common.communication.envelopes

import kotlinx.serialization.Serializable

@Serializable
data class TextEnvelope(
  val text: String
) : Envelope.StaticEnvelope {

  override val type = EnvelopeType.TEXT
}