package com.carlom.klardrop.common

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.DiscoveryNetwork
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.UtilsModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class Klardrop(
  private val storageModule: StorageModule = StorageModule(),
  private val utilsModule: UtilsModule = UtilsModule(),
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  private lateinit var commonComponent: CommonComponent

  fun init() {
    commonComponent = CommonComponent(storageModule, utilsModule, internalPlatformDependency)

    initProperties()
  }

  fun discovery() = DiscoveryNetwork(commonComponent.coroutines, commonComponent.localProperties)

  private fun initProperties() {
    commonComponent.coroutines.appScope.launch {
      val localProperties = commonComponent.localProperties

      val initialProperties = localProperties.properties.first()
      if (initialProperties.deviceId.isEmpty()) {
        localProperties.save(initialProperties.copy(deviceId = UUID.randomUUID().toString()))
      }
    }

  }

}