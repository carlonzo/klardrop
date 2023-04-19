package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.discovery.DiscoveryModule
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.UtilsModule
import kotlinx.serialization.protobuf.ProtoBuf

class CommonComponent(
  private val storageModule: StorageModule,
  private val utilsModule: UtilsModule,
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  private val localProperties: LocalPropertiesRepository by lazy {
    storageModule.localPropertiesRepository(
      coroutines, internalPlatformDependency::getRootPath
    )
  }

  private val knownDevicesRepository: KnownDevicesRepository by lazy {
    storageModule.knownDevicesRepository(
      coroutines, internalPlatformDependency::getRootPath
    )
  }

  private val coroutines: Coroutines by lazy { utilsModule.coroutines() }
  private val clock: Clock by lazy { utilsModule.clock() }
  private val protoBuf = ProtoBuf { }

  private val communicationModule by lazy {
    CommunicationModule(
      coroutines,
      knownDevicesRepository,
      localProperties,
      discoveryModule.visibleDevices(),
      protoBuf,
      internalPlatformDependency,
      clock
    )
  }
  private val discoveryModule by lazy {
    DiscoveryModule(
      coroutines, localProperties, internalPlatformDependency, clock
    )
  }

  fun discoveryNetwork() = discoveryModule.discoveryNetwork()
  fun server() = communicationModule.server()
  fun coroutines() = coroutines
  fun localPropertiesRepository() = localProperties

  fun visibleDevices() = discoveryModule.visibleDevices()

  fun knownDevicesRepository() = knownDevicesRepository

  fun messenger() = communicationModule.messenger()

  fun fileResolver() = internalPlatformDependency.fileResolver()
}