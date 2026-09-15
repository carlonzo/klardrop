package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import TestCoroutines
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.ClipboardSyncMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardAccess
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clipboard moves with no user prompt on either end, so pairing is the only consent
 * gating it. These cover both directions: we never broadcast to an unpaired peer, and we
 * never write an unpaired peer's content into the local clipboard.
 */
class ClipboardSyncManagerTest {

  private val trustedId = "trusted1"
  private val untrustedId = "stranger"

  private class FakeClipboard(
    initial: String = "",
    changes: Flow<String> = emptyFlow(),
  ) : ClipboardAccess {
    var content: String = initial
      private set
    var writes: Int = 0
      private set
    var collections: Int = 0
      private set

    // Count active collectors so tests can see whether ClipboardSyncManager is
    // actually subscribed (and therefore whether the 500ms poller would run).
    override val flow: Flow<String> = callbackFlow {
      collections++
      val job = launch { changes.collect { send(it) } }
      awaitClose {
        job.cancel()
        collections--
      }
    }
    override fun read(): String = content
    override fun write(text: String) {
      content = text
      writes++
    }
  }

  private class FakeVisibleDevices(devices: List<DeviceInfo> = emptyList()) : VisibleDevices {
    private val state = MutableStateFlow(toMap(devices))

    fun setDevices(devices: List<DeviceInfo>) {
      state.value = toMap(devices)
    }

    private fun toMap(devices: List<DeviceInfo>) = devices.associate { info ->
      info.deviceId to DiscoveryDevice(
        deviceInfo = info,
        deviceConnections = listOf(DeviceConnection.KlardropConnection("127.0.0.1", 1234)),
        lastSeenTimestamp = 0L,
      )
    }

