package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * B23 — silent-peer churn keeps the awake device's klardrop NsdManager browse
 * wedged "found", so the peer never re-appears after it drops to BLE-only.
 *
 * ROOT CAUSE (confirmed live): the klardrop browse is subscribed exactly ONCE
 * (Klardrop.init -> DiscoveryNetwork.discoveryKlardropDevices ->
 * ServiceDiscoveryMdns.discoverServices) and is re-subscribed ONLY by
 * rebuildMdnsState() on a NetworkChangeEvent. A Wi-Fi-power-save peer stops
 * answering multicast WITHOUT a goodbye, so NsdManager never fires
 * onServiceLost and keeps the instance cached as 'found', de-duplicating any
 * later onServiceFound when the peer wakes. Meanwhile the app already removed
 * the peer's klardrop endpoint (Client.invalidateKlardropEndpoint after the
 * B17 connect-timeout, plus the 5-minute VisibleDevices TTL sweep). With no
 * fresh ServiceFound and no browse restart, the peer is stuck BLE/Nearby-only
 * / offline permanently.
 *
 * The NsdManager dedup itself can't be unit-tested, but the DiscoveryNetwork
 * ORCHESTRATION can: the fix must RESTART the klardrop browse (re-subscribe
 * discoverServices(KLARDROP_SERVICE_TYPE), tearing down the wedged session and
 * forcing NsdManager to re-evaluate the peer) when
 *   (a) a known KLARDROP peer loses its last klardrop endpoint / drops to
 *       BLE-only, and
 *   (b) a gated periodic backstop fires while a peer lacks a Klardrop transport,
 * while a >= 30 s debounce prevents restart storms.
 *
 * TODAY no such restart happens — only rebuildMdnsState() on a
 * NetworkChangeEvent re-subscribes — so every assertion below is RED on the
 * current code (overturning the earlier B12 "restart() no-op" and B14 "no
 * periodic re-discover" dismissals for SILENT PEER churn).
 *
 * Test-seam note: ServiceDiscoveryMdns / NetworkLifecycleMonitor / BleTransport
 * are final `expect class`es and cannot be subclassed into a counting fake from
 * common test code, and the Android actual needs a Context. This test therefore
 * lives in the desktopJvm test source set, drives a REAL [DiscoveryNetwork] with
 * the real-but-inert desktopJvm collaborators (cold mDNS flow, unsupported BLE,
 * never-triggered NIC monitor) plus a controllable [B23VisibleDevices], and
 * tracks klardrop browse subscriptions via the harness counter. The B23 fix is
 * expected to wire the browse-restart so that counter rises beyond the single
 * init-time subscription.
 */
class DiscoveryKlardropBrowseRestartB23Test {

  /**
   * (a) When a known KLARDROP peer loses its last klardrop endpoint (drops to
   * BLE-only — exactly what B17 endpoint-invalidation + TTL sweep produce for a
   * silent peer), DiscoveryNetwork MUST restart the klardrop browse so a wedged
   * NsdManager session is re-evaluated and the peer can be re-discovered.
   *
   * RED today: nothing restarts the browse on endpoint loss, so the
   * subscription count stays at the single init-time subscription.
   */
  @Test
  fun klardropBrowseRestartsWhenKnownPeerDropsToBleOnly() = runTest {
    val harness = newB23DiscoveryHarness(this)

    // Init-time browse subscription (Klardrop.init -> discoveryKlardropDevices).
    harness.discoveryNetwork.discoveryKlardropDevices()
    harness.advanceUntilIdle()
    assertEquals(
      1,
      harness.klardropBrowseSubscriptions(),
      "sanity: the init-time klardrop browse subscribes exactly once",
    )

    // A known klardrop peer is visible, then its last klardrop endpoint is
    // invalidated (B17 connect-timeout) leaving it BLE-only.
    harness.visibleDevices.addKlardropDevice(deviceId = "pixel-7a", address = "192.168.1.50", port = 44321)
    harness.visibleDevices.dropPeerToBleOnly(deviceId = "pixel-7a", bleAddress = "AA:BB:CC:DD:EE:FF")
    harness.advanceUntilIdle()

    assertTrue(
      harness.klardropBrowseSubscriptions() >= 2,
      "a known KLARDROP peer dropping to BLE-only must RESTART the klardrop " +
        "browse to clear a wedged NsdManager 'found' instance, but the browse " +
        "was subscribed ${harness.klardropBrowseSubscriptions()} time(s) " +
        "(only the init-time subscription)",
    )
  }

  /**
   * (b1) A gated periodic backstop MUST issue at least TWO browse restarts for a
   * peer that LOST klardrop and remains BLE-only with NO further state changes
   * after the initial drop (i.e. no additional reactive-watcher triggers).
   *
   * This tests that the backstop is a genuine TIMER-driven retry loop, not just
   * the one-shot reactive watcher. A single reactive watcher fire produces exactly
   * ONE restart and then stops (distinctUntilChanged suppresses re-fires for an
   * unchanged set). A SECOND restart can only come from the periodic backstop.
   *
   * Note: we advance by precise bounded durations to measure counts at exact virtual-
   * time points (rather than advanceUntilIdle which would exhaust the entire backstop
   * loop and produce ambiguous counts).
   */
  @Test
  fun gatedPeriodicBackstopIssuesAtLeastTwoBrowseRestartsForStuckBleOnlyPeer() = runTest {
    val harness = newB23DiscoveryHarness(this)

    harness.discoveryNetwork.discoveryKlardropDevices()
    // Only process idle tasks at t=0 (no time advance yet).
    advanceTimeBy(1.seconds)
    harness.advanceUntilIdle()
    assertEquals(1, harness.klardropBrowseSubscriptions())

    // Peer starts with klardrop, then drops to BLE-only (reactive watcher fires once).
    harness.visibleDevices.addKlardropDevice(deviceId = "pixel-7a", address = "192.168.1.50", port = 44321)
    harness.visibleDevices.dropPeerToBleOnly(deviceId = "pixel-7a", bleAddress = "AA:BB:CC:DD:EE:FF")
    // Allow exactly the reactive watcher debounce to fire: 30s + a small epsilon.
    // The periodic backstop waits BROWSE_BACKSTOP_INTERVAL (60s) before first fire,
    // so advancing 31s fires the reactive debounce but NOT the backstop.
    advanceTimeBy(DiscoveryNetwork.BROWSE_RESTART_DEBOUNCE + 1.seconds)
    // Process the coroutines scheduled to run at/before this point (NOT any future tasks).
    // We deliberately do NOT call advanceUntilIdle() here because that would exhaust
    // the entire backstop loop across all 5 intervals and skew the count.
    // Instead, run only what's currently dispatchable at this virtual time.
    testScheduler.runCurrent()

    val afterFirstReactive = harness.klardropBrowseSubscriptions()
    assertTrue(
      afterFirstReactive >= 2,
      "The reactive watcher should have produced at least one restart by t=31s, " +
        "but got ${afterFirstReactive - 1} restart(s). " +
        "(Count = $afterFirstReactive, expected >= 2)",
    )

    // Now advance past one full backstop interval beyond the reactive watcher's fire,
    // WITHOUT advancing through the full backstop loop.
    // Backstop fires at BROWSE_BACKSTOP_INTERVAL after it was started (t=0 state change).
    // At this point we're at t=31s. The backstop waits 60s from start so fires at t=60s.
    // The backstop's debounce adds another 30s. So the browse restart lands at t=90s.
    // We advance 60s more (to t=91s total) to land just past the backstop's first fire+debounce.
    advanceTimeBy(DiscoveryNetwork.BROWSE_BACKSTOP_INTERVAL + 1.seconds)
    testScheduler.runCurrent()

    val afterOneBackstopCycle = harness.klardropBrowseSubscriptions()
    assertTrue(
      afterOneBackstopCycle > afterFirstReactive,
      "The periodic backstop must issue an ADDITIONAL browse restart beyond the " +
        "reactive watcher's single fire. After one backstop interval (60s), expected > " +
        "$afterFirstReactive subscription(s), but got $afterOneBackstopCycle. " +
        "A single reactive watcher fire cannot satisfy this (distinctUntilChanged " +
        "suppresses re-fires for an unchanged BLE-only peer set).",
    )
  }

  /**
   * (b2) A peer that is LEGITIMATELY always BLE-only (never had klardrop, never will)
   * must NOT cause PERPETUAL restarts. After [BROWSE_BACKSTOP_MAX_RETRIES] the
   * backstop must stop — so the subscription count must stop growing.
   */
  @Test
  fun gatedPeriodicBackstopStopsAfterCapForLegitimatelyBleOnlyPeer() = runTest {
    val harness = newB23DiscoveryHarness(this)

    harness.discoveryNetwork.discoveryKlardropDevices()
    harness.advanceUntilIdle()
    assertEquals(1, harness.klardropBrowseSubscriptions())

    // Add a BLE-only peer that never recovers a klardrop connection.
    harness.visibleDevices.addBleOnlyDevice(deviceId = "ble-only-device", bleAddress = "11:22:33:44:55:66")

    // Advance past BROWSE_BACKSTOP_MAX_RETRIES intervals + debounce each time,
    // giving the backstop every opportunity to fire, then advance a LOT more.
    val singleCycle = DiscoveryNetwork.BROWSE_BACKSTOP_INTERVAL + DiscoveryNetwork.BROWSE_RESTART_DEBOUNCE + 1.seconds
    val maxRetries = DiscoveryNetwork.BROWSE_BACKSTOP_MAX_RETRIES
    // Advance enough to exhaust all retries.
    advanceTimeBy(singleCycle * (maxRetries + 1))
    harness.advanceUntilIdle()

    val afterCap = harness.klardropBrowseSubscriptions()

    // Advance a large amount more — the backstop should NOT fire again.
    advanceTimeBy(singleCycle * 5)
    harness.advanceUntilIdle()

    val afterLongWait = harness.klardropBrowseSubscriptions()

    assertEquals(
      afterCap,
      afterLongWait,
      "After reaching the retry cap ($maxRetries), no more browse restarts should be " +
        "issued for a legitimately BLE-only peer. Subscriptions grew from $afterCap to " +
        "$afterLongWait after an additional 5 backstop intervals, indicating the cap " +
        "is not respected.",
    )

    // Also check we didn't fire MORE than cap+1 (init) restarts (belt-and-suspenders).
    assertTrue(
      afterLongWait <= maxRetries + 1 + 1, // +1 init + 1 reactive watcher fire + cap
      "Total subscriptions $afterLongWait exceeded cap ($maxRetries retries + 1 init + 1 reactive)",
    )
  }

  /**
   * The restart must be debounced (>= 30 s) so the two restart triggers
   * (endpoint loss + periodic backstop) and rapid repeated endpoint losses do
   * not produce a browse-restart storm. Two endpoint-loss events 5 s apart must
   * collapse to a single restart.
   *
   * Note: we advance by exactly one debounce window (30s + epsilon) to measure
   * only the first debounce-collapsed restart. We do NOT call advanceUntilIdle()
   * (which would exhaust the periodic backstop loop across all intervals).
   */
  @Test
  fun rapidEndpointLossesDebounceToASingleBrowseRestart() = runTest {
    val harness = newB23DiscoveryHarness(this)

    harness.discoveryNetwork.discoveryKlardropDevices()
    advanceTimeBy(1.seconds)
    testScheduler.runCurrent()
    assertEquals(1, harness.klardropBrowseSubscriptions())

    harness.visibleDevices.addKlardropDevice(deviceId = "pixel-7a", address = "192.168.1.50", port = 44321)
    harness.visibleDevices.dropPeerToBleOnly(deviceId = "pixel-7a", bleAddress = "AA:BB:CC:DD:EE:FF")
    advanceTimeBy(5.seconds)
    testScheduler.runCurrent()
    // A second endpoint loss within the debounce window — this cancels the first
    // debounce and starts a fresh one.
    harness.visibleDevices.addKlardropDevice(deviceId = "pixel-7a", address = "192.168.1.50", port = 55777)
    harness.visibleDevices.dropPeerToBleOnly(deviceId = "pixel-7a", bleAddress = "AA:BB:CC:DD:EE:FF")
    // Advance exactly one debounce window from the SECOND drop (most recent debounce start).
    // Total: 1 + 5 + 30 + 1 = 37s. The first debounce (started at t=1s) would have fired
    // at t=31s but is cancelled by the second drop at t=6s. The second debounce fires at t=36s.
    // The periodic backstop is waiting 60s so has NOT fired yet (at t=37s, only 37s elapsed).
    advanceTimeBy(DiscoveryNetwork.BROWSE_RESTART_DEBOUNCE + 1.seconds)
    testScheduler.runCurrent()

    val restarts = harness.klardropBrowseSubscriptions() - 1 // minus the init subscription
    assertEquals(
      1,
      restarts,
      "two endpoint losses within the 30 s debounce window must collapse to " +
        "exactly one browse restart, but observed $restarts restart(s)",
    )
  }
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/**
 * Test seam for the B23 klardrop-browse-restart orchestration. Wraps a REAL
 * [DiscoveryNetwork] driven with real-but-inert desktopJvm collaborators and
 * exposes:
 *  - the klardrop browse subscription count ([klardropBrowseSubscriptions]), and
 *  - a controllable [B23VisibleDevices] for simulating silent-peer churn.
 */
interface B23DiscoveryHarness {
  val discoveryNetwork: DiscoveryNetwork
  val visibleDevices: B23VisibleDevices

  /**
   * How many times the klardrop service browse
   * (`discoverServices(KLARDROP_SERVICE_TYPE)`) has been subscribed. The
   * init-time call subscribes once; a B23 browse-restart re-subscribes,
   * incrementing this.
   */
  fun klardropBrowseSubscriptions(): Int

  fun advanceUntilIdle()
}

/**
 * Builds a [B23DiscoveryHarness] bound to [testScope].
 *
 * The final `expect class` collaborators are constructed but kept inert:
 *  - [ServiceDiscoveryMdns] is real; `discoverServices` is a COLD flow that does
 *    no network work until collected.
 *  - [NetworkLifecycleMonitor] is real but `observe()` only works when collected,
 *    and we never trigger a NetworkChangeEvent — so the ONLY existing
 *    browse-restart path is intentionally not exercised, isolating the B23
 *    restart paths (endpoint loss + periodic backstop) under test.
 *  - [BleTransport] is real and unsupported on this host (helper == null), so
 *    BLE advertise/scan are no-ops.
 */
fun newB23DiscoveryHarness(testScope: TestScope): B23DiscoveryHarness {
  val coroutines = TestScopeCoroutines(testScope)
  val visible = B23VisibleDevices()

  val discoveryNetwork = DiscoveryNetwork(
    coroutines,
    visible,
    ServiceDiscoveryMdns(),
    NearbyShareDiscoveryUtils(),
    KlardropDiscoveryUtils(),
    CurrentDeviceProvider(B23LocalProperties()),
    BleTransport(),
    NetworkLifecycleMonitor(),
  )

  return object : B23DiscoveryHarness {
    override val discoveryNetwork = discoveryNetwork
    override val visibleDevices = visible

    override fun klardropBrowseSubscriptions(): Int {
      // Read directly from the production counter that DiscoveryNetwork increments
      // every time discoveryKlardropDevices() is called (initial + each restart).
      // The B23 fix wires the browse-restart so this counter rises beyond 1.
      return discoveryNetwork.klardropBrowseStartCount.value
    }

    override fun advanceUntilIdle() {
      testScope.advanceUntilIdle()
    }
  }
}

/**
 * Minimal controllable [VisibleDevices] for the B23 orchestration tests. Models
 * just enough of the visible-device lifecycle to reproduce silent-peer churn:
 * a peer with a klardrop endpoint, the loss of that endpoint (drop to BLE-only),
 * and a peer that is BLE-only from the start.
 */
class B23VisibleDevices : VisibleDevices {

  private val devices = linkedMapOf<String, DiscoveryDevice>()
  private val flow = MutableStateFlow<Map<String, DiscoveryDevice>>(emptyMap())

  override val visibleDevices = flow

  fun addKlardropDevice(deviceId: String, address: String, port: Int) {
    val existing = devices[deviceId]
    val connections = existing?.deviceConnections.orEmpty()
      .filterNot { it is DeviceConnection.KlardropConnection } +
      DeviceConnection.KlardropConnection(address, port)
    devices[deviceId] = DiscoveryDevice(
      deviceInfo = existing?.deviceInfo ?: DeviceInfo(
        deviceId = deviceId,
        name = "Pixel",
        deviceType = DeviceType.MOBILE,
        osType = OsType.ANDROID,
      ),
      deviceConnections = connections,
      lastSeenTimestamp = 0L,
    )
    publish()
  }

  fun addBleOnlyDevice(deviceId: String, bleAddress: String) {
    devices[deviceId] = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = deviceId,
        deviceType = DeviceType.UNKNOWN,
        osType = OsType.UNKNOWN,
      ),
      deviceConnections = listOf(DeviceConnection.BleConnection(bleAddress)),
      lastSeenTimestamp = 0L,
    )
    publish()
  }

  /**
   * Remove the peer's klardrop endpoint(s) and leave it BLE-only — exactly the
   * state B17 endpoint-invalidation + the TTL sweep leave a silent peer in.
   */
  fun dropPeerToBleOnly(deviceId: String, bleAddress: String) {
    val existing = devices[deviceId] ?: return
    val remaining = existing.deviceConnections
      .filterNot { it is DeviceConnection.KlardropConnection }
      .toMutableList()
    if (remaining.none { it is DeviceConnection.BleConnection }) {
      remaining += DeviceConnection.BleConnection(bleAddress)
    }
    devices[deviceId] = existing.copy(deviceConnections = remaining)
    publish()
  }

  private fun publish() {
    flow.value = devices.toMap()
  }

  // --- VisibleDevices surface not central to these tests ---

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
    val existing = devices[deviceInfo.deviceId]
    val connections = (existing?.deviceConnections.orEmpty() + deviceConnection).distinct()
    devices[deviceInfo.deviceId] = DiscoveryDevice(deviceInfo, connections, lastSeenTimestamp = 0L)
    publish()
  }

  override fun isDeviceVisible(deviceId: String) = devices.containsKey(deviceId)

  override fun getDevice(deviceId: String) = devices[deviceId]

  override fun cachedNameFor(deviceId: String) = devices[deviceId]?.deviceInfo?.name

  override fun touchLastSeen(deviceId: String) { /* no-op */ }

  override fun onDeviceLost(deviceId: String) {
    devices.remove(deviceId); publish()
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {
    val existing = devices[deviceId] ?: return
    val remaining = existing.deviceConnections.filterNot { it == deviceConnectionToRemove }
    if (remaining.isEmpty()) devices.remove(deviceId)
    else devices[deviceId] = existing.copy(deviceConnections = remaining)
    publish()
  }

  override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? =
    devices.values.firstOrNull { d -> d.deviceConnections.any { it.address == address.hostname } }

  override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) {
    onDeviceLost(deviceId, DeviceConnection.KlardropConnection(address, port))
  }
}

