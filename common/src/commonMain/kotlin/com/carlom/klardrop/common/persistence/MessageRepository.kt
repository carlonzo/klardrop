@file:OptIn(ExperimentalUuidApi::class)

package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface MessageRepository {
  suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: MessageType,
    fileTransferId: Long? = null,
    isRead: Boolean = false,
    mimeType: String = "text/plain",
    messageId: Long? = null,
    sendStatus: SendStatus = SendStatus.SENT,
  )

  suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: FileTransferStatus,
    mimeType: String = "application/octet-stream"
  ): Long

  suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus)

  /**
   * Mark every transfer still flagged IN_PROGRESS as FAILED. Intended to run once at
   * app start: the process just came up, so nothing can actually still be in flight,
   * and a row left in IN_PROGRESS from a prior crash/kill would otherwise sit on the
   * chat screen forever as a stuck "0 B of N MB" entry with no terminal status.
   */
  suspend fun markStaleInProgressAsFailed()

  /**
   * Returns a merged flow of [ChatMessage] for the given device.
   *
   * Merges the on-disk SQLDelight flow with the in-memory outbox flow (sorted by timestamp
   * descending). Deduplication: a disk row wins over an outbox entry with the same id.
   * Outbox entries appear as [DeliveryStatus.SENDING]; disk rows with send_status='FAILED'
   * appear as [DeliveryStatus.FAILED]; everything else (send_status NULL) appears as
   * [DeliveryStatus.SENT].
   */
  fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>>

  fun getFileTransferById(id: Long): Flow<File_transfers?>

  suspend fun updateFileTransferFilePath(id: Long, filePath: String)

  suspend fun markMessagesAsRead(remoteDeviceId: String)

  suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long

  fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>>
}

enum class MessageType { TEXT, FILE }
enum class FileTransferStatus { IN_PROGRESS, COMPLETED, FAILED, REJECTED }

class MessageRepositoryImpl(
  private val database: AppDatabase,
  private val clock: Clock,
  private val ioDispatcher: CoroutineDispatcher,
  private val outbox: MessageOutbox = MessageOutbox(),
) : MessageRepository {

  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: MessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String,
    messageId: Long?,
    sendStatus: SendStatus,
  ) {
    withContext(ioDispatcher) {
      val sendStatusString = if (sendStatus == SendStatus.FAILED) "FAILED" else null
      database.messageQueries.insert(
        remote_device_id = remoteDeviceId,
        content = content,
        timestamp = clock.currentTimeMillis(),
        is_sender = if (isSender) 1L else 0L,
        message_type = messageType.name,
        file_transfer_id = fileTransferId,
        is_read = if (isRead) 1L else 0L,
        mime_type = mimeType,
        send_status = sendStatusString,
      ).await().also {
        log(
          "MessageRepositoryImpl",
          "Inserted message for device $remoteDeviceId with type $messageType, " +
            "file transfer ID $fileTransferId, sendStatus=$sendStatus"
        )
      }
    }
  }

  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: FileTransferStatus,
    mimeType: String
  ): Long = withContext(ioDispatcher) {
    val uuid = Uuid.random().toHexString()

    database.fileTransferQueries.insertFileTransfer(
      uuid = uuid,
      file_name = fileName,
      file_path = filePath,
      total_size = totalSize,
      transferred_size = 0, // Initial transferred size is 0
      status = status.name,
      mime_type = mimeType
    ).await()

    database.fileTransferQueries.getIdByUuid(uuid).executeAsOne().also {
      log("MessageRepositoryImpl", "Inserted file transfer: fileName: $fileName filePath: $filePath status: $status and id $it")
    }
  }

  override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) {
    withContext(ioDispatcher) {
      database.fileTransferQueries.updateStatus(
        status = status.name,
        id = id
      ).await().also {
        log("MessageRepositoryImpl", "Updated file transfer status for ID $id to $status")
      }
    }
  }

  override suspend fun markStaleInProgressAsFailed() {
    withContext(ioDispatcher) {
      database.fileTransferQueries.markStaleInProgressAsFailed().await()
    }
  }

  override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>> {
    val diskFlow: Flow<List<ChatMessage>> = database.messageQueries
      .getMessagesForDevice(remoteDeviceId, limit)
      .asFlow()
      .mapToList(ioDispatcher)
      .map { rows ->
        rows.map { row ->
          val delivery = when (row.send_status) {
            "FAILED" -> DeliveryStatus.FAILED
            else -> DeliveryStatus.SENT
          }
          ChatMessage(
            id = row.id,
            remoteDeviceId = row.remote_device_id,
            content = row.content,
            timestamp = row.timestamp,
            isSender = row.is_sender != 0L,
            messageType = row.message_type,
            fileTransferId = row.file_transfer_id,
            isRead = row.is_read,
            mimeType = row.mime_type,
            deliveryStatus = delivery,
          )
        }
      }

    val outboxFlow: Flow<List<ChatMessage>> = outbox.entries.map { entries ->
      entries
        .filter { it.remoteDeviceId == remoteDeviceId }
        .map { entry ->
          ChatMessage(
            id = entry.messageId,
            remoteDeviceId = entry.remoteDeviceId,
            content = entry.content,
            timestamp = entry.timestamp,
            isSender = true,
            messageType = MessageType.TEXT.name,
            fileTransferId = null,
            isRead = 1L,
            mimeType = "text/plain",
            deliveryStatus = DeliveryStatus.SENDING,
          )
        }
    }

    // Merge: disk rows take precedence over outbox entries with the same id.
    return diskFlow.combine(outboxFlow) { diskMessages, outboxMessages ->
      val diskIds = diskMessages.map { it.id }.toSet()
      val filteredOutbox = outboxMessages.filter { it.id !in diskIds }
      (diskMessages + filteredOutbox)
        .sortedByDescending { it.timestamp }
        .take(limit.toInt())
    }
  }

  override fun getFileTransferById(id: Long): Flow<File_transfers?> {
    return database.fileTransferQueries.getById(id)
      .asFlow()
      .mapToOneOrNull(ioDispatcher)
  }

  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {
    withContext(ioDispatcher) {
      database.fileTransferQueries.updateFilePath(
        file_path = filePath,
        id = id
      ).await()
    }
  }

  override suspend fun markMessagesAsRead(remoteDeviceId: String) {
    withContext(ioDispatcher) {
      database.messageQueries.markMessagesAsRead(remoteDeviceId)
        .await()
    }
  }

  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long {
    return withContext(ioDispatcher) {
      database.messageQueries.getUnreadCountForDevice(remoteDeviceId).executeAsOne()
    }
  }

  override fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>> {
    return database.messageQueries.getAllDevicesWithUnreadCounts()
      .asFlow()
      .mapToList(ioDispatcher)
      .map { results ->
        results.associate { it.remote_device_id to it.unread_count }
      }
  }
}
