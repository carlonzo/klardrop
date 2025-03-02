package com.carlom.klardrop.common.mdns

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.BufferedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NearbyIntegrationTest {

  private val coroutines = TestCoroutines()
  private val fakeFileManager = FakeFileManager()
  private val fakeVisibleDevices = FakeVisibleDevices()
  private val fakeLocalPropertiesRepository = FakeLocalPropertiesRepository()

  private val messageReceiver = MessageReceiverImpl(coroutines, fakeVisibleDevices)
  private val currentDeviceProvider = CurrentDeviceProvider(fakeLocalPropertiesRepository)
  private val receiverConnectionHandlerFactory = NearbyReceiverConnectionHandlerFactory(fakeFileManager, coroutines)
  private val shareServer = NearbyShareServer(coroutines, receiverConnectionHandlerFactory, fakeVisibleDevices, messageReceiver)
  private val shareClient = NearbyClient(coroutines, currentDeviceProvider, fakeFileManager)

  @Test
  fun startNearbyServer() = runTest(coroutines.dispatcher) {
    shareServer.start()
    val serverStatus = shareServer.status.first()
    assertTrue { serverStatus.isRunning }

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

    shareServer.cancel()
  }

  private suspend fun <T> ReceiveTurbine<T>.awaitFor(block: ((T)-> Boolean)): T{
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

private class FakeFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    error("not implemented")
  }

  override fun getReadStreamFromUri(fileName: String): Source {
    error("not implemented")
  }

}

private class FakeVisibleDevices : VisibleDevices {

  val devices = mutableListOf<DiscoveryDevice>()

  init {
    devices.add(
      DiscoveryDevice(
        deviceInfo = DeviceInfo(
          "deviceid-123",
          "fake-device-nama",
          deviceType = DeviceType.MOBILE,
          osType = OsType.ANDROID,
        ),

        )
    )
  }

  override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? {
    val hostname = address.hostname

    return devices.firstOrNull { device -> device.deviceConnections.any { it.address == hostname } }
  }

  override val visibleDevices: Flow<Map<String, DiscoveryDevice>>
    get() = error("not required to be implemented for this test")

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
    error("not required to be implemented for this test")
  }

  override fun isDeviceVisible(deviceId: String): Boolean {
    return devices.any { it.deviceInfo.deviceId == deviceId }
  }

  override fun getDevice(deviceId: String): DiscoveryDevice? {
   return devices.firstOrNull { it.deviceInfo.deviceId == deviceId }
  }

  override fun onDeviceLost(deviceId: String) {
    devices.removeAll { it.deviceInfo.deviceId == deviceId }
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {
    error("not required to be implemented for this test")
  }


}

//private class FakeMessageReceiver : MessageReceiver {
//
//  private val _notifier = MutableSharedFlow<StateFlow<ReceiveMessageUpdate>>()
//
//  override fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate> {
//    val receiveMessageFlow = MutableStateFlow(
//      ReceiveMessageUpdate(
//        DeviceInfo(
//          deviceId = deviceId,
//          name = "",
//          deviceType = DeviceType.UNKNOWN
//        ), emptyList(), ReceiveMessageStatus.Started
//      )
//    )
//    _notifier.tryEmit(receiveMessageFlow)
//
//
//    return receiveMessageFlow
//  }
//
//  override val notifier: Flow<Flow<ReceiveMessageUpdate>>
//    get() = _notifier
//
//}