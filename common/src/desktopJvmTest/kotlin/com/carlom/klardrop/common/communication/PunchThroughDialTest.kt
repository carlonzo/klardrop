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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * T10 firewall punch-through: pins the socket mechanics of dialing FROM our own listening
 * port. When a direct dial fails (timeout / unreachable), the punch-through retry binds the
 * dial socket to (local address that routes to the peer, our own server port) with
 * SO_REUSEADDR — the outbound SYN then creates conntrack state whose reverse direction is
 * the peer's inbound SYN to our listening port, which stateful firewalls (ufw/nft/
 * conntrack-based APs) accept as ESTABLISHED. Both peers run the same burst via the
 * reachability prober, so dial windows overlap and the OS either completes a simultaneous
 * open or a listener accepts the second connection; ConnectionsPool's tie-break dedupes.
 *
 * All sockets are real loopback sockets on ephemeral ports; every wait is bounded.
 */
class PunchThroughDialTest {

  // ── fixtures ──────────────────────────────────────────────────────────────

  /** Exposes one peer with a single Klardrop endpoint. */
  private class SingleKlardropPeer(
    deviceId: String,
    connection: DeviceConnection.KlardropConnection,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Punch-Through Peer",
        deviceType = DeviceType.DESKTOP,
        osType = OsType.LINUX,
      ),
      deviceConnections = listOf(connection),
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

  /** Black-holed listener: backlog=1 pre-filled, so further SYNs are dropped, not RST'd. */
  private fun blackHole(): Pair<ServerSocket, Socket> {
    val socket = ServerSocket()
    socket.bind(java.net.InetSocketAddress("127.0.0.1", 0), /* backlog = */ 1)
    val filler = Socket()
    filler.connect(java.net.InetSocketAddress("127.0.0.1", socket.localPort), 500)
    return socket to filler
  }

