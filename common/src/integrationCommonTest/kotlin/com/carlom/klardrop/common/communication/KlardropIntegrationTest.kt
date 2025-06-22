package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.ReceiveTurbine
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.native.concurrent.ThreadLocal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KlardropIntegrationTest {
  private val coroutines = TestCoroutines()
  private val clock = Clock()

  private val clientVisibleDevices = FakeVisibleDevices()

  val clientDeviceId = "client01"
  val serverDeviceId = "server01"

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
    val server = serverCommunicationModule.unifiedServer()
    val serverStatus = server.startServer()

    // add server to visible devices
    clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)


    turbineScope {
      val notifierScope = coroutines.newScope()
      val serverMessageReceiver = serverCommunicationModule.messageReceiver()

      // server receiver
      val receiverChannel = serverMessageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

      // prepare the client to send a message
      val textMessage = textSendRequest()
      val clientMessenger = clientCommunicationModule.messenger()

      // send
      val sendProgressFlow = clientMessenger.send(serverDeviceId, textMessage)
      val sendProgressChannel = sendProgressFlow.testIn(this)

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
  fun testKlardropReconnectionAfterConnectionClosed() = runTest(coroutines.dispatcher) {
    val server = serverCommunicationModule.unifiedServer()
    val serverStatus = server.startServer()

    // add server to visible devices
    clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)

    turbineScope {
      val notifierScope = coroutines.newScope()

      // First connection and message send

      val messageReceiver = serverCommunicationModule.messageReceiver()
      val firstReceiverChannelDelayed = notifierScope.async { messageReceiver.messageReceivedNotifier.testIn(this@turbineScope) }

      val firstMessage = textSendRequest("first message")

      // Send first message
      val clientMessenger = clientCommunicationModule.messenger()

      val firstSenderChannel = clientMessenger.send(serverDeviceId, firstMessage).testIn(this@turbineScope)

      // Verify first message is sent successfully
      firstSenderChannel.awaitFor { it is Completed }

      // Verify first message is received
      val firstReceiverChannel = firstReceiverChannelDelayed.await()
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      val firstCompletedUpdate = firstReceiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
      assertIs<ReceiveMessageStatus.Completed>(firstCompletedUpdate.status)
      assertEquals(1, firstCompletedUpdate.messages.size)
      assertEquals((firstMessage.message as TextMessage).text, (firstCompletedUpdate.messages.first() as TextMessage).text)

      firstSenderChannel.cancelAndIgnoreRemainingEvents()
      firstReceiverChannel.cancelAndIgnoreRemainingEvents()

      // Now try to reconnect and send a second message after the first connection was closed
      val secondReceiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

      val secondMessage = textSendRequest("reconnection message")

      // Attempt to send second message (this should trigger the reconnection issue)
      val secondSenderChannel = clientMessenger.send(serverDeviceId, secondMessage).testIn(this@turbineScope)

      // Check if second message send succeeds or fails
      val secondFirstStatus = secondSenderChannel.awaitItem()
      assertEquals(MessengerSendProgress.Pending, secondFirstStatus)

      val secondSecondStatus = secondSenderChannel.awaitItem()
      secondSecondStatus is Completed

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


  // TODO we need an ack back signal when a message is received

  @Test
  fun testMessengerReconnectionFromClient() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = false)

  @Test
  fun testMessengerReconnectionFromServer() = testMessengerReconnection(clientDropsConnection = false, serverDropsConnection = true)

  @Test
  fun testMessengerReconnectionFromBothSides() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = true)

  // simple method to parametize the test for reconnection issues. using a boolean to indicate if the client should drop the connection or the server
  @Suppress("VisibleForTests")
  private fun testMessengerReconnection(clientDropsConnection: Boolean, serverDropsConnection: Boolean) = runTest(coroutines.dispatcher) {
    val server = serverCommunicationModule.unifiedServer()
    val serverStatus = server.startServer()

    // Add device to visible devices
    clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)

    turbineScope {
      val notifierScope = coroutines.newScope()

      val firstMessage = textSendRequest("first messenger message")

      val messageReceiver = serverCommunicationModule.messageReceiver()
      val clientMessenger = clientCommunicationModule.messenger()

      val firstSendFlow = clientMessenger.send(serverDeviceId, firstMessage)
      val firstSenderChannel = firstSendFlow.testIn(this)
      val firstReceiverChannelDelayed = notifierScope.async { messageReceiver.messageReceivedNotifier.testIn(this@turbineScope) }

      // Wait for first message to complete
      firstSenderChannel.awaitFor { it is Completed }

      // Verify first message received
      val firstReceiverChannel = firstReceiverChannelDelayed.await()
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      val firstCompletedUpdate = firstReceiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
      assertIs<ReceiveMessageStatus.Completed>(firstCompletedUpdate.status)
      assertEquals(1, firstCompletedUpdate.messages.size)
      assertEquals((firstMessage.message as TextMessage).text, (firstCompletedUpdate.messages.first() as TextMessage).text)

      firstSenderChannel.cancelAndIgnoreRemainingEvents()
      firstReceiverChannel.cancelAndIgnoreRemainingEvents()

      if (clientDropsConnection) {
        // Force close all client connections to simulate the disconnect issue
        val connectionsPool = clientCommunicationModule.connectionsPool()
        connectionsPool.closeAllConnections()
      } else {
        // Force close all server connections to simulate the disconnect issue
        val connectionsPool = serverCommunicationModule.connectionsPool()
        connectionsPool.closeAllConnections()
      }

      // Wait a bit to ensure cleanup
      coroutines.dispatcher.scheduler.advanceUntilIdle()

      // Now try to send a second message - this should trigger reconnection
      val secondMessage = textSendRequest("reconnection messenger message")

      val secondSendFlow = clientMessenger.send(serverDeviceId, secondMessage)
      val secondSenderChannel = secondSendFlow.testIn(this)
      val secondReceiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

      // Wait for second message to be sent
      secondSenderChannel.awaitFor { it is Completed }

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
