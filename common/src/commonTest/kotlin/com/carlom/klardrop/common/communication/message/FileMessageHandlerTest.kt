package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.carlom.klardrop.common.discovery.DeviceInfo // Required for ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType // Required for DeviceInfo
import com.carlom.klardrop.common.FileTransfer // Required for FileManager mock
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.io.Sink
import kotlinx.io.Source


@OptIn(ExperimentalCoroutinesApi::class)
class FileMessageHandlerTest {

    private lateinit var fileMessageHandler: FileMessageHandler
    private lateinit var mockMessageRepository: MockMessageRepository
    private lateinit var mockFileManager: MockFileManager
    private lateinit var realClock: Clock
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var mockCoroutines: Coroutines

    // --- Mocks ---
    class MockMessageRepository : MessageRepository {
        val calls = mutableListOf<String>()
        var nextFileTransferId = 1L
        var nextMessageId = 1L

        override suspend fun insertMessage(remoteDeviceId: String, content: String, isSender: Boolean, messageType: PersistenceMessageType, fileTransferId: Long?, isRead: Boolean): Long {
            calls.add("insertMessage($remoteDeviceId, $content, $isSender, $messageType, $fileTransferId, $isRead)")
            return nextMessageId++
        }

        override suspend fun insertFileTransfer(fileName: String, filePath: String, totalSize: Long, status: FileTransferStatus): Long {
            calls.add("insertFileTransfer($fileName, $filePath, $totalSize, $status)")
            return nextFileTransferId++
        }

        override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) {
            calls.add("updateFileTransferStatus($id, $status)")
        }

        override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {
            calls.add("updateFileTransferFilePath($id, $filePath)")
        }
        
        override suspend fun markMessagesAsRead(remoteDeviceId: String) {
            calls.add("markMessagesAsRead($remoteDeviceId)")
        }
        
        override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long {
            calls.add("getUnreadCountForDevice($remoteDeviceId)")
            return 0L
        }
        
        override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> {
            calls.add("getAllDevicesWithUnreadCounts()")
            return kotlinx.coroutines.flow.flowOf(emptyMap())
        }
        
