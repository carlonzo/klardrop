package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.mdns.NearbyShare
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.SingletonProvider
import com.carlom.klardrop.common.utils.network.NetworkAddressUtil

internal class DiscoveryModule(
  private val coroutines: Coroutines,
  private val localProperties: LocalPropertiesRepository,
  private val internalPlatformDependency: InternalPlatformDependencies,
  private val clock: Clock
) {

  private val discoveryMessenger = SingletonProvider {
    DiscoveryMessenger(
      coroutines, localProperties, internalPlatformDependency.getDeviceName(), internalPlatformDependency.deviceType()
    )
  }

  private val visibleDevices = SingletonProvider<VisibleDevices> { VisibleDevicesImpl(coroutines, clock) }

  private val discoveryNetwork = SingletonProvider {
    DiscoveryNetwork(coroutines, discoveryMessenger(), visibleDevices(), socketBroadcastUtility())
  }

  private val nearbyShare = SingletonProvider {
    NearbyShare(
      internalPlatformDependency.serviceDiscoveryMdns(),
      internalPlatformDependency.getDeviceName(),
      internalPlatformDependency.deviceType()
    )
  }


  private fun socketBroadcastUtility() = SocketBroadcastUtility(coroutines, NetworkAddressUtil())
  private fun discoveryMessenger() = discoveryMessenger.get()
  fun discoveryNetwork() = discoveryNetwork.get()
  fun visibleDevices() = visibleDevices.get()
  fun nearbyShare() = nearbyShare.get()
}