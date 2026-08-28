package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.TrustedDevicesDirectory
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.persistence.ChatMessage
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.PairingProtocolCoordinator
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * (ii) concurrent-requests-both-shown: a pairing request arriving while another pairing
 * dialog is active must be QUEUED (FIFO) and presented as soon as the active dialog
 * resolves — not swallowed. A duplicate/replayed request for a device that is already
 * showing or already queued is deduped: one dialog per device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryControllerPairingQueueTest {

  private val dispatcher = StandardTestDispatcher()

  @Test
  fun concurrentPairingRequestsAreQueuedAndPresentedAfterTheActiveDialogResolves() = runTest(dispatcher) {
    val controller = newController()
    try {
      fun dialog() = controller.screenStateFlow.value.pairingDialogState

      controller.onPairingRequested("deviceA", "Device A", "DESKTOP", onAccept = {}, onReject = {})
      advanceUntilIdle()
      assertEquals("deviceA", dialog()?.deviceId)

      // A second, concurrent request must be queued — not ignored.
      controller.onPairingRequested("deviceB", "Device B", "MOBILE", onAccept = {}, onReject = {})
      advanceUntilIdle()
      assertEquals("deviceA", dialog()?.deviceId, "Active dialog must stay up while another request queues")

      // Replay of the request that is already showing: deduped, never queued behind itself.
      controller.onPairingRequested("deviceA", "Device A", "DESKTOP", onAccept = {}, onReject = {})
      advanceUntilIdle()
      assertEquals("deviceA", dialog()?.deviceId)

      // Resolving the active dialog presents the queued request next.
      dialog()!!.onAccept()
      advanceUntilIdle()
      assertEquals("deviceB", dialog()?.deviceId, "Queued request must be presented after the active dialog resolves")

      // The deduped duplicate must not re-appear once deviceB's dialog resolves.
      dialog()!!.onAccept()
      advanceUntilIdle()
      assertNull(dialog(), "Deduped duplicate for deviceA must not be presented")
    } finally {
      controller.dispose()
    }
  }

  private fun newController(): DiscoveryController {
    val coroutines = FakeCoroutines(dispatcher)
    val trustStorage = InMemoryTrustStorage()
    val trustManager = TrustManager(
      crypto = TrustCrypto(),
      storage = trustStorage,
      clock = Clock(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("controller01")),
    )
    val messenger = FakeMessenger()
    val localProperties = FakeLocalPropertiesRepository("controller01")
    val trustedDevicesDirectory = TrustedDevicesDirectory(
      visibleDevices = FakeVisibleDevices(),
      knownDevicesRepository = FakeKnownDevicesRepository(),
      trustStorage = trustStorage,
      trustChanges = emptyFlow(),
      coroutines = coroutines,
    )
    return DiscoveryController(
      coroutines = coroutines,
      visibleDevices = FakeVisibleDevices(),
      messenger = messenger,
      platformFileSystem = FakePlatformFileSystem(),
      clipboardManager = ClipboardManager(coroutines, com.carlom.klardrop.common.features.ClipboardReaderWriter()),
      messageRepository = QueueTestMessageRepository(),
      trustedDevicesDirectory = trustedDevicesDirectory,
      trustManager = trustManager,
      pairingProtocolCoordinator = PairingProtocolCoordinator(trustManager, messenger),
      currentDeviceProvider = CurrentDeviceProvider(localProperties),
      localPropertiesRepository = localProperties,
      connectionInfoJoiner = FakeConnectionInfoJoiner(),
      reachability = MutableStateFlow(emptyMap<String, Reachability>()),
      permissionsMonitor = com.carlom.klardrop.common.permissions.PermissionsMonitor(),
      notifier = com.carlom.klardrop.common.notifications.Notifier(),
      foregroundState = com.carlom.klardrop.common.notifications.ForegroundState(),
    )
  }
}

private class FakeCoroutines(private val dispatcher: CoroutineDispatcher) : Coroutines {
  override fun newScope(): CoroutineScope = CoroutineScope(dispatcher)
  override fun newScope(context: CoroutineContext): CoroutineScope = CoroutineScope(context + dispatcher)
  override val appScope: CoroutineScope = CoroutineScope(dispatcher)
  override val ioDispatcher: CoroutineDispatcher = dispatcher
  override val mainDispatcher: CoroutineDispatcher = dispatcher
  override val cpuDispatcher: CoroutineDispatcher = dispatcher
}

