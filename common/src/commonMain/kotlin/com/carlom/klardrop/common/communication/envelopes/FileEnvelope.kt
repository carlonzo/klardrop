package com.carlom.klardrop.common.communication.envelopes

class FileEnvelope(
  val fileName: String
) : StreamingEnvelope {

  override val type = EnvelopeType.FILE

}