package com.carlom.klardrop.common.communication.message

fun interface MessageHandlers {
  operator fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>?
}
