package com.carlom.klardrop.common.communication.envelopes

import io.ktor.utils.io.charsets.*

enum class EnvelopeTypes(val id: Int) {

  INTRO(0), TEXT(1), FILE(2);
  companion object {
    fun fromId(id: Int): EnvelopeTypes {
      return values().first { it.id == id }
    }
  }

}


interface Envelope {
  val type: EnvelopeTypes
  fun serialize(charset: Charset): ByteArray
}