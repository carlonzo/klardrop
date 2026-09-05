package com.carlom.klardrop.common.communication

import TestCoroutines
import app.cash.turbine.test
import com.carlom.klardrop.common.FakeConnectionPool
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [EagerReachabilityConnector.probe] reachability state machine.
 *
 * Covers three outcomes of [Client.connectTo]:
 *   - [ConnectOutcome.NotInitiated] must NOT force [Reachability.Unreachable] (main bug fix)
 *   - [ConnectOutcome.Failed]       MUST   force [Reachability.Unreachable] (regression guard)
 *   - [ConnectOutcome.Connected]    must NOT downgrade reachability to Unreachable (happy path)
 */
class EagerReachabilityConnectorTest {

  // -----------------------------------------------------------------------
  // Fakes
  // -----------------------------------------------------------------------

  /** Minimal [VisibleDevices] that exposes exactly one peer with a Klardrop TCP connection. */
  private class SinglePeerVisibleDevices(deviceId: String) : VisibleDevices {

    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Test Peer",
        deviceType = DeviceType.DESKTOP,
        osType = OsType.LINUX,
      ),
      deviceConnections = listOf(DeviceConnection.KlardropConnection("10.0.0.1", 5050)),
      lastSeenTimestamp = 0L,
    )

    private val flow = MutableStateFlow(mapOf(deviceId to device))
    override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = flow

    override suspend fun onNewDeviceVisible(
      deviceInfo: DeviceInfo,
      deviceConnection: DeviceConnection,
    ) = Unit

    override fun isDeviceVisible(deviceId: String) = flow.value.containsKey(deviceId)
    override fun getDevice(deviceId: String) = flow.value[deviceId]
    override fun cachedNameFor(deviceId: String) = null
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) { flow.value = emptyMap() }
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
  }

  /** Minimal [LocalPropertiesRepository] backed by a fixed device ID. */
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

  /** [Client] that always returns the same pre-configured [ConnectOutcome]. */
  private class FixedOutcomeClient(private val outcome: ConnectOutcome) : Client {
    override suspend fun connectTo(deviceId: String): ConnectOutcome = outcome
  }

  // -----------------------------------------------------------------------
  // Helper: build a connector for a given peer and client
  // -----------------------------------------------------------------------

  private class NearbyOnlyPeerVisibleDevices(deviceId: String) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Nearby Peer",
        deviceType = DeviceType.MOBILE,
        osType = OsType.ANDROID,
      ),
      deviceConnections = listOf(DeviceConnection.NearbyConnection("10.0.0.2", 5050)),
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

  private class CountingClient : Client {
    var connectCalls = 0
    override suspend fun connectTo(deviceId: String): ConnectOutcome {
      connectCalls++
      return ConnectOutcome.Connected
    }
  }

  private fun buildConnector(
    peerId: String,
    client: Client,
    pool: FakeConnectionPool = FakeConnectionPool(),
    visibleDevices: VisibleDevices = SinglePeerVisibleDevices(peerId),
  ): Pair<EagerReachabilityConnector, FakeConnectionPool> {
    // Self id is lexicographically smaller than the typical peer ids used in tests
    // so BleRoleSelector would say we should initiate — doesn't matter here because
    // our fake client ignores it.
    val selfId = "aaaaaaaa"
    val repo = FixedIdPropertiesRepository(selfId)
    val currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(repo)
    val coroutines = TestCoroutines()

    val connector = EagerReachabilityConnector(
      coroutines = coroutines,
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider,
      client = client,
      connectionsPool = pool,
      // NetworkLifecycleMonitor has a no-arg constructor on JVM; it won't fire during
      // the short test window (it polls every 5 seconds).
      networkLifecycleMonitor = NetworkLifecycleMonitor(),
    )
    return connector to pool
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  /**
   * Main bug regression: [ConnectOutcome.NotInitiated] (e.g. BLE non-initiator
   * role, or already-connected short-circuit) must be treated as *inconclusive* —
   * the peer may still dial us inbound.  Reachability must NOT become
   * [Reachability.Unreachable].
   *
   * Before the fix, [EagerReachabilityConnector.probe] called
   * `connectionsPool.markUnreachable(deviceId)` whenever `connectTo` returned
   * normally but the pool had no connection, covering both this case and genuine
   * failures — causing "says offline but sometimes works".
   */
  @Test
  fun probeNotInitiated_doesNotMarkUnreachable() = runTest(timeout = 10.seconds) {
    val peerId = "peerXXXX"
    val (connector, pool) = buildConnector(peerId, FixedOutcomeClient(ConnectOutcome.NotInitiated))

    pool.reachability.test {
      connector.start()

      // Consume the initial empty-map emission.
      awaitItem()

      // The probe sets Probing, then NotInitiated does NOT call markUnreachable.
      // We expect to see Probing but never Unreachable.
      val probingState = awaitItem()
      assertEquals(
        expected = Reachability.Probing,
        actual = probingState[peerId],
        message = "Expected Probing state after probe starts",
      )

      // Allow time for the inner probe coroutine to complete — it runs on Dispatchers.IO.
      delay(500)

      // Cancel the turbine collection; check the current snapshot is not Unreachable.
      cancelAndIgnoreRemainingEvents()

      val reachability = pool.reachability.value[peerId]
      assertNotEquals(
        illegal = Reachability.Unreachable,
        actual = reachability,
        message = "ConnectOutcome.NotInitiated must NOT mark device Unreachable — " +
          "the peer may still establish an inbound connection. Got: $reachability",
      )
    }

    connector.stop()
  }

  @Test
  fun nearbyOnlyPeerIsProbed() = runTest(timeout = 10.seconds) {
    val peerId = "peerNEAR"
    val client = CountingClient()
    val (connector, pool) = buildConnector(
      peerId = peerId,
      client = client,
      visibleDevices = NearbyOnlyPeerVisibleDevices(peerId),
    )
    pool.reachability.test {
      connector.start()
      awaitItem()
      awaitItem()
      // Probe launches connectTo on Dispatchers.IO after markProbing; wait for it.
      delay(500)
      cancelAndIgnoreRemainingEvents()
    }
    connector.stop()
    assertEquals(
      true,
      client.connectCalls >= 1,
      "Nearby-only peers advertise the unified TCP port and must be probed (calls=${client.connectCalls})",
    )
  }

  /**
   * Regression guard: a genuine dial failure ([ConnectOutcome.Failed], meaning
   * every TCP/BLE attempt was exhausted) MUST mark the peer
   * [Reachability.Unreachable].  This ensures the UI correctly shows offline for
   * peers that are genuinely unreachable.
   */
  @Test
  fun probeFailed_marksUnreachable() = runTest(timeout = 10.seconds) {
    val peerId = "peerYYYY"
    val (connector, pool) = buildConnector(peerId, FixedOutcomeClient(ConnectOutcome.Failed))

    pool.reachability.test {
      connector.start()

      // Consume the initial empty-map emission.
      awaitItem()

      // The probe sets Probing immediately...
      val probingState = awaitItem()
      assertEquals(Reachability.Probing, probingState[peerId], "Expected Probing first")

      // ...then Failed → markUnreachable → Unreachable.
      val failedState = awaitItem()
      assertEquals(
        expected = Reachability.Unreachable,
        actual = failedState[peerId],
        message = "ConnectOutcome.Failed must mark device Unreachable",
      )

      cancelAndIgnoreRemainingEvents()
    }

    connector.stop()
  }

  /**
   * Happy path: [ConnectOutcome.Connected] means the pool already holds the
   * connection and [updateConnection] inside [ClientImpl] already set
   * [Reachability.Reachable].  The connector must NOT downgrade it to Unreachable.
   */
  @Test
  fun probeConnected_doesNotMarkUnreachable() = runTest(timeout = 10.seconds) {
    val peerId = "peerZZZZ"
    val pool = FakeConnectionPool()

    // Simulate Client.connectTo returning Connected without downgrading reachability.
    val client = FixedOutcomeClient(ConnectOutcome.Connected)

    val (connector, _) = buildConnector(peerId, client, pool)

    pool.reachability.test {
      connector.start()

      // Consume the initial empty-map emission.
      awaitItem()

      // The probe sets Probing then Connected — the connector removes the cooldown
      // but does NOT call markUnreachable. Reachability stays Probing (or gets
      // bumped to Reachable by a real ClientImpl, but our fake client doesn't touch
      // the pool, so it stays Probing at worst).
      val probingState = awaitItem()
      assertEquals(Reachability.Probing, probingState[peerId], "Expected Probing first")

      // Allow time for the inner probe coroutine to complete.
      delay(500)
      cancelAndIgnoreRemainingEvents()

      val reachability = pool.reachability.value[peerId]
      assertNotEquals(
        illegal = Reachability.Unreachable,
        actual = reachability,
        message = "ConnectOutcome.Connected must NOT mark device Unreachable. Got: $reachability",
      )
    }

    connector.stop()
  }

  /**
   * V4 (a) / F2 regression: [ConnectOutcome.NotInitiated] leaves reachability at
   * [Reachability.Probing] (see [probeNotInitiated_doesNotMarkUnreachable] above), but nothing
   * in [EagerReachabilityConnector] itself ever moves it further. Using the REAL
   * [ConnectionsPoolImpl] (not [FakeConnectionPool], which has no watchdog) and a shared virtual
   * clock, advance past the Probing watchdog window and assert the state does NOT stay wedged
   * on Probing — it must fall back to [Reachability.Unknown].
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun probeNotInitiated_doesNotStayProbingForever() = runTest(timeout = 10.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines: Coroutines = object : Coroutines {
      override val ioDispatcher: CoroutineDispatcher = dispatcher
      override val mainDispatcher: CoroutineDispatcher = dispatcher
      override val cpuDispatcher: CoroutineDispatcher = dispatcher
      override val appScope: CoroutineScope = this@runTest
      override fun newScope(): CoroutineScope = CoroutineScope(dispatcher)
      override fun newScope(context: CoroutineContext): CoroutineScope = CoroutineScope(dispatcher + context)
    }

    val peerId = "peerWWWW"
    val selfId = "aaaaaaaa"
    val repo = FixedIdPropertiesRepository(selfId)
    val currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(repo)
    val pool = ConnectionsPoolImpl(coroutines = coroutines)

    val connector = EagerReachabilityConnector(
      coroutines = coroutines,
      visibleDevices = SinglePeerVisibleDevices(peerId),
      currentDeviceProvider = currentDeviceProvider,
      client = FixedOutcomeClient(ConnectOutcome.NotInitiated),
      connectionsPool = pool,
      networkLifecycleMonitor = NetworkLifecycleMonitor(),
    )

    connector.start()
    runCurrent()
    assertEquals(
      Reachability.Probing,
      pool.reachability.value[peerId],
      "expected Probing right after the probe starts",
    )

    // Advance well past the Probing watchdog window.
    advanceTimeBy(16.seconds)
    runCurrent()

    assertNotEquals(
      illegal = Reachability.Probing,
      actual = pool.reachability.value[peerId],
      message = "NotInitiated must not wedge Probing forever — the watchdog must fall back to Unknown",
    )
    assertEquals(
      Reachability.Unknown,
      pool.reachability.value[peerId],
      "wedge must resolve to Unknown specifically, not Unreachable",
    )

    connector.stop()
  }

  // -----------------------------------------------------------------------
  // T7: periodic re-probe ticker, cooldown, per-tick probe cap
  // -----------------------------------------------------------------------

  /**
   * [Coroutines] whose every dispatcher is the test's [StandardTestDispatcher] so the
   * connector's 30s ticker, probe coroutines and cooldown timers all run on the virtual
   * clock ([advanceTimeBy]/[runCurrent] drive them deterministically). Mirrors the local
   * helper in ConnectionsPoolProbingWatchdogTest.
   */
  private fun testCoroutines(
    scope: TestScope,
    dispatcher: kotlinx.coroutines.test.TestDispatcher,
  ): Coroutines = object : Coroutines {
    override val ioDispatcher: CoroutineDispatcher = dispatcher
    override val mainDispatcher: CoroutineDispatcher = dispatcher
    override val cpuDispatcher: CoroutineDispatcher = dispatcher
    override val appScope: CoroutineScope = scope
    override fun newScope(): CoroutineScope = CoroutineScope(dispatcher)
    override fun newScope(context: CoroutineContext): CoroutineScope =
      CoroutineScope(dispatcher + context)
  }

  private class FakeBleSession(override val deviceId: String) : BleSession {
    override var isOpen: Boolean = true
      private set

    override val mtu: Int get() = 512

    override suspend fun sendChunk(chunk: ByteArray) = Unit
    override suspend fun receiveChunk(): ByteArray? = null

    override fun close() {
      isOpen = false
    }
  }

  /** Inert messenger (BLE transport, heartbeat disabled) used only to occupy a pool slot. */
  private fun fakeMessenger(coroutines: Coroutines, deviceId: String): ConnectionMessenger {
    val conn = Connection.Ble(FakeBleSession(deviceId), deviceId)
    return ConnectionMessenger(
      coroutines = coroutines,
      connection = conn,
      messagesRouter = FakeMessagesRouter(),
      readChannel = ByteChannel(autoFlush = true),
      writeChannel = ByteChannel(autoFlush = true),
      ackTimeoutMs = 500L,
    )
  }

  /** VisibleDevices fake backed by a mutable StateFlow so tests can re-emit device sets. */
  private class FakeVisibleDevices(initial: Map<String, DiscoveryDevice>) : VisibleDevices {
    private val flow = MutableStateFlow(initial)
    override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = flow

    fun emit(devices: Map<String, DiscoveryDevice>) {
      flow.value = devices
    }

    override suspend fun onNewDeviceVisible(
      deviceInfo: DeviceInfo,
      deviceConnection: DeviceConnection,
    ) = Unit

    override fun isDeviceVisible(deviceId: String) = flow.value.containsKey(deviceId)
    override fun getDevice(deviceId: String) = flow.value[deviceId]
    override fun cachedNameFor(deviceId: String) = null
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) { flow.value = emptyMap() }
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
  }

  private fun testDevice(id: String, lastSeen: Long = 0L) = DiscoveryDevice(
    deviceInfo = DeviceInfo(
      deviceId = id,
      name = "Peer $id",
      deviceType = DeviceType.DESKTOP,
      osType = OsType.LINUX,
    ),
    deviceConnections = listOf(DeviceConnection.KlardropConnection("10.0.0.1", 5050)),
    lastSeenTimestamp = lastSeen,
  )

  /** Client fake with a switchable outcome; counts every dial so tests can bound probes. */
  private class SwitchableClient(
    private val onConnected: (suspend (String) -> Unit)? = null,
  ) : Client {
    var outcome: ConnectOutcome = ConnectOutcome.Failed
    val connectCalls = mutableListOf<String>()

    override suspend fun connectTo(deviceId: String): ConnectOutcome {
      connectCalls += deviceId
      if (outcome == ConnectOutcome.Connected) onConnected?.invoke(deviceId)
      return outcome
    }
  }

  private fun buildVirtualConnector(
    devices: FakeVisibleDevices,
    client: Client,
    pool: FakeConnectionPool,
    coroutines: Coroutines,
  ): EagerReachabilityConnector {
    val repo = FixedIdPropertiesRepository("aaaaaaaa")
    val currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(repo)
    return EagerReachabilityConnector(
      coroutines = coroutines,
      visibleDevices = devices,
      currentDeviceProvider = currentDeviceProvider,
      client = client,
      connectionsPool = pool,
      networkLifecycleMonitor = NetworkLifecycleMonitor(),
    )
  }

  /**
   * Recovery-to-Reachable: a peer whose first probe failed (server not up) used to stay
   * Offline forever because probes only fired on visibleDevices emissions. The 30s ticker
   * must re-probe the CURRENT device set without any new emission; once the server is up,
   * the second probe connects and reachability emits Reachable.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun tickerReprobesFailedPeer_reachesReachableOnceServerIsUp() = runTest(timeout = 10.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = testCoroutines(this, dispatcher)
    val pool = FakeConnectionPool()
    val peerId = "peerRRRR"
    val client = SwitchableClient(
      onConnected = { id -> pool.updateConnection(id, fakeMessenger(coroutines, id)) },
    )
    val connector = buildVirtualConnector(
      FakeVisibleDevices(mapOf(peerId to testDevice(peerId))),
      client,
      pool,
      coroutines,
    )

    connector.start()
    runCurrent()

    // First probe (collect path) fails — server not up.
    assertEquals(1, client.connectCalls.size, "collect path should probe the newly visible peer once")
    assertEquals(Reachability.Unreachable, pool.reachability.value[peerId])

    // Server comes up. No new discovery emission happens — only the 30s ticker can recover this.
    client.outcome = ConnectOutcome.Connected
    advanceTimeBy(30.seconds)
    runCurrent()

    assertEquals(
      2,
      client.connectCalls.size,
      "ticker must re-probe the failed peer after 30s without a new emission",
    )
    assertEquals(
      Reachability.Reachable,
      pool.reachability.value[peerId],
      "second probe must flip reachability to Reachable",
    )

    connector.stop()
  }

  /**
   * Cooldown: a failed probe arms a 5s per-peer cooldown and BOTH probe paths (collect
   * re-emission and the 30s ticker) must honor it — the ticker must not bypass the cooldown
   * map — and the cooldown must release afterwards so the peer is re-probed.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun cooldownPreventsBackToBackProbes_onBothCollectAndTickerPaths() = runTest(timeout = 10.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = testCoroutines(this, dispatcher)
    val pool = FakeConnectionPool()
    val peerId = "peerCCCC"
    val client = SwitchableClient()
    val devices = FakeVisibleDevices(mapOf(peerId to testDevice(peerId)))
    val connector = buildVirtualConnector(devices, client, pool, coroutines)

    connector.start()
    runCurrent()
    assertEquals(1, client.connectCalls.size, "initial collect probe at t=0")

    // t=4 (cooldown until t=5): re-emitting the same peer must NOT re-probe.
    advanceTimeBy(4.seconds)
    devices.emit(mapOf(peerId to testDevice(peerId, lastSeen = 1L)))
    runCurrent()
    assertEquals(1, client.connectCalls.size, "cooldown must suppress a re-probe within 5s of the failed one")

    // t=29: cooldown long expired — a fresh emission re-probes (collect path), re-arming
    // the cooldown until t=34.
    advanceTimeBy(25.seconds)
    devices.emit(mapOf(peerId to testDevice(peerId, lastSeen = 2L)))
    runCurrent()
    assertEquals(2, client.connectCalls.size)

    // t=30: the ticker fires 1s after the t=29 probe — the cooldown must bind for the ticker too.
    advanceTimeBy(1.seconds)
    runCurrent()
    assertEquals(2, client.connectCalls.size, "ticker must honor the cooldown map (no bypass)")

    // t=60: next tick — cooldown expired → probes again.
    advanceTimeBy(30.seconds)
    runCurrent()
    assertEquals(3, client.connectCalls.size, "cooldown must release after 5s so the peer is re-probed")

    connector.stop()
  }

  /**
   * Probe cap: with 5 visible devices the ticker issues at most one probe per device per
   * tick — 5 devices → ≤5 probes per tick (and exactly 5 when all are eligible), never a
   * per-device storm.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun tickerProbesEachDeviceAtMostOncePerTick() = runTest(timeout = 10.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = testCoroutines(this, dispatcher)
    val pool = FakeConnectionPool()
    val peerIds = listOf("peerA1", "peerB1", "peerC1", "peerD1", "peerE1")
    val client = SwitchableClient()
    val devices = FakeVisibleDevices(peerIds.associateWith { testDevice(it) })
    val connector = buildVirtualConnector(devices, client, pool, coroutines)

    connector.start()
    runCurrent()
    assertEquals(5, client.connectCalls.size, "collect path probes each of the 5 devices once")

    advanceTimeBy(30.seconds)
    runCurrent()
    assertEquals(
      10,
      client.connectCalls.size,
      "one tick must add exactly one probe per device (5 devices → ≤5 probes per tick)",
    )

    connector.stop()
  }
}
