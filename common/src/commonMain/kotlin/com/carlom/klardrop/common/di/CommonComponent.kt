package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileManagerImpl
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryModule
import com.carlom.klardrop.common.mdns.NearbyModule
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
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
  private val protoBuf = ProtoBuf
  private val currentDeviceProvider by lazy {
    CurrentDeviceProvider(
      localProperties,
      internalPlatformDependency
    )
  }

  private val communicationModule by lazy {
    CommunicationModule(
      coroutines,
      localProperties,
      discoveryModule.visibleDevices(),
      protoBuf,
      clock,
      fileManager,
      nearbyModule.nearbyClient()
    )
  }

  private val discoveryModule by lazy {
    DiscoveryModule(
      coroutines, currentDeviceProvider, internalPlatformDependency
    )
  }

  private val nearbyModule by lazy {
    NearbyModule(
      coroutines, internalPlatformDependency, currentDeviceProvider, fileManager
    )
  }

  private val platformFileSystem: PlatformFileSystem
    get() = internalPlatformDependency.platformFileSystem()

  private val fileManager: FileManager
    get() = FileManagerImpl(platformFileSystem, internalPlatformDependency)


  fun discoveryNetwork() = discoveryModule.discoveryNetwork()
  fun server() = communicationModule.server()
  fun coroutines() = coroutines
  fun visibleDevices() = discoveryModule.visibleDevices()
  fun messenger() = communicationModule.messenger()

  fun platformFileSystem() = platformFileSystem

  fun nearbyServer() = nearbyModule.nearbyServer()

}