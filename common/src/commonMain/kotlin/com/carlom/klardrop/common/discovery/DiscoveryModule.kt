package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.mdns.NearbyShare
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.SingletonProvider
import com.carlom.klardrop.common.utils.network.NetworkAddressUtil

internal class DiscoveryModule(
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val internalPlatformDependency: InternalPlatformDependencies,
  private val clock: Clock
) {

  private val discoveryMessenger = SingletonProvider {
    DiscoveryMessenger(
      coroutines, currentDeviceProvider
    )
  }

  private val visibleDevices = SingletonProvider<VisibleDevices> { VisibleDevicesImpl(coroutines, clock) }

  private val discoveryNetwork = SingletonProvider {
    DiscoveryNetwork(coroutines, discoveryMessenger(), visibleDevices(), socketBroadcastUtility())
  }

  private fun socketBroadcastUtility() = SocketBroadcastUtility(coroutines, NetworkAddressUtil())
  private fun discoveryMessenger() = discoveryMessenger.get()
  fun discoveryNetwork() = discoveryNetwork.get()
  fun visibleDevices() = visibleDevices.get()
  fun serviceDiscoveryMdns() = internalPlatformDependency.serviceDiscoveryMdns()
}