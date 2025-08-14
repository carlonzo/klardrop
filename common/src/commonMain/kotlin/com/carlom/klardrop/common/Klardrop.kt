package com.carlom.klardrop.common

import com.carlom.klardrop.common.di.CommonComponent

import com.carlom.klardrop.common.utils.UtilsModule
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.launch

class Klardrop(
  private val applicationInfo: ApplicationInfo = ApplicationInfo(),
  private val utilsModule: UtilsModule = UtilsModule(),
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  lateinit var commonComponent: CommonComponent
  private val appScope by lazy { commonComponent.coroutines().appScope }

  fun init() {
    if (::commonComponent.isInitialized) throw IllegalStateException("Klardrop already initialized")

    log("Starting Klardrop with ApplicationInfo: $applicationInfo")

    commonComponent = CommonComponent(applicationInfo, utilsModule, internalPlatformDependency)

    val discoveryNetwork = commonComponent.discoveryNetwork()

    // start unified server for both protocols
    if (applicationInfo.enableKlardropServer || applicationInfo.enableNearbyServer) {
      appScope.launch(commonComponent.coroutines().ioDispatcher) {

        val serverConfig = commonComponent.server().startServer()
        val serverPort = serverConfig.port

        // Publish discovery for both protocols on the same port
        if (applicationInfo.enableKlardropServer) {
          discoveryNetwork.startPublishKlardrop(serverPort)
        }
        if (applicationInfo.enableNearbyServer) {
          discoveryNetwork.startPublishNearbyShare(serverPort)
        }
      }
    }
    
    // start clipboard monitoring
    commonComponent.clipboardSyncManager().startClipboardMonitoring()

    // start discovery jobs
    discoveryNetwork.discoveryKlardropDevices()
    discoveryNetwork.discoveryNearbyShareDevices()
//    discoveryNetwork.discoverAirdrop()
  }

  fun visibleDevices() = commonComponent.visibleDevices()

}