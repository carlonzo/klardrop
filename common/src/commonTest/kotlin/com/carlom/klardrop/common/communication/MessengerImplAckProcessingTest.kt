package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.discovery.Device
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceStatus
import com.carlom.klardrop.common.discovery.KlardropDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

// Minimal Fakes for MessengerImpl dependencies
class FakeVisibleDevices : VisibleDevices {
    override val visibleDevices: Flow<Map<String, KlardropDevice>> = emptyFlow()
    override fun getDevice(deviceId: String): KlardropDevice? = KlardropDevice(Device(deviceId, "FakeDevice", DeviceStatus.IDLE, emptyList(), KlardropDevice.Type.DESKTOP, 0L))
    override fun addDevice(device: KlardropDevice) {}
    override fun removeDevice(deviceId: String) {}
    override fun updateDeviceStatus(deviceId: String, status: DeviceStatus) {}
    override fun updateDeviceConnection(deviceId: String, connection: DeviceConnection) {}
}

class FakeConnectionsPool : ConnectionsPool {
    override val connections: Flow<Map<String, ConnectionMessenger>> = emptyFlow()
    override fun getConnection(deviceId: String): ConnectionMessenger? = null
    override fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {}
    override fun removeConnection(deviceId: String) {}
    override fun isAvailable(deviceId: String): Boolean = false
}

class FakeClient : Client {
    override suspend fun connectTo(deviceId: String) {}
}

class FakeNearbyClient : NearbyClient {
    override suspend fun send(
        ipAddress: String,
        port: Int,
        messages: List<com.carlom.klardrop.common.communication.message.SendMessageRequest>,
        sendProgress: MutableSharedFlow<MessengerSendProgress>
    ) {}
}

