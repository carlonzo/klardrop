package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.discovery.DeviceConnection.DeviceConnectionType
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.ATTRIBUTE_DEVICE
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.ATTRIBUTE_DEVICE_NAME
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.KLARDROP_SERVICE_TYPE
import com.carlom.klardrop.common.discovery.NearbyShareDiscoveryUtils.Companion.NEARBY_SERVICE_TYPE
import com.carlom.klardrop.common.mdns.ServiceDiscoveryEvent
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
  private val currentDeviceProvider: CurrentDeviceProvider
) {

  private val discoveryScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val currentDevice = discoveryScope.async(coroutines.ioDispatcher) { currentDeviceProvider.get() }

  private var nearbySharePublishJob: Job? = null
  private var klardropPublishJob: Job? = null
  private var nearbySharePort: Int? = null
  private var klardropPort: Int? = null
  private var deviceFlowSubscription: Job? = null


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
    
    deviceFlowSubscription = currentDeviceProvider.deviceInfoFlow
      .onEach { deviceInfo ->
        log("DiscoveryNetwork", "Device info changed: ${deviceInfo.deviceName}")
        // Republish services if they were previously started
        nearbySharePort?.let { port -> republishNearbyShare(port, deviceInfo) }
        klardropPort?.let { port -> republishKlardrop(port, deviceInfo) }
      }
      .launchIn(discoveryScope)
  }


  fun discoveryNearbyShareDevices() {

    serviceDiscoveryMdns.discoverServices(NEARBY_SERVICE_TYPE)
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

    serviceDiscoveryMdns.discoverServices(KLARDROP_SERVICE_TYPE)
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

  fun discoverAirdrop() {

    discoveryScope.launch {

      serviceDiscoveryMdns.discoverServices("_airdrop._tcp.local.")
        .collect {
          println("Discovered airdrop: $it")
        }

    }


  }

  private suspend fun onDiscoveredService(serviceInfo: ServiceInfo, connectionType: DeviceConnectionType) {
    serviceInfo.addresses.forEach { address ->

      val deviceConnection = when (connectionType) {
        DeviceConnectionType.NEARBY -> DeviceConnection.NearbyConnection(address, serviceInfo.port)
        DeviceConnectionType.KLARDROP -> DeviceConnection.KlardropConnection(address, serviceInfo.port)
      }

      val deviceInfo = when (connectionType) {
        DeviceConnectionType.NEARBY -> nearbyShareDiscoveryUtils.toDeviceInfo(serviceInfo)
        DeviceConnectionType.KLARDROP -> klardropDiscoveryUtils.toDeviceInfo(serviceInfo)
      }

      visibleDevices.onNewDeviceVisible(deviceInfo, deviceConnection)
    }
  }

  private fun onLostService(deviceId: String, serviceInfo: ServiceInfo, connectionType: DeviceConnectionType) {
    if (serviceInfo.addresses.isNotEmpty()) {
      serviceInfo.addresses.forEach { address ->
        val deviceConnection = when (connectionType) {
          DeviceConnectionType.NEARBY -> DeviceConnection.NearbyConnection(address, serviceInfo.port)
          DeviceConnectionType.KLARDROP -> DeviceConnection.KlardropConnection(address, serviceInfo.port)
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

