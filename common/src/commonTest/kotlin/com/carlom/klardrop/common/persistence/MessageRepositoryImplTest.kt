package com.carlom.klardrop.common.persistence

import app.cash.turbine.test
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.createTestDriver
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var realClock: Clock

    // Use real Clock - tests will verify with actual timestamps

    @BeforeTest
    fun setup() {
        val driver = createTestDriver()
        db = AppDatabase(driver)
        testDispatcher = UnconfinedTestDispatcher()
        realClock = Clock()
        messageRepository = MessageRepositoryImpl(db, realClock, testDispatcher)
    }

    @Test
    fun testInsertTextMessageWithReadStatus() = runTest(testDispatcher) {
        val remoteDeviceId = "device-123"
        val content = "Hello, Klardrop!"

        messageRepository.insertMessage(
            remoteDeviceId = remoteDeviceId,
            content = content,
            isSender = true,
            messageType = MessageType.TEXT,
            isRead = true
        )

        messageRepository.getMessagesForDevice(remoteDeviceId, 10).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            val msg = messages.first()
            assertEquals(remoteDeviceId, msg.remote_device_id)
            assertEquals(content, msg.content)
            assertEquals(1L, msg.is_sender)
            assertEquals(MessageType.TEXT.name, msg.message_type)
            assertEquals(1L, msg.is_read) // Check read status
            assertTrue(msg.timestamp > 0) // Just verify timestamp is set
        }
    }

    @Test
    fun testInsertUnreadMessage() = runTest(testDispatcher) {
        val remoteDeviceId = "device-unread"
        val content = "Unread message"

        messageRepository.insertMessage(
            remoteDeviceId = remoteDeviceId,
            content = content,
            isSender = false,
            messageType = MessageType.TEXT,
            isRead = false
        )

        messageRepository.getMessagesForDevice(remoteDeviceId, 10).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            val msg = messages.first()
            assertEquals(0L, msg.is_read) // Should be unread
        }
    }

    @Test
    fun testMarkMessagesAsRead() = runTest(testDispatcher) {
        val remoteDeviceId = "device-mark-read"

        // Insert multiple unread messages
        messageRepository.insertMessage(remoteDeviceId, "Message 1", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(remoteDeviceId, "Message 2", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(remoteDeviceId, "Message 3", false, MessageType.TEXT, isRead = false)

        // Mark all messages as read
        messageRepository.markMessagesAsRead(remoteDeviceId)

        // Verify all messages are now read
        messageRepository.getMessagesForDevice(remoteDeviceId, 10).test {
            val messages = awaitItem()
            assertEquals(3, messages.size)
            messages.forEach { message ->
                assertEquals(1L, message.is_read)
            }
        }
    }

    @Test
    fun testGetUnreadCountForDevice() = runTest(testDispatcher) {
        val remoteDeviceId = "device-unread-count"

        // Insert mix of read and unread messages
        messageRepository.insertMessage(remoteDeviceId, "Read 1", false, MessageType.TEXT, isRead = true)
        messageRepository.insertMessage(remoteDeviceId, "Unread 1", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(remoteDeviceId, "Unread 2", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(remoteDeviceId, "Read 2", false, MessageType.TEXT, isRead = true)

        val unreadCount = messageRepository.getUnreadCountForDevice(remoteDeviceId)
        assertEquals(2L, unreadCount)
    }

    @Test
    fun testGetAllDevicesWithUnreadCounts() = runTest(testDispatcher) {
        val device1 = "device-1"
        val device2 = "device-2"
        val device3 = "device-3"

        // Insert messages for different devices
        messageRepository.insertMessage(device1, "Unread 1", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(device1, "Unread 2", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(device2, "Unread 1", false, MessageType.TEXT, isRead = false)
        messageRepository.insertMessage(device3, "Read 1", false, MessageType.TEXT, isRead = true) // Read message, shouldn't appear

        messageRepository.getAllDevicesWithUnreadCounts().test {
            val unreadCounts = awaitItem()
            assertEquals(2, unreadCounts.size) // Only device1 and device2 should have unread messages
            assertEquals(2L, unreadCounts[device1])
            assertEquals(1L, unreadCounts[device2])
            assertEquals(null, unreadCounts[device3]) // No unread messages
        }
    }

    @Test
    fun testInsertFileMessageWithReadStatus() = runTest(testDispatcher) {
        val remoteDeviceId = "device-file"
        val fileName = "test_file.zip"

        val fileTransferId = messageRepository.insertFileTransfer(
            fileName = fileName,
            filePath = "/path/to/test_file.zip",
            totalSize = 1024 * 1024, // 1MB
            status = FileTransferStatus.IN_PROGRESS
        )
        assertTrue(fileTransferId > 0)

        messageRepository.insertMessage(
            remoteDeviceId = remoteDeviceId,
            content = fileName,
            isSender = false,
            messageType = MessageType.FILE,
            fileTransferId = fileTransferId,
            isRead = false
        )

        messageRepository.getMessagesForDevice(remoteDeviceId, 10).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            val msg = messages.first()
            assertEquals(remoteDeviceId, msg.remote_device_id)
            assertEquals(fileName, msg.content)
            assertEquals(0L, msg.is_sender)
            assertEquals(MessageType.FILE.name, msg.message_type)
            assertEquals(fileTransferId, msg.file_transfer_id)
            assertEquals(0L, msg.is_read) // Should be unread
            assertTrue(msg.timestamp > 0) // Just verify timestamp is set
        }
    }

    @Test
    fun testUpdateFileTransferStatus() = runTest(testDispatcher) {
        val fileName = "document.pdf"
        val initialStatus = FileTransferStatus.IN_PROGRESS
        val updatedStatus = FileTransferStatus.COMPLETED

        val fileTransferId = messageRepository.insertFileTransfer(
            fileName = fileName,
            filePath = "/path/doc.pdf",
            totalSize = 1000L,
            status = initialStatus
        )
        assertTrue(fileTransferId > 0)

        messageRepository.updateFileTransferStatus(fileTransferId, updatedStatus)

        messageRepository.getFileTransferById(fileTransferId).test {
            val fileTransfer = awaitItem()
            assertNotNull(fileTransfer)
            assertEquals(fileTransferId, fileTransfer.id)
            assertEquals(updatedStatus.name, fileTransfer.status)
        }
    }
}
