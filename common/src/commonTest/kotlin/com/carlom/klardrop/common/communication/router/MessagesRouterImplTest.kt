package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals


@OptIn(ExperimentalCoroutinesApi::class)
class MessagesRouterImplTest {

  private lateinit var messagesRouter: MessagesRouterImpl
  private lateinit var mockMessageRepository: MockMessageRepository
  private lateinit var mockMessageHandlers: MockMessageHandlers
  private lateinit var mockMessageSerializer: MessageSerializer // Real one, but could be mocked if needed
  private lateinit var mockMessageReceiver: MockMessageReceiver
  private lateinit var testDispatcher: TestDispatcher
  private lateinit var mockCoroutines: Coroutines


  // --- Mocks ---
  class MockMessageRepository : MessageRepository {
    val calls = mutableListOf<String>()

    override suspend fun insertMessage(
      remoteDeviceId: String,
      content: String,
      isSender: Boolean,
      messageType: com.carlom.klardrop.common.persistence.MessageType,
      fileTransferId: Long?,
      isRead: Boolean
    ): Long {
      calls.add("insertMessage($remoteDeviceId, $content, $isSender, $messageType, $fileTransferId, $isRead)")
      return 1L
    }

    override suspend fun insertFileTransfer(
      fileName: String,
      filePath: String,
      totalSize: Long,
      status: com.carlom.klardrop.common.persistence.FileTransferStatus
    ): Long {
      calls.add("insertFileTransfer($fileName, $filePath, $totalSize, $status)")
      return 1L
    }

    override suspend fun updateFileTransferStatus(id: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus) {
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

    override fun getMessagesForDevice(
      remoteDeviceId: String,
      limit: Long
    ): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.database.Messages>> =
      kotlinx.coroutines.flow.flowOf(emptyList())

    override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> =
      kotlinx.coroutines.flow.flowOf(null)
  }

  class MockMessageHandlers : MessageHandlers {
    var handlerToReturn: MessageHandler<Message, SendMessageRequest>? = null

    override fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>? {
      return handlerToReturn // Only return a handler if one is specifically set for a test
    }
  }

  class MockMessageHandler<E : Message, R : SendMessageRequest> : MessageHandler<E, R> {
    var incomingMessageHandled: E? = null
    var outgoingRequestHandled: R? = null
    override suspend fun handleIncoming(message: E, readChannel: ByteReadChannel, receiveFlow: MutableStateFlow<ReceiveMessageUpdate>) {
      incomingMessageHandled = message
    }

    override suspend fun handleOutgoing(
      toDeviceId: String,
      request: R,
      writeChannel: ByteWriteChannel,
      progressFlow: MutableSharedFlow<MessengerSendProgress>
    ) {
      outgoingRequestHandled = request
    }
  }


  class MockMessageReceiver : MessageReceiver {
    val onReceiveMessageFlow = MutableStateFlow(
      ReceiveMessageUpdate(
        device = DeviceInfo("test-device", "Test Device", DeviceType.DESKTOP),
        status = ReceiveMessageStatus.Started
      )
    )

    override fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate> = onReceiveMessageFlow
    override val notifier: kotlinx.coroutines.flow.Flow<Pair<String, kotlinx.coroutines.flow.StateFlow<ReceiveMessageUpdate>>> =
      kotlinx.coroutines.flow.flowOf()
    override val messageReceivedNotifier: kotlinx.coroutines.flow.Flow<ReceiveMessageUpdate> = kotlinx.coroutines.flow.flowOf()
  }


  @BeforeTest
  fun setup() {
    testDispatcher = UnconfinedTestDispatcher()
    mockCoroutines = object : Coroutines {
      override val ioDispatcher = testDispatcher
      override val mainDispatcher = testDispatcher
      override val cpuDispatcher = testDispatcher
      override val appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
      override fun newScope() = kotlinx.coroutines.CoroutineScope(testDispatcher)
      override fun newScope(context: kotlin.coroutines.CoroutineContext) = kotlinx.coroutines.CoroutineScope(context)
    }
    mockMessageRepository = MockMessageRepository()
    mockMessageHandlers = MockMessageHandlers()
    // Using ProtoBuf for actual serialization as it's part of the contract
    mockMessageSerializer = MessageSerializer(ProtoBuf, mockCoroutines)
    mockMessageReceiver = MockMessageReceiver()

    messagesRouter = MessagesRouterImpl(
      handlers = mockMessageHandlers,
      messageSerializer = mockMessageSerializer,
      coroutines = mockCoroutines,
      messengeReceiver = mockMessageReceiver,
      messageRepository = mockMessageRepository
    )
  }