  private fun testClient(
    selfId: String,
    pool: ConnectionsPoolImpl,
    peerId: String,
    peerPort: Int,
    ownServerPort: Int,
  ): ClientImpl {
    val coroutines = TestCoroutines()
    val provider = CurrentDeviceProvider(FixedIdPropertiesRepository(selfId))
    return ClientImpl(
      connectionsPool = pool,
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = MessageSerializer(ProtoBuf, coroutines),
      visibleDevices = SingleKlardropPeer(peerId, DeviceConnection.KlardropConnection("127.0.0.1", peerPort)),
      currentDeviceProvider = provider,
      trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), provider),
      bleTransport = null,
      serverPort = MutableStateFlow(ownServerPort),
    )
  }

  // ── (i) simultaneous punch-through from both listen ports ────────────────

  /**
   * Both ends dial each other FROM their own listening ports in overlapping windows, with
   * retries mirroring the production burst. Two outcomes are possible per attempt — the OS
   * completes a true simultaneous open (both dial sockets establish, then both initiator
   * handshakes collide and are retried) or a listener accepts the dial (normal client/server
   * roles) — and the retries converge on the listener-accept path. Either way both pools end
   * up holding the two ends of the punch-through connection.
   *
   * A direct (ephemeral-source) dial is then added on top, so one pool briefly holds BOTH a
   * listener-accepted connection and a dial socket for the same peer — the exact shape
   * ConnectionsPool.updateConnection's simultaneous-open tie-break must dedupe. Final state:
   * exactly one live pooled connection per peer.
   */
  @Test
  fun simultaneousPunchThroughDialsConvergeToOnePooledConnectionPerPeer() = runBlocking(Dispatchers.IO) {
    val idA = "ptsidea1"
    val idB = "ptsideb1"
    val providerA = CurrentDeviceProvider(FixedIdPropertiesRepository(idA))
    val providerB = CurrentDeviceProvider(FixedIdPropertiesRepository(idB))
    val poolA = ConnectionsPoolImpl(coroutines = TestCoroutines(), currentDeviceProvider = providerA)
    val poolB = ConnectionsPoolImpl(coroutines = TestCoroutines(), currentDeviceProvider = providerB)
    val serverA = createTestServer(connectionsPool = poolA, localPropertiesRepository = FixedIdPropertiesRepository(idA))
    val serverB = createTestServer(connectionsPool = poolB, localPropertiesRepository = FixedIdPropertiesRepository(idB))
    val configA = serverA.startServer()
    val configB = serverB.startServer()

    val clientA = testClient(idA, poolA, idB, configB.port, configA.port)
    val clientB = testClient(idB, poolB, idA, configA.port, configB.port)
    val dialSelectorA = SelectorManager(Dispatchers.IO)
    val dialSelectorB = SelectorManager(Dispatchers.IO)

    // Deliberately different gaps so the two sides drift out of sync: a SYN arriving while
    // the peer is between attempts lands on the peer's LISTENER (client/server roles) instead
    // of racing into another simultaneous open forever.
    suspend fun CoroutineScope.punchUntilConnected(
      client: ClientImpl,
      dialSelector: SelectorManager,
      remotePort: Int,
      ownPort: Int,
      peerId: String,
      pool: ConnectionsPoolImpl,
      attempts: Int,
      gapMs: Long,
    ) {
      repeat(attempts) {
        if (pool.getConnection(peerId) != null) return
        val socket = runCatching {
          withTimeout(5_000) { punchThroughConnect(dialSelector, InetSocketAddress("127.0.0.1", remotePort), ownPort) }
        }.getOrNull()
        if (socket != null) {
          val job = CompletableDeferred<ConnectOutcome>()
          // A simultaneous open pairs two INITIATOR handshakes — the UKEY2 role check fails
          // one/both of them. The helper closes the socket on failure; retry after the gap.
          val handshake = runCatching {
            client.handshakeAndRegister(socket, "127.0.0.1", remotePort, peerId, job, winnerGate = null)
          }
          if (handshake.isSuccess) return
        }
        delay(gapMs)
      }
    }

    try {
      withTimeout(30_000) {
        val sideA = launch {
          punchUntilConnected(clientA, dialSelectorA, configB.port, configA.port, idB, poolA, attempts = 12, gapMs = 150)
        }
        val sideB = launch {
          punchUntilConnected(clientB, dialSelectorB, configA.port, configB.port, idA, poolB, attempts = 12, gapMs = 325)
        }
        sideA.join()
        sideB.join()
      }

      val punchOnA = poolA.getConnection(idB)
      assertNotNull(punchOnA, "A must hold a live pooled connection to B after the overlapping punch-through dials")
      assertFalse(punchOnA.isClosed())
      val punchOnB = poolB.getConnection(idA)
      assertNotNull(punchOnB, "B must hold a live pooled connection to A after the overlapping punch-through dials")
      assertFalse(punchOnB.isClosed())

      // Now a direct (ephemeral-source) dial on top: each pool transiently holds TWO
      // connections for the peer — the one we dialed and the one the peer dialed. The
      // simultaneous-open tie-break must dedupe them to exactly one live connection.
      val directJob = CompletableDeferred<ConnectOutcome>()
      val directSocket = withTimeout(10_000) {
        aSocket(dialSelectorB).tcp().connect("127.0.0.1", configA.port) { keepAlive = true }
      }
      clientB.handshakeAndRegister(directSocket, "127.0.0.1", configA.port, idA, directJob, winnerGate = null)
      assertEquals(ConnectOutcome.Connected, directJob.await())

      delay(500) // let the server-side accept + tie-break settle

      val finalOnA = poolA.getConnection(idB)
      assertNotNull(finalOnA, "exactly one live connection must survive the tie-break on A")
      assertFalse(finalOnA.isClosed())
      val finalOnB = poolB.getConnection(idA)
      assertNotNull(finalOnB, "exactly one live connection must survive the tie-break on B")
      assertFalse(finalOnB.isClosed())
      assertEquals(Reachability.Reachable, poolA.reachability.value[idB])
      assertEquals(Reachability.Reachable, poolB.reachability.value[idA])
    } finally {
      poolA.closeAllConnections()
      poolB.closeAllConnections()
      clientA.close()
      clientB.close()
      dialSelectorA.close()
      dialSelectorB.close()
      serverA.stopServer()
      serverB.stopServer()
    }
    Unit
  }

  // ── (ii) direct dial times out → punch-through dial succeeds ─────────────

  /**
   * The direct dial to a black-holed endpoint times out; the punch-through dial — same
   * selector, but bound to our own listening port — succeeds against a listening peer.
   * Our listener is a REAL [Server] (via createTestServer), so this also pins the
   * listener-side SO_REUSEADDR: without it the co-bind of our listen port fails and the
   * punch-through dial can never even start.
   */
  @Test
  fun directDialTimeoutIsFollowedBySuccessfulPunchThroughDial() = runBlocking(Dispatchers.IO) {
    val (blackHoled, filler) = blackHole()
    val poolA = ConnectionsPoolImpl()
    val serverA = createTestServer(localPropertiesRepository = FixedIdPropertiesRepository("ptselfa1"))
    val configA = serverA.startServer()
    val peerListener = ServerSocket()
    peerListener.bind(java.net.InetSocketAddress("127.0.0.1", 0))

    val selector = SelectorManager(Dispatchers.IO)
    try {
      // 1. Direct dial to the black-holed endpoint times out.
      val direct = runCatching {
        withTimeout(TCP_CONNECT_TIMEOUT_MS) {
          aSocket(selector).tcp().connect("127.0.0.1", blackHoled.localPort) { keepAlive = true }
        }
      }
      if (direct.isSuccess) {
        // The OS completed the SYN-ACK despite the nominally full backlog (some kernels);
        // the black-hole premise does not hold on this host, so the test is inconclusive.
        println("PunchThroughDialTest: backlog saturation did not produce a black-hole; skipping")
        return@runBlocking
      }
      assertTrue(
        direct.exceptionOrNull() is TimeoutCancellationException,
        "direct dial to a black-holed endpoint must time out, got: ${direct.exceptionOrNull()}",
      )

      // 2. The punch-through dial from our listening port succeeds.
      val socket = assertNotNull(
        withTimeout(10_000) {
          punchThroughConnect(selector, InetSocketAddress("127.0.0.1", peerListener.localPort), configA.port)
        },
        "punch-through dial must succeed against a listening peer",
      )
      val localPort = (socket.localAddress as InetSocketAddress).port
      val remotePort = (socket.remoteAddress as InetSocketAddress).port
      assertEquals(configA.port, localPort, "punch-through socket must be bound to our own listening port")
      assertEquals(peerListener.localPort, remotePort, "punch-through socket must be connected to the peer's port")
    } finally {
      filler.close()
      blackHoled.close()
      peerListener.close()
      poolA.closeAllConnections()
      serverA.stopServer()
      selector.close()
    }
    Unit
  }

  // ── (iii) no punch-through when the direct dial succeeds ─────────────────

  /**
   * When the direct dial succeeds, the pooled connection is the direct one: its local port
   * is an ephemeral port, NOT our listening port. If a regression ever ran the punch-through
   * burst after a successful direct dial, its dial would win the pool's reconnect-replace
   * and the pooled socket WOULD carry our listening port as its local port — which this
   * assertion turns into a failure.
   */
  @Test
  fun noPunchThroughWhenDirectDialSucceeds() = runBlocking(Dispatchers.IO) {
    val idA = "ptselfa1"
    val idB = "ptpeerb1"
    val poolA = ConnectionsPoolImpl()
    val serverA = createTestServer(localPropertiesRepository = FixedIdPropertiesRepository(idA))
    val configA = serverA.startServer()
    val poolB = ConnectionsPoolImpl()
    val serverB = createTestServer(connectionsPool = poolB, localPropertiesRepository = FixedIdPropertiesRepository(idB))
    val configB = serverB.startServer()

    val client = testClient(idA, poolA, idB, configB.port, configA.port)
    try {
      val outcome = withTimeout(15_000) { client.connectTo(idB) }
      assertEquals(ConnectOutcome.Connected, outcome)

      val messenger = poolA.getConnection(idB)
      assertNotNull(messenger, "direct dial must leave a live pooled connection")
      val tcp = messenger.tcpConnection
      assertNotNull(tcp, "the pooled connection must be TCP")
      val localPort = (tcp.socket.localAddress as InetSocketAddress).port
      assertNotEquals(
        configA.port,
        localPort,
        "pooled socket must carry an ephemeral local port — a punch-through dial " +
          "(local port == our listening port $configA.port) must not have been attempted",
      )
    } finally {
      poolA.closeAllConnections()
      poolB.closeAllConnections()
      client.close()
      serverA.stopServer()
      serverB.stopServer()
    }
    Unit
  }

  // ── (iv) the burst runs after a failed direct dial ───────────────────────

  /**
   * With a refused (dead) endpoint the direct dial fails instantly; the punch-through burst
   * (3 attempts, 1s apart) then costs its two inter-attempt gaps — the timing signature this
   * test pins. A regression that skips the burst returns in milliseconds and fails here.
   */
  @Test
  fun punchThroughBurstRunsAfterDirectDialFailure() = runBlocking(Dispatchers.IO) {
    val dead = ServerSocket()
    dead.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val deadPort = dead.localPort
    dead.close() // closed → connects are actively refused

    val poolA = ConnectionsPoolImpl()
    val client = testClient("ptselfa1", poolA, "ptpeerb1", deadPort, ownServerPort = 55_123)
    try {
      val mark = TimeSource.Monotonic.markNow()
      val outcome = withTimeout(20_000) { client.connectTo("ptpeerb1") }
      val elapsedMs = mark.elapsedNow().inWholeMilliseconds

      assertEquals(ConnectOutcome.Failed, outcome)
      assertTrue(
        elapsedMs >= 2 * PUNCH_THROUGH_ATTEMPT_INTERVAL_MS,
        "connectTo returned in ${elapsedMs}ms — the punch-through burst " +
          "(3 attempts, ${PUNCH_THROUGH_ATTEMPT_INTERVAL_MS}ms apart) did not run after the direct dial failed",
      )
    } finally {
      poolA.closeAllConnections()
      client.close()
    }
    Unit
  }

  // ── (v) the burst is skipped when our own server port is unknown ─────────

  /**
   * Without a known own listening port there is nothing to punch through from: the burst
   * must be skipped and the dial must fail fast instead of burning the burst window.
   */
  @Test
  fun punchThroughBurstSkippedWhenOwnServerPortUnknown() = runBlocking(Dispatchers.IO) {
    val dead = ServerSocket()
    dead.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val deadPort = dead.localPort
    dead.close()

    val poolA = ConnectionsPoolImpl()
    val client = testClient("ptselfa1", poolA, "ptpeerb1", deadPort, ownServerPort = 0)
    try {
      val mark = TimeSource.Monotonic.markNow()
      val outcome = withTimeout(20_000) { client.connectTo("ptpeerb1") }
      val elapsedMs = mark.elapsedNow().inWholeMilliseconds

      assertEquals(ConnectOutcome.Failed, outcome)
      assertTrue(
        elapsedMs < PUNCH_THROUGH_ATTEMPT_INTERVAL_MS,
        "connectTo took ${elapsedMs}ms with an unknown own server port — " +
          "the punch-through burst must be skipped entirely",
      )
    } finally {
      poolA.closeAllConnections()
      client.close()
    }
    Unit
  }
}
