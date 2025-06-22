package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileManagerImpl
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryModule
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.PlatformFileSystemImpl
import com.carlom.klardrop.common.utils.UtilsModule
import kotlinx.serialization.protobuf.ProtoBuf

class CommonComponent(
  private val applicationInfo: ApplicationInfo,
  private val utilsModule: UtilsModule,
  private val internalPlatformDependency: InternalPlatformDependencies,
) {

  private val storageModule: StorageModule by lazy {
    StorageModule(
      applicationInfo,
      coroutines,
      platformFileSystem
    )
  }

  private val localProperties: LocalPropertiesRepository by lazy {
    storageModule.localPropertiesRepository()
  }

  private val coroutines: Coroutines by lazy { utilsModule.coroutines() }
  private val clock: Clock by lazy { utilsModule.clock() }
  private val protoBuf = ProtoBuf
  private val currentDeviceProvider by lazy { CurrentDeviceProvider(localProperties) }
  private val platformFileSystem: PlatformFileSystem by lazy { PlatformFileSystemImpl(internalPlatformDependency, coroutines) }

  private val communicationModule by lazy {
    CommunicationModule(
      coroutines,
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

  private val fileManager: FileManager
    get() = FileManagerImpl(platformFileSystem)


  fun discoveryNetwork() = discoveryModule.discoveryNetwork()
  fun unifiedServer() = communicationModule.unifiedServer()
  fun coroutines() = coroutines
  fun visibleDevices() = discoveryModule.visibleDevices()
  fun messenger() = communicationModule.messenger()

  fun platformFileSystem() = platformFileSystem

  fun clipboardManager() = clipboardManager

}