        override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.database.Messages>> = 
            kotlinx.coroutines.flow.flowOf(emptyList())
        override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> = 
            kotlinx.coroutines.flow.flowOf(null)
    }

    open class MockFileManager : FileManager {
        var preparedFile: MockFileTransfer? = null
        override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
            preparedFile = MockFileTransfer("/fake/path/$fileName")
            return preparedFile!!
        }
        override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource = TODO("Not yet implemented for tests")
        override suspend fun openFile(filePath: String): Boolean = true
    }

    class MockFileTransfer(val path: String) : FileTransfer {
        override val bufferedSink: Sink = kotlinx.io.Buffer() // Use a Buffer as a dummy Sink
        var transferCompleted = false
        var transferFailed = false
        override suspend fun onTransferCompleted() { transferCompleted = true }
        override suspend fun onTransferFailed() { transferFailed = true }
    }


    // Just use a real Clock instance - tests will work with actual timestamps

    @BeforeTest
    fun setup() {
        mockMessageRepository = MockMessageRepository()
        mockFileManager = MockFileManager()
        realClock = Clock()
        testDispatcher = UnconfinedTestDispatcher()
        mockCoroutines = object : Coroutines {
            override val ioDispatcher = testDispatcher
            override val mainDispatcher = testDispatcher
            override val cpuDispatcher = testDispatcher
            override val appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
            override fun newScope() = kotlinx.coroutines.CoroutineScope(testDispatcher)
            override fun newScope(context: kotlin.coroutines.CoroutineContext) = kotlinx.coroutines.CoroutineScope(context)
        }

        fileMessageHandler = FileMessageHandler(
            serializer = MessageSerializer(kotlinx.serialization.protobuf.ProtoBuf, mockCoroutines), // Dummy serializer
            fileManager = mockFileManager,
            clock = realClock,
            coroutines = mockCoroutines,
            messageRepository = mockMessageRepository
        )
    }

    @Test
    fun `handleIncoming starts transfer, inserts message, and completes without intermediate progress updates`() = runTest(testDispatcher) {
        val remoteDeviceId = "sender-device"
        val fileMessage = FileMessage("test.txt", 100, "text/plain")
        val receiveFlow = MutableStateFlow(ReceiveMessageUpdate(
            device = DeviceInfo(remoteDeviceId, "Sender", DeviceType.DESKTOP),
            status = ReceiveMessageStatus.Started
        ))
        // Simulate reading 50 bytes, then another 50 bytes.
        val data = ByteArray(100) { 0 }
        val byteReadChannel = ByteReadChannel(data)

        fileMessageHandler.handleIncoming(fileMessage, byteReadChannel, receiveFlow)

        // Verify initial DB calls
        assertEquals("insertFileTransfer(test.txt, dummy_path_placeholder, 100, IN_PROGRESS)", mockMessageRepository.calls[0])
        val expectedFileTransferId = mockMessageRepository.nextFileTransferId -1
        assertEquals("insertMessage($remoteDeviceId, test.txt, false, FILE, $expectedFileTransferId, false)", mockMessageRepository.calls[1])

        // Verify NO intermediate progress updates (only final state)
        // Note: file path update is commented out due to interface limitations
        assertEquals("updateFileTransferStatus($expectedFileTransferId, COMPLETED)", mockMessageRepository.calls[2])
        assertEquals(3, mockMessageRepository.calls.size) // Only 3 calls: insert file transfer, insert message, final status
        assert(mockFileManager.preparedFile?.transferCompleted == true)
    }

    @Test
    fun `handleIncoming handles read failure with only final status update`() = runTest(testDispatcher) {
        val remoteDeviceId = "sender-device-fail"
        val fileMessage = FileMessage("fail.txt", 100, "text/plain")
         val receiveFlow = MutableStateFlow(ReceiveMessageUpdate(
            device = DeviceInfo(remoteDeviceId, "SenderFail", DeviceType.DESKTOP),
            status = ReceiveMessageStatus.Started
        ))
        // Simulate a channel that closes prematurely
        val byteReadChannel = ByteReadChannel(ByteArray(0)) // Empty channel will cause readFully to fail or hang then timeout

        var exceptionThrown = false
        try {
            fileMessageHandler.handleIncoming(fileMessage, byteReadChannel, receiveFlow)
        } catch (e: Exception) { // Catching general Exception as timeout or specific read error might occur
            exceptionThrown = true
        }

        assert(exceptionThrown) // Expecting an exception due to read failure / timeout

        // Verify initial DB calls
        assertEquals("insertFileTransfer(fail.txt, dummy_path_placeholder, 100, IN_PROGRESS)", mockMessageRepository.calls[0])
        val expectedFileTransferId = mockMessageRepository.nextFileTransferId -1
        assertEquals("insertMessage($remoteDeviceId, fail.txt, false, FILE, $expectedFileTransferId, false)", mockMessageRepository.calls[1])

        // Verify only final failure status (no intermediate progress updates)
        assertEquals("updateFileTransferStatus($expectedFileTransferId, FAILED)", mockMessageRepository.calls.last())
        assertEquals(3, mockMessageRepository.calls.size) // Only 3 calls: insert file transfer, insert message, final failure status
        assert(mockFileManager.preparedFile?.transferFailed == true)
    }


    @Test
    fun `handleOutgoing starts transfer, inserts message and completes without intermediate progress updates`() = runTest(testDispatcher) {
        val toDeviceId = "receiver-device"
        val fileName = "outgoing.dat"
        val fileSize = 200L
        val fileMessage = FileMessage(fileName, fileSize, "application/octet-stream")
        // Create a simple mock that satisfies the path property requirement
        val mockPlatformFile = object {
            val path: String = "/fake/path/outgoing.dat"
            val name: String = fileName
            val size: Long = fileSize
        } as PlatformFile
        val sendRequest = FileMessage.FileSendRequest(fileMessage, mockPlatformFile)
        val progressFlow = MutableSharedFlow<MessengerSendProgress>()
        val byteWriteChannel = io.ktor.utils.io.ByteChannel(true).apply { close() } // Auto-flush true

        // Mock FileManager to return a valid RawSource
        mockFileManager = object : MockFileManager() {
            override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource {
                return kotlinx.io.Buffer().apply { write(ByteArray(fileSize.toInt()) { 0 }) } // Provide a source with enough bytes
            }
        }
        fileMessageHandler = FileMessageHandler( // Re-init with new mockFileManager
            serializer = MessageSerializer(kotlinx.serialization.protobuf.ProtoBuf, mockCoroutines),
            fileManager = mockFileManager,
            clock = realClock,
            coroutines = mockCoroutines,
            messageRepository = mockMessageRepository
        )

        fileMessageHandler.handleOutgoing(toDeviceId, sendRequest, byteWriteChannel, progressFlow)

        // Verify initial DB calls
        assertEquals("insertFileTransfer($fileName, /fake/path/outgoing.dat, $fileSize, IN_PROGRESS)", mockMessageRepository.calls[0])
        val expectedFileTransferId = mockMessageRepository.nextFileTransferId -1
        assertEquals("insertMessage($toDeviceId, $fileName, true, FILE, $expectedFileTransferId, true)", mockMessageRepository.calls[1])

        // Verify only final completion status (no intermediate progress updates to DB)
        assertEquals("updateFileTransferStatus($expectedFileTransferId, COMPLETED)", mockMessageRepository.calls.last())
        assertEquals(3, mockMessageRepository.calls.size) // Only 3 calls: insert file transfer, insert message, final status
    }

    @Test
    fun `progress updates are sent to UI but not persisted to database`() = runTest(testDispatcher) {
        val remoteDeviceId = "sender-device"
        val fileMessage = FileMessage("test.txt", 100, "text/plain")
        val receiveFlow = MutableStateFlow(ReceiveMessageUpdate(
            device = DeviceInfo(remoteDeviceId, "Sender", DeviceType.DESKTOP),
            status = ReceiveMessageStatus.Started
        ))
        
        // Create a channel with data to simulate progress
        val data = ByteArray(100) { 0 }
        val byteReadChannel = ByteReadChannel(data)

        fileMessageHandler.handleIncoming(fileMessage, byteReadChannel, receiveFlow)

        // Verify that receiveFlow was updated with progress (in memory)
        // The final state should be Completed
        assertEquals(ReceiveMessageStatus.Completed::class, receiveFlow.value.status::class)
        
        // Verify that only final states were persisted (no intermediate progress in DB)
        val statusUpdateCalls = mockMessageRepository.calls.filter { it.contains("updateFileTransferStatus") }
        assertEquals(1, statusUpdateCalls.size) // Only final COMPLETED status
        assertEquals("updateFileTransferStatus(1, COMPLETED)", statusUpdateCalls[0])
    }
}
