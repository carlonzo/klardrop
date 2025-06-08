package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.coroutines. bijna.collectToList
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.createTestDriver
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
    private lateinit var testDispatcher: StandardTestDispatcher
    private lateinit var mockClock: MockClock

    // Mock Clock implementation
    class MockClock(private var currentTime: Long = 1000L) : Clock {
        override fun nowMillis(): Long = currentTime
        fun advanceTimeBy(millis: Long) {
            currentTime += millis
        }
    }

    @BeforeTest
    fun setup() {
        val driver = createTestDriver()
        db = AppDatabase(driver)
        testDispatcher = StandardTestDispatcher()
        mockClock = MockClock()
        messageRepository = MessageRepositoryImpl(db, mockClock, testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        // No explicit close for JdbcSqliteDriver.IN_MEMORY needed for each test,
        // as it's in-memory and will be garbage collected.
        // If using a file-based DB for tests, driver.close() would be important.
    }

    @Test
    fun testInsertTextMessageAndGetMessages() = runTest(testDispatcher) {
        val remoteDeviceId = "device-123"
        val content = "Hello, Klardrop!"

        val insertedId = messageRepository.insertMessage(
            remoteDeviceId = remoteDeviceId,
            content = content,
            isSender = true,
            messageType = MessageType.TEXT
        )
        assertTrue(insertedId > 0)

        val messages = messageRepository.getMessagesForDevice(remoteDeviceId, 10).collectToList().flatten()

        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(insertedId, msg.id)
        assertEquals(remoteDeviceId, msg.remote_device_id)
        assertEquals(content, msg.content)
        assertEquals(true, msg.is_sender)
        assertEquals(MessageType.TEXT.name, msg.message_type)
        assertEquals(mockClock.nowMillis(), msg.timestamp)
    }

    @Test
    fun testInsertFileMessageAndGetMessages() = runTest(testDispatcher) {
        val remoteDeviceId = "device-file"
        val fileName = "test_file.zip"

        val fileTransferId = messageRepository.insertFileTransfer(
            fileName = fileName,
            filePath = "/path/to/test_file.zip",
            totalSize = 1024 * 1024, // 1MB
            status = FileTransferStatus.IN_PROGRESS
        )
        assertTrue(fileTransferId > 0)

        val messageId = messageRepository.insertMessage(
            remoteDeviceId = remoteDeviceId,
            content = fileName,
            isSender = false,
            messageType = MessageType.FILE,
            fileTransferId = fileTransferId
        )
        assertTrue(messageId > 0)

        val messages = messageRepository.getMessagesForDevice(remoteDeviceId, 10).collectToList().flatten()
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(messageId, msg.id)
        assertEquals(remoteDeviceId, msg.remote_device_id)
        assertEquals(fileName, msg.content)
        assertEquals(false, msg.is_sender)
        assertEquals(MessageType.FILE.name, msg.message_type)
        assertEquals(fileTransferId, msg.file_transfer_id)
        assertEquals(mockClock.nowMillis(), msg.timestamp)
    }

    @Test
    fun testInsertFileTransferAndUpdateStatus() = runTest(testDispatcher) {
        val fileName = "document.pdf"
        val initialStatus = FileTransferStatus.IN_PROGRESS
        val updatedStatus = FileTransferStatus.COMPLETED
        val transferredSize = 500L

        val fileTransferId = messageRepository.insertFileTransfer(
            fileName = fileName,
            filePath = "/path/doc.pdf",
            totalSize = 1000L,
            status = initialStatus
        )
        assertTrue(fileTransferId > 0)

        messageRepository.updateFileTransferStatus(fileTransferId, updatedStatus, transferredSize)

        val fileTransfer = messageRepository.getFileTransferById(fileTransferId).collectToList().first()
        assertNotNull(fileTransfer)
        assertEquals(fileTransferId, fileTransfer.id)
        assertEquals(updatedStatus.name, fileTransfer.status)
        assertEquals(transferredSize, fileTransfer.transferred_size)
    }

    @Test
    fun testUpdateFileTransferStatusWithoutSize() = runTest(testDispatcher) {
        val fileName = "archive.zip"
        val initialStatus = FileTransferStatus.IN_PROGRESS
        val updatedStatus = FileTransferStatus.FAILED

        val fileTransferId = messageRepository.insertFileTransfer(
            fileName = fileName,
            filePath = "/path/archive.zip",
            totalSize = 2000L,
            status = initialStatus
        )
        assertTrue(fileTransferId > 0)

        // Check initial transferred_size (should be 0 as per schema default)
        var fileTransfer = messageRepository.getFileTransferById(fileTransferId).collectToList().first()
        assertNotNull(fileTransfer)
        assertEquals(0, fileTransfer.transferred_size)

        messageRepository.updateFileTransferStatus(fileTransferId, updatedStatus) // Not passing transferredSize

        fileTransfer = messageRepository.getFileTransferById(fileTransferId).collectToList().first()
        assertNotNull(fileTransfer)
        assertEquals(fileTransferId, fileTransfer.id)
        assertEquals(updatedStatus.name, fileTransfer.status)
        // Transferred size should remain unchanged from its previous value (0 in this case)
        assertEquals(0, fileTransfer.transferred_size)
    }


    @Test
    fun testUpdateFileTransferFilePath() = runTest(testDispatcher) {
        val fileName = "image.jpg"
        val initialPath = "/tmp/image.jpg"
        val updatedPath = "/storage/image_final.jpg"

        val fileTransferId = messageRepository.insertFileTransfer(
            fileName = fileName,
            filePath = initialPath,
            totalSize = 300L,
            status = FileTransferStatus.COMPLETED
        )
        assertTrue(fileTransferId > 0)

        messageRepository.updateFileTransferFilePath(fileTransferId, updatedPath)

        val fileTransfer = messageRepository.getFileTransferById(fileTransferId).collectToList().first()
        assertNotNull(fileTransfer)
        assertEquals(fileTransferId, fileTransfer.id)
        assertEquals(updatedPath, fileTransfer.file_path)
    }

    @Test
    fun testGetMessagesForDevice_OrdersByTimestampDescending() = runTest(testDispatcher) {
        val remoteDeviceId = "device-timestamps"

        messageRepository.insertMessage(remoteDeviceId, "Message 1", true, MessageType.TEXT) // time = 1000L
        mockClock.advanceTimeBy(100) // time = 1100L
        messageRepository.insertMessage(remoteDeviceId, "Message 2", false, MessageType.TEXT)
        mockClock.advanceTimeBy(100) // time = 1200L
        val id3 = messageRepository.insertMessage(remoteDeviceId, "Message 3", true, MessageType.TEXT)

        val messages = messageRepository.getMessagesForDevice(remoteDeviceId, 10).collectToList().flatten()
        assertEquals(3, messages.size)
        assertEquals(id3, messages[0].id) // Message 3 should be first (latest timestamp)
        assertEquals(1200L, messages[0].timestamp)
        assertEquals(1100L, messages[1].timestamp)
        assertEquals(1000L, messages[2].timestamp)
    }

    @Test
    fun testGetMessagesForDevice_Limit() = runTest(testDispatcher) {
        val remoteDeviceId = "device-limit"
        messageRepository.insertMessage(remoteDeviceId, "M1", true, MessageType.TEXT)
        mockClock.advanceTimeBy(10)
        messageRepository.insertMessage(remoteDeviceId, "M2", true, MessageType.TEXT)
        mockClock.advanceTimeBy(10)
        messageRepository.insertMessage(remoteDeviceId, "M3", true, MessageType.TEXT)

        var messages = messageRepository.getMessagesForDevice(remoteDeviceId, 2).collectToList().flatten()
        assertEquals(2, messages.size)

        messages = messageRepository.getMessagesForDevice(remoteDeviceId, 5).collectToList().flatten()
        assertEquals(3, messages.size)
    }

    @Test
    fun testGetFileTransferById_NotFound() = runTest(testDispatcher) {
        val fileTransfer = messageRepository.getFileTransferById(9999L).collectToList().firstOrNull()
        assertEquals(null, fileTransfer)
    }
}
