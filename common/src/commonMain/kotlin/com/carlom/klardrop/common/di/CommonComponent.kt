package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileManagerImpl
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryModule
import com.carlom.klardrop.common.discovery.TrustAwareDiscoveryUtils
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.MessageRepository
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
      platformFileSystem,
      internalPlatformDependency.driverFactory(),
      clock
    )
  }

  private val localProperties: LocalPropertiesRepository by lazy {
    storageModule.localPropertiesRepository()
  }

  private val knownDevicesRepository: KnownDevicesRepository by lazy {
    storageModule.knownDevicesRepository()
  }

  private val messageRepository: MessageRepository by lazy {
    storageModule.messageRepository()
  }

  private val coroutines: Coroutines by lazy { utilsModule.coroutines() }
  private val clock: Clock by lazy { utilsModule.clock() }
  private val protoBuf = ProtoBuf
  private val currentDeviceProvider by lazy { CurrentDeviceProvider(localProperties) }
  private val platformFileSystem: PlatformFileSystem by lazy { PlatformFileSystemImpl(internalPlatformDependency, coroutines) }

  private val communicationModule by lazy {
    CommunicationModule(
      coroutines = coroutines,
      visibleDevices = discoveryModule.visibleDevices(),
      protoBuf = protoBuf,
      clock = clock,
      fileManager = fileManager,
      currentDeviceProvider = currentDeviceProvider,
      messageRepository = messageRepository,
      clipboardManager = clipboardManager,
      trustStorage = internalPlatformDependency.trustStorage(),
      bleTransport = internalPlatformDependency.bleTransport(),
    )
  }

  private val discoveryModule by lazy {
    DiscoveryModule(
      coroutines, currentDeviceProvider, internalPlatformDependency, utilsModule
    )
  }

  private val clipboardManager by lazy {
    ClipboardManager(coroutines, internalPlatformDependency.clipboardReaderWriter())
  }

  private val fileManager: FileManager
    get() = FileManagerImpl(platformFileSystem)

  private val trustAwareDiscoveryUtils: TrustAwareDiscoveryUtils by lazy {
    TrustAwareDiscoveryUtils(communicationModule.trustManager())
  }

  fun discoveryNetwork() = discoveryModule.discoveryNetwork()
  fun server() = communicationModule.server()
  fun bleServerListener() = communicationModule.bleServerListener()
  fun coroutines() = coroutines
  fun visibleDevices() = discoveryModule.visibleDevices()
  fun messenger() = communicationModule.messenger()

  fun platformFileSystem() = platformFileSystem

  fun clipboardManager() = clipboardManager

  fun messageRepository() = messageRepository

  fun fileManager() = fileManager

  fun trustManager() = communicationModule.trustManager()

  fun pairingProtocolCoordinator() = communicationModule.pairingProtocolCoordinator()

  fun trustStorage() = communicationModule.trustStorage()

  fun trustAwareDiscoveryUtils() = trustAwareDiscoveryUtils

  fun clipboardSyncManager() = communicationModule.clipboardSyncManager()

  fun currentDeviceProvider() = currentDeviceProvider

  fun localPropertiesRepository() = localProperties

  fun connectionInfoJoiner() = internalPlatformDependency.connectionInfoJoiner()

}
