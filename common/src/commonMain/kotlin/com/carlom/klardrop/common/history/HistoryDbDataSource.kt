package com.carlom.klardrop.common.history

import com.carlom.klardrop.common.discovery.DeviceInfo
import kotlinx.coroutines.flow.Flow

interface HistoryDbDataSource {

  suspend fun getMessagesFromDevice(senderDeviceInfo: DeviceInfo, limit: Long = 10, offset: Long = 0): List<HistoryMessage>
  suspend fun getMessagesToDevice(receiverDeviceInfo: DeviceInfo, limit: Long = 10, offset: Long = 0): List<HistoryMessage>
  fun getMessagesFromDeviceAsFlow(senderDeviceInfo: DeviceInfo, limit: Long = 10, offset: Long = 0): Flow<List<HistoryMessage>>
  fun getMessagesToDeviceAsFlow(receiverDeviceInfo: DeviceInfo, limit: Long = 10, offset: Long = 0): Flow<List<HistoryMessage>>
  suspend fun insertMessage(senderDeviceId: String, receiverDeviceId: String, timestamp: Long, payload: HistoryMessagePayload)

  suspend fun deleteMessage(messageId: Long)

}