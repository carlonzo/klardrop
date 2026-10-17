package com.carlom.klardrop.common.communication.message

internal fun interface MessageHandlers {
  operator fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>?
}
