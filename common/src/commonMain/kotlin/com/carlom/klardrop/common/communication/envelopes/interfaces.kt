package com.carlom.klardrop.common.communication.envelopes

import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

enum class EnvelopeType(val id: Byte) {

  INTRO(0), TEXT(1), FILE(2);

  companion object {
    fun fromId(id: Byte): EnvelopeType {
      return values().first { it.id == id }
    }
  }

}

sealed interface Envelope {
  val type: EnvelopeType

  interface StaticEnvelope : Envelope
  interface StreamingEnvelope : Envelope
}


interface EnvelopeHandler<E : Envelope> {

  suspend fun handleIncoming(envelope: E, receiveChannel: ReceiveChannel<Frame>)

  suspend fun handleOutgoing(envelope: E, sendChannel: SendChannel<Frame>)

}