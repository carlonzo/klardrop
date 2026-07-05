package com.carlom.klardrop.common.communication

import TestCoroutines
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repro/regression for connection-review round 3, finding 1: [ClientImpl.connectTo]'s `finally`
 * block used to clean up `inFlightConnects` with a plain `inFlightMutex.withLock { ... }` — a
 * suspending lock acquisition with NO `withContext(NonCancellable)` guard. `Mutex.lock()` only
 * checks cancellation on its *suspending* (contended) path: if some other `connectTo` call holds
 * `inFlightMutex` at the exact moment a cancelled owner's `finally` runs, `withLock` suspends,
 * immediately observes the already-cancelled job, and throws `CancellationException` BEFORE the
 * cleanup lambda ever runs — so `inFlightConnects.remove(deviceId)` never executes and the
 * completed (Failed) deferred is leaked in the map forever. Every subsequent `connectTo(deviceId)`
 * then finds that stale entry in `obtainInFlightDeferred`, coalesces onto it, and immediately
 * replays the old `Failed` outcome — the device becomes permanently undialable outbound.
 *
 * This test deterministically forces that exact contention window: it reflectively grabs the
 * private `inFlightMutex`/`inFlightConnects` fields, externally holds the mutex (via the
 * non-suspending `tryLock`/`unlock` pair — no need to keep a coroutine parked) while cancelling
 * the in-flight owner, and asserts the map entry is still cleaned up once the external hold is
 * released — i.e. the cleanup survives cancellation instead of being skipped by it.
 */
class ClientDialCoalescingCleanupCancellationTest {

  /** Exposes exactly one peer whose only Klardrop endpoint is the given stalling address/port. */
  private class SingleKlardropPeer(
    private val deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Coalescing Cleanup Cancellation Test Peer",
        deviceType = DeviceType.DESKTOP,
        osType = OsType.LINUX,
      ),
      deviceConnections = listOf(DeviceConnection.KlardropConnection(address, port)),
      lastSeenTimestamp = 0L,
    )
    private val flow = MutableStateFlow(mapOf(deviceId to device))
    override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = flow

    override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) = Unit
    override fun isDeviceVisible(deviceId: String) = flow.value.containsKey(deviceId)
    override fun getDevice(deviceId: String) = flow.value[deviceId]
    override fun cachedNameFor(deviceId: String) = null
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) { flow.value = emptyMap() }
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
  }

  private class FixedIdPropertiesRepository(deviceId: String) : LocalPropertiesRepository {
    override val properties = MutableStateFlow(KlardropProperties(deviceId))
    override suspend fun getProperty() = properties.value
    override suspend fun save(properties: KlardropProperties) { this.properties.value = properties }
    override suspend fun saveCustomDeviceName(customDeviceName: String?) {
      properties.value = properties.value.copy(customDeviceName = customDeviceName)
    }
    override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
      properties.value = properties.value.copy(backgroundDiscoveryEnabled = enabled)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun inFlightMutexOf(client: ClientImpl): Mutex {
    val field = ClientImpl::class.java.getDeclaredField("inFlightMutex")
    field.isAccessible = true
    return field.get(client) as Mutex
  }

  @Suppress("UNCHECKED_CAST")
  private fun inFlightConnectsOf(client: ClientImpl): MutableMap<String, *> {
    val field = ClientImpl::class.java.getDeclaredField("inFlightConnects")
    field.isAccessible = true
    return field.get(client) as MutableMap<String, *>
  }

  @Test
  fun ownerCancellationDuringContendedLockStillCleansUpInFlightMap() = runBlocking(Dispatchers.IO) {
    val stallServerSocket = ServerSocket()
    stallServerSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val stallPort = stallServerSocket.localPort
    val accepterThread = thread(name = "stall-accepter", isDaemon = true) {
      runCatching { stallServerSocket.accept() } // accept and hold; never reply
    }

    val serverId = "cleancan"
    val visibleDevices = SingleKlardropPeer(serverId, "127.0.0.1", stallPort)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("cleacli1"))
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)
    val coroutines = TestCoroutines()
    val clientPool = ConnectionsPoolImpl()

    val client = ClientImpl(
      connectionsPool = clientPool,
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = MessageSerializer(ProtoBuf, coroutines),
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider,
      trustManager = trustManager,
      bleTransport = null,
    )

    // ClientImpl mirrors visibleDevices via stateIn(Eagerly); give the sharing coroutine a
    // moment to copy the already-populated StateFlow value before we dial.
    delay(200)

    val mutex = inFlightMutexOf(client)
    val inFlightConnects = inFlightConnectsOf(client)

    try {
      // Owner: dials and gets stuck against the stall endpoint (TCP connect succeeds, the peer
      // never replies to the greeting) — this keeps its connectTo() suspended long enough for us
      // to control the cancellation window precisely.
      val owner = async(Dispatchers.IO) { client.connectTo(serverId) }
      delay(300) // let the owner register itself in inFlightConnects and reach the stalled read
      assertTrue(inFlightConnects.containsKey(serverId), "owner must have registered itself as in-flight")

      // Externally hold inFlightMutex — tryLock/unlock are plain (non-suspending) calls, so no
      // coroutine needs to stay parked to keep the hold; this deterministically forces the
      // owner's finally-block cleanup to hit the SUSPENDING (contended) path of withLock.
      assertTrue(mutex.tryLock(), "test must be able to acquire the uncontended mutex first")

      owner.cancel()
      // Give the owner's coroutine time to unwind into its `finally` block and suspend on the
      // now-contended inFlightMutex.withLock — without the NonCancellable fix this would instead
      // throw CancellationException immediately and skip the cleanup lambda entirely.
      delay(300)
      assertTrue(
        inFlightConnects.containsKey(serverId),
        "sanity: cleanup must still be pending while we hold the mutex externally",
      )

      mutex.unlock()

      var ownerObservedCancellation = false
      try {
        withTimeout(2_000) { owner.await() }
      } catch (e: CancellationException) {
        ownerObservedCancellation = true
      }
      assertTrue(ownerObservedCancellation, "the owner's own connectTo() call must observe its own cancellation")

      // The crux of the fix: even though the owner's job was already cancelled when it hit the
      // contended lock, the cleanup must still have run once the external hold was released —
      // NOT be permanently skipped, which would leak the completed Failed deferred forever and
      // wedge every subsequent connectTo(serverId) onto that stale outcome.
      assertFalse(
        inFlightConnects.containsKey(serverId),
        "owner cancellation during a contended inFlightMutex must not leak the map entry",
      )
    } finally {
      clientPool.closeAllConnections()
      runCatching { stallServerSocket.close() }
      accepterThread.join(2_000)
    }
    Unit
  }
}
