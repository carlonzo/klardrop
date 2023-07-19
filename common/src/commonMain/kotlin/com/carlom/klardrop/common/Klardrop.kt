package com.carlom.klardrop.common

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.mdns.NearbyModule
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.UtilsModule
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class Klardrop(
  private val storageModule: StorageModule = StorageModule(),
  private val utilsModule: UtilsModule = UtilsModule(),
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  lateinit var commonComponent: CommonComponent
  private val appScope by lazy { commonComponent.coroutines().appScope }

  fun init() {

    commonComponent = CommonComponent(storageModule, utilsModule, internalPlatformDependency)

    // start server
    val discoveryNetwork = commonComponent.discoveryNetwork()

    appScope.launch(commonComponent.coroutines().ioDispatcher) {
      val serverPort = commonComponent.server().startServer().port

      discoveryNetwork.startPublishKlardrop(serverPort)
    }

    // start nearby share
    appScope.launch(commonComponent.coroutines().ioDispatcher) {
      commonComponent.nearbyServer().start()

      commonComponent.nearbyServer().status
        .filter { it.isRunning }
        .collect {
          discoveryNetwork.startPublishNearbyShare(it.port)
        }
    }

    // start discovery jobs
    discoveryNetwork.discoveryKlardropDevices()
    discoveryNetwork.discoveryNearbyShareDevices()
  }

  fun visibleDevices() = commonComponent.visibleDevices()

}