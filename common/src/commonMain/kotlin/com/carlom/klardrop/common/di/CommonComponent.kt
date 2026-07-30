package com.carlom.klardrop.common.di

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileManagerImpl
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.KlardropVersion
import com.carlom.klardrop.common.update.UpdateChecker
import com.carlom.klardrop.common.update.createUpdateManifestFetcher
import com.carlom.klardrop.common.update.detectInstallChannel
import com.carlom.klardrop.common.communication.Client
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryModule
import com.carlom.klardrop.common.discovery.TrustAwareDiscoveryUtils
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.MessageOutbox
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
  /**
   * Keeps the host process alive and awake while a file transfer is in flight, in either
   * direction. Supplied by the platform app (Android backs it with a foreground service);
   * defaults to a no-op so tests and headless callers don't have to wire one.
   */
  private val transferAnchor: TransferAnchor = TransferAnchor.None,
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
      networkLifecycleMonitor = internalPlatformDependency.networkLifecycleMonitor(),
      transferAnchor = transferAnchor,
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

  private val updateChecker: UpdateChecker by lazy {
    val channel = KlardropVersion.UPDATE_CHANNEL
    UpdateChecker(
      currentVersion = applicationInfo.appVersion,
      osType = CommonPlatformDependencies.osType(),
      fetcher = createUpdateManifestFetcher(),
      detectChannel = ::detectInstallChannel,
      coroutines = coroutines,
      releaseChannel = channel,
      manifestUrl = UpdateChecker.manifestUrlForChannel(channel),
    )
  }

  fun discoveryNetwork() = discoveryModule.discoveryNetwork()
  fun server() = communicationModule.server()
  fun bleServerListener() = communicationModule.bleServerListener()
  fun bleEagerConnector() = communicationModule.bleEagerConnector()
  fun eagerReachabilityConnector() = communicationModule.eagerReachabilityConnector()
  fun coroutines() = coroutines
  fun visibleDevices() = discoveryModule.visibleDevices()
  fun messenger() = communicationModule.messenger()
  fun messageReceiver() = communicationModule.messageReceiver()
  fun reachability() = communicationModule.reachability()
  fun client(): Client = communicationModule.client()
  fun connectionsPool(): ConnectionsPool = communicationModule.connectionsPool()

  fun platformFileSystem() = platformFileSystem

  fun clipboardManager() = clipboardManager

  fun messageRepository() = messageRepository

  fun messageOutbox(): MessageOutbox = storageModule.messageOutbox

  fun fileManager() = fileManager

  fun trustManager() = communicationModule.trustManager()

  fun pairingProtocolCoordinator() = communicationModule.pairingProtocolCoordinator()

  fun trustStorage() = communicationModule.trustStorage()

  fun trustAwareDiscoveryUtils() = trustAwareDiscoveryUtils

  fun clipboardSyncManager() = communicationModule.clipboardSyncManager()

  fun currentDeviceProvider() = currentDeviceProvider

  fun localPropertiesRepository() = localProperties

  fun connectionInfoJoiner() = internalPlatformDependency.connectionInfoJoiner()

  fun permissionsMonitor() = internalPlatformDependency.permissionsMonitor()

  fun notifier() = internalPlatformDependency.notifier()

  fun foregroundState() = internalPlatformDependency.foregroundState()

  fun updateChecker() = updateChecker

  /** Hand a URL to the system handler (browser, etc.). Used by the update banner. */
  suspend fun openUrl(url: String) = internalPlatformDependency.openUrl(url)

}
