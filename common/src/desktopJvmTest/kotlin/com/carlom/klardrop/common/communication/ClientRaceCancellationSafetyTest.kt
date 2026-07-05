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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.time.TimeSource

/**
 * Repro/regression for the two Client.kt findings around [ClientImpl]'s TCP endpoint race
 * (docs/connection-review.md remediation round 1, issues 1 and 5):
 *
 *  - **Socket leak (issue 1)**: a losing endpoint that's already past `connect()` and suspended in
 *    the greeting-read or UKEY2 `withTimeout` when the watcher cancels it must still have its raw
 *    TCP socket closed — `establishConnection`'s `runCatching` used to swallow the resulting
 *    `CancellationException` with no `finally`, leaking the fd.
 *  - **Winner spuriously cancelled (issue 5)**: the watcher used to cancel EVERY job on
 *    `winnerGate` completion, including the winner's own job — which still has to run
 *    `ConnectionsPool.updateConnection` (suspendable) and `connectionJob.complete` after flipping
 *    the gate. Under contention that suspension could get cancelled, turning a successful race
 *    into a spurious `Failed`.
 *
 * Both use a "stall" endpoint: a raw [ServerSocket] that accepts the TCP connection (so `connect()`
 * succeeds and the client sends its greeting) but never replies — the client attempt against it
 * ends up parked in the greeting-read `withTimeout`, exactly where the watcher would cancel a loser
 * once the sibling "good" endpoint (a real [Server]) wins the race.
 */
class ClientRaceCancellationSafetyTest {

  /** Exposes one peer with TWO Klardrop endpoints: a stalling one and a good one. */
  private class TwoEndpointPeer(
    deviceId: String,
    connections: List<DeviceConnection.KlardropConnection>,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Race Cancellation Test Peer",
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

  /**
   * Delegates to a real [ConnectionsPoolImpl] but inserts an artificial suspend before
   * [updateConnection] actually registers the connection — standing in for a genuinely contended
   * pool mutex (concurrent inbound/outbound registrations from other devices). Deterministically
   * reproduces the exact window issue 5 is about: the winner is suspended INSIDE
   * `updateConnection`, after flipping `winnerGate`, when the watcher's cancellation sweep runs.
   */
  private class SlowRegistrationConnectionsPool(
    private val delegate: ConnectionsPool = ConnectionsPoolImpl(),
    private val registrationDelayMs: Long,
  ) : ConnectionsPool by delegate {
    override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
      delay(registrationDelayMs)
      delegate.updateConnection(deviceId, connectionMessenger)
    }
  }

