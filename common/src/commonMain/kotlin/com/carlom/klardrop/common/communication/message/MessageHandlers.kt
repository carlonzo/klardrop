package com.carlom.klardrop.common.communication.message

interface MessageHandlers {
  operator fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>?
}


internal class MessageHandlersImpl(
  private val handlers: Map<MessageType, MessageHandler<*, *>>
) : MessageHandlers {
  @Suppress("UNCHECKED_CAST")
  override operator fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>? {
    return handlers[messageType] as MessageHandler<Message, SendMessageRequest>?
  }
}