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
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Repro/regression for F7: [ClientImpl.connectTo] used to iterate a device's advertised TCP
 * endpoints ONE AT A TIME (`Client.kt`, originally around line 139), each costing up to
 * `TCP_CONNECT_TIMEOUT_MS` (3 s) before the next address is even tried. A device with one stale
 * (black-holed) endpoint ahead of a good one paid the full per-address timeout before the good
 * address was dialed at all.
 *
 * The fix races every advertised endpoint concurrently: the first to complete a full handshake
 * wins, and the others are cancelled. This test gives the client TWO Klardrop endpoints — a
 * black-holed one FIRST, a real working one SECOND — and asserts `connectTo` succeeds well under
 * the old sequential worst case.
 */
class ClientConcurrentEndpointRaceTest {

  /** Exposes one peer with MULTIPLE Klardrop endpoints, in the given order. */
  private class MultiKlardropPeer(
    deviceId: String,
    connections: List<DeviceConnection.KlardropConnection>,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Multi-Endpoint Peer",
        deviceType = DeviceType.DESKTOP,
        osType = OsType.LINUX,
      ),
      deviceConnections = connections,
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
  fun connectToRacesEndpointsInsteadOfDialingSequentially() = runBlocking(Dispatchers.IO) {
    // Black-holed endpoint: backlog=1, pre-filled, so a further connect's SYN is dropped by the
    // OS rather than RST'd (same technique as ClientConnectTimeoutTest).
    val blackHoleServerSocket = ServerSocket()
    blackHoleServerSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0), /* backlog = */ 1)
    val blackHolePort = blackHoleServerSocket.localPort
    val filler = Socket()
    filler.connect(java.net.InetSocketAddress("127.0.0.1", blackHolePort), 500)

    // Good endpoint: a real production Server that completes the full handshake. Device ids must
    // be <= 8 chars: CurrentDevice.shortDeviceId truncates to 8, and the client's mismatch check
    // compares against the untruncated key used in VisibleDevices.
    val serverId = "racesrv1"
    val serverPool = ConnectionsPoolImpl()
    val goodServer = createTestServer(
      connectionsPool = serverPool,
      localPropertiesRepository = FixedIdPropertiesRepository(serverId),
    )
    val goodServerConfig = goodServer.startServer()

    val coroutines = TestCoroutines()
    val visibleDevices = MultiKlardropPeer(
      serverId,
      listOf(
        DeviceConnection.KlardropConnection("127.0.0.1", blackHolePort), // black-holed, listed FIRST
        DeviceConnection.KlardropConnection("127.0.0.1", goodServerConfig.port), // good, listed SECOND
      ),
    )
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("racecli1"))
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)

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

    delay(200)

    try {
      val mark = TimeSource.Monotonic.markNow()
      val outcome = try {
        withTimeout(10_000L) { client.connectTo(serverId) }
      } catch (e: TimeoutCancellationException) {
        fail("connectTo did not return within 10s")
      }
      val elapsedMs = mark.elapsedNow().inWholeMilliseconds

      assertEquals(
        ConnectOutcome.Connected,
        outcome,
        "The good second endpoint must win the race even though the first endpoint is black-holed",
      )

      // Generous bound (avoids flakiness) — the point is this must be well under the OLD
      // sequential worst case of TCP_CONNECT_TIMEOUT_MS (3s) just for the black-holed FIRST
      // endpoint, plus whatever the good endpoint then took on top of that.
      assertTrue(
        elapsedMs < 3_000L,
        "connectTo took ${elapsedMs}ms racing a black-holed + a good endpoint; expected well under " +
          "3000ms (TCP_CONNECT_TIMEOUT_MS) since the good endpoint should win concurrently instead " +
          "of waiting for the black-holed one to time out first.",
      )
    } finally {
      // Both sides' acceptIncomingMessages() read loops spin on FakeMessagesRouter's no-op
      // onMessageIncoming (it never actually reads, so the loop never blocks) — left running,
      // they busy-log forever and can OOM the test JVM. Closing the sockets makes each loop's
      // `!readChannel.isClosedForRead` guard trip so it exits.
      clientPool.closeAllConnections()
      serverPool.closeAllConnections()
      filler.close()
      blackHoleServerSocket.close()
      goodServer.stopServer()
    }
    Unit
  }
}