  @Test
  fun winnerSurvivesCancellationSweepDuringContendedPoolRegistration() = runBlocking(Dispatchers.IO) {
    val stallServerSocket = ServerSocket()
    stallServerSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val stallPort = stallServerSocket.localPort
    val accepterThread = thread(name = "stall-accepter", isDaemon = true) {
      runCatching { stallServerSocket.accept() } // accept and hold; never reply
    }

    val serverId = "wincanc1"
    val serverPool = ConnectionsPoolImpl()
    val goodServer = createTestServer(
      connectionsPool = serverPool,
      localPropertiesRepository = FixedIdPropertiesRepository(serverId),
    )
    val goodServerConfig = goodServer.startServer()

    val coroutines = TestCoroutines()
    val visibleDevices = TwoEndpointPeer(
      serverId,
      listOf(
        DeviceConnection.KlardropConnection("127.0.0.1", stallPort), // never responds
        DeviceConnection.KlardropConnection("127.0.0.1", goodServerConfig.port), // wins the race
      ),
    )
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("wincli01"))
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)

    // 500ms of artificial suspension inside updateConnection — long enough that the watcher's
    // `winnerGate.await()` + cancellation sweep will unquestionably run WHILE the winner is still
    // suspended there (pre-fix: this cancels the winner's own job and the dial spuriously fails).
    val registrationDelayMs = 500L
    val clientPool = SlowRegistrationConnectionsPool(registrationDelayMs = registrationDelayMs)
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
      val outcome = withTimeout(10_000L) { client.connectTo(serverId) }
      val elapsedMs = mark.elapsedNow().inWholeMilliseconds

      assertEquals(
        ConnectOutcome.Connected,
        outcome,
        "The winner must still complete Connected even though the watcher's cancellation sweep " +
          "fires while it's suspended inside a slow ConnectionsPool.updateConnection — cancelling " +
          "the winner itself (instead of only its losing siblings) turns a successful race into a " +
          "spurious Failed.",
      )
      assertTrue(
        elapsedMs >= registrationDelayMs,
        "connectTo returned in ${elapsedMs}ms, before the $registrationDelayMs ms registration " +
          "delay could even elapse — it must have actually awaited updateConnection to completion.",
      )
      assertTrue(
        clientPool.getConnection(serverId)?.isClosed() == false,
        "The winning connection must end up pooled and open",
      )
    } finally {
      clientPool.closeAllConnections()
      serverPool.closeAllConnections()
      runCatching { stallServerSocket.close() }
      accepterThread.join(2_000)
      goodServer.stopServer()
    }
    Unit
  }

  @Test
  fun losingAttemptCancelledMidHandshakeStillClosesItsSocket() = runBlocking(Dispatchers.IO) {
    val stallServerSocket = ServerSocket()
    stallServerSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val stallPort = stallServerSocket.localPort

    val acceptedSocket = CompletableDeferred<java.net.Socket>()
    val accepterThread = thread(name = "stall-accepter", isDaemon = true) {
      val socket = runCatching { stallServerSocket.accept() }.getOrNull()
      if (socket != null) acceptedSocket.complete(socket) else acceptedSocket.cancel()
    }

    // Device ids must be <= 8 chars: CurrentDevice.shortDeviceId truncates to 8, and the client's
    // mismatch check (Client.kt establishConnection) compares against the untruncated key used in
    // VisibleDevices — a longer id here would make the good server "wrongly" appear mismatched.
    val serverId = "sockleak"
    val serverPool = ConnectionsPoolImpl()
    val goodServer = createTestServer(
      connectionsPool = serverPool,
      localPropertiesRepository = FixedIdPropertiesRepository(serverId),
    )
    val goodServerConfig = goodServer.startServer()

    val coroutines = TestCoroutines()
    val visibleDevices = TwoEndpointPeer(
      serverId,
      listOf(
        DeviceConnection.KlardropConnection("127.0.0.1", stallPort), // never responds -> loser
        DeviceConnection.KlardropConnection("127.0.0.1", goodServerConfig.port), // wins fast
      ),
    )
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("sockcli0"))
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
      val outcome = withTimeout(10_000L) { client.connectTo(serverId) }
      assertEquals(ConnectOutcome.Connected, outcome, "The good endpoint must win the race")

      // The stalled endpoint's server-side socket must observe the CLIENT closing its end shortly
      // after losing the race — proof establishConnection's cancellation path actually closed the
      // raw TCP socket instead of leaking the fd (issue 1). Without the fix this blocks until the
      // soTimeout below fires and the test fails.
      val stalledSocket = withTimeout(5_000L) { acceptedSocket.await() }
      stalledSocket.soTimeout = 5_000
      // Drain whatever the client already wrote (its greeting, which this stalling server never
      // read) before the real assertion: EOF (-1) once the client closes its end. A leaked socket
      // would instead block here until soTimeout fires.
      val buffer = ByteArray(4096)
      var readResult = 0
      while (readResult >= 0) {
        readResult = stalledSocket.getInputStream().read(buffer)
      }
      assertEquals(
        -1,
        readResult,
        "Expected EOF (client closed its socket) on the losing endpoint's accepted connection; " +
          "the client never closed the cancelled loser's socket.",
      )
    } finally {
      clientPool.closeAllConnections()
      serverPool.closeAllConnections()
      runCatching { stallServerSocket.close() }
      accepterThread.join(2_000)
      goodServer.stopServer()
    }
    Unit
  }
}
