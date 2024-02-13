package com.carlom.klardrop.common.history

import com.carlom.klardrop.common.discovery.DeviceInfo
import kotlinx.serialization.Serializable

sealed interface HistoryMessagePayload {

  val type: MessagePayloadType

  @Serializable
  data class TextMessagePayload(val content: String) : HistoryMessagePayload {
    override val type: MessagePayloadType = MessagePayloadType.TextMessage
  }

  @Serializable
  data class FileMessagePayload(val fileName: String, val mimetype: String, val storagePath: String) : HistoryMessagePayload {
    override val type: MessagePayloadType = MessagePayloadType.FileMessage
  }

  enum class MessagePayloadType(val id: Long) {
    TextMessage(1L),
    FileMessage(2L);

    companion object {
      fun fromId(id: Long): MessagePayloadType {
        return entries.first { it.id == id }
      }
    }
  }

}

data class HistoryMessage(
  val id: Long,
  val device: DeviceInfo,
  val timestamp: Long,
  val payload: HistoryMessagePayload
)