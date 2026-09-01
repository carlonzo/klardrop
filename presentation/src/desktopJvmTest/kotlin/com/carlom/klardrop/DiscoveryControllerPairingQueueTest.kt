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

  /**
   * T11: a pairing/connect failure while this device's OS actively blocks
   * connectivity (battery saver) must be prefixed with the restriction so the
   * user looks at THIS device first, not the peer.
   */
  @Test
  fun pairingFailureWhileRestrictedIsPrefixedWithTheRestriction() = runTest(dispatcher) {
    val monitor = com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor(
      com.carlom.klardrop.common.connectivity.ConnectivityRestrictions(
        batterySaverBlocking = true,
        batteryOptimizationNotExempt = true,
      )
    )
    val visibleDevices = FakeVisibleDevices()
    val coordinator = PairingProtocolCoordinator(
      TrustManager(
        crypto = TrustCrypto(),
        storage = InMemoryTrustStorage(),
        clock = Clock(),
        currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("controller01")),
      ),
      FakeMessenger(),
    )
    val controller = newController(monitor, visibleDevices, coordinator)
    try {
      visibleDevices.push(
        DiscoveryDevice(
          deviceInfo = com.carlom.klardrop.common.discovery.DeviceInfo(
            deviceId = "ghost",
            name = "Ghost",
            deviceType = com.carlom.klardrop.common.utils.DeviceType.DESKTOP,
          ),
          deviceConnections = listOf(
            DeviceConnection.KlardropConnection(address = "10.0.2.2", port = 1)
          ),
          lastSeenTimestamp = 0L,
        )
      )
      advanceUntilIdle()

      // Drive the same callback the coordinator fires on a real connect failure.
      coordinator.onPairingFailed?.invoke("ghost", "connect-failed(IOException)")
      advanceUntilIdle()

      val error = controller.screenStateFlow.value.devices
        .first { it.deviceId == "ghost" }
        .pairingError
      assertEquals(
        "Battery saver is blocking Klardrop — " +
          "Could not reach Ghost — the device may be offline or a firewall blocks direct connections",
        error,
      )
    } finally {
      controller.dispose()
    }
  }

  @Test
  fun pairingFailureWhileUnrestrictedIsNotPrefixed() = runTest(dispatcher) {
    val visibleDevices = FakeVisibleDevices()
    val coordinator = PairingProtocolCoordinator(
      TrustManager(
        crypto = TrustCrypto(),
        storage = InMemoryTrustStorage(),
        clock = Clock(),
        currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("controller01")),
      ),
      FakeMessenger(),
    )
    val controller = newController(visibleDevices = visibleDevices, pairingProtocolCoordinator = coordinator)
    try {
      visibleDevices.push(
        DiscoveryDevice(
          deviceInfo = com.carlom.klardrop.common.discovery.DeviceInfo(
            deviceId = "ghost",
            name = "Ghost",
            deviceType = com.carlom.klardrop.common.utils.DeviceType.DESKTOP,
          ),
          deviceConnections = listOf(
            DeviceConnection.KlardropConnection(address = "10.0.2.2", port = 1)
          ),
          lastSeenTimestamp = 0L,
        )
      )
      advanceUntilIdle()

      coordinator.onPairingFailed?.invoke("ghost", "connect-failed(IOException)")
      advanceUntilIdle()

      val error = controller.screenStateFlow.value.devices
        .first { it.deviceId == "ghost" }
        .pairingError
      assertEquals(
        "Could not reach Ghost — the device may be offline or a firewall blocks direct connections",
        error,
      )
    } finally {
      controller.dispose()
    }
  }

  /**
   * Not every PairingFailed means "we are not paired". The acceptor emits
   * response-delivery-failed AFTER it has already persisted trust, and the receiver emits
   * clock-skew for a peer that may be trusted already. Downgrading the row on those would
   * show a paired device as unpaired while the trust store says otherwise, so the store —
   * not the event — decides the trust status. The error message still surfaces.
   */
  @Test
  fun pairingFailureForAnAlreadyTrustedDeviceKeepsItTrusted() = runTest(dispatcher) {
    val trustStorage = InMemoryTrustStorage()
    trustStorage.storeTrustedDevice("ghost", ByteArray(1))
    val visibleDevices = FakeVisibleDevices()
    val coordinator = PairingProtocolCoordinator(
      TrustManager(
        crypto = TrustCrypto(),
        storage = trustStorage,
        clock = Clock(),
        currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("controller01")),
      ),
      FakeMessenger(),
    )
    val controller = newController(
      visibleDevices = visibleDevices,
      pairingProtocolCoordinator = coordinator,
      trustStorage = trustStorage,
    )
    try {
      visibleDevices.push(
        DiscoveryDevice(
          deviceInfo = com.carlom.klardrop.common.discovery.DeviceInfo(
            deviceId = "ghost",
            name = "Ghost",
            deviceType = com.carlom.klardrop.common.utils.DeviceType.DESKTOP,
          ),
          deviceConnections = listOf(
            DeviceConnection.KlardropConnection(address = "10.0.2.2", port = 1)
          ),
          lastSeenTimestamp = 0L,
        )
      )
      advanceUntilIdle()
      assertEquals(
        TrustStatus.Trusted,
        controller.screenStateFlow.value.devices.first { it.deviceId == "ghost" }.trustStatus,
      )

      // The acceptor's own trust is already stored; only the response never made it out.
      coordinator.onPairingFailed?.invoke("ghost", "response-delivery-failed")
      advanceUntilIdle()

      val device = controller.screenStateFlow.value.devices.first { it.deviceId == "ghost" }
      assertEquals(TrustStatus.Trusted, device.trustStatus)
      assertEquals("Could not pair with Ghost", device.pairingError)
    } finally {
      controller.dispose()
    }
  }

  private fun newController(
    connectivityRestrictionMonitor: com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor =
      com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor(),
    visibleDevices: FakeVisibleDevices = FakeVisibleDevices(),
    pairingProtocolCoordinator: PairingProtocolCoordinator? = null,
    trustStorage: InMemoryTrustStorage = InMemoryTrustStorage(),
  ): DiscoveryController {
    val coroutines = FakeCoroutines(dispatcher)
    val trustManager = TrustManager(
      crypto = TrustCrypto(),
      storage = trustStorage,
      clock = Clock(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("controller01")),
    )
    val messenger = FakeMessenger()
    val localProperties = FakeLocalPropertiesRepository("controller01")
    val coordinator = pairingProtocolCoordinator ?: PairingProtocolCoordinator(trustManager, messenger)
    val trustedDevicesDirectory = TrustedDevicesDirectory(
      visibleDevices = visibleDevices,
      knownDevicesRepository = FakeKnownDevicesRepository(),
      trustStorage = trustStorage,
      trustChanges = emptyFlow(),
      coroutines = coroutines,
    )
    return DiscoveryController(
      coroutines = coroutines,
      visibleDevices = visibleDevices,
      messenger = messenger,
      platformFileSystem = FakePlatformFileSystem(),
      clipboardManager = ClipboardManager(coroutines, com.carlom.klardrop.common.features.ClipboardReaderWriter()),
      messageRepository = QueueTestMessageRepository(),
      trustedDevicesDirectory = trustedDevicesDirectory,
      trustManager = trustManager,
      pairingProtocolCoordinator = coordinator,
      currentDeviceProvider = CurrentDeviceProvider(localProperties),
      localPropertiesRepository = localProperties,
      connectionInfoJoiner = FakeConnectionInfoJoiner(),
      reachability = MutableStateFlow(emptyMap<String, Reachability>()),
      permissionsMonitor = com.carlom.klardrop.common.permissions.PermissionsMonitor(),
      connectivityRestrictionMonitor = connectivityRestrictionMonitor,
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
  private val devices = MutableStateFlow(emptyMap<String, DiscoveryDevice>())
  override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = devices

  /** Test seam: make a device visible without going through discovery. */
  fun push(device: DiscoveryDevice) {
    devices.value = devices.value + (device.deviceInfo.deviceId to device)
  }

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
