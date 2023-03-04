package com.carlom.klardrop.common.communication.envelopes

import java.nio.charset.Charset

class TextEnvelope(
  private val text: String,
  private val charset: Charset = Charsets.UTF_8
) : StaticEnvelope {

  override val type: EnvelopeTypes = EnvelopeTypes.TEXT
  override val payload: ByteArray
    get() = text.toByteArray(charset = charset)
}