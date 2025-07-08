package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.TurbineContext
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection.DeviceConnectionType
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.Clock
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime

class KlardropIntegrationTest {

  private val coroutines = TestCoroutines()
  private val clock = Clock()
  private val clientVisibleDevices = FakeVisibleDevices()
  private val clientDeviceId = "client01"
  private val serverDeviceId = "server01"

  private val testContext = KlardropTestContext(
    coroutines = coroutines,
    clock = clock,
    clientVisibleDevices = clientVisibleDevices,
    clientDeviceId = clientDeviceId,
    serverDeviceId = serverDeviceId
  )

  @Test
  fun startServerAndSendTextMessage() = runTest(coroutines.dispatcher) {
    testContext.setupServerAndClient()

    turbineScope {
      with(testContext) {
        sendAndVerifyMessage("klardrop protocol test")
      }
    }
  }

  @Test
  fun testSendTwoMessagesForKlardrop() = runTest(coroutines.dispatcher) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope {
      with(testContext) {
        sendAndVerifyMessage("This is the first message")
        sendAndVerifyMessage("This is a second message!")
      }
    }
  }

  @Test
  fun testSendTwoMessagesForNearby() = runTest(coroutines.dispatcher) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope {
      with(testContext) {
        sendAndVerifyMessage("This is the first message")
        sendAndVerifyMessage("This is a second message!")
      }
    }
  }

  @Test
  fun testMessengerReconnectionFromClient() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = false)

  @Test
  fun testMessengerReconnectionFromServer() = testMessengerReconnection(clientDropsConnection = false, serverDropsConnection = true)

  @Test
  fun testMessengerReconnectionFromBothSides() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = true)

  // simple method to parametize the test for reconnection issues. using a boolean to indicate if the client should drop the connection or the server
  @OptIn(ExperimentalTime::class)
  @Suppress("VisibleForTests")
  private fun testMessengerReconnection(clientDropsConnection: Boolean, serverDropsConnection: Boolean) = runTest(coroutines.dispatcher) {
    testContext.setupServerAndClient()

    turbineScope {
      with(testContext) {
        // send first message between client and server
        sendAndVerifyMessage("firstMessage")

        if (clientDropsConnection) {
          dropClientConnections()
        }

        if (serverDropsConnection) {
          dropServerConnections()
        }

        // Wait a bit to ensure cleanup
        advanceToCompletion()

        val messageReceiver = serverCommunicationModule.messageReceiver()
        val clientMessenger = clientCommunicationModule.messenger()

        // Now try to send a second message - this should trigger reconnection
        val secondMessage = textSendRequest("reconnection messenger message")

        val secondSendFlow = clientMessenger.send(serverDeviceId, secondMessage)
        val secondSenderChannel = secondSendFlow.testIn(this@turbineScope)
        val secondReceiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)


        // Since connections were dropped, this should trigger timeouts and retries
        // Advance past the ACK timeout (2 seconds) to trigger retry logic
        advanceTimeAndComplete(2100) // Past 2-second ACK timeout

        // Wait for second message to be sent

        try {
          secondSenderChannel.awaitFor {
            it is Completed
          }
        } catch (e: Exception) {
          throw e
        }

        // If it completes, verify the message was received
        advanceToCompletion()
        val secondCompletedUpdate = secondReceiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
        assertIs<ReceiveMessageStatus.Completed>(secondCompletedUpdate.status)
        assertEquals(1, secondCompletedUpdate.messages.size)
        assertEquals((secondMessage.message as TextMessage).text, (secondCompletedUpdate.messages.first() as TextMessage).text)
        secondReceiverChannel.cancelAndIgnoreRemainingEvents()

        secondSenderChannel.cancelAndIgnoreRemainingEvents()
      }
    }
  }

}

internal class KlardropTestFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    error("not implemented")
  }

  override fun getReadStreamFrom(file: PlatformFile): Source {
    error("not implemented")
  }
}

internal class KlardropTestContext(
  val coroutines: TestCoroutines,
  private val clock: Clock,
  private val clientVisibleDevices: FakeVisibleDevices,
  private val clientDeviceId: String,
  private val serverDeviceId: String
) {

  val clientCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = clientVisibleDevices,
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = KlardropTestFileManager(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(clientDeviceId))
  )

  val serverCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = FakeVisibleDevices(),
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = KlardropTestFileManager(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(serverDeviceId))
  )

  data class ServerContext(
    val server: Server,
    val port: Int
  )


  suspend fun setupServerAndClient(clientConnectionType: DeviceConnectionType = DeviceConnectionType.KLARDROP): ServerContext {
    val server = serverCommunicationModule.server()
    val serverStatus = server.startServer()

    when (clientConnectionType) {
      DeviceConnectionType.NEARBY -> clientVisibleDevices.addNearbyDevice(serverDeviceId, "localhost", serverStatus.port)
      DeviceConnectionType.KLARDROP -> clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)
    }

    return ServerContext(server, serverStatus.port)
  }

  fun advanceToCompletion() {
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()
  }

  fun advanceTimeAndComplete(timeMs: Long) {
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(timeMs)
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()
  }

  @Suppress("VisibleForTests")
  suspend fun dropClientConnections() {
    val connectionsPool = clientCommunicationModule.connectionsPool()
    connectionsPool.closeAllConnections()
  }

  @Suppress("VisibleForTests")
  suspend fun dropServerConnections() {
    val connectionsPool = serverCommunicationModule.connectionsPool()
    connectionsPool.closeAllConnections()
  }

  fun textSendRequest(text: String = "klardrop protocol test"): SendMessageRequest {
    return SimpleSendMessageRequest(TextMessage("Test Title", text = text))
  }

  suspend fun <T> ReceiveTurbine<T>.awaitFor(block: ((T) -> Boolean)): T {
    var item: T
    do {
      item = awaitItem()
    } while (!block(item))
    return item
  }

  suspend fun TurbineContext.sendAndVerifyMessage(text: String) {
    val message = textSendRequest(text)

    val messageReceiver = serverCommunicationModule.messageReceiver()
    val clientMessenger = clientCommunicationModule.messenger()

    val senderFlow = clientMessenger.send(serverDeviceId, message)
    val senderChannel = senderFlow.testIn(this)
    val receiverChannel = messageReceiver.messageReceivedNotifier.testIn(this)

    // Start coroutines and advance time to complete the operation
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()

    // Wait for send completion
    senderChannel.awaitFor { it is Completed }

    // Verify message received
    coroutines.dispatcher.scheduler.advanceUntilIdle()
    val completedUpdate = receiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
    assertIs<ReceiveMessageStatus.Completed>(completedUpdate.status)
    assertEquals(1, completedUpdate.messages.size)
    assertEquals((message.message as TextMessage).text, (completedUpdate.messages.first() as TextMessage).text)

    senderChannel.cancelAndIgnoreRemainingEvents()
    receiverChannel.cancelAndIgnoreRemainingEvents()
  }
}

internal class FakeMessageRepository : com.carlom.klardrop.common.persistence.MessageRepository {
  override suspend fun insertMessage(remoteDeviceId: String, content: String, isSender: Boolean, messageType: com.carlom.klardrop.common.persistence.MessageType, fileTransferId: Long?, isRead: Boolean): Long = 1L
  override suspend fun insertFileTransfer(fileName: String, filePath: String, totalSize: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus): Long = 1L
  override suspend fun updateFileTransferStatus(id: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus) {}
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {}
  override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
  override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.database.Messages>> = kotlinx.coroutines.flow.flowOf(emptyList())
  override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> = kotlinx.coroutines.flow.flowOf(null)
}
