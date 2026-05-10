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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

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

          is ServiceDiscoveryEvent.ServiceLost -> onLostService(deviceId, it.serviceInfo, DeviceConnectionType.KLARDROP)

        }

      }.launchIn(discoveryScope)

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

