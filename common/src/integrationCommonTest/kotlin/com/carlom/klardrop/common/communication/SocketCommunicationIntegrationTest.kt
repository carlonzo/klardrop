package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.UtilsModule
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocketCommunicationIntegrationTest {

  private val testCoroutines = TestCoroutines()
  private val fakeLocalPropertiesRepository = FakeLocalPropertiesRepository()
  private val fakeFileManager = FakeFileManager()
  private val fakeVisibleDevices = FakeVisibleDevices()
  private val utilsModule = UtilsModule()

  @Test
  fun serverClientSocketCommunication() = runTest(testCoroutines.dispatcher) {
    // Setup server dependencies
    val serverCurrentDeviceProvider = CurrentDeviceProvider(fakeLocalPropertiesRepository)
    val serverCommunicationModule = CommunicationModule(
      coroutines = testCoroutines,
      visibleDevices = fakeVisibleDevices,
      protoBuf = ProtoBuf,
      clock = utilsModule.clock(),
      fileManager = fakeFileManager,
      currentDeviceProvider = serverCurrentDeviceProvider
    )

    // Setup client dependencies  
    val clientLocalPropertiesRepository = FakeLocalPropertiesRepository()
    val clientVisibleDevices = FakeVisibleDevices()
    val clientCurrentDeviceProvider = CurrentDeviceProvider(clientLocalPropertiesRepository)
    val clientCommunicationModule = CommunicationModule(
      coroutines = testCoroutines,
      visibleDevices = clientVisibleDevices,
      protoBuf = ProtoBuf,
      clock = utilsModule.clock(),
      fileManager = fakeFileManager,
      currentDeviceProvider = clientCurrentDeviceProvider
    )

    // Initialize device properties for both client and server (keep short for shortDeviceId compatibility)
    val serverDeviceId = "server01"
    val clientDeviceId = "client01"
    
    fakeLocalPropertiesRepository.save(KlardropProperties(serverDeviceId))
    clientLocalPropertiesRepository.save(KlardropProperties(clientDeviceId))

    // Start unified server
    val unifiedServer = serverCommunicationModule.unifiedServer()
    val serverConfig = unifiedServer.startServer()
    
    assertTrue(serverConfig.port > 0, "Server should start on a valid port")
    
    // Add server as visible device for client
    val serverDeviceInfo = DeviceInfo(
      deviceId = serverDeviceId,
      name = "Test Server",
      deviceType = DeviceType.DESKTOP,
      osType = OsType.UNKNOWN
    )
    val serverConnection = DeviceConnection.KlardropConnection(
      address = "localhost",
      port = serverConfig.port
    )
    clientVisibleDevices.addDevice(serverDeviceInfo, serverConnection)

    // Get client and message receiver
    val client = clientCommunicationModule.client()
    val messageReceiver = serverCommunicationModule.messageReceiver()

    // Create test message
    val testText = "Hello from client to server via socket!"
    val testTitle = "Socket Test Message"
    val textMessage = TextMessage(title = testTitle, text = testText)
    val sendRequest = SimpleSendMessageRequest(textMessage)

    turbineScope {
      // Setup message receiver monitoring
      val receiverScope = testCoroutines.newScope()
      val receiverChannelDelayed = receiverScope.async { 
        messageReceiver.notifier.first().testIn(this@turbineScope) 
      }

      // Connect client to server and send message
      client.connectTo(serverDeviceId)
      
      // Allow some time for connection establishment
      delay(100)

      // Send the message using the messenger
      val messenger = clientCommunicationModule.messenger()
      val sendProgressFlow = messenger.send(serverDeviceId, sendRequest)

      // Verify send progress
      val sendProgress = sendProgressFlow.first { it.isCompleted() }
      assertIs<MessengerSendProgress.Completed>(sendProgress, "Message should be sent successfully")

      // Verify message received on server side
      val receiverChannel = receiverChannelDelayed.await()
      testCoroutines.dispatcher.scheduler.advanceUntilIdle()

      val receivedUpdate = receiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }

      // Assertions
      assertIs<ReceiveMessageStatus.Completed>(receivedUpdate.status, "Message should be received successfully")
      assertEquals(1, receivedUpdate.messages.size, "Should receive exactly one message")
      
      val receivedMessage = receivedUpdate.messages.first()
      assertIs<TextMessage>(receivedMessage, "Received message should be TextMessage")
      assertEquals(testText, receivedMessage.text, "Message text should match")
      assertEquals(testTitle, receivedMessage.title, "Message title should match")

      receiverChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  private suspend fun <T> ReceiveTurbine<T>.awaitFor(predicate: (T) -> Boolean): T {
    var item: T
    do {
      item = awaitItem()
    } while (!predicate(item))
    return item
  }
}

// Test helper classes
internal class FakeFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    error("File operations not needed for socket communication test")
  }

  override fun getReadStreamFrom(file: PlatformFile): Source {
    error("File operations not needed for socket communication test")
  }
}

private class FakeVisibleDevices : VisibleDevices {
  private val devices = mutableMapOf<String, DiscoveryDevice>()

  fun addDevice(deviceInfo: DeviceInfo, connection: DeviceConnection) {
    devices[deviceInfo.deviceId] = DiscoveryDevice(
      deviceInfo = deviceInfo,
      deviceConnections = listOf(connection)
    )
  }

  override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? {
    return devices.values.firstOrNull { device ->
      device.deviceConnections.any { 
        when (it) {
          is DeviceConnection.KlardropConnection -> it.address == address.hostname
          is DeviceConnection.NearbyConnection -> it.address == address.hostname
        }
      }
    }
  }

  override val visibleDevices: Flow<Map<String, DiscoveryDevice>>
    get() = MutableStateFlow(devices)

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
    addDevice(deviceInfo, deviceConnection)
  }

  override fun isDeviceVisible(deviceId: String): Boolean {
    return devices.containsKey(deviceId)
  }

  override fun getDevice(deviceId: String): DiscoveryDevice? {
    return devices[deviceId]
  }

  override fun onDeviceLost(deviceId: String) {
    devices.remove(deviceId)
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {
    // For simplicity, just remove the whole device
    devices.remove(deviceId)
  }
}

