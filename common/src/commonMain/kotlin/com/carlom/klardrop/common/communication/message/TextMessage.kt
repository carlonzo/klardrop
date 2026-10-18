package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
public data class TextMessage(
  public val title: String = "",
  public val text: String,
  override val id: Int = Random.nextInt(),
) : Message() {

  override val type: MessageType = MessageType.TEXT
  override val hasPayload: Boolean = false
}

internal fun TextMessage.Companion.create(title: String = "", text: String): TextMessage = TextMessage(title, text)