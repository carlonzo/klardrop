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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro/regression for connection-review round 2, finding 1: [ClientImpl]'s per-device dial
 * coalescing (`Client.kt` around line 149) used to couple an owner's cancellation to independent
 * coalesced waiters. When the in-flight owner's `performDial` was cancelled, the `catch` block ran
 * `inFlight.completeExceptionally(CancellationException)`; every coalesced waiter's `inFlight.await()`
 * then threw that SAME [CancellationException] — even though the waiter's own coroutine was never
 * cancelled. All three real `connectTo` callers (`Messenger.getOrEstablishConnection`,
 * `EagerReachabilityConnector`, dial-on-open) wrap the call in `runCatching`, which swallows a
 * foreign `CancellationException` and misreports it as a failed connect — so an unrelated caller's
 * cancellation would spuriously fail a completely independent waiter's send.
 *
 * The fix: on owner cancellation, resolve the shared deferred to a normal [ConnectOutcome.Failed]
 * instead of completing it exceptionally with the `CancellationException` — only genuine (non
 * cancellation) dial errors are still reported via `completeExceptionally`.
 *
 * This test uses a "stall" endpoint (a raw [ServerSocket] that accepts the TCP connection but never
 * replies, exactly as in [ClientRaceCancellationSafetyTest]) so the owner's dial can be cancelled
 * mid-flight, deterministically, before it ever completes on its own.
 */
class ClientDialCoalescingCancellationTest {

  /** Exposes exactly one peer whose only Klardrop endpoint is the given stalling address/port. */
  private class SingleKlardropPeer(
    private val deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Coalescing Cancellation Test Peer",
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

  @Test
  fun ownerCancellationDoesNotFailCoalescedWaiterWithCancellationException() = runBlocking(Dispatchers.IO) {
    val stallServerSocket = ServerSocket()
    stallServerSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val stallPort = stallServerSocket.localPort
    val accepterThread = thread(name = "stall-accepter", isDaemon = true) {
      runCatching { stallServerSocket.accept() } // accept and hold; never reply
    }

    val serverId = "cancstal"
    val visibleDevices = SingleKlardropPeer(serverId, "127.0.0.1", stallPort)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("cancli01"))
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

    try {
      // Owner: dials and becomes stuck against the stall endpoint (TCP connect succeeds, the
      // peer never replies to the greeting).
      val owner = async(Dispatchers.IO) { client.connectTo(serverId) }
      // Give the owner time to actually register itself in inFlightConnects and open the socket.
      delay(300)

      // Coalesced waiter: an unrelated, independent caller — exactly like the real
      // Messenger/EagerReachabilityConnector/dial-on-open call sites, each wrapped in runCatching.
      val waiterResult = async(Dispatchers.IO) { runCatching { client.connectTo(serverId) } }
      delay(100)

      // Cancel ONLY the owner's own coroutine (e.g. its caller navigated away / its scope was torn
      // down) — the waiter's coroutine is never touched.
      owner.cancel()
      var ownerObservedCancellation = false
      try {
        withTimeout(2_000) { owner.await() }
      } catch (e: CancellationException) {
        ownerObservedCancellation = true
      }
      assertTrue(ownerObservedCancellation, "the owner's own connectTo() call must observe its own cancellation")

      val outcome = withTimeout(5_000) { waiterResult.await() }
      assertTrue(
        outcome.isSuccess,
        "The coalesced waiter must not observe the owner's CancellationException — it is an " +
          "unrelated, independent caller. Got: $outcome",
      )
      assertEquals(
        ConnectOutcome.Failed,
        outcome.getOrNull(),
        "An owner cancellation should resolve unrelated waiters to a normal Failed outcome, not " +
          "propagate a foreign CancellationException across the coalescing boundary.",
      )
    } finally {
      clientPool.closeAllConnections()
      runCatching { stallServerSocket.close() }
      accepterThread.join(2_000)
    }
    Unit
  }
}
