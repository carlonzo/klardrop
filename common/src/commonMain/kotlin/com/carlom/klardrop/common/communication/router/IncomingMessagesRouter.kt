package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.envelopes.TextEnvelope
import com.carlom.klardrop.common.utils.log

interface IncomingMessagesRouter {

  fun onMessageReceived(fromDeviceId: String, envelope: Envelope)

}

class IncomingMessagesRouterImpl : IncomingMessagesRouter {

  override fun onMessageReceived(fromDeviceId: String, envelope: Envelope) {

    when (envelope) {
      is TextEnvelope -> log("Received text message from $fromDeviceId: ${envelope.text}")

      else -> log("Received unknown message from $fromDeviceId: ${envelope.type}")
    }

  }

}