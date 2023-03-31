package com.carlom.klardrop.common.communication.message

interface EnvelopeHandlers {
  operator fun get(messageType: MessageType): EnvelopeHandler<Message, SendMessageRequest>?
}


internal class EnvelopeHandlersImpl(
  private val handlers: Map<MessageType, EnvelopeHandler<*, *>>
) : EnvelopeHandlers {
  @Suppress("UNCHECKED_CAST")
  override operator fun get(messageType: MessageType): EnvelopeHandler<Message, SendMessageRequest>? {
    return handlers[messageType] as EnvelopeHandler<Message, SendMessageRequest>?
  }
}