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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Integration test for review finding 1.2, driving the PRODUCTION [ClientImpl.connectTo] path
 * (not a raw Ktor connect like [TcpConnectTimeoutTest]). It proves the per-address connect is
 * actually wrapped in `withTimeout(TCP_CONNECT_TIMEOUT_MS)` so a single stale/black-holed
 * address cannot stall the whole connect budget.
 *
 * **Black-hole address**: a non-routable TEST-NET-1 address ([BLACK_HOLE_ADDRESS], RFC 5737),
 * which is reserved and not routed anywhere. A connect to it has no host to answer the SYN, so
 * the dial hangs for the OS retransmit cycle (tens of seconds) absent an explicit timeout — and,
 * crucially, the connect NEVER completes, so the test can't accidentally fall through to
 * `ClientImpl`'s (intentionally un-timed) handshake read. This is more robust than localhost
 * backlog saturation, which some kernels complete instead of dropping.
 *
 * **Regression guard**: the call is wrapped in a 10s test-side [withTimeout]. If someone removes
 * the production `withTimeout`, the black-holed connect hangs past 10s and this test fails. With
 * the fix, `connectTo` returns [ConnectOutcome.Failed] in ≈[TCP_CONNECT_TIMEOUT_MS]. On the rare
 * host that fast-fails the dial (immediate ICMP unreachable), the test still asserts a bounded
 * Failed result and logs that the timeout path was not exercised.
 */
class ClientConnectTimeoutTest {

  private companion object {
    // RFC 5737 TEST-NET-1: reserved, unrouted — SYNs are dropped, so connect() hangs until the
    // per-address withTimeout fires. Never completes, so the handshake read is never reached.
    const val BLACK_HOLE_ADDRESS = "192.0.2.1"
    const val BLACK_HOLE_PORT = 9 // discard; irrelevant since no host answers
  }

  /** Exposes exactly one peer whose only Klardrop endpoint is the given address/port. */
  private class SingleKlardropPeer(
    private val deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Black Hole Peer",
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
  fun connectToReturnsFailedWithinBudgetForBlackHoledAddress() = runBlocking(Dispatchers.IO) {
    val coroutines = TestCoroutines()
    val peerId = "peerblkh"
    val visibleDevices = SingleKlardropPeer(peerId, BLACK_HOLE_ADDRESS, BLACK_HOLE_PORT)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("self0001"))
    // Never invoked on the black-hole path (the connect throws before any handshake), but
    // ClientImpl requires non-null instances.
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)

    val client = ClientImpl(
      connectionsPool = ConnectionsPoolImpl(),
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = MessageSerializer(ProtoBuf, coroutines),
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider,
      trustManager = trustManager,
      bleTransport = null,
    )

    // ClientImpl mirrors visibleDevices via stateIn(Eagerly); give the sharing coroutine a
    // moment to copy the already-populated StateFlow value before we dial, otherwise connectTo
    // could return Failed("cant be found") without ever attempting the connect.
    delay(200)

    val mark = TimeSource.Monotonic.markNow()
    val outcome = try {
      withTimeout(10_000L) { client.connectTo(peerId) }
    } catch (e: TimeoutCancellationException) {
      fail("connectTo did not return within 10s — the per-address connect timeout (withTimeout) is missing")
    }
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    assertEquals(
      ConnectOutcome.Failed,
      outcome,
      "A sole black-holed address must resolve to Failed once the connect times out",
    )

    if (elapsedMs < TCP_CONNECT_TIMEOUT_MS - 500L) {
      // The connect fast-failed (immediate ICMP unreachable) instead of black-holing on this
      // host. Still a valid bounded result, but the timeout path was not exercised.
      println(
        "ClientConnectTimeoutTest: inconclusive on this host (connect resolved in ${elapsedMs}ms); " +
          "timeout path not exercised but result is bounded.",
      )
    } else {
      assertTrue(
        elapsedMs < TCP_CONNECT_TIMEOUT_MS + 3_000L,
        "connectTo took ${elapsedMs}ms; expected ≈ TCP_CONNECT_TIMEOUT_MS (${TCP_CONNECT_TIMEOUT_MS}ms) " +
          "+ slack — the per-address withTimeout should have bounded the black-holed dial.",
      )
    }
    Unit
  }
}