  private suspend fun createMessageBytes(message: Message): ByteArray {
    // Use the actual MessageSerializer to create properly formatted messages
    val messagePayload = mockMessageSerializer.serialize(message)

    // Create the length prefix (4 bytes) as expected by readByteArrayMessage
    val lengthBytes = ByteArray(4)
    lengthBytes[0] = (messagePayload.size shr 24).toByte()
    lengthBytes[1] = (messagePayload.size shr 16).toByte()
    lengthBytes[2] = (messagePayload.size shr 8).toByte()
    lengthBytes[3] = messagePayload.size.toByte()

    return lengthBytes + messagePayload
  }


  @Test
  fun onMessageIncomingForTextMessageNoPayloadInsertsMessage() = runTest(testDispatcher) {
    val fromDeviceId = "sender-text"
    val textContent = "Hello from router test!"
    val textMessage = TextMessage(text = textContent)

    val serializedMessage = createMessageBytes(textMessage)
    val readChannel = ByteReadChannel(serializedMessage)
    val writeChannel = ByteChannel(true)

    messagesRouter.onMessageIncoming(fromDeviceId, writeChannel, readChannel) { }

    assertEquals(1, mockMessageRepository.calls.size)
    assertEquals(
      "insertMessage($fromDeviceId, $textContent, false, TEXT, null, false)",
      mockMessageRepository.calls[0]
    )
    // Also check that receiveFlow was updated
    assertEquals(ReceiveMessageStatus.Completed, mockMessageReceiver.onReceiveMessageFlow.value.status)
    assertEquals(textMessage, mockMessageReceiver.onReceiveMessageFlow.value.messages.firstOrNull())
  }

  @Test
  fun onSendingMessageForTextMessageNoPayloadInsertsMessage() = runTest(testDispatcher) {
    val toDeviceId = "receiver-text"
    val textContent = "Router test sending!"
    val textMessage = TextMessage(text = textContent)
    val request = textMessage.toSimpleSendRequest() // SimpleSendMessageRequest

    val writeChannel = ByteChannel(true)
    val readChannel = ByteReadChannel(byteArrayOf()) // Not used for no-payload sending
    val progressFlow = MutableSharedFlow<MessengerSendProgress>()

    messagesRouter.onSendingMessage(toDeviceId, request, writeChannel, readChannel, progressFlow)

    assertEquals(1, mockMessageRepository.calls.size)
    assertEquals(
      "insertMessage($toDeviceId, $textContent, true, TEXT, null, true)",
      mockMessageRepository.calls[0]
    )
    // Verify that sendMessage was called on writeChannel (actual bytes are complex to check here)
    // Note: We rely on the repository call above to verify the operation succeeded
  }

  @Test
  fun onMessageIncomingForFileMessagePayloadCallsSpecificHandler() = runTest(testDispatcher) {
    val fromDeviceId = "sender-file"
    val fileMessage = FileMessage("test.dat", 123, "app/data")
    val mockHandler = MockMessageHandler<FileMessage, FileMessage.FileSendRequest>()
    @Suppress("UNCHECKED_CAST")
    mockMessageHandlers.handlerToReturn = mockHandler as MessageHandler<Message, SendMessageRequest>


    val serializedMessage = createMessageBytes(fileMessage) // Create header for FileMessage
    val readChannel = ByteReadChannel(serializedMessage + byteArrayOf(1, 2, 3)) // Add some dummy payload bytes
    val writeChannel = ByteChannel(true)

    messagesRouter.onMessageIncoming(fromDeviceId, writeChannel, readChannel) { }

    assertEquals(0, mockMessageRepository.calls.size) // MessageRepository should not be called directly by router for handled messages
    assertEquals(fileMessage, mockHandler.incomingMessageHandled)
  }

  @Test
  fun onSendingMessageForFileMessagePayloadCallsSpecificHandler() = runTest(testDispatcher) {
    val toDeviceId = "receiver-file"
    val fileMessage = FileMessage("outgoing.dat", 456, "app/foo")
    // Create a mock PlatformFile instance
    val mockPlatformFile = PlatformFile(Path("/tmp", "test"))
    val request = FileMessage.FileSendRequest(fileMessage, mockPlatformFile)
    val mockHandler = MockMessageHandler<FileMessage, FileMessage.FileSendRequest>()
    @Suppress("UNCHECKED_CAST")
    mockMessageHandlers.handlerToReturn = mockHandler as MessageHandler<Message, SendMessageRequest>


    val writeChannel = ByteChannel(true)
    val readChannel = ByteReadChannel(byteArrayOf())
    val progressFlow = MutableSharedFlow<MessengerSendProgress>()

    messagesRouter.onSendingMessage(toDeviceId, request, writeChannel, readChannel, progressFlow)

    assertEquals(0, mockMessageRepository.calls.size) // MessageRepository should not be called directly by router for handled messages
    assertEquals(request, mockHandler.outgoingRequestHandled)
  }
}
