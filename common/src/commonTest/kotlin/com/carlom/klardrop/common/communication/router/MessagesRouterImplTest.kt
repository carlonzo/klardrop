package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.*
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.carlom.klardrop.common.discovery.DeviceInfo // Required for ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType // Required for DeviceInfo
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf


@OptIn(ExperimentalCoroutinesApi::class)
class MessagesRouterImplTest {

    private lateinit var messagesRouter: MessagesRouterImpl
    private lateinit var mockMessageRepository: MockMessageRepository
    private lateinit var mockMessageHandlers: MockMessageHandlers
    private lateinit var mockMessageSerializer: MessageSerializer // Real one, but could be mocked if needed
    private lateinit var mockMessageReceiver: MockMessageReceiver
    private lateinit var testDispatcher: StandardTestDispatcher
    private lateinit var mockCoroutines: Coroutines


    // --- Mocks ---
    class MockMessageRepository : MessageRepository by FileMessageHandlerTest.MockMessageRepository() // Delegate to existing mock

    class MockMessageHandlers : MessageHandlers {
        var handleIncomingCalledWith: Message? = null
        var handleOutgoingCalledWith: SendMessageRequest? = null
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
        override suspend fun handleOutgoing(toDeviceId: String, request: R, writeChannel: ByteWriteChannel, progressFlow: MutableSharedFlow<MessengerSendProgress>) {
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
        override val notifier: MutableSharedFlow<MutableStateFlow<ReceiveMessageUpdate>> = MutableSharedFlow()
    }


    @BeforeTest
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        mockCoroutines = Coroutines(testDispatcher, testDispatcher, testDispatcher)
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

    private fun createTextMessageBytes(textMessage: TextMessage): ByteArray {
        val messageBytes = ProtoBuf.encodeToByteArray(textMessage)
        val headerPacket = buildPacket {
            writeByte(textMessage.type.id) // MessageType ID
            writeInt(messageBytes.size)    // Length of the message
        }
        return headerPacket.readBytes() + messageBytes
    }


    @Test
    fun `onMessageIncoming for TextMessage (no payload) inserts message`() = runTest(testDispatcher) {
        val fromDeviceId = "sender-text"
        val textContent = "Hello from router test!"
        val textMessage = TextMessage(text = textContent)

        val serializedMessage = createTextMessageBytes(textMessage)
        val readChannel = ByteReadChannel(serializedMessage)
        val writeChannel = ByteWriteChannel(true)

        messagesRouter.onMessageIncoming(fromDeviceId, writeChannel, readChannel)

        assertEquals(1, mockMessageRepository.calls.size)
        assertEquals(
            "insertMessage($fromDeviceId, $textContent, false, TEXT, null)",
            mockMessageRepository.calls[0]
        )
        // Also check that receiveFlow was updated
        assertEquals(ReceiveMessageStatus.Completed, mockMessageReceiver.onReceiveMessageFlow.value.status)
        assertEquals(textMessage, mockMessageReceiver.onReceiveMessageFlow.value.messages.firstOrNull())
    }

    @Test
    fun `onSendingMessage for TextMessage (no payload) inserts message`() = runTest(testDispatcher) {
        val toDeviceId = "receiver-text"
        val textContent = "Router test sending!"
        val textMessage = TextMessage(text = textContent)
        val request = textMessage.toSimpleSendRequest() // SimpleSendMessageRequest

        val writeChannel = ByteWriteChannel(true)
        val readChannel = ByteReadChannel(byteArrayOf()) // Not used for no-payload sending
        val progressFlow = MutableSharedFlow<MessengerSendProgress>()

        messagesRouter.onSendingMessage(toDeviceId, request, writeChannel, readChannel, progressFlow)

        assertEquals(1, mockMessageRepository.calls.size)
        assertEquals(
            "insertMessage($toDeviceId, $textContent, true, TEXT, null)",
            mockMessageRepository.calls[0]
        )
        // Verify that sendMessage was called on writeChannel (actual bytes are complex to check here)
        assert(writeChannel.totalBytesWritten > 0)
    }

    @Test
    fun `onMessageIncoming for FileMessage (payload) calls specific handler`() = runTest(testDispatcher) {
        val fromDeviceId = "sender-file"
        val fileMessage = FileMessage("test.dat", 123, "app/data")
        val mockHandler = MockMessageHandler<FileMessage, FileMessage.FileSendRequest>()
        mockMessageHandlers.handlerToReturn = mockHandler as MessageHandler<Message, SendMessageRequest>


        val serializedMessage = createTextMessageBytes(fileMessage as Message) // Create header for FileMessage
        val readChannel = ByteReadChannel(serializedMessage + byteArrayOf(1,2,3)) // Add some dummy payload bytes
        val writeChannel = ByteWriteChannel(true)

        messagesRouter.onMessageIncoming(fromDeviceId, writeChannel, readChannel)

        assertEquals(0, mockMessageRepository.calls.size) // MessageRepository should not be called directly by router for handled messages
        assertEquals(fileMessage, mockHandler.incomingMessageHandled)
    }

    @Test
    fun `onSendingMessage for FileMessage (payload) calls specific handler`() = runTest(testDispatcher) {
        val toDeviceId = "receiver-file"
        val fileMessage = FileMessage("outgoing.dat", 456, "app/foo")
        // PlatformFile mock is not strictly needed here as handler is mocked
        val mockPlatformFile = object : PlatformFile {
            override val path: String? = "/dev/null"; override val name: String = "f"; override val size: Long? = 0L
            override suspend fun readBytes(): ByteArray = byteArrayOf(); override suspend fun writeBytes(bytes: ByteArray) {}
            override suspend fun exists(): Boolean = true; override suspend fun delete() {}; override suspend fun create() {}
            override suspend fun uriString(): String = ""
        }
        val request = fileMessage.toSendRequest(mockPlatformFile)
        val mockHandler = MockMessageHandler<FileMessage, FileMessage.FileSendRequest>()
        mockMessageHandlers.handlerToReturn = mockHandler as MessageHandler<Message, SendMessageRequest>


        val writeChannel = ByteWriteChannel(true)
        val readChannel = ByteReadChannel(byteArrayOf())
        val progressFlow = MutableSharedFlow<MessengerSendProgress>()

        messagesRouter.onSendingMessage(toDeviceId, request, writeChannel, readChannel, progressFlow)

        assertEquals(0, mockMessageRepository.calls.size) // MessageRepository should not be called directly by router for handled messages
        assertEquals(request, mockHandler.outgoingRequestHandled)
    }
}
