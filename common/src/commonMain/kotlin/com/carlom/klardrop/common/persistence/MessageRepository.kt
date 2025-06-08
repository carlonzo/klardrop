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

    suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus, transferredSize: Long? = null)

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
        database.messagesQueries.insert(
            remote_device_id = remoteDeviceId,
            content = content,
            timestamp = clock.nowMillis(),
            is_sender = isSender,
            message_type = messageType.name,
            file_transfer_id = fileTransferId
        )
        database.messagesQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun insertFileTransfer(
        fileName: String,
        filePath: String,
        totalSize: Long,
        status: FileTransferStatus
    ): Long = withContext(ioDispatcher) {
        database.fileTransfersQueries.insert(
            file_name = fileName,
            file_path = filePath,
            total_size = totalSize,
            transferred_size = 0, // Initial transferred size is 0
            status = status.name
        )
        database.fileTransfersQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus, transferredSize: Long?) {
        withContext(ioDispatcher) {
            if (transferredSize != null) {
                database.fileTransfersQueries.updateStatusAndSize(
                    id = id,
                    status = status.name,
                    transferred_size = transferredSize
                )
            } else {
                database.fileTransfersQueries.updateStatus(
                    id = id,
                    status = status.name
                )
            }
        }
    }

    override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<Messages>> {
        return database.messagesQueries.getMessagesForDevice(remoteDeviceId, limit)
            .asFlow()
            .mapToList(ioDispatcher)
    }

    override fun getFileTransferById(id: Long): Flow<File_transfers?> {
        return database.fileTransfersQueries.getById(id)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
    }

    override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {
        withContext(ioDispatcher) {
            database.fileTransfersQueries.updateFilePath(
                id = id,
                file_path = filePath
            )
        }
    }
}
