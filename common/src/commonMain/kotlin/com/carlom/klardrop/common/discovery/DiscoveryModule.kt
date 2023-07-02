package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.network.NetworkAddressUtil

internal class DiscoveryModule(
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val internalPlatformDependency: InternalPlatformDependencies,
  private val clock: Clock
) {

  private val discoveryMessenger by lazy {
    DiscoveryMessenger(
      coroutines, currentDeviceProvider
    )
  }

  private val visibleDevices: VisibleDevices by lazy { VisibleDevicesImpl(coroutines) }

  private val discoveryNetwork by lazy {
    DiscoveryNetwork(coroutines, discoveryMessenger(), visibleDevices(), socketBroadcastUtility(), clock)
  }

  private fun socketBroadcastUtility() = SocketBroadcastUtility(coroutines, NetworkAddressUtil())
  private fun discoveryMessenger() = discoveryMessenger
  fun discoveryNetwork() = discoveryNetwork
  fun visibleDevices() = visibleDevices
  fun serviceDiscoveryMdns() = internalPlatformDependency.serviceDiscoveryMdns()
}