/** Mirrors the fake in TrustedDevicesDirectoryTest: no periodic staleness sweep to spin on. */
private class FakeVisibleDevices : VisibleDevices {
  override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = MutableStateFlow(emptyMap())
  override suspend fun onNewDeviceVisible(deviceInfo: com.carlom.klardrop.common.discovery.DeviceInfo, deviceConnection: DeviceConnection) = Unit
  override fun isDeviceVisible(deviceId: String) = false
  override fun getDevice(deviceId: String): DiscoveryDevice? = null
  override fun cachedNameFor(deviceId: String): String? = null
  override fun touchLastSeen(deviceId: String) = Unit
  override fun onDeviceLost(deviceId: String) = Unit
  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
  override fun findDeviceByAddress(address: io.ktor.network.sockets.InetSocketAddress): DiscoveryDevice? = null
  override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
}

private class FakeMessenger : Messenger {
  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<com.carlom.klardrop.common.communication.MessengerSendProgress> =
    flowOf(com.carlom.klardrop.common.communication.MessengerSendProgress.Completed)

  override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> = emptyFlow()
}

private class FakePlatformFileSystem : PlatformFileSystem {
  override fun getReadStreamFrom(platformFile: PlatformFile): kotlinx.io.RawSource =
    error("not used in this test")
  override fun getWriteStreamTo(path: kotlinx.io.files.Path): kotlinx.io.RawSink =
    error("not used in this test")
  override fun getResolvedFileData(platformFile: PlatformFile) =
    error("not used in this test")
  override suspend fun prepareFileForSending(platformFile: PlatformFile) =
    error("not used in this test")
  override suspend fun delete(path: kotlinx.io.files.Path) = Unit
  override suspend fun moveToStorage(path: kotlinx.io.files.Path, mimeType: String): kotlinx.io.files.Path? = null
  override fun getTempStoragePath(): kotlinx.io.files.Path = error("not used in this test")
  override fun getInternalStoragePath(): kotlinx.io.files.Path = error("not used in this test")
  override suspend fun openFile(filePath: String): Boolean = false
  override suspend fun openUrl(url: String): Boolean = false
}

private class QueueTestMessageRepository : MessageRepository {
  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: MessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String,
    messageId: Long?,
    sendStatus: SendStatus,
  ): Long = 0L
  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: FileTransferStatus,
    mimeType: String,
  ): Long = 0L
  override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) = Unit
  override suspend fun markStaleInProgressAsFailed() = Unit
  override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>> = flowOf(emptyList())
  override fun getFileTransferById(id: Long): Flow<File_transfers?> = flowOf(null)
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) = Unit
  override suspend fun markMessagesAsRead(remoteDeviceId: String) = Unit
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>> = flowOf(emptyMap())
}

private class FakeKnownDevicesRepository : KnownDevicesRepository {
  override val knownDevices: Flow<Map<String, com.carlom.klardrop.common.discovery.DeviceInfo>> = flowOf(emptyMap())
  override suspend fun addKnownDevice(deviceInfo: com.carlom.klardrop.common.discovery.DeviceInfo) = Unit
  override suspend fun removeKnownDevice(deviceId: String) = Unit
}

private class FakeLocalPropertiesRepository(deviceId: String) : LocalPropertiesRepository {
  override val properties = MutableStateFlow(KlardropProperties(deviceId))
  override suspend fun getProperty(): KlardropProperties = properties.value
  override suspend fun save(properties: KlardropProperties) {
    this.properties.value = properties
  }
  override suspend fun saveCustomDeviceName(customDeviceName: String?) {
    save(properties.value.copy(customDeviceName = customDeviceName))
  }
  override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
    save(properties.value.copy(backgroundDiscoveryEnabled = enabled))
  }
}

private class FakeConnectionInfoJoiner : ConnectionInfoJoiner {
  override suspend fun tryJoin(message: ConnectionInfoMessage): Boolean = false
}