private class B23LocalProperties : LocalPropertiesRepository {
  override val properties = MutableStateFlow(KlardropProperties(deviceId = "selfselfself"))
  override suspend fun getProperty(): KlardropProperties = properties.first()
  override suspend fun save(properties: KlardropProperties) { this.properties.value = properties }
  override suspend fun saveCustomDeviceName(customDeviceName: String?) {
    properties.value = properties.value.copy(customDeviceName = customDeviceName)
  }
  override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
    properties.value = properties.value.copy(backgroundDiscoveryEnabled = enabled)
  }
}

private class TestScopeCoroutines(testScope: TestScope) : Coroutines {
  private val ctx: CoroutineContext = testScope.coroutineContext
  private val testDispatcher: CoroutineDispatcher = ctx[ContinuationInterceptor] as CoroutineDispatcher

  override fun newScope(): CoroutineScope = CoroutineScope(ctx)
  override fun newScope(context: CoroutineContext): CoroutineScope = CoroutineScope(ctx + context)
  override val appScope: CoroutineScope = CoroutineScope(ctx)
  override val ioDispatcher: CoroutineDispatcher = testDispatcher
  override val mainDispatcher: CoroutineDispatcher = testDispatcher
  override val cpuDispatcher: CoroutineDispatcher = testDispatcher
}
