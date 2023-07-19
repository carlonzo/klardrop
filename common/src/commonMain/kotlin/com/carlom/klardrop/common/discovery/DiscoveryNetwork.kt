package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.discovery.DeviceConnection.DeviceConnectionType
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils.Companion.KLARDROP_SERVICE_TYPE
import com.carlom.klardrop.common.discovery.NearbyShareDiscoveryUtils.Companion.NEARBY_SERVICE_TYPE
import com.carlom.klardrop.common.mdns.ServiceDiscoveryEvent
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Service that keeps emitting pings to announce availability and discover new devices or update info of the known ones
 */
class DiscoveryNetwork internal constructor(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val serviceDiscoveryMdns: ServiceDiscoveryMdns,
  private val nearbyShareDiscoveryUtils: NearbyShareDiscoveryUtils,
  private val klardropDiscoveryUtils: KlardropDiscoveryUtils,
  private val currentDeviceProvider: CurrentDeviceProvider
) {

  private val discoveryScope = CoroutineScope(coroutines.ioDispatcher)
  private val currentDevice = discoveryScope.async(coroutines.ioDispatcher) { currentDeviceProvider.get() }

  private var nearbySharePublishJob: Job? = null
  private var klardropPublishJob: Job? = null


  fun startPublishNearbyShare(port: Int) {

    nearbySharePublishJob?.cancel()
    nearbySharePublishJob = discoveryScope.launch {

      val registerServiceInfo = nearbyShareDiscoveryUtils.buildServiceInfo(port, currentDevice.await())

      serviceDiscoveryMdns.registerService(registerServiceInfo)
    }

  }

  fun startPublishKlardrop(port: Int) {
    klardropPublishJob?.cancel()
    klardropPublishJob = discoveryScope.launch {

      val registerServiceInfo = klardropDiscoveryUtils.provideRegisterServiceInfo(port, currentDevice.await())

      serviceDiscoveryMdns.registerService(registerServiceInfo)
    }
  }


  fun discoveryNearbyShareDevices() {

    discoveryScope.launch {
      serviceDiscoveryMdns.discoverServices(NEARBY_SERVICE_TYPE).collect {

        log("DiscoveryNetwork", "New discovery event for NearbyShare: $it")

        val deviceId = nearbyShareDiscoveryUtils.getDeviceId(it.serviceInfo)

        if (deviceId == currentDevice.await().shortDeviceId) {
          log("DiscoveryNetwork", "Ignoring own service: ${it.serviceInfo}")
          return@collect
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
    }

  }

  fun discoveryKlardropDevices() {

    discoveryScope.launch {
      serviceDiscoveryMdns.discoverServices(KLARDROP_SERVICE_TYPE).collect {
        log("DiscoveryNetwork", "New discovery event for Klardrop: $it")

        val deviceId = klardropDiscoveryUtils.getDeviceId(it.serviceInfo)

        if (deviceId == currentDevice.await().shortDeviceId) {
          log("DiscoveryNetwork", "Ignoring own service: ${it.serviceInfo}")
          return@collect
        }

        when (it) {

          is ServiceDiscoveryEvent.ServiceFound -> if (klardropDiscoveryUtils.isValidService(it.serviceInfo)) {
            onDiscoveredService(it.serviceInfo, DeviceConnectionType.KLARDROP)
          } else {
            log("DiscoveryNetwork", "Invalid service found for Klardrop: ${it.serviceInfo}")
          }

          is ServiceDiscoveryEvent.ServiceLost -> onLostService(deviceId, it.serviceInfo, DeviceConnectionType.KLARDROP)

        }

      }
    }

  }

  private suspend fun onDiscoveredService(serviceInfo: ServiceInfo, connectionType: DeviceConnectionType) {
    serviceInfo.addresses.forEach { address ->

      val deviceConnection = when (connectionType) {
        DeviceConnectionType.NEARBY -> DeviceConnection.Nearby(address, serviceInfo.port)
        DeviceConnectionType.KLARDROP -> DeviceConnection.Klardrop(address, serviceInfo.port)
      }

      val deviceInfo = when (connectionType) {
        DeviceConnectionType.NEARBY -> nearbyShareDiscoveryUtils.toDeviceInfo(serviceInfo)
        DeviceConnectionType.KLARDROP -> klardropDiscoveryUtils.toDeviceInfo(serviceInfo)
      }

      visibleDevices.onNewDeviceVisible(deviceInfo, deviceConnection)
    }
  }

  private suspend fun onLostService(deviceId: String, serviceInfo: ServiceInfo, connectionType: DeviceConnectionType) {
    if (serviceInfo.addresses.isNotEmpty()) {
      serviceInfo.addresses.forEach { address ->
        val deviceConnection = when (connectionType) {
          DeviceConnectionType.NEARBY -> DeviceConnection.Nearby(address, serviceInfo.port)
          DeviceConnectionType.KLARDROP -> DeviceConnection.Klardrop(address, serviceInfo.port)
        }
        visibleDevices.onDeviceLost(deviceId, deviceConnection)
      }
    } else {
      visibleDevices.onDeviceLost(deviceId)
    }
  }

}

