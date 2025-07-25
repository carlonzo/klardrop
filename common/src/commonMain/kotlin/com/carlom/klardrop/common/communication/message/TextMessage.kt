package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class TextMessage(
  val title: String = "",
  val text: String,
  override val id: Int = Random.nextInt(),
) : Message() {

  override val type = MessageType.TEXT
  override val hasPayload: Boolean = false
}

fun TextMessage.Companion.create(title: String = "", text: String) = TextMessage(title, text)