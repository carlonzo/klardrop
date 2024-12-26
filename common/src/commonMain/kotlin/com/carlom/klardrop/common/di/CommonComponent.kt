package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileManagerImpl
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryModule
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.UtilsModule
import kotlinx.serialization.protobuf.ProtoBuf

class CommonComponent(
  private val applicationInfo: ApplicationInfo,
  private val utilsModule: UtilsModule,
  private val internalPlatformDependency: InternalPlatformDependencies
) {

  private val storageModule: StorageModule by lazy {
    StorageModule(
      applicationInfo,
      utilsModule.coroutines(),
      internalPlatformDependency
    )
  }

  private val localProperties: LocalPropertiesRepository by lazy {
    storageModule.localPropertiesRepository()
  }

  private val knownDevicesRepository: KnownDevicesRepository by lazy {
    storageModule.knownDevicesRepository()
  }

  private val coroutines: Coroutines by lazy { utilsModule.coroutines() }
  private val clock: Clock by lazy { utilsModule.clock() }
  private val protoBuf = ProtoBuf
  private val currentDeviceProvider by lazy {
    CurrentDeviceProvider(
      localProperties
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
      currentDeviceProvider
    )
  }

  private val discoveryModule by lazy {
    DiscoveryModule(
      coroutines, currentDeviceProvider, internalPlatformDependency
    )
  }

  private val clipboardManager by lazy {
    ClipboardManager(coroutines, internalPlatformDependency.clipboardReaderWriter())
  }

  private val platformFileSystem: PlatformFileSystem
    get() = internalPlatformDependency.platformFileSystem()

  private val fileManager: FileManager
    get() = FileManagerImpl(platformFileSystem)


  fun discoveryNetwork() = discoveryModule.discoveryNetwork()
  fun server() = communicationModule.server()
  fun coroutines() = coroutines
  fun visibleDevices() = discoveryModule.visibleDevices()
  fun messenger() = communicationModule.messenger()

  fun platformFileSystem() = platformFileSystem

  fun nearbyServer() = communicationModule.nearbyServer()

  fun clipboardManager() = clipboardManager

}