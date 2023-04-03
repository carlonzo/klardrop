package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable

@Serializable
data class TextMessage(
  val text: String
) : Message {

  override val type = MessageType.TEXT
  override val hasPayload: Boolean = false
}

fun TextMessage.Companion.create(text: String) = TextMessage(text)