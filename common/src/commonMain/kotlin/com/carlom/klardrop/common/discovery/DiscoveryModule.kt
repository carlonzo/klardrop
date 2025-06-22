package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.utils.Coroutines

internal class DiscoveryModule(
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val internalPlatformDependency: InternalPlatformDependencies,
) {

  private val visibleDevices: VisibleDevices by lazy { VisibleDevicesImpl(coroutines) }

  private val discoveryNetwork by lazy {
    DiscoveryNetwork(
      coroutines,
      visibleDevices,
      internalPlatformDependency.serviceDiscoveryMdns(),
      NearbyShareDiscoveryUtils(),
      KlardropDiscoveryUtils(),
      currentDeviceProvider
    )
  }

  fun discoveryNetwork() = discoveryNetwork
  fun visibleDevices() = visibleDevices
}