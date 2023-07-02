package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines

class NearbyModule(
  private val coroutines: Coroutines,
  private val internalPlatformDependencies: InternalPlatformDependencies,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val fileManager: FileManager
) {

  private val nearbyServer by lazy {
    NearbyShareServer(
      coroutines,
      NearbyReceiverConnectionHandler(internalPlatformDependencies, fileManager, coroutines),
    )
  }


  fun nearbyServer(): NearbyShareServer {
    return nearbyServer
  }

  fun nearbyClient(): NearbyClient {
    return NearbyClient(
      coroutines,
      currentDeviceProvider,
      internalPlatformDependencies,
      fileManager,
    )
  }

}