    override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = state
    override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) = Unit
    override fun isDeviceVisible(deviceId: String) = state.value.containsKey(deviceId)
    override fun getDevice(deviceId: String) = state.value[deviceId]
    override fun cachedNameFor(deviceId: String): String? = state.value[deviceId]?.deviceInfo?.name
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
  }

  private class RecordingMessenger : Messenger {
    val sentTo = mutableListOf<String>()

    override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {
      sentTo += deviceId
      return flowOf(MessengerSendProgress.Completed)
    }

    override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> = emptyFlow()
  }

  /** TrustStorage where exactly the listed deviceIds are considered paired. */
  private fun trustManagerWith(trustedIds: Set<String>): TrustManager {
    val storage = object : TrustStorage {
      override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {}
      override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {}
      override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? =
        if (deviceId in trustedIds) byteArrayOf(0x1) else null

      override suspend fun getECDSAKey(deviceId: String): ByteArray? = null
      override suspend fun getAllTrustedDevices(): Map<String, ByteArray> = emptyMap()
      override suspend fun removeTrustedDevice(deviceId: String) {}
      override suspend fun clearAllTrustedDevices() {}
      override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {}
      override suspend fun getDevicePrivateKey(): ByteArray? = null
      override suspend fun storeDevicePublicKey(publicKey: ByteArray) {}
      override suspend fun getDevicePublicKey(): ByteArray? = null
      override suspend fun deleteDevicePrivateKey() {}
    }
    return TrustManager(
      crypto = TrustCrypto(),
      storage = storage,
      clock = Clock(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid00")),
    )
  }

  private fun deviceInfo(id: String) = DeviceInfo(id, "Device $id", DeviceType.MOBILE)

  private fun manager(
    clipboard: ClipboardAccess,
    messenger: Messenger,
    coroutines: TestCoroutines,
    trustedIds: Set<String>,
    visible: List<DeviceInfo> = listOf(deviceInfo(trustedId), deviceInfo(untrustedId)),
    visibleDevices: FakeVisibleDevices = FakeVisibleDevices(visible),
  ) = ClipboardSyncManager(
    clipboardManager = clipboard,
    visibleDevices = visibleDevices,
    trustManager = trustManagerWith(trustedIds),
    clock = Clock(),
    coroutines = coroutines,
    messenger = lazy { messenger },
  )

  @Test
  fun incomingSyncFromUntrustedDeviceIsIgnored() = runTest {
    val clipboard = FakeClipboard("local content")
    val syncManager = manager(clipboard, RecordingMessenger(), TestCoroutines(), trustedIds = setOf(trustedId))

    syncManager.handleIncomingClipboardSync(clipboardMessage("pasted by a stranger"), untrustedId)

    assertEquals("local content", clipboard.content, "Untrusted device must not write the clipboard")
    assertEquals(0, clipboard.writes)
  }

  @Test
  fun incomingSyncWithNoResolvedSenderIsIgnored() = runTest {
    val clipboard = FakeClipboard("local content")
    val syncManager = manager(clipboard, RecordingMessenger(), TestCoroutines(), trustedIds = setOf(trustedId))

    syncManager.handleIncomingClipboardSync(clipboardMessage("anonymous"), senderId = "")

    assertEquals("local content", clipboard.content)
    assertEquals(0, clipboard.writes)
  }

  @Test
  fun incomingSyncFromTrustedDeviceUpdatesClipboard() = runTest {
    val clipboard = FakeClipboard("local content")
    val syncManager = manager(clipboard, RecordingMessenger(), TestCoroutines(), trustedIds = setOf(trustedId))

    syncManager.handleIncomingClipboardSync(clipboardMessage("from my laptop"), trustedId)

    assertEquals("from my laptop", clipboard.content)
    assertEquals(1, clipboard.writes)
    // Sync stays enabled; outgoing echo is muted separately so a visibility snapshot
    // during the window does not cancel the clipboard collector.
    assertTrue(syncManager.isClipboardSyncEnabled())
  }

  @Test
  fun outgoingSyncOnlyReachesTrustedDevices() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val messenger = RecordingMessenger()
    val syncManager = manager(
      clipboard = FakeClipboard(changes = flowOf("copied text")),
      messenger = messenger,
      coroutines = coroutines,
      trustedIds = setOf(trustedId),
    )

    syncManager.startClipboardMonitoring()
    advanceUntilIdle()

    assertEquals(listOf(trustedId), messenger.sentTo, "Only paired devices receive the clipboard")
  }

  @Test
  fun outgoingSyncSendsNothingWithoutAnyTrustedDevice() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val messenger = RecordingMessenger()
    val syncManager = manager(
      clipboard = FakeClipboard(changes = flowOf("copied text")),
      messenger = messenger,
      coroutines = coroutines,
      trustedIds = emptySet(),
    )

    syncManager.startClipboardMonitoring()
    advanceUntilIdle()

    assertTrue(messenger.sentTo.isEmpty(), "Nothing is sent when no device is paired")
  }

  @Test
  fun outgoingSyncStopsWhenSyncIsDisabled() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val messenger = RecordingMessenger()
    val clipboard = FakeClipboard(changes = flowOf("copied text"))
    val syncManager = manager(
      clipboard = clipboard,
      messenger = messenger,
      coroutines = coroutines,
      trustedIds = setOf(trustedId),
    )

    syncManager.startClipboardMonitoring()
    syncManager.setClipboardSyncEnabled(false)
    advanceUntilIdle()

    assertTrue(messenger.sentTo.isEmpty(), "A disabled sync must not push the clipboard anywhere")
    assertEquals(0, clipboard.collections, "Disabled sync must not keep collecting the clipboard flow")
  }

  @Test
  fun clipboardFlowIsNotCollectedWithoutAnyTrustedDevice() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val clipboard = FakeClipboard(changes = flowOf("copied text"))
    val syncManager = manager(
      clipboard = clipboard,
      messenger = RecordingMessenger(),
      coroutines = coroutines,
      trustedIds = emptySet(),
    )

    syncManager.startClipboardMonitoring()
    advanceUntilIdle()

    assertEquals(0, clipboard.collections, "Do not poll the clipboard when nobody is paired")
  }

  @Test
  fun clipboardFlowIsNotCollectedWhenTrustedDeviceIsNotVisible() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val clipboard = FakeClipboard(changes = flowOf("copied text"))
    val syncManager = manager(
      clipboard = clipboard,
      messenger = RecordingMessenger(),
      coroutines = coroutines,
      trustedIds = setOf(trustedId),
      visible = emptyList(),
    )

    syncManager.startClipboardMonitoring()
    advanceUntilIdle()

    assertEquals(0, clipboard.collections, "Paired-but-offline is not enough to start clipboard polling")
  }

  @Test
  fun clipboardFlowStartsWhenTrustedDeviceBecomesVisibleThenStopsWhenItLeaves() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val messenger = RecordingMessenger()
    val clipboard = FakeClipboard(changes = flowOf("copied text"))
    val visibleDevices = FakeVisibleDevices(emptyList())
    val syncManager = manager(
      clipboard = clipboard,
      messenger = messenger,
      coroutines = coroutines,
      trustedIds = setOf(trustedId),
      visibleDevices = visibleDevices,
    )

    syncManager.startClipboardMonitoring()
    advanceUntilIdle()
    assertEquals(0, clipboard.collections, "No trusted peer visible yet; do not collect")

    visibleDevices.setDevices(listOf(deviceInfo(trustedId)))
    advanceUntilIdle()
    assertEquals(1, clipboard.collections, "Trusted peer became visible; start collecting")
    assertEquals(listOf(trustedId), messenger.sentTo, "Outgoing sync reaches the trusted visible peer")

    visibleDevices.setDevices(emptyList())
    advanceUntilIdle()
    assertEquals(0, clipboard.collections, "Last trusted visible peer left; stop collecting")
  }

  @Test
  fun clipboardFlowIsNotCollectedWhenOnlyUntrustedDeviceIsVisible() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val clipboard = FakeClipboard(changes = flowOf("copied text"))
    val syncManager = manager(
      clipboard = clipboard,
      messenger = RecordingMessenger(),
      coroutines = coroutines,
      trustedIds = emptySet(),
      visible = listOf(deviceInfo(untrustedId)),
    )

    syncManager.startClipboardMonitoring()
    advanceUntilIdle()

    assertEquals(0, clipboard.collections, "An untrusted nearby device must not start clipboard polling")
  }

  private fun clipboardMessage(content: String) = ClipboardSyncMessage(
    content = content,
    mimeType = "text/plain",
    timestamp = Clock().currentTimeMillis(),
    signature = ByteArray(0),
  )
}
