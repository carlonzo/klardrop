package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.UtilsModule

internal class DiscoveryModule(
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val internalPlatformDependency: InternalPlatformDependencies,
  private val utilsModule: UtilsModule
) {

  private val visibleDevices: VisibleDevices by lazy { VisibleDevicesImpl(coroutines, utilsModule.clock()) }

  private val discoveryNetwork by lazy {
    DiscoveryNetwork(
      coroutines,
      visibleDevices,
      internalPlatformDependency.serviceDiscoveryMdns(),
      NearbyShareDiscoveryUtils(),
      KlardropDiscoveryUtils(),
      currentDeviceProvider,
      internalPlatformDependency.bleTransport(),
      internalPlatformDependency.networkLifecycleMonitor(),
    )
  }

  fun discoveryNetwork() = discoveryNetwork
  fun visibleDevices() = visibleDevices
}