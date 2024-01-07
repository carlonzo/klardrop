package com.carlom.klardrop.common.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.klardrop.common.persistence.KlardropDatabase
import com.klardrop.common.persistence.SelectByDeviceIdWithDeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.invoke
import kotlinx.serialization.protobuf.ProtoBuf

internal class HistoryDbDataSourceImpl(
  private val sqlDriver: SqlDriver,
  private val coroutines: Coroutines,
  private val protoBuf: ProtoBuf
) : HistoryDbDataSource {

  private val database by lazy { KlardropDatabase(sqlDriver) }
  private val messagesQueries by lazy { database.messagesQueries }

  override suspend fun getMessagesForDevice(deviceId: String, limit: Long, offset: Long): List<HistoryMessage> = coroutines.ioDispatcher {
    val dbMessages = messagesQueries.selectByDeviceIdWithDeviceInfo(deviceId, limit, offset).awaitAsList()

    dbMessages.map { dbMessage -> dbMessage.toHistoryMessage() }
  }

  override fun getMessagesForDeviceAsFlow(deviceId: String, limit: Long, offset: Long): Flow<List<HistoryMessage>> {
    return messagesQueries.selectByDeviceIdWithDeviceInfo(deviceId, limit, offset).asFlow()
      .mapToList(coroutines.ioDispatcher)
      .map {
        it.map { dbMessage -> dbMessage.toHistoryMessage() }
      }
  }

  override suspend fun insertMessage(deviceId: String, timestamp: Long, payload: HistoryMessagePayload) = coroutines.ioDispatcher {
    val byteArrayPayload = serializePayload(payload)

    messagesQueries.insert_message(device_id = deviceId, message_type = payload.type, timestamp = timestamp, payload = byteArrayPayload)
  }

  override suspend fun deleteMessage(messageId: Long) = coroutines.ioDispatcher {
    messagesQueries.delete_message(messageId)
  }

  private fun SelectByDeviceIdWithDeviceInfo.toHistoryMessage(): HistoryMessage {
    val payload = payload.toHistoryMessagePayload(message_type)
    return HistoryMessage(
      id = id,
      device = DeviceInfo(
        deviceId = device_id,
        name = device_name,
        deviceType = DeviceType.fromId(device_type.toByte()),
        osType = OsType.fromId(device_os.toByte())
      ),
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

    val serializer = when (type) {
      HistoryMessagePayload.TextMessagePayloadType -> HistoryMessagePayload.TextMessagePayload.serializer()
      HistoryMessagePayload.FileMessagePayloadType -> HistoryMessagePayload.FileMessagePayload.serializer()
      else -> throw IllegalArgumentException("Unknown type $type")
    }

    return protoBuf.decodeFromByteArray(serializer, this)
  }
}