package com.carlom.klardrop.common.communication.envelopes

import io.ktor.utils.io.charsets.*

enum class EnvelopeType(val id: Int) {

  INTRO(0), TEXT(1), FILE(2);

  companion object {
    fun fromId(id: Int): EnvelopeType {
      return values().first { it.id == id }
    }
  }

}

interface Envelope{
  val type: EnvelopeType
}

interface StaticEnvelope : Envelope {
  fun serialize(charset: Charset): ByteArray
}

interface StreamingEnvelope: Envelope