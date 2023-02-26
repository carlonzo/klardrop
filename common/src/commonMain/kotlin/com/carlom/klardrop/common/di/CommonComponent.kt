package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.discovery.DiscoveryMessenger
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.UtilsModule

class CommonComponent(
  private val storageModule: StorageModule,
  private val utilsModule: UtilsModule,
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  val localProperties: LocalPropertiesRepository by lazy { storageModule.localPropertiesRepository(coroutines, internalPlatformDependency::getRootPath) }
  val knownDevicesRepository: KnownDevicesRepository by lazy { storageModule.knownDevicesRepository(coroutines, internalPlatformDependency::getRootPath) }
  val coroutines: Coroutines by lazy { utilsModule.coroutines() }

  val discoveryMessenger: DiscoveryMessenger
    get() = DiscoveryMessenger(utilsModule.coroutines(), localProperties, internalPlatformDependency.getDeviceName(), internalPlatformDependency.deviceType())

}