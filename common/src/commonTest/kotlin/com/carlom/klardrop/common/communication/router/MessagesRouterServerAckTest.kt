package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.AckDelegate
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.message.*
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// --- Fakes and Test Setup ---

class FakeAckDelegate : AckDelegate {
    var onAckReceivedCalledWith: Pair<String, String>? = null
    override fun onAckReceived(originalMessageId: String, fromDeviceId: String) {
        onAckReceivedCalledWith = originalMessageId to fromDeviceId
    }
}

class FakeMessageReceiver : MessageReceiver {
    override val notifier: Flow<Flow<ReceiveMessageUpdate>> = emptyFlow()
    override fun onReceiveMessage(fromDeviceId: String): MutableStateFlow<ReceiveMessageUpdate> {
        return MutableStateFlow(ReceiveMessageUpdate(fromDeviceId = fromDeviceId, status = com.carlom.klardrop.common.receiver.ReceiveMessageStatus.Pending))
    }
}

class FakeMessageHandlers : MessageHandlers {
    override fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesRouterServerAckTest {

    private suspend fun prepareMessageBytesForReadChannel(message: Message, serializer: MessageSerializer): ByteArray {
        val messageBytes = serializer.serialize(message)
        val lengthBytes = ByteArray(4)
        // Ensure Big Endian order for length
        lengthBytes[0] = (messageBytes.size shr 24).toByte()
        lengthBytes[1] = (messageBytes.size shr 16).toByte()
        lengthBytes[2] = (messageBytes.size shr 8).toByte()
        lengthBytes[3] = (messageBytes.size).toByte()
        return lengthBytes + messageBytes
    }

    @Test
    fun testServerSendsAckForTextMessage() = runTest {
        val testScheduler = coroutineContext[Job] // Access TestCoroutineScheduler if needed, or use Unconfined
        val testDispatcher = UnconfinedTestDispatcher(testScheduler) // Using Unconfined for simplicity here

        val testCoroutines = Coroutines(
            appScope = CoroutineScope(testDispatcher + Job()), // Child Job for cancellation
            ioDispatcher = testDispatcher,
            cpuDispatcher = testDispatcher
        )

        val serializer = MessageSerializer(ProtoBuf { encodeDefaults = true }, testCoroutines)
        val serverReadChannel = ByteChannel(autoFlush = true)  // Client writes here, server reads from here
        val serverWriteChannel = ByteChannel(autoFlush = true) // Server writes here, test reads from here

        val fakeAckDelegate = FakeAckDelegate()
        val fakeMessageReceiver = FakeMessageReceiver()
        val fakeMessageHandlers = FakeMessageHandlers()

        val router = MessagesRouterImpl(
            handlers = fakeMessageHandlers,
            messageSerializer = serializer,
            coroutines = testCoroutines,
            messengeReceiver = fakeMessageReceiver,
            ackDelegate = fakeAckDelegate
        )

        val originalTextMessage = TextMessage(text = "Hello Server", messageId = "textMsg123")
        val bytesToSend = prepareMessageBytesForReadChannel(originalTextMessage, serializer)

        // Simulate client sending data
        serverReadChannel.writeFully(bytesToSend)
        serverReadChannel.close() // Close the write side for the server to know input has ended for this message.

        // Server processes the message
        val job = launch(testDispatcher) { // Launch router processing on the test dispatcher
            router.onMessageIncoming("testClientDeviceId", serverWriteChannel, serverReadChannel)
        }

        // Read ACK from serverWriteChannel
        val ackLengthBytes = ByteArray(4)
        serverWriteChannel.readFully(ackLengthBytes) // Read the length of the ACK
        val ackLength = (ackLengthBytes[0].toInt() and 0xFF shl 24) or
                        (ackLengthBytes[1].toInt() and 0xFF shl 16) or
                        (ackLengthBytes[2].toInt() and 0xFF shl 8) or
                        (ackLengthBytes[3].toInt() and 0xFF)

        val ackMessageBytes = ByteArray(ackLength)
        serverWriteChannel.readFully(ackMessageBytes) // Read the ACK message itself

        job.join() // Ensure onMessageIncoming completes

        val receivedAck = serializer.deserialize(ackMessageBytes)

        assertIs<AckMessage>(receivedAck, "Server should send an AckMessage")
        assertEquals(originalTextMessage.messageId, receivedAck.ackedMessageId, "AckedMessageId should match original TextMessage id")
        assertNull(fakeAckDelegate.onAckReceivedCalledWith, "AckDelegate should not be called by the server when it *sends* an ACK")
    }
}
