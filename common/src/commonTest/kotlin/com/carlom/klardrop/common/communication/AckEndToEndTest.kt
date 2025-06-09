package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.di.CommunicationModule // Assuming this is where real instances can be set up or used as a reference
import com.carlom.klardrop.common.communication.message.*
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.Device
import com.carlom.klardrop.common.discovery.DeviceStatus
import com.carlom.klardrop.common.discovery.KlardropDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readFully
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.*

// --- Fakes for E2E Test ---

class FakeE2EVisibleDevices(initialDevices: List<KlardropDevice> = emptyList()) : VisibleDevices {
    private val devices = MutableStateFlow(initialDevices.associateBy { it.device.deviceId })
    override val visibleDevices: Flow<Map<String, KlardropDevice>> = devices
    override fun getDevice(deviceId: String): KlardropDevice? = devices.value[deviceId]
    override fun addDevice(device: KlardropDevice) { devices.update { it + (device.device.deviceId to device) } }
    override fun removeDevice(deviceId: String) { devices.update { it - deviceId } }
    override fun updateDeviceStatus(deviceId: String, status: DeviceStatus) { /* no-op */ }
    override fun updateDeviceConnection(deviceId: String, connection: com.carlom.klardrop.common.discovery.DeviceConnection) { /* no-op */ }
}

class FakeE2ECurrentDeviceProvider(private val device: KlardropDevice) : CurrentDeviceProvider {
    override fun get(): KlardropDevice = device
}

class FakeE2EMessageReceiver : MessageReceiver {
    override val notifier: Flow<Flow<ReceiveMessageUpdate>> = emptyFlow()
    private val _updates = MutableStateFlow(ReceiveMessageUpdate("fakeReceiver", messages = emptyList(), status = com.carlom.klardrop.common.receiver.ReceiveMessageStatus.Pending))
    override fun onReceiveMessage(fromDeviceId: String): MutableStateFlow<ReceiveMessageUpdate> = _updates
    fun pushUpdate(update: ReceiveMessageUpdate) { _updates.value = update }
}

// Minimal Fake for NearbyClient, not used in this test path
class FakeE2ENearbyClient : NearbyClient {
    override suspend fun send(ipAddress: String, port: Int, messages: List<SendMessageRequest>, sendProgress: MutableSharedFlow<MessengerSendProgress>) {}
}

// Minimal Fake for LocalPropertiesRepository
class FakeE2ELocalPropertiesRepository : LocalPropertiesRepository {
    private val props = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = props[key]
    override suspend fun putString(key: String, value: String) { props[key] = value }
    override fun getStringFlow(key: String, defaultValue: String): Flow<String> = flowOf(props[key] ?: defaultValue)
}

// Minimal Fake for FileManager
class FakeE2EFileManager : com.carlom.klardrop.common.FileManager {
    override suspend fun prepareSaveFile(fileName: String, mimeType: String?): com.carlom.klardrop.common.FileTransfer = TODO("Not used in this test")
    override suspend fun getReadStreamFrom(file: io.github.vinceglb.filekit.PlatformFile): io.ktor.utils.io.ByteReadChannel = TODO("Not used in this test")
}


@OptIn(ExperimentalCoroutinesApi::class)
class AckEndToEndTest {

    private suspend fun prepareMessageBytesForChannel(message: Message, serializer: MessageSerializer): ByteArray {
        val messageBytes = serializer.serialize(message)
        val lengthBytes = ByteArray(4)
        lengthBytes[0] = (messageBytes.size shr 24).toByte()
        lengthBytes[1] = (messageBytes.size shr 16).toByte()
        lengthBytes[2] = (messageBytes.size shr 8).toByte()
        lengthBytes[3] = (messageBytes.size).toByte()
        return lengthBytes + messageBytes
    }

    // Helper to read a length-prefixed message from a ByteReadChannel
    private suspend fun readMessageFromChannel(channel: ByteChannel, serializer: MessageSerializer): Message {
        val lengthBytes = ByteArray(4)
        channel.readFully(lengthBytes)
        val length = (lengthBytes[0].toInt() and 0xFF shl 24) or
                     (lengthBytes[1].toInt() and 0xFF shl 16) or
                     (lengthBytes[2].toInt() and 0xFF shl 8) or
                     (lengthBytes[3].toInt() and 0xFF)
        val messageBytes = ByteArray(length)
        channel.readFully(messageBytes)
        return serializer.deserialize(messageBytes)
    }


