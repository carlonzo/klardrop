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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Repro driver for bug **B17**.
 *
 * [ClientImpl.establishConnection]'s `.onFailure` handler invalidates the stale cached endpoint
 * (via [VisibleDevices.invalidateKlardropEndpoint]) ONLY when the dial fails with a
 * connection-refused error ([Throwable.isConnectionRefused]). On a per-address connect/handshake
 * **TIMEOUT** — a [TimeoutCancellationException] thrown by one of the
 * `withTimeout(TCP_CONNECT_TIMEOUT_MS)` blocks — the endpoint is NOT invalidated.
 *
 * Live evidence ( .reliability/logs/cli-linux-smoke/nodeB-mac-listen.log ): after a peer restarted
 * on a new ephemeral port, the Mac kept dialing the OLD cached port first and burned the full 3 s
 * `TimeoutCancellationException: Timed out waiting for 3000 ms` every probe cycle, because the
 * stale endpoint was never invalidated — delaying recovery until the next mDNS ServiceFound.
 *
 * ## How this test forces a deterministic timeout (no flaky black-hole connect)
 * The peer ACCEPTS the TCP 3-way handshake (so the bounded connect succeeds) and then never sends
 * its greeting HandshakeMessage. The handshake-READ `withTimeout(TCP_CONNECT_TIMEOUT_MS)` in
 * [ClientImpl.establishConnection] therefore fires deterministically with a
 * [TimeoutCancellationException] on every host — the same exception class the live log shows.
 *
 * ## What it asserts
 * After `connectTo` resolves to [ConnectOutcome.Failed] via that timeout, the stale endpoint MUST
 * have been invalidated. A recording [VisibleDevices] captures every
 * `invalidateKlardropEndpoint(deviceId, address, port)` call.
 *
 * **Expected on CURRENT (buggy) code:** RED — `invalidateKlardropEndpoint` is never called for a
 * timeout (only `isConnectionRefused()` triggers it), so [recordingDevices.invalidated] is empty
 * and the assertion fails.
 *
 * **Expected after the fix:** GREEN — the `.onFailure` branch also invalidates on a connect/
 * handshake timeout, so the stale `address:port` is removed and the next probe can pick up the
 * fresh SRV without burning another full timeout.
 */
class ClientConnectTimeoutInvalidatesEndpointTest {

  data class Invalidation(val deviceId: String, val address: String, val port: Int)

  /**
   * Exposes exactly one peer with a single Klardrop endpoint AND records every
   * [invalidateKlardropEndpoint] call so the test can assert the stale endpoint was dropped.
   */
  private class RecordingSingleKlardropPeer(
    private val deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
    val invalidated = mutableListOf<Invalidation>()

    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Stalled Reader Peer",
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
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) {
      invalidated += Invalidation(deviceId, address, port)
    }
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
  fun connectTimeoutInvalidatesStaleEndpoint() = runBlocking(Dispatchers.IO) {
    // A peer that ACCEPTS the TCP connection (so the bounded connect succeeds) but never sends its
    // greeting — the handshake-READ withTimeout(TCP_CONNECT_TIMEOUT_MS) then fires deterministically
    // with a TimeoutCancellationException, exactly the stale-port symptom B17 describes.
    val serverSocket = ServerSocket()
    serverSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0), /* backlog = */ 16)
    val port = serverSocket.localPort
    val accepted = AtomicReference<Socket?>(null)
    val acceptorThread = thread(name = "b17-stalled-reader-acceptor", isDaemon = true) {
      try {
        accepted.set(serverSocket.accept()) // accept, then never read / never write
      } catch (_: Throwable) {
        // socket closed during teardown
      }
    }

    val coroutines = TestCoroutines()
    val peerId = "peerb17x"
    val address = "127.0.0.1"
    val recordingDevices = RecordingSingleKlardropPeer(peerId, address, port)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("self0001"))
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)

    val client = ClientImpl(
      connectionsPool = ConnectionsPoolImpl(),
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = MessageSerializer(ProtoBuf, coroutines),
      visibleDevices = recordingDevices,
      currentDeviceProvider = currentDeviceProvider,
      trustManager = trustManager,
      bleTransport = null,
    )

    // ClientImpl mirrors visibleDevices via stateIn(Eagerly); give the sharing coroutine a moment to
    // copy the already-populated StateFlow value before we dial.
    delay(200)

    val mark = TimeSource.Monotonic.markNow()
    val outcome = try {
      withTimeout(10_000L) { client.connectTo(peerId) }
    } catch (e: TimeoutCancellationException) {
      accepted.get()?.close()
      serverSocket.close()
      acceptorThread.interrupt()
      fail("connectTo did not return within 10s — the bounded handshake phase did not fire as expected")
    }
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    accepted.get()?.close()
    serverSocket.close()
    acceptorThread.interrupt()

    // Sanity: the dial must have actually exercised the timeout path (bounded Failed), not some
    // unrelated fast failure — otherwise the invalidation assertion below would be meaningless.
    assertEquals(
      ConnectOutcome.Failed,
      outcome,
      "A sole peer that accepts then stalls must resolve to Failed once the bounded handshake times out",
    )
    assertTrue(
      elapsedMs >= TCP_CONNECT_TIMEOUT_MS - 500L && elapsedMs < TCP_CONNECT_TIMEOUT_MS + 4_000L,
      "connectTo took ${elapsedMs}ms; expected ≈ TCP_CONNECT_TIMEOUT_MS (${TCP_CONNECT_TIMEOUT_MS}ms) — " +
        "the timeout path must have been the cause of the failure for this repro to be valid.",
    )

    // THE B17 ASSERTION: a connect/handshake TIMEOUT must invalidate the stale endpoint, exactly
    // like a connection-refused does, so the dead address:port is not re-dialed (burning another
    // full timeout) on the next probe cycle while mDNS delivers the fresh SRV.
    assertTrue(
      recordingDevices.invalidated.contains(Invalidation(peerId, address, port)),
      "B17: a connect/handshake TIMEOUT (TimeoutCancellationException) must invalidate the stale " +
        "Klardrop endpoint $address:$port for $peerId — just like a connection-refused does. " +
        "It currently does NOT (only isConnectionRefused() triggers invalidation), so the Mac " +
        "keeps re-dialing the dead port and burns the full ${TCP_CONNECT_TIMEOUT_MS}ms timeout every " +
        "probe cycle. Recorded invalidations: ${recordingDevices.invalidated}",
    )
    Unit
  }
}
