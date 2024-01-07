package com.carlom.klardrop.common.history

import kotlinx.coroutines.flow.Flow

interface HistoryDbDataSource {

  suspend fun getMessagesForDevice(deviceId: String, limit: Long = 10, offset: Long = 0): List<HistoryMessage>

  fun getMessagesForDeviceAsFlow(deviceId: String, limit: Long = 10, offset: Long = 0): Flow<List<HistoryMessage>>

  suspend fun insertMessage(deviceId: String, timestamp: Long, payload: HistoryMessagePayload)

  suspend fun deleteMessage(messageId: Long)

}