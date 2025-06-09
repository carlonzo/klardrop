package com.carlom.klardrop.common.communication.message

import com.benasher44.uuid.UUID
import kotlinx.serialization.Serializable

@Serializable
data class TextMessage(
  override val messageId: String = UUID.randomUUID().toString(),
  val title: String = "",
  val text: String
) : Message {

  override val type = MessageType.TEXT
  override val hasPayload: Boolean = false
}

fun TextMessage.Companion.create(title: String = "", text: String) = TextMessage(text = text, title = title)