    @Test
    fun testAckHappyPath_ClientSendsText_ServerReceivesAndAcks_ClientProcessesAck() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testAppScope = CoroutineScope(testDispatcher + Job())
        val testCoroutines = Coroutines(testDispatcher, testDispatcher, testDispatcher, testAppScope)

        val serializer = MessageSerializer(ProtoBuf { encodeDefaults = true }, testCoroutines)

        // --- Device Setup ---
        val clientDevice = KlardropDevice(Device("client1", "Client Device", DeviceStatus.IDLE, emptyList(), KlardropDevice.Type.DESKTOP, 0L))
        val serverDevice = KlardropDevice(Device("server1", "Server Device", DeviceStatus.IDLE, emptyList(), KlardropDevice.Type.DESKTOP, 0L))

        val clientDeviceProvider = FakeE2ECurrentDeviceProvider(clientDevice)
        val serverDeviceProvider = FakeE2ECurrentDeviceProvider(serverDevice)

        val visibleDevices = FakeE2EVisibleDevices(listOf(clientDevice, serverDevice))

        // --- Shared Communication Channels ---
        // Client writes to this, Server reads from this
        val clientToServerChannel = ByteChannel(autoFlush = true)
        // Server writes to this, Client reads from this
        val serverToClientChannel = ByteChannel(autoFlush = true)

        // --- Server Setup ---
        val serverConnectionsPool = ConnectionsPoolImpl()
        val serverMessageReceiver = FakeE2EMessageReceiver() // Can be more specific if needed
        val serverMessageHandlers = MessageHandlersImpl(emptyMap()) // TextMessage is handled by router directly

        val serverMessenger = MessengerImpl( // Server also has a Messenger for potential internal use or if it sends non-ACK messages
            visibleDevices = visibleDevices,
            connectionsPool = serverConnectionsPool,
            client = FakeClient(), // Server doesn't initiate client connections in this test flow
            coroutines = testCoroutines,
            nearbyClient = FakeE2ENearbyClient(),
            messageReceiver = serverMessageReceiver
        )
        val serverRouter = MessagesRouterImpl(
            handlers = serverMessageHandlers,
            messageSerializer = serializer,
            coroutines = testCoroutines,
            messengeReceiver = serverMessageReceiver,
            ackDelegate = serverMessenger // Server's Messenger acts as its AckDelegate
        )

        // --- Client Setup ---
        val clientConnectionsPool = ConnectionsPoolImpl()
        val clientMessageReceiver = FakeE2EMessageReceiver()
        // Client's MessageHandlers can also be empty for this TextMessage test
        val clientMessageHandlers = MessageHandlersImpl(emptyMap())


        // Client's Client - needs a way to "connect"
        // For this test, we'll manually create the ConnectionMessenger for the client side
        // and bypass ClientImpl.connectTo() and Server.start() for simplicity.

        val clientMessenger = MessengerImpl(
            visibleDevices = visibleDevices,
            connectionsPool = clientConnectionsPool,
            // The client's ClientImpl isn't strictly needed if we manually set up ConnectionMessenger
            client = FakeClient(), // Or a client that can be "connected" via test hook
            coroutines = testCoroutines,
            nearbyClient = FakeE2ENearbyClient(),
            messageReceiver = clientMessageReceiver
        )
        val clientRouter = MessagesRouterImpl(
            handlers = clientMessageHandlers,
            messageSerializer = serializer,
            coroutines = testCoroutines,
            messengeReceiver = clientMessageReceiver,
            ackDelegate = clientMessenger // Client's Messenger is its AckDelegate
        )

        // --- Simulate Connection Establishment ---
        // Server side: "Accept" a connection
        // The server's router will handle incoming messages on clientToServerChannel
        // and write responses (like ACKs) to serverToClientChannel.
        val serverConnectionJob = launch(testDispatcher) {
            // Simulate server's message reading loop for one connection
            try {
                // Minimal Handshake (or assume already done)
                // Normally, server would read handshake, then pass to router.
                // For TextMessage, router directly handles it.
                // If handshake is needed by router's onMessageIncoming path:
                // val clientHandshake = HandshakeMessage(clientDevice.device.deviceId)
                // clientToServerChannel.writeFully(prepareMessageBytesForChannel(clientHandshake, serializer))
                // val serverHandshake = HandshakeMessage(serverDevice.device.deviceId)
                // serverToClientChannel.writeFully(prepareMessageBytesForChannel(serverHandshake, serializer))

                serverRouter.onMessageIncoming(clientDevice.device.deviceId, serverToClientChannel, clientToServerChannel)
            } catch (e: Exception) {
                if (e !is kotlinx.io.EOFException) { // EOF is expected when client closes channel
                    println("Server connection job error: $e")
                }
            }
        }