class FakeMessageReceiver : MessageReceiver {
    override val notifier: Flow<Flow<ReceiveMessageUpdate>> = emptyFlow()
    override fun onReceiveMessage(fromDeviceId: String): MutableSharedFlow<ReceiveMessageUpdate> = MutableSharedFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessengerImplAckProcessingTest {

    @Test
    fun testOnAckReceivedUpdatesFlowAndRemovesFromPending() = runTest { // Uses StandardTestDispatcher by default in new versions
        val testDispatcher = UnconfinedTestDispatcher(testScheduler) // Or StandardTestDispatcher(testScheduler)

        val messenger = MessengerImpl(
            visibleDevices = FakeVisibleDevices(),
            connectionsPool = FakeConnectionsPool(),
            client = FakeClient(),
            // Pass the test dispatcher to Coroutines
            coroutines = Coroutines(testDispatcher, testDispatcher, testDispatcher, CoroutineScope(Job() + testDispatcher)),
            nearbyClient = FakeNearbyClient(),
            messageReceiver = FakeMessageReceiver()
        )

        val testMessageId = "ack-test-id-123"
        val progressFlow = MutableSharedFlow<MessengerSendProgress>()

        // Manually add to pendingAckMessages for this specific test of onAckReceived
        // This requires pendingAckMessages to be accessible.
        // If not, this test needs rethinking or MessengerImpl needs a test helper.
        // For now, let's assume we can modify it for test purposes (e.g. if it was internal or package-private)
        // Since it's private, this direct test of onAckReceived is hard.
        // Let's pivot: Test the effect of onAckReceived via the send flow if possible,
        // or acknowledge this limitation for a pure unit test of onAckReceived itself.

        // Alternative: Test `onAckReceived` by having it called by a mock `MessagesRouter`
        // This is also slightly indirect. The purest test of `onAckReceived` would be if `pendingAckMessages`
        // was injectable or modifiable.

        // For this subtask, let's test the behavior as best as possible:
        // 1. Put something in pendingAcks (even if indirectly or by assumption for test)
        // 2. Call onAckReceived
        // 3. Check flow emission & map removal

        // To make pendingAckMessages accessible for this test, a common pattern is to
        // make it internal and use @VisibleForTesting, or pass it in the constructor (overkill).
        // Or, the test for onAckReceived could be part of a larger integration test.

        // Given the constraints, I'll simulate the map state.
        // This is not ideal as it tests an internal implementation detail directly.
        val pendingMessagesField = messenger::class.java.getDeclaredField("pendingAckMessages")
        pendingMessagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingAckMessagesMap = pendingMessagesField.get(messenger) as MutableMap<String, MutableSharedFlow<MessengerSendProgress>>

        pendingAckMessagesMap[testMessageId] = progressFlow

        val job = launch {
            val ack = progressFlow.first { it is MessengerSendProgress.Acknowledged }
            assertIs<MessengerSendProgress.Acknowledged>(ack)
            assertEquals(testMessageId, ack.ackedMessageId)
        }

        messenger.onAckReceived(testMessageId, "testDevice")

        job.join() // Ensure the collector has run

        assertFalse(pendingAckMessagesMap.containsKey(testMessageId), "MessageId should be removed from pending map after ACK")
    }

    @Test
    fun testAckTimeoutEmitsErrorAndCleansUp() = runTest {
        // Use StandardTestDispatcher for controlled execution
        val testDispatcher = StandardTestDispatcher(testScheduler)
        // The scope for MessengerImpl's internal operations
        val messengerInternalScope = CoroutineScope(testDispatcher + Job())

        val messenger = MessengerImpl(
            visibleDevices = FakeVisibleDevices(),
            connectionsPool = FakeConnectionsPool(),
            client = FakeClient(),
            // Ensure MessengerImpl's internal coroutines use this testDispatcher
            coroutines = Coroutines(testDispatcher, testDispatcher, testDispatcher, messengerInternalScope),
            nearbyClient = FakeNearbyClient(),
            messageReceiver = FakeMessageReceiver()
        )

        val testMessage = com.carlom.klardrop.common.communication.message.TextMessage(text = "test for timeout")
        val sendMessageRequest = com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest(testMessage)
        val testDeviceId = "timeout-device"

        val progressFlow = messenger.send(testDeviceId, sendMessageRequest)
        val receivedEmissions = mutableListOf<MessengerSendProgress>()
        // Collector can run on the default test dispatcher provided by runTest, or a specific one.
        // Using a separate dispatcher for collection to ensure it doesn't interfere.
        val collectorDispatcher = UnconfinedTestDispatcher(testScheduler)
        val collectionJob = launch(collectorDispatcher) {
            progressFlow.collect { receivedEmissions.add(it) }
        }

        // Check that pendingAckMessages contains the ID (requires reflection again or a test helper)
        val pendingMessagesField = messenger::class.java.getDeclaredField("pendingAckMessages")
        pendingMessagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingAckMessagesMap = pendingMessagesField.get(messenger) as MutableMap<String, Any> // Value is Flow

        assertTrue(pendingAckMessagesMap.containsKey(testMessage.messageId), "MessageId should be in pending map before timeout")

        // Advance time past the 30-second timeout + a small buffer
        advanceTimeBy(30_001) // Advance time by just over 30 seconds

        // After advancing time, the timeout coroutine in send() should have executed.
        // We need to ensure that the collection job has a chance to receive the Error emission.
        // StandardTestDispatcher requires explicit yielding or running of pending tasks.
        // runCurrent() // If using StandardTestDispatcher to run tasks scheduled at the current virtual time.
        // If UnconfinedTestDispatcher was used for the timeout coroutine, it might run eagerly.
        // Since send() launches into messengerScope which uses standardTestDispatcher from Coroutines,
        // advanceTimeBy should be sufficient for the delay() to complete and the error to be emitted.

        collectionJob.cancel() // Stop collecting after advancing time

        assertTrue(receivedEmissions.any { it is MessengerSendProgress.Pending }, "Should have emitted Pending")
        val errorEmission = receivedEmissions.find { it is MessengerSendProgress.Error }
        assertNotNull(errorEmission, "Should have emitted Error after timeout")
        assertEquals(
            "Timeout waiting for ACK for ${testMessage.messageId}",
            (errorEmission as MessengerSendProgress.Error).message,
            "Error message should indicate timeout for the correct messageId"
        )

        assertFalse(pendingAckMessagesMap.containsKey(testMessage.messageId), "MessageId should be removed from pending map after timeout")
    }
}
