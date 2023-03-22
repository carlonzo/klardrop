package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.envelopes.EnvelopeHandlers
import com.carlom.klardrop.common.communication.envelopes.FileEnvelope
import com.carlom.klardrop.common.communication.envelopes.TextEnvelope
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel

interface IncomingMessagesRouter {
  suspend fun onMessageReceived(fromDeviceId: String, envelope: Envelope, receiveChannel: ReceiveChannel<Frame>)
}

class IncomingMessagesRouterImpl(
  private val handlers: EnvelopeHandlers
) : IncomingMessagesRouter {

  override suspend fun onMessageReceived(fromDeviceId: String, envelope: Envelope, receiveChannel: ReceiveChannel<Frame>) {

    when (envelope) {
      is TextEnvelope -> log("Received text message from $fromDeviceId: ${envelope.text}")
      is FileEnvelope -> {
        handlers[envelope.type]?.handleIncoming(envelope, receiveChannel)
      }

      else -> log("Received unknown message from $fromDeviceId: $envelope type: ${envelope.type}")
    }

  }

}
