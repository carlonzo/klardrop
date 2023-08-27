package com.carlom.klardrop.common

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.UtilsModule
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class Klardrop(
  private val applicationInfo: ApplicationInfo = ApplicationInfo(),
  private val storageModule: StorageModule = StorageModule(applicationInfo),
  private val utilsModule: UtilsModule = UtilsModule(),
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  lateinit var commonComponent: CommonComponent
  private val appScope by lazy { commonComponent.coroutines().appScope }

  fun init() {
    if (::commonComponent.isInitialized) throw IllegalStateException("Klardrop already initialized")

    log("Starting Klardrop with ApplicationInfo: $applicationInfo")

    commonComponent = CommonComponent(storageModule, utilsModule, internalPlatformDependency)

    val discoveryNetwork = commonComponent.discoveryNetwork()

    // start server

    if (applicationInfo.enableKlardropServer) {
      appScope.launch(commonComponent.coroutines().ioDispatcher) {
        val serverPort = commonComponent.server().startServer().port

        discoveryNetwork.startPublishKlardrop(serverPort)
      }
    }

    if (applicationInfo.enableNearbyServer) {
      // start nearby share
      appScope.launch(commonComponent.coroutines().ioDispatcher) {
        commonComponent.nearbyServer().start()

        commonComponent.nearbyServer().status
          .filter { it.isRunning }
          .collect {
            discoveryNetwork.startPublishNearbyShare(it.port)
          }
      }
    }

    // start discovery jobs
    discoveryNetwork.discoveryKlardropDevices()
    discoveryNetwork.discoveryNearbyShareDevices()
  }

  fun visibleDevices() = commonComponent.visibleDevices()

}