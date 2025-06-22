package com.carlom.klardrop.common.mdns

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.createTestUnifiedServer
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NearbyIntegrationTest {

  private val coroutines = TestCoroutines()
  private val fakeFileManager = FakeFileManager()
  private val fakeVisibleDevices = FakeVisibleDevices()
  private val fakeLocalPropertiesRepository = FakeLocalPropertiesRepository()
  private val messageReceiver = MessageReceiverImpl(coroutines, fakeVisibleDevices)
  private val currentDeviceProvider = CurrentDeviceProvider(fakeLocalPropertiesRepository)
  private val shareClient = NearbyClient(coroutines, currentDeviceProvider, fakeFileManager)

  private val server = createTestUnifiedServer(
    fileManager = fakeFileManager,
    coroutines = coroutines,
    localPropertiesRepository = fakeLocalPropertiesRepository,
    visibleDevices = fakeVisibleDevices,
    messageReceiver = messageReceiver
    )

  @Test
  fun startNearbyServer() = runTest(coroutines.dispatcher) {
    val serverStatus = server.startServer()

    val sendProgressFlow = MutableSharedFlow<MessengerSendProgress>()

    turbineScope {
      val senderChannel = sendProgressFlow.testIn(this)
      val notifierScope = coroutines.newScope()
      val receiverChannelDelayed = notifierScope.async { messageReceiver.notifier.first().testIn(this@turbineScope) }

      val textMessage = textSendRequest()

      shareClient.send("localhost", serverStatus.port, listOf(textMessage), sendProgressFlow)

      // sender statuses
      assertEquals(MessengerSendProgress.Pending, senderChannel.awaitItem())
      assertEquals(MessengerSendProgress.Completed, senderChannel.awaitItem())

      // receiver statuses
      val receiverChannel = receiverChannelDelayed.await()
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      val completedUpdate = receiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
      assertIs<ReceiveMessageStatus.Completed>(completedUpdate.status)
      assertEquals(1, completedUpdate.messages.size)
      assertEquals((textMessage.message as TextMessage).text, (completedUpdate.messages.first() as TextMessage).text)

      senderChannel.cancelAndIgnoreRemainingEvents()
      receiverChannel.cancelAndIgnoreRemainingEvents()
    }

  }

  private suspend fun <T> ReceiveTurbine<T>.awaitFor(block: ((T) -> Boolean)): T {
    var item: T?

    do {
      item = awaitItem()
    } while (!block(item!!))

    return item
  }

  private fun textSendRequest(text: String = "marion is cute"): SendMessageRequest {
    return SimpleSendMessageRequest(TextMessage("This is a title", text = text))
  }
}

internal class FakeFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    error("not implemented")
  }

  override fun getReadStreamFrom(file: PlatformFile): Source {
    error("not implemented")
  }

}