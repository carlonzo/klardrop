package com.carlom.klardrop.common.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.klardrop.common.persistence.KlardropDatabase
import com.klardrop.common.persistence.Messages_db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.invoke
import kotlinx.serialization.protobuf.ProtoBuf

internal class HistoryDbDataSourceImpl(
  private val coroutines: Coroutines,
  private val protoBuf: ProtoBuf,
  klardropDatabase: Lazy<KlardropDatabase>
) : HistoryDbDataSource {

  private val messagesQueries by lazy { klardropDatabase.value.messagesQueries }

  override suspend fun getMessagesFromDevice(senderDeviceInfo: DeviceInfo, limit: Long, offset: Long): List<HistoryMessage> =
    coroutines.ioDispatcher {
      val dbMessages = messagesQueries.select_by_sender_device_id(senderDeviceInfo.deviceId, limit, offset).awaitAsList()

      dbMessages.map { dbMessage -> dbMessage.toHistoryMessage(senderDeviceInfo) }
    }

  override suspend fun getMessagesToDevice(receiverDeviceInfo: DeviceInfo, limit: Long, offset: Long): List<HistoryMessage> {
    val dbMessages = messagesQueries.select_by_receiver_device_id(receiverDeviceInfo.deviceId, limit, offset).awaitAsList()

    return dbMessages.map { dbMessage -> dbMessage.toHistoryMessage(receiverDeviceInfo) }
  }

  override fun getMessagesFromDeviceAsFlow(senderDeviceInfo: DeviceInfo, limit: Long, offset: Long): Flow<List<HistoryMessage>> {
    return messagesQueries.select_by_sender_device_id(senderDeviceInfo.deviceId, limit, offset)
      .asFlow().mapToList(coroutines.ioDispatcher)
      .map { messagesList -> messagesList.map { it.toHistoryMessage(senderDeviceInfo) } }
  }

  override fun getMessagesToDeviceAsFlow(receiverDeviceInfo: DeviceInfo, limit: Long, offset: Long): Flow<List<HistoryMessage>> {
    return messagesQueries.select_by_receiver_device_id(receiverDeviceInfo.deviceId, limit, offset)
      .asFlow().mapToList(coroutines.ioDispatcher)
      .map { messagesList -> messagesList.map { it.toHistoryMessage(receiverDeviceInfo) } }
  }

  override suspend fun insertMessage(senderDeviceId: String, receiverDeviceId: String, timestamp: Long, payload: HistoryMessagePayload) {
    val byteArrayPayload = serializePayload(payload)

    messagesQueries.insert_message(
      sender_device_id = senderDeviceId,
      receiver_device_id = receiverDeviceId,
      message_type = payload.type.id,
      timestamp = timestamp,
      payload = byteArrayPayload
    )
  }

  override suspend fun deleteMessage(messageId: Long) = coroutines.ioDispatcher {
    messagesQueries.delete_message(messageId)
  }

  private fun Messages_db.toHistoryMessage(deviceInfo: DeviceInfo): HistoryMessage {
    val payload = payload.toHistoryMessagePayload(message_type)
    return HistoryMessage(
      id = id,
      device = deviceInfo,
      timestamp = timestamp,
      payload = payload
    )
  }

  private fun serializePayload(payload: HistoryMessagePayload): ByteArray {
    return when (payload) {
      is HistoryMessagePayload.TextMessagePayload -> protoBuf.encodeToByteArray(
        HistoryMessagePayload.TextMessagePayload.serializer(),
        payload
      )

      is HistoryMessagePayload.FileMessagePayload -> protoBuf.encodeToByteArray(
        HistoryMessagePayload.FileMessagePayload.serializer(),
        payload
      )
    }
  }

  private fun ByteArray.toHistoryMessagePayload(type: Long): HistoryMessagePayload {
    val serializer = when (HistoryMessagePayload.MessagePayloadType.fromId(type)) {
      HistoryMessagePayload.MessagePayloadType.TextMessage -> HistoryMessagePayload.TextMessagePayload.serializer()
      HistoryMessagePayload.MessagePayloadType.FileMessage -> HistoryMessagePayload.FileMessagePayload.serializer()
    }

    return protoBuf.decodeFromByteArray(serializer, this)
  }


}