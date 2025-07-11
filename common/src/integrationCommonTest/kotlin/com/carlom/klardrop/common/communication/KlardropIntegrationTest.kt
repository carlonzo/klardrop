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

  private val clientCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = clientVisibleDevices,
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = KlardropTestFileManager(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(clientDeviceId))
  )

  private val serverCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = FakeVisibleDevices(),
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = KlardropTestFileManager(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(serverDeviceId))
  )

  @Test
  fun startKlardropServerAndSendTextMessage() = runTest(coroutines.dispatcher) {
    val server = serverCommunicationModule.server()
    val serverStatus = server.startServer()

    // add server to visible devices
    clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)


    turbineScope {
      val serverMessageReceiver = serverCommunicationModule.messageReceiver()

      // server receiver
      val receiverChannel = serverMessageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

      // prepare the client to send a message
      val textMessage = textSendRequest()
      val clientMessenger = clientCommunicationModule.messenger()

      // send
      val sendProgressFlow = clientMessenger.send(serverDeviceId, textMessage)
      val sendProgressChannel = sendProgressFlow.testIn(this)

      // Start coroutines and advance time to complete the operation
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceUntilIdle()

      // sender statuses
      sendProgressChannel.awaitFor { it is Completed }

      // receiver statuses
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      val completedUpdate = receiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
      assertIs<ReceiveMessageStatus.Completed>(completedUpdate.status)
      assertEquals(1, completedUpdate.messages.size)
      assertEquals((textMessage.message as TextMessage).text, (completedUpdate.messages.first() as TextMessage).text)

      sendProgressChannel.cancelAndIgnoreRemainingEvents()
      receiverChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun testKlardropSendTwoMessages() = runTest(coroutines.dispatcher) {
    val server = serverCommunicationModule.server()
    val serverStatus = server.startServer()

    // add server to visible devices
    clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)

    turbineScope {
      sendAndReceiveMessage("This is the first message")

      // Now try to send a second message
      sendAndReceiveMessage("This is a second message!")
    }
  }


  // TODO we need an ack back signal when a message is received

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
    val server = serverCommunicationModule.server()
    val serverStatus = server.startServer()

    // Add device to visible devices
    clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)

    turbineScope {

      // send first message between client and server
      println("[TEST-DEBUG] Sending first message...")
      sendAndReceiveMessage("firstMessage")
      println("[TEST-DEBUG] First message completed successfully")

      if (clientDropsConnection) {
        // Force close all client connections to simulate the disconnect issue
        println("[TEST-DEBUG] Closing CLIENT connections...")
        val connectionsPool = clientCommunicationModule.connectionsPool()
        connectionsPool.closeAllConnections()
        println("[TEST-DEBUG] CLIENT connections closed")
      }

      if (serverDropsConnection) {
        // Force close all server connections to simulate the disconnect issue
        println("[TEST-DEBUG] Closing SERVER connections...")
        val connectionsPool = serverCommunicationModule.connectionsPool()
        connectionsPool.closeAllConnections()
        println("[TEST-DEBUG] SERVER connections closed")
      }

      // Wait a bit to ensure cleanup
      println("[TEST-DEBUG] Advancing scheduler to ensure cleanup...")
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      println("[TEST-DEBUG] Cleanup completed")

      val messageReceiver = serverCommunicationModule.messageReceiver()
      val clientMessenger = clientCommunicationModule.messenger()

      // Now try to send a second message - this should trigger reconnection
      val secondMessage = textSendRequest("reconnection messenger message")
      println("[TEST-DEBUG] About to send second message: ${secondMessage.message.id}")

      val secondSendFlow = clientMessenger.send(serverDeviceId, secondMessage)
      val secondSenderChannel = secondSendFlow.testIn(this)
      val secondReceiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

      println("[TEST-DEBUG] Started second message send, now waiting for completion...")

      // Start coroutines and try to advance until completion
      coroutines.dispatcher.scheduler.runCurrent()
      
      // Since connections were dropped, this should trigger timeouts and retries
      // Advance past the ACK timeout (2 seconds) to trigger retry logic
      println("[TEST-DEBUG] Advancing time to trigger timeout and retry...")
      coroutines.dispatcher.scheduler.advanceTimeBy(2100) // Past 2-second ACK timeout
      coroutines.dispatcher.scheduler.runCurrent()
      
      // Allow retry logic to complete
      coroutines.dispatcher.scheduler.advanceUntilIdle()

      // Wait for second message to be sent
      println("[TEST-DEBUG] Waiting for Completed status from second message...")
      
      try {
        secondSenderChannel.awaitFor { 
          println("[TEST-DEBUG] Received progress update: $it")
          it is Completed 
        }
        println("[TEST-DEBUG] Second message completed successfully!")
      } catch (e: Exception) {
        println("[TEST-DEBUG] Test failed waiting for Completed: ${e::class.simpleName}: ${e.message}")
        throw e
      }

      // If it completes, verify the message was received
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      val secondCompletedUpdate = secondReceiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
      assertIs<ReceiveMessageStatus.Completed>(secondCompletedUpdate.status)
      assertEquals(1, secondCompletedUpdate.messages.size)
      assertEquals((secondMessage.message as TextMessage).text, (secondCompletedUpdate.messages.first() as TextMessage).text)
      secondReceiverChannel.cancelAndIgnoreRemainingEvents()

      secondSenderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  private suspend fun TurbineContext.sendAndReceiveMessage(textMessage: String) {
    val firstMessage = textSendRequest(textMessage)

    val messageReceiver = serverCommunicationModule.messageReceiver()
    val clientMessenger = clientCommunicationModule.messenger()

    val senderFlow = clientMessenger.send(serverDeviceId, firstMessage)
    val senderChannel = senderFlow.testIn(this)
    val firstReceiverChannel = messageReceiver.messageReceivedNotifier.testIn(this)

    // Start coroutines and advance time to complete the operation
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()

    // Wait for first message to complete
    senderChannel.awaitFor { it is Completed }

    // Verify first message received
    coroutines.dispatcher.scheduler.advanceUntilIdle()
    val firstCompletedUpdate = firstReceiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
    assertIs<ReceiveMessageStatus.Completed>(firstCompletedUpdate.status)
    assertEquals(1, firstCompletedUpdate.messages.size)
    assertEquals((firstMessage.message as TextMessage).text, (firstCompletedUpdate.messages.first() as TextMessage).text)

    senderChannel.cancelAndIgnoreRemainingEvents()
    firstReceiverChannel.cancelAndIgnoreRemainingEvents()
  }

  private suspend fun <T> ReceiveTurbine<T>.awaitFor(block: ((T) -> Boolean)): T {
    var item: T

    do {
      item = awaitItem()
    } while (!block(item))

    return item
  }

  private fun textSendRequest(text: String = "klardrop protocol test"): SendMessageRequest {
    return SimpleSendMessageRequest(TextMessage("Test Title", text = text))
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
