package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.KLARDROP_SERVICE_TYPE
import com.carlom.klardrop.common.discovery.NearbyShareDiscoveryUtils.Companion.NEARBY_SERVICE_TYPE
import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceDiscoveryEvent
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdnsBackend
import com.carlom.klardrop.common.network.NetworkChangeEvent
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T4 (pairing-offline-diagnosis): the mDNS advertisement must always match the
 * live server port. Live evidence: the phone's server port churned
 * 34283 -> 46651 -> 46039 across app restarts while stale advertisements
 * lingered, so peers dialled dead ports and marked the device "Offline".
 *
 * The fix under test:
 *  - [DiscoveryNetwork.republishIfPortChanged] re-registers BOTH the klardrop
 *    and the Nearby publication (exactly one new register per protocol) when
 *    the server's live port differs from the advertised one, and is a strict
 *    no-op when it does not (or when nothing was published yet).
 *  - a [NetworkChangeEvent.Changed] (network-change handler) re-runs the
 *    publication path against the advertised port.
 *
 * Test-seam note (same constraint as DiscoveryKlardropBrowseRestartTest):
 * ServiceDiscoveryMdns / NetworkLifecycleMonitor / BleTransport are final
 * `expect class`es that cannot be subclassed from common test code, so this
 * test lives in the desktopJvm source set. The desktopJvm actuals gained
 * constructor seams (backend / event-flow override) so the mDNS registration
 * calls can be counted and network changes can be fired deterministically.
 *
 * "Unregister+register pair" wording: ServiceDiscoveryMdns has no unregister
 * API — re-registration goes through the existing cancel-publish-job +
 * registerService path (backends replace the record internally), so exactly
 * ONE new registerService call per protocol is the observable contract.
 */
class DiscoveryNetworkRepublishTest {

  // --- Port-change republish ------------------------------------------------

  /**
   * A server-port change must trigger exactly ONE new registration per
   * protocol (klardrop + Nearby), carrying the NEW port. Ports are the ones
   * observed churning on the phone (34283 -> 46651).
   */
  @Test
  fun portChangeRepublishesExactlyOncePerProtocol() = runTest {
    val backend = CountingMdnsBackend()
    val network = newDiscoveryNetwork(this, backend)

    network.startPublishKlardrop(34283)
    network.startPublishNearbyShare(34283)
    advanceUntilIdle()
    val klardropBefore = backend.registrationsFor(KLARDROP_SERVICE_TYPE)
    val nearbyBefore = backend.registrationsFor(NEARBY_SERVICE_TYPE)
    assertTrue(klardropBefore.isNotEmpty(), "sanity: klardrop publish registered")
    assertTrue(nearbyBefore.isNotEmpty(), "sanity: Nearby publish registered")

    network.republishIfPortChanged(46651)
    advanceUntilIdle()

    val klardropAfter = backend.registrationsFor(KLARDROP_SERVICE_TYPE)
    val nearbyAfter = backend.registrationsFor(NEARBY_SERVICE_TYPE)
    assertEquals(
      1,
      klardropAfter.size - klardropBefore.size,
      "a server-port change must re-register the klardrop publication exactly once",
    )
    assertEquals(
      1,
      nearbyAfter.size - nearbyBefore.size,
      "a server-port change must re-register the Nearby publication exactly once",
    )
    assertEquals(46651, klardropAfter.last().port, "klardrop must be re-registered on the NEW port")
    assertEquals(46651, nearbyAfter.last().port, "Nearby must be re-registered on the NEW port")
  }

  /** Same port -> strict no-op: no additional registrations of any kind. */
  @Test
  fun unchangedPortIsANoOp() = runTest {
    val backend = CountingMdnsBackend()
    val network = newDiscoveryNetwork(this, backend)

    network.startPublishKlardrop(46039)
    network.startPublishNearbyShare(46039)
    advanceUntilIdle()
    val before = backend.registrations.size

    network.republishIfPortChanged(46039)
    advanceUntilIdle()

    assertEquals(
      before,
      backend.registrations.size,
      "republishIfPortChanged with an unchanged port must not register anything",
    )
  }

  /** Nothing published yet -> no-op (no crash, no registration). */
  @Test
  fun republishBeforeAnyPublishIsANoOp() = runTest {
    val backend = CountingMdnsBackend()
    val network = newDiscoveryNetwork(this, backend)

    network.republishIfPortChanged(1234)
    advanceUntilIdle()

    assertEquals(0, backend.registrations.size)
  }

  // --- Network-change handler ----------------------------------------------

  /**
   * A NetworkChangeEvent.Changed must run the publication path: the mDNS state
   * is rebuilt (restart) and both active publications are re-issued on the
   * advertised port — exactly one new registration per protocol.
   */
  @Test
  fun networkChangeCallbackTriggersRepublish() = runTest {
    val events = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 8)
    val backend = CountingMdnsBackend()
    val network = newDiscoveryNetwork(this, backend, networkEvents = events)

