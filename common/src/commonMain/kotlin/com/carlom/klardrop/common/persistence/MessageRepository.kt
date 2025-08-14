package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface MessageRepository {
    suspend fun insertMessage(
        remoteDeviceId: String,
        content: String,
        isSender: Boolean,
        messageType: MessageType,
        fileTransferId: Long? = null,
        isRead: Boolean = false,
        mimeType: String = "text/plain"
    ): Long

    suspend fun insertFileTransfer(
        fileName: String,
        filePath: String,
        totalSize: Long,
        status: FileTransferStatus,
        mimeType: String = "application/octet-stream"
    ): Long

    suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus)

    fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<Messages>>

    fun getFileTransferById(id: Long): Flow<File_transfers?>

    suspend fun updateFileTransferFilePath(id: Long, filePath: String)

    suspend fun markMessagesAsRead(remoteDeviceId: String)

    suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long

    fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>>
}

enum class MessageType { TEXT, FILE }
enum class FileTransferStatus { IN_PROGRESS, COMPLETED, FAILED }

class MessageRepositoryImpl(
    private val database: AppDatabase,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher
) : MessageRepository {

    override suspend fun insertMessage(
        remoteDeviceId: String,
        content: String,
        isSender: Boolean,
        messageType: MessageType,
        fileTransferId: Long?,
        isRead: Boolean,
        mimeType: String
    ): Long = withContext(ioDispatcher) {
        database.messageQueries.insert(
            remote_device_id = remoteDeviceId,
            content = content,
            timestamp = clock.currentTimeMillis(),
            is_sender = if (isSender) 1L else 0L,
            message_type = messageType.name,
            file_transfer_id = fileTransferId,
            is_read = if (isRead) 1L else 0L,
            mime_type = mimeType
        )
        database.messageQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun insertFileTransfer(
        fileName: String,
        filePath: String,
        totalSize: Long,
        status: FileTransferStatus,
        mimeType: String
    ): Long = withContext(ioDispatcher) {
        database.fileTransferQueries.insertFileTransfer(
            file_name = fileName,
            file_path = filePath,
            total_size = totalSize,
            transferred_size = 0, // Initial transferred size is 0
            status = status.name,
            mime_type = mimeType
        )
        database.fileTransferQueries.lastInsertRowIdFileTransfer().executeAsOne()
    }

    override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) {
        withContext(ioDispatcher) {
            database.fileTransferQueries.updateStatus(
                status = status.name,
                id = id
            )
        }
    }

    override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<Messages>> {
        return database.messageQueries.getMessagesForDevice(remoteDeviceId, limit)
            .asFlow()
            .mapToList(ioDispatcher)
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
            )
        }
    }

    override suspend fun markMessagesAsRead(remoteDeviceId: String) {
        withContext(ioDispatcher) {
            database.messageQueries.markMessagesAsRead(remoteDeviceId)
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
