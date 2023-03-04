package com.carlom.klardrop.common.communication.envelopes

import okio.BufferedSource

enum class EnvelopeTypes(val type: Int){

  INTRO(0), TEXT(1), FILE(2)

}

interface Envelope {
  val type: EnvelopeTypes
}

interface StaticEnvelope : Envelope {
  val payload: ByteArray
}

interface StreamEnvelope : Envelope {
  val payload: BufferedSource
}

val envelopesRegistry = setOf<Envelope>(

)