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
import kotlinx.coroutines.withContext

interface MessageRepository {
    suspend fun insertMessage(
        remoteDeviceId: String,
        content: String,
        isSender: Boolean,
        messageType: MessageType,
        fileTransferId: Long? = null
    ): Long

    suspend fun insertFileTransfer(
        fileName: String,
        filePath: String,
        totalSize: Long,
        status: FileTransferStatus
    ): Long

    suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus)

    fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<Messages>>

    fun getFileTransferById(id: Long): Flow<File_transfers?>

    suspend fun updateFileTransferFilePath(id: Long, filePath: String)
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
        fileTransferId: Long?
    ): Long = withContext(ioDispatcher) {
        database.messageQueries.insert(
            remote_device_id = remoteDeviceId,
            content = content,
            timestamp = clock.currentTimeMillis(),
            is_sender = if (isSender) 1L else 0L,
            message_type = messageType.name,
            file_transfer_id = fileTransferId
        )
        database.messageQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun insertFileTransfer(
        fileName: String,
        filePath: String,
        totalSize: Long,
        status: FileTransferStatus
    ): Long = withContext(ioDispatcher) {
        database.fileTransferQueries.insertFileTransfer(
            file_name = fileName,
            file_path = filePath,
            total_size = totalSize,
            transferred_size = 0, // Initial transferred size is 0
            status = status.name
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
}
