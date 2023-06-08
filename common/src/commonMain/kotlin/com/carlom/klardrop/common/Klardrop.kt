package com.carlom.klardrop.common

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.UUIDGenerator
import com.carlom.klardrop.common.utils.UtilsModule
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class Klardrop(
  private val storageModule: StorageModule = StorageModule(),
  private val utilsModule: UtilsModule = UtilsModule(),
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  lateinit var commonComponent: CommonComponent

  fun init() {
    commonComponent = CommonComponent(storageModule, utilsModule, internalPlatformDependency)

    initProperties()

    // start discovery
//    commonComponent.discoveryNetwork().start()

    // start server
    commonComponent.server().startServer()

    commonComponent.nearbyShare().startDiscovery()
  }

  fun visibleDevices() = commonComponent.visibleDevices()


  private fun initProperties() {
    commonComponent.coroutines().appScope.launch {
      val localProperties = commonComponent.localPropertiesRepository()

      val initialProperties = localProperties.properties.first()
      if (initialProperties.deviceId.isEmpty()) {
        localProperties.save(initialProperties.copy(deviceId = UUIDGenerator().generate()))
      }
    }

  }

}