        // Client side: "Connect"
        // The client's ConnectionMessenger will read from serverToClientChannel and write to clientToServerChannel
        val clientConnectionMessenger = ConnectionMessenger(
            coroutines = testCoroutines,
            connection = Connection(socket = null, deviceId = serverDevice.device.deviceId), // Socket is null as we use channels
            messagesRouter = clientRouter,
            readChannel = serverToClientChannel, // Client reads what server writes
            writeChannel = clientToServerChannel, // Client writes to what server reads
            scope = testAppScope
        )
        clientConnectionsPool.updateConnection(serverDevice.device.deviceId, clientConnectionMessenger)
        // Launch client's message reading loop
        val clientMessageLoopJob = clientConnectionMessenger.acceptIncomingMessages()


        // --- Test Logic ---
        val textToSend = TextMessage(text = "E2E ACK Test", messageId = "e2eMsg001")
        val sendRequest = SimpleSendMessageRequest(textToSend)

        val sendProgressFlow = clientMessenger.send(serverDevice.device.deviceId, sendRequest)

        val results = mutableListOf<MessengerSendProgress>()
        val collectionJob = launch(testDispatcher) {
            sendProgressFlow.collect { results.add(it) }
        }

        // Wait for processing to complete
        // advanceUntilIdle() // Ensures all scheduled tasks on testDispatcher execute

        // Check results
        // Depending on how ConnectionMessenger.send and router logic is structured,
        // we might need to ensure server processes, sends ACK, and client processes ACK.
        // The `advanceUntilIdle` should allow this to play out.

        // Let's give it a bit of virtual time to ensure all parts interact
        testScheduler.advanceUntilIdle()


        collectionJob.cancel() // Stop collecting
        serverConnectionJob.cancel() // Clean up server loop
        clientMessageLoopJob.cancel() // Clean up client loop

        // Assertions
        assertTrue(results.contains(MessengerSendProgress.Pending), "Should emit Pending")
        val ackProgress = results.find { it is MessengerSendProgress.Acknowledged }
        assertNotNull(ackProgress, "Should emit Acknowledged")
        assertEquals(textToSend.messageId, (ackProgress as MessengerSendProgress.Acknowledged).ackedMessageId)

