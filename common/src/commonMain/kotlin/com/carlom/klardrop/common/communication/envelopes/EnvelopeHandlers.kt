package com.carlom.klardrop.common.communication.envelopes

interface EnvelopeHandlers {
  operator fun get(envelopeType: EnvelopeType): EnvelopeHandler<Envelope>?
}


internal class EnvelopeHandlersImpl(
  private val handlers: Map<EnvelopeType, EnvelopeHandler<*>>
) : EnvelopeHandlers {
  override operator fun get(envelopeType: EnvelopeType): EnvelopeHandler<Envelope>? {
    return handlers[envelopeType] as EnvelopeHandler<Envelope>?
  }
}