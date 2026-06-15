package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.discovery.DeviceConnection.DeviceConnectionType
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.ATTRIBUTE_DEVICE
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.ATTRIBUTE_DEVICE_NAME
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.KLARDROP_SERVICE_TYPE
import com.carlom.klardrop.common.discovery.NearbyShareDiscoveryUtils.Companion.NEARBY_SERVICE_TYPE
import com.carlom.klardrop.common.mdns.ServiceDiscoveryEvent
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Service that keeps emitting pings to announce availability and discover new devices or update info of the known ones
 */
class DiscoveryNetwork internal constructor(
  coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val serviceDiscoveryMdns: ServiceDiscoveryMdns,
  private val nearbyShareDiscoveryUtils: NearbyShareDiscoveryUtils,
  private val klardropDiscoveryUtils: KlardropDiscoveryUtils,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val bleTransport: BleTransport,
  private val networkLifecycleMonitor: NetworkLifecycleMonitor,
) {

  private val discoveryScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val currentDevice = discoveryScope.async(coroutines.ioDispatcher) { currentDeviceProvider.get() }

  private var nearbySharePublishJob: Job? = null
  private var klardropPublishJob: Job? = null
  private var nearbySharePort: Int? = null
  private var klardropPort: Int? = null
  private var deviceFlowSubscription: Job? = null

  private var bleAdvertiseJob: Job? = null
  private var bleScanJob: Job? = null

  private var klardropDiscoveryJob: Job? = null
  private var nearbyDiscoveryJob: Job? = null
  private var lifecycleSubscription: Job? = null

  /**
   * How many times the klardrop browse has been started (initial + restarts).
   * Exposed as `internal` for the repro test to observe that browse-restarts
   * actually fire. Not intended for production callers.
   */
  internal val klardropBrowseStartCount = MutableStateFlow(0)

  // --- browse-restart state ---

  /**
   * Running debounce job — cancelled when a second trigger arrives within the
   * debounce window, collapsing concurrent triggers to a single restart.
   */
  private var browseRestartDebounceJob: Job? = null

  /** Monitors visible-devices flow and triggers browse-refresh on peer churn. */
  private var peerLossWatchJob: Job? = null

  /**
   * Periodic backstop job — while a peer lacks Klardrop transport, retries the
   * browse restart on [BROWSE_BACKSTOP_INTERVAL]. Bounded by
   * [BROWSE_BACKSTOP_MAX_RETRIES]; cancelled when the peer regains a Klardrop
   * connection. See [startKlardropBrowseRestartGuards].
   */
  private var periodicBackstopJob: Job? = null

  init {
    startNetworkLifecycleSubscription()
  }

  /**
   * On a [com.carlom.klardrop.common.network.NetworkChangeEvent] (NIC up/down,
   * sleep/wake, address change) we tear down the mDNS internals and re-issue
   * every active discovery + publish. Without this, after sleep on JVM the
   * jmDNS instances stay bound to stale sockets and silently stop receiving
   * announcements. Restarting + re-launching the discovery jobs is the
   * blunt-but-reliable recovery.
   */
  private fun startNetworkLifecycleSubscription() {
    if (lifecycleSubscription != null) return
    lifecycleSubscription = networkLifecycleMonitor.observe()
      .onEach {
        log("DiscoveryNetwork", "Network change detected; rebuilding mDNS state")
        rebuildMdnsState()
      }
      .launchIn(discoveryScope)
  }

  private suspend fun rebuildMdnsState() {
    runCatching { serviceDiscoveryMdns.restart() }
      .onFailure { log("DiscoveryNetwork", "mDNS restart failed: ${it.message}") }

    // Re-launch any active discovery flows so they bind to the freshly
    // rebuilt mDNS instances.
    if (klardropDiscoveryJob != null) discoveryKlardropDevices()
    if (nearbyDiscoveryJob != null) discoveryNearbyShareDevices()

    // Re-publish active services on the same ports.
    klardropPort?.let { republishKlardrop(it) }
    nearbySharePort?.let { republishNearbyShare(it) }
  }


  fun startPublishNearbyShare(port: Int) {
    log("DiscoveryNetwork", "startPublishNearbyShare $port")
    nearbySharePort = port
    
    startDeviceFlowSubscriptionIfNeeded()
    republishNearbyShare(port)
  }
  
  private fun republishNearbyShare(port: Int, deviceInfo: CurrentDevice? = null) {
    nearbySharePublishJob?.cancel()
    nearbySharePublishJob = discoveryScope.launch {
      val currentDeviceInfo = deviceInfo ?: currentDeviceProvider.get()
      val registerServiceInfo = nearbyShareDiscoveryUtils.getRegisterServiceInfo(port, currentDeviceInfo)
      serviceDiscoveryMdns.registerService(registerServiceInfo)
    }
  }

  fun startPublishKlardrop(port: Int) {
    log("DiscoveryNetwork", "startPublishKlardrop $port")
    klardropPort = port
    startDeviceFlowSubscriptionIfNeeded()
    republishKlardrop(port)
  }
  
  private fun republishKlardrop(port: Int, deviceInfo: CurrentDevice? = null) {
    klardropPublishJob?.cancel()
    klardropPublishJob = discoveryScope.launch {
      val currentDeviceInfo = deviceInfo ?: currentDeviceProvider.get()
      val registerServiceInfo = klardropDiscoveryUtils.getRegisterServiceInfo(port, currentDeviceInfo)
      serviceDiscoveryMdns.registerService(registerServiceInfo)
    }
  }
  
  private fun startDeviceFlowSubscriptionIfNeeded() {
    if (deviceFlowSubscription != null) return

    // distinctUntilChanged: the LocalPropertiesRepository flow re-emits on every
    // DataStore tick — including the cold-start sequence where deviceId starts empty,
    // gets generated and saved (re-emit), then customDeviceName loads (re-emit). Each
    // emission was triggering a republish, and since NSNetService publish/stop are
    // both async, the iPad ended up with N concurrent advertisements named `xxx`,
    // `xxx (2)`, `xxx (3)`, … on the wire. Skipping no-op emissions keeps publishes
    // serialized to actual identity changes (rename, new install).
    deviceFlowSubscription = currentDeviceProvider.deviceInfoFlow
      .distinctUntilChanged()
      .onEach { deviceInfo ->
        log("DiscoveryNetwork", "Device info changed: ${deviceInfo.deviceName}")
        // Republish services if they were previously started
        nearbySharePort?.let { port -> republishNearbyShare(port, deviceInfo) }
        klardropPort?.let { port -> republishKlardrop(port, deviceInfo) }
      }
      .launchIn(discoveryScope)
  }


  fun discoveryNearbyShareDevices() {

    nearbyDiscoveryJob?.cancel()
    nearbyDiscoveryJob = serviceDiscoveryMdns.discoverServices(NEARBY_SERVICE_TYPE)
      .onCompletion { log("DiscoveryNetwork", "Discovery completed for Nearby discovery") }
      .onEach {

//        log("DiscoveryNetwork", "New discovery event for NearbyShare: $it")

        val deviceId = nearbyShareDiscoveryUtils.getDeviceId(it.serviceInfo)

        if (deviceId == currentDevice.await().shortDeviceId) {
//          log("DiscoveryNetwork", "Ignoring own service: ${it.serviceInfo}")
          return@onEach
        }

        when (it) {

          is ServiceDiscoveryEvent.ServiceFound -> if (nearbyShareDiscoveryUtils.isValidService(it.serviceInfo)) {
            onDiscoveredService(it.serviceInfo, DeviceConnectionType.NEARBY)
          } else {
            log("DiscoveryNetwork", "Invalid service found for Nearby: ${it.serviceInfo}")
          }

          is ServiceDiscoveryEvent.ServiceLost -> onLostService(deviceId, it.serviceInfo, DeviceConnectionType.NEARBY)

        }

      }
      .launchIn(discoveryScope)

  }

  fun discoveryKlardropDevices() {

    klardropDiscoveryJob?.cancel()
    klardropBrowseStartCount.update { it + 1 }
    log("DiscoveryNetwork", "Starting klardrop browse #${klardropBrowseStartCount.value}")

    klardropDiscoveryJob = serviceDiscoveryMdns.discoverServices(KLARDROP_SERVICE_TYPE)
      .onCompletion { log("DiscoveryNetwork", "Discovery completed for Klardrop discovery") }
      .onEach {
        log("DiscoveryNetwork", "New discovery event for Klardrop: $it")

        val deviceId = klardropDiscoveryUtils.getDeviceId(it.serviceInfo)

        if (deviceId == currentDevice.await().shortDeviceId) {
//            log("DiscoveryNetwork", "Ignoring own service: ${it.serviceInfo}")
          return@onEach
        }

        when (it) {

          is ServiceDiscoveryEvent.ServiceFound -> if (klardropDiscoveryUtils.isValidService(it.serviceInfo)) {
            onDiscoveredService(it.serviceInfo, DeviceConnectionType.KLARDROP)
          } else {
            log("DiscoveryNetwork", "Invalid service found for Klardrop: ${it.serviceInfo}")
          }

          is ServiceDiscoveryEvent.ServiceLost -> {
            onLostService(deviceId, it.serviceInfo, DeviceConnectionType.KLARDROP)
            // A ServiceLost from mDNS may indicate the peer dropped to Wi-Fi power-save.
            // Restart the browse so NsdManager re-evaluates the peer when it wakes
            // (wedged 'found' sessions are cleared by re-subscribe).
            requestKlardropDiscoveryRefresh("ServiceLost for $deviceId")
          }

        }

      }.launchIn(discoveryScope)

    // Start the browse-restart guard jobs once (idempotent).
    startKlardropBrowseRestartGuards()
  }

  /**
   * Idempotently starts the browse-restart triggers:
   *   1. Peer-loss watcher: observes [visibleDevices] and fires [requestKlardropDiscoveryRefresh]
   *      when ANY visible peer currently has no Klardrop endpoint (BLE-only or Nearby-only).
   *   2. Periodic backstop: while any peer lacks Klardrop transport, retries the browse restart
   *      on [BROWSE_BACKSTOP_INTERVAL], bounded by [BROWSE_BACKSTOP_MAX_RETRIES]. Stops when
   *      the peer regains a Klardrop connection. This handles the silent-peer case where the
   *      reactive watcher's single restart did not recover the peer.
   *
   * This covers:
   *   (a) A ServiceLost from mDNS removes the peer's klardrop endpoint — the watcher fires
   *       and schedules a debounced browse restart so a wedged NsdManager session is cleared.
   *   (b) The 5-min TTL sweep (VisibleDevicesImpl) removes a stale klardrop endpoint —
   *       the watcher fires and schedules a restart so the peer can be re-discovered.
   *   (c) Endpoint-invalidation (connect/handshake timeout) removes a dead klardrop
   *       endpoint — same watcher path, same recovery.
   *   (d) A peer with NO prior klardrop endpoint (BLE-only from the start) triggers the
   *       watcher as soon as it appears, attempting an mDNS rediscovery. Useful for peers
   *       that advertise over BLE but may also be on the LAN.
   *
   * Uses [distinctUntilChanged] on the reactive watcher to suppress re-firing when the set
   * of BLE-only peers hasn't changed. The periodic backstop is gated: it starts only while
   * peers WITHOUT klardrop transport remain, and stops once all peers recover or the retry
   * cap is reached (so a legitimately BLE-only/Nearby-only peer doesn't cause perpetual churn).
   */
  private fun startKlardropBrowseRestartGuards() {

    // 1. Peer-loss watcher: watch the set of "klardrop-endpoint-less peer IDs".
    // When any peer has no KlardropConnection, request a debounced browse refresh.
    // Also controls the periodic backstop: starts it when non-klardrop peers appear,
    // stops it when they all recover.
    if (peerLossWatchJob?.isActive != true) {
      peerLossWatchJob = visibleDevices.visibleDevices
        .map { snapshot ->
          // Emit the set of deviceIds that currently have NO Klardrop connection but ARE visible.
          snapshot.values
            .filter { device -> !device.hasKlardropConnection() }
            .map { it.deviceInfo.deviceId }
            .toSet()
        }
        .distinctUntilChanged()
        .onEach { noKlardropIds ->
          if (noKlardropIds.isNotEmpty()) {
            log("DiscoveryNetwork", "${noKlardropIds.size} peer(s) without klardrop endpoint — requesting browse refresh")
            requestKlardropDiscoveryRefresh("peer(s) without klardrop endpoint: $noKlardropIds")
            // Start the periodic backstop if not already running.
            startPeriodicBackstopIfNeeded()
          } else {
            // All peers have recovered Klardrop transport — stop the periodic backstop.
            stopPeriodicBackstop("all peers recovered klardrop transport")
          }
        }
        .launchIn(discoveryScope)
    }
  }

  /**
   * Starts the periodic backstop loop if it isn't already running.
   *
   * The backstop retries [requestKlardropDiscoveryRefresh] every [BROWSE_BACKSTOP_INTERVAL]
   * while at least one peer is missing a Klardrop connection, for up to
   * [BROWSE_BACKSTOP_MAX_RETRIES] iterations. After the cap is reached it stops
   * automatically so a legitimately BLE/Nearby-only peer does NOT cause perpetual restarts.
   *
   * The backstop is GATED: each iteration re-checks whether non-Klardrop peers still exist
   * before firing. This means if the peer is recovered between intervals the backstop fires
   * no further restarts even before being explicitly stopped.
   */
  private fun startPeriodicBackstopIfNeeded() {
    if (periodicBackstopJob?.isActive == true) return  // already running

    periodicBackstopJob = discoveryScope.launch {
      var retries = 0
      while (retries < BROWSE_BACKSTOP_MAX_RETRIES) {
        delay(BROWSE_BACKSTOP_INTERVAL)
        retries++

        // Gate: only retry if there are still peers without Klardrop transport.
        val nonKlardropPeers = visibleDevices.visibleDevices.value.values
          .filter { !it.hasKlardropConnection() }
          .map { it.deviceInfo.deviceId }
          .toSet()

        if (nonKlardropPeers.isEmpty()) {
          log("DiscoveryNetwork", "periodic backstop: all peers recovered; stopping after $retries retry(ies)")
          break
        }

        log(
          "DiscoveryNetwork",
          "periodic backstop retry $retries/$BROWSE_BACKSTOP_MAX_RETRIES: ${nonKlardropPeers.size} peer(s) still without klardrop endpoint"
        )
        requestKlardropDiscoveryRefresh("periodic backstop retry $retries for peers: $nonKlardropPeers")
      }

      if (retries >= BROWSE_BACKSTOP_MAX_RETRIES) {
        log("DiscoveryNetwork", "periodic backstop: reached retry cap ($BROWSE_BACKSTOP_MAX_RETRIES); stopping to avoid battery drain")
      }
    }
  }

  private fun stopPeriodicBackstop(reason: String) {
    if (periodicBackstopJob?.isActive == true) {
      log("DiscoveryNetwork", "periodic backstop: stopping ($reason)")
      periodicBackstopJob?.cancel()
      periodicBackstopJob = null
    }
  }

  /**
   * Request a klardrop browse restart, subject to a >= [BROWSE_RESTART_DEBOUNCE] debounce.
   *
   * Uses a delay-based debounce: each call cancels any pending debounce job and
   * schedules a new one. Only the LAST trigger within the debounce window causes a
   * restart. Concurrent calls collapse to a single restart after the debounce delay.
   *
   * Browse-only — intentionally does NOT republish to avoid `name (2)` churn.
   *
   * Because it uses [delay] for the debounce, it is compatible with virtual-time
   * test schedulers ([kotlinx.coroutines.test.TestScope]).
   */
  internal fun requestKlardropDiscoveryRefresh(reason: String = "") {
    if (klardropDiscoveryJob == null) return  // browsing not active; nothing to restart

    browseRestartDebounceJob?.cancel()
    browseRestartDebounceJob = discoveryScope.launch {
      log("DiscoveryNetwork", "browse refresh scheduled (reason: $reason); debounce ${BROWSE_RESTART_DEBOUNCE}")
      delay(BROWSE_RESTART_DEBOUNCE)
      log("DiscoveryNetwork", "browse refresh: restarting klardrop browse ($reason)")
      discoveryKlardropDevices()
    }
  }

  fun startPublishBle() {
    log("DiscoveryNetwork", "startPublishBle")
    bleAdvertiseJob?.cancel()
    bleAdvertiseJob = discoveryScope.launch {
      if (!bleTransport.isSupported()) {
        log("DiscoveryNetwork", "BLE not supported on this platform/device; skipping advertising")
        return@launch
      }
      runCatching { bleTransport.startAdvertising(currentDeviceProvider.get()) }
        .onFailure { log("DiscoveryNetwork", "BLE advertise failed: ${it.message}") }
    }
  }

  fun stopPublishBle() {
    bleAdvertiseJob?.cancel()
    bleAdvertiseJob = null
    discoveryScope.launch { runCatching { bleTransport.stopAdvertising() } }
  }

  fun discoverBleDevices() {
    if (bleScanJob?.isActive == true) return
    bleScanJob = discoveryScope.launch {
      if (!bleTransport.isSupported()) {
        log("DiscoveryNetwork", "BLE not supported; skipping scan")
        return@launch
      }
      bleTransport.scanForPeers()
        .onCompletion { log("DiscoveryNetwork", "BLE scan completed") }
        .onEach { event ->
          val selfId = currentDevice.await().shortDeviceId
          when (event) {
            is BlePeerEvent.Found -> {
              if (event.shortDeviceId == selfId) return@onEach
              val deviceInfo = DeviceInfo(
                deviceId = event.shortDeviceId,
                name = event.localName ?: event.shortDeviceId,
                deviceType = DeviceType.UNKNOWN,
                osType = OsType.UNKNOWN,
              )
              visibleDevices.onNewDeviceVisible(deviceInfo, DeviceConnection.BleConnection(event.address))
            }
            is BlePeerEvent.Lost -> {
              val device = visibleDevices.visibleDevices.value.values
                .firstOrNull { d -> d.deviceConnections.any { it is DeviceConnection.BleConnection && it.address == event.address } }
              if (device != null) {
                visibleDevices.onDeviceLost(device.deviceInfo.deviceId, DeviceConnection.BleConnection(event.address))
              }
            }
          }
        }
        .launchIn(discoveryScope)
    }
  }

  fun discoverAirdrop() {

    discoveryScope.launch {

      serviceDiscoveryMdns.discoverServices("_airdrop._tcp.local.")
        .collect {
          println("Discovered airdrop: $it")
        }

    }


  }

  private suspend fun onDiscoveredService(serviceInfo: ServiceInfo, connectionType: DeviceConnectionType) {
    serviceInfo.addresses.filter { it.isReachableAddress() }.forEach { address ->

      val deviceConnection = when (connectionType) {
        DeviceConnectionType.NEARBY -> DeviceConnection.NearbyConnection(address, serviceInfo.port)
        DeviceConnectionType.KLARDROP -> DeviceConnection.KlardropConnection(address, serviceInfo.port)
        DeviceConnectionType.BLE -> error("BLE connections are not discovered via mDNS")
      }

      val deviceInfo = when (connectionType) {
        DeviceConnectionType.NEARBY -> nearbyShareDiscoveryUtils.toDeviceInfo(serviceInfo)
        DeviceConnectionType.KLARDROP -> klardropDiscoveryUtils.toDeviceInfo(serviceInfo)
        DeviceConnectionType.BLE -> error("BLE connections are not discovered via mDNS")
      }

      visibleDevices.onNewDeviceVisible(deviceInfo, deviceConnection)
    }
  }

  private fun onLostService(deviceId: String, serviceInfo: ServiceInfo, connectionType: DeviceConnectionType) {
    val reachableAddresses = serviceInfo.addresses.filter { it.isReachableAddress() }
    if (reachableAddresses.isNotEmpty()) {
      reachableAddresses.forEach { address ->
        val deviceConnection = when (connectionType) {
          DeviceConnectionType.NEARBY -> DeviceConnection.NearbyConnection(address, serviceInfo.port)
          DeviceConnectionType.KLARDROP -> DeviceConnection.KlardropConnection(address, serviceInfo.port)
          DeviceConnectionType.BLE -> error("BLE connections are not discovered via mDNS")
        }
        visibleDevices.onDeviceLost(deviceId, deviceConnection)
      }
    } else {
      visibleDevices.onDeviceLost(deviceId)
    }
  }

  companion object {

    /**
     * Minimum gap between consecutive klardrop browse restarts (debounce).
     * Two restart-triggers within this window collapse to a single restart.
     * Uses [delay]-based debouncing so it is compatible with virtual-time test
     * schedulers ([kotlinx.coroutines.test.TestScope]).
     */
    val BROWSE_RESTART_DEBOUNCE = 30.seconds

    /**
     * How long the periodic backstop waits between retry attempts while a peer
     * is missing its Klardrop transport. Chosen to be at least 2x the debounce
     * window so backstop retries don't collide with reactive-watcher restarts.
     */
    val BROWSE_BACKSTOP_INTERVAL: Duration = 60.seconds

    /**
     * Maximum number of periodic backstop retries before giving up.
     * At [BROWSE_BACKSTOP_INTERVAL] = 60s, 5 retries = ~5 minutes of recovery window.
     * After this cap, a peer that has not recovered is assumed to be legitimately
     * BLE/Nearby-only and no further browse restarts are issued (avoids battery drain).
     */
    const val BROWSE_BACKSTOP_MAX_RETRIES = 5

    private val test_device = ServiceDiscoveryEvent.ServiceFound(
      ServiceInfo(
        port = 0,
        serviceName = "Test_device",
        serviceType = KLARDROP_SERVICE_TYPE,
        attributes = mapOf(
          ATTRIBUTE_DEVICE_NAME to urlSafeBase64EncodedString("Test device"),
          ATTRIBUTE_DEVICE to (DeviceType.MOBILE.nearbyId.toInt() shl 4).or(OsType.ANDROID.nearbyId.toInt()).toString()
        ),
        addresses = listOf("192.168.1.1")
      )
    )
  }
}

