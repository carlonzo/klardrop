package com.carlom.klardrop.common.history

import com.carlom.klardrop.common.discovery.DeviceInfo
import kotlinx.serialization.Serializable

sealed interface HistoryMessagePayload {

  val type: Long

  @Serializable
  data class TextMessagePayload(val content: String) : HistoryMessagePayload {
    override val type: Long = TextMessagePayloadType
  }

  @Serializable
  data class FileMessagePayload(val fileName: String, val mimetype: String, val storagePath: String) : HistoryMessagePayload {
    override val type: Long = FileMessagePayloadType
  }

  companion object {
    const val TextMessagePayloadType = 1L
    const val FileMessagePayloadType = 2L
  }

}

data class HistoryMessage(
  val id: Long,
  val device: DeviceInfo, // maybe can be removed or replaced with DeviceInfo
  val timestamp: Long,
  val payload: HistoryMessagePayload
)