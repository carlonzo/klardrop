package com.carlom.klardrop.common.communication

import TestCoroutines
import app.cash.turbine.test
import com.carlom.klardrop.common.FakeConnectionPool
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

  private fun buildConnector(
    peerId: String,
    client: Client,
    pool: FakeConnectionPool = FakeConnectionPool(),
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
      visibleDevices = SinglePeerVisibleDevices(peerId),
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
}