    // Let the lifecycle collector subscribe before emitting (replay=0 flow).
    runCurrent()

    network.startPublishKlardrop(34283)
    network.startPublishNearbyShare(34283)
    advanceUntilIdle()
    val klardropBefore = backend.registrationsFor(KLARDROP_SERVICE_TYPE)
    val nearbyBefore = backend.registrationsFor(NEARBY_SERVICE_TYPE)
    val restartsBefore = backend.restarts

    events.tryEmit(NetworkChangeEvent.Changed)
    advanceUntilIdle()

    assertEquals(1, backend.restarts - restartsBefore, "network change must rebuild mDNS state")
    val klardropAfter = backend.registrationsFor(KLARDROP_SERVICE_TYPE)
    val nearbyAfter = backend.registrationsFor(NEARBY_SERVICE_TYPE)
    assertEquals(
      1,
      klardropAfter.size - klardropBefore.size,
      "network change must re-issue the klardrop publication exactly once",
    )
    assertEquals(
      1,
      nearbyAfter.size - nearbyBefore.size,
      "network change must re-issue the Nearby publication exactly once",
    )
    assertEquals(34283, klardropAfter.last().port, "re-issue must keep the advertised port")
    assertEquals(34283, nearbyAfter.last().port, "re-issue must keep the advertised port")
  }
}

/**
 * The desktop-JVM actual of [verifyAdvertisedPortAlive] probes the loopback
 * listener: a port with a live listener must report alive, a closed port must
 * report dead (this is what surfaces "advertised port has no listener").
 */
class AdvertisedPortProbeTest {

  @Test
  fun liveListenerReportsAlive() {
    val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    try {
      assertTrue(verifyAdvertisedPortAlive(server.localPort))
    } finally {
      server.close()
    }
  }

  @Test
  fun closedPortReportsDead() {
    val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val deadPort = server.localPort
    server.close()
    assertFalse(verifyAdvertisedPortAlive(deadPort), "closed port must not report alive")
  }
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/** Counts registerService/restart calls instead of touching a real mDNS stack. */
private class CountingMdnsBackend : ServiceDiscoveryMdnsBackend {
  val registrations = mutableListOf<RegisterServiceInfo>()
  var restarts = 0

  fun registrationsFor(serviceType: String) =
    registrations.filter { it.serviceType == serviceType }

  override fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> = emptyFlow()

  override suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {
    registrations += registerServiceInfo
  }

  override suspend fun restart() {
    restarts++
  }
}

private fun newDiscoveryNetwork(
  testScope: TestScope,
  backend: CountingMdnsBackend,
  networkEvents: MutableSharedFlow<NetworkChangeEvent> = MutableSharedFlow(extraBufferCapacity = 8),
): DiscoveryNetwork =
  DiscoveryNetwork(
    coroutines = RepublishTestCoroutines(testScope),
    visibleDevices = VisibleDevicesStub(),
    serviceDiscoveryMdns = ServiceDiscoveryMdns(backend),
    nearbyShareDiscoveryUtils = NearbyShareDiscoveryUtils(),
    klardropDiscoveryUtils = KlardropDiscoveryUtils(),
    currentDeviceProvider = CurrentDeviceProvider(RepublishStubLocalProperties()),
    bleTransport = BleTransport(),
    networkLifecycleMonitor = NetworkLifecycleMonitor(networkEvents),
  )

private class RepublishStubLocalProperties : LocalPropertiesRepository {
  override val properties = MutableStateFlow(
    KlardropProperties(deviceId = "selfselfself", customDeviceName = "RepublishTest")
  )
  override suspend fun getProperty(): KlardropProperties = properties.first()
  override suspend fun save(properties: KlardropProperties) {
    this.properties.value = properties
  }
  override suspend fun saveCustomDeviceName(customDeviceName: String?) {
    properties.value = properties.value.copy(customDeviceName = customDeviceName)
  }
  override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
    properties.value = properties.value.copy(backgroundDiscoveryEnabled = enabled)
  }
}

private class RepublishTestCoroutines(testScope: TestScope) : Coroutines {
  private val ctx: CoroutineContext = testScope.coroutineContext
  private val testDispatcher: CoroutineDispatcher = ctx[ContinuationInterceptor] as CoroutineDispatcher

  override fun newScope(): CoroutineScope = CoroutineScope(ctx)
  override fun newScope(context: CoroutineContext): CoroutineScope = CoroutineScope(ctx + context)
  override val appScope: CoroutineScope = CoroutineScope(ctx)
  override val ioDispatcher: CoroutineDispatcher = testDispatcher
  override val mainDispatcher: CoroutineDispatcher = testDispatcher
  override val cpuDispatcher: CoroutineDispatcher = testDispatcher
}
