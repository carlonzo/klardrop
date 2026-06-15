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
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Repro driver: the handshake WRITE in [ClientImpl.establishConnection]
 * (`writeChannel.sendMessage(handshakeMessage, serializer)`) is NOT wrapped in a [withTimeout],
 * whereas the per-address TCP connect and the handshake READ both are (each bounded to
 * [TCP_CONNECT_TIMEOUT_MS]).
 *
 * This test drives the PRODUCTION [ClientImpl.connectTo] against a real loopback peer that
 * completes the TCP 3-way handshake (so the bounded connect succeeds) and then **never reads and
 * never replies** — the exact "peer accepts TCP then stalls" scenario described. The whole
 * `connectTo` is wrapped in a generous test-side `withTimeout(10s)`; the fix's intent is that the
 * handshake write be bounded by `withTimeout(TCP_CONNECT_TIMEOUT_MS)` so a stalled peer cannot
 * burn the whole connection budget.
 *
 * NOTE ON PLATFORM BEHAVIOR (read before relying on this as a strict red/green gate):
 * On the JVM / Ktor 3.5 stack the client's `ByteWriteChannel` heap-buffers a single message write
 * and its `flush()` does NOT await the kernel drain, so the handshake `sendMessage` returns
 * essentially instantly even when the peer never reads (verified empirically: a 64 MB single
 * write to a never-reading loopback peer completes in tens of milliseconds, and a 600 MB single
 * write OOMs rather than blocking — i.e. there is no socket-level backpressure for a single
 * message). Consequently, on this stack the connection attempt is actually gated by the
 * already-bounded handshake READ (it returns [ConnectOutcome.Failed] at ≈[TCP_CONNECT_TIMEOUT_MS]
 * because the stalled peer sends no greeting), and the missing write `withTimeout` has no
 * additional observable effect. The assertions below therefore lock down the bounded-Failed
 * contract and will catch any regression that makes a stalled peer hang `connectTo` past the
 * budget — but they do not, on this stack, fail solely due to the absent write timeout.
 */
class ClientHandshakeWriteTimeoutTest {

  /** Exposes exactly one peer whose only Klardrop endpoint is the given address/port. */
  private class SingleKlardropPeer(
    deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
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
  fun connectToReturnsFailedWithinBudgetWhenPeerStallsAfterAccept() = runBlocking(Dispatchers.IO) {
    // A peer that ACCEPTS the TCP connection (so the bounded connect succeeds) but never reads and
    // never replies — the stalled-peer scenario described.
    val serverSocket = ServerSocket()
    serverSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0), /* backlog = */ 16)
    val port = serverSocket.localPort
    val accepted = AtomicReference<Socket?>(null)
    val acceptorThread = thread(name = "stalled-reader-acceptor", isDaemon = true) {
      try {
        accepted.set(serverSocket.accept()) // accept, then never read / never write
      } catch (_: Throwable) {
        // socket closed during teardown
      }
    }

    val coroutines = TestCoroutines()
    val peerId = "peerstal"
    val visibleDevices = SingleKlardropPeer(peerId, "127.0.0.1", port)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("self0001"))
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
    // moment to copy the already-populated StateFlow value before we dial.
    delay(200)

    val mark = TimeSource.Monotonic.markNow()
    val outcome = try {
      withTimeout(10_000L) { client.connectTo(peerId) }
    } catch (e: TimeoutCancellationException) {
      accepted.get()?.close()
      serverSocket.close()
      acceptorThread.interrupt()
      fail(
        "connectTo did not return within 10s — a peer that accepts TCP but then stalls (no read, " +
          "no greeting) hung the whole connection attempt. The handshake write/read phases must " +
          "each be bounded by withTimeout(TCP_CONNECT_TIMEOUT_MS) so a stalled peer cannot burn " +
          "the connection budget.",
      )
    }
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    accepted.get()?.close()
    serverSocket.close()
    acceptorThread.interrupt()

    assertEquals(
      ConnectOutcome.Failed,
      outcome,
      "A sole peer that accepts then stalls must resolve to Failed once the bounded handshake phase times out",
    )
    assert(elapsedMs < TCP_CONNECT_TIMEOUT_MS + 4_000L) {
      "connectTo took ${elapsedMs}ms; expected ~TCP_CONNECT_TIMEOUT_MS (${TCP_CONNECT_TIMEOUT_MS}ms) + slack — " +
        "the handshake phase against a stalled peer should be bounded."
    }
    Unit
  }
}