        // Verify server actually received the message (optional, could check serverMessageReceiver)
        // Verify client's AckDelegate (MessengerImpl) had its pending map cleared (optional, needs reflection)
        val pendingMessagesField = clientMessenger::class.java.getDeclaredField("pendingAckMessages")
        pendingMessagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingAckMessagesMap = pendingMessagesField.get(clientMessenger) as Map<*, *>
        assertFalse(pendingAckMessagesMap.containsKey(textToSend.messageId), "Pending ACK should be cleared after successful ACK")
    }


    @Test
    fun testAckTimeout_ClientSendsText_ServerDoesNotAck_ClientTimesOut() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testAppScope = CoroutineScope(testDispatcher + Job())
        val testCoroutines = Coroutines(testDispatcher, testDispatcher, testDispatcher, testAppScope)

        val serializer = MessageSerializer(ProtoBuf { encodeDefaults = true }, testCoroutines)

        // --- Device Setup ---
        val clientDevice = KlardropDevice(Device("clientTimeout", "Client Timeout Device", DeviceStatus.IDLE, emptyList(), KlardropDevice.Type.DESKTOP, 0L))
        val serverDevice = KlardropDevice(Device("serverNoAck", "Server No-ACK Device", DeviceStatus.IDLE, emptyList(), KlardropDevice.Type.DESKTOP, 0L))

        val clientDeviceProvider = FakeE2ECurrentDeviceProvider(clientDevice)
        // Server device provider not strictly needed as server won't send handshake in this modified test

        val visibleDevices = FakeE2EVisibleDevices(listOf(clientDevice, serverDevice))

        // --- Shared Communication Channels ---
        val clientToServerChannel = ByteChannel(autoFlush = true)
        val serverToClientChannel = ByteChannel(autoFlush = true) // Client reads from this, server *would* write ACK here

        // --- Server Setup (Modified: Will not send ACK) ---
        // Server components are minimal as its router logic for sending ACK is bypassed.
        // We only need it to consume the message.
        val serverMessageReceiver = FakeE2EMessageReceiver()


        // --- Client Setup (Same as happy path) ---
        val clientConnectionsPool = ConnectionsPoolImpl()
        val clientMessageHandlers = MessageHandlersImpl(emptyMap()) // TextMessage handled by router

        val clientMessenger = MessengerImpl(
            visibleDevices = visibleDevices,
            connectionsPool = clientConnectionsPool,
            client = FakeClient(),
            coroutines = testCoroutines,
            nearbyClient = FakeE2ENearbyClient(),
            messageReceiver = FakeE2EMessageReceiver() // Client's own receiver
        )
        val clientRouter = MessagesRouterImpl(
            handlers = clientMessageHandlers,
            messageSerializer = serializer,
            coroutines = testCoroutines,
            messengeReceiver = clientMessageReceiver, // Client's own receiver
            ackDelegate = clientMessenger
        )

        // --- Simulate Connection Establishment ---
        // Server Side (Modified: Reads message, does not ACK)
        val serverProcessingJob = launch(testDispatcher) {
            try {
                // Simulate server reading the client's message
                val incomingMessageBytes = prepareMessageBytesForChannel(
                    TextMessage(text = "message that server will not ack", messageId = "e2eTimeoutMsg001"), // Need to know messageId for client send
                    serializer
                )
                // This is what the client will write. Server needs to consume this exact structure.
                // The actual message the client sends will be used, this is just to show server's action.

                // Server reads the message from the channel the client writes to.
                clientToServerChannel.readMessage(serializer) // Consumes one length-prefixed message
                println("Test Server: Message read and ignored (no ACK will be sent).")
                // DO NOT write an ACK back to serverToClientChannel
            } catch (e: Exception) {
                 if (e !is kotlinx.io.EOFException && e !is kotlinx.coroutines.CancellationException) {
                    println("Test Server processing error: $e")
                }
            } finally {
                // Ensure channels are closed if server loop exits, to prevent client read loop from suspending indefinitely
                // serverToClientChannel.close() // Not strictly needed if client times out first
            }
        }

        // Client Side (Same connection setup as happy path)
        val clientConnectionMessenger = ConnectionMessenger(
            coroutines = testCoroutines,
            connection = Connection(socket = null, deviceId = serverDevice.device.deviceId),
            messagesRouter = clientRouter,
            readChannel = serverToClientChannel,  // Client reads from here (expects ACK)
            writeChannel = clientToServerChannel, // Client writes to here
            scope = testAppScope
        )
        clientConnectionsPool.updateConnection(serverDevice.device.deviceId, clientConnectionMessenger)
        val clientMessageLoopJob = clientConnectionMessenger.acceptIncomingMessages()

        // --- Test Logic ---
        val textToSend = TextMessage(text = "E2E Timeout Test", messageId = "e2eTimeoutMsg001")
        val sendRequest = SimpleSendMessageRequest(textToSend)

        val sendProgressFlow = clientMessenger.send(serverDevice.device.deviceId, sendRequest)

        val results = mutableListOf<MessengerSendProgress>()
        val collectionJob = launch(testDispatcher) { // Use testDispatcher for collection to sync with advanceTimeBy
            sendProgressFlow.collect { results.add(it) }
        }

        // Advance time past the ACK timeout (30 seconds in MessengerImpl)
        testScheduler.advanceTimeBy(30_001L)
        testScheduler.advanceUntilIdle() // Ensure timeout coroutine and subsequent emissions complete

        collectionJob.cancel()
        serverProcessingJob.cancel()
        clientMessageLoopJob.cancel()

        // Assertions
        assertTrue(results.any { it is MessengerSendProgress.Pending }, "Should have emitted Pending")
        val errorResult = results.find { it is MessengerSendProgress.Error }
        assertNotNull(errorResult, "Should have emitted Error after timeout")
        assertEquals(
            "Timeout waiting for ACK for ${textToSend.messageId}",
            (errorResult as MessengerSendProgress.Error).message
        )

        val pendingMessagesField = clientMessenger::class.java.getDeclaredField("pendingAckMessages")
        pendingMessagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingAckMessagesMap = pendingMessagesField.get(clientMessenger) as Map<*, *>
        assertFalse(pendingAckMessagesMap.containsKey(textToSend.messageId), "Pending ACK should be cleared after timeout")
    }
}
