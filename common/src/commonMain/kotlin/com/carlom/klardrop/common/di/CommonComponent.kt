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
import com.carlom.klardrop.common.discovery.DiscoveryNetwork
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils
import com.carlom.klardrop.common.discovery.NearbyShareDiscoveryUtils
import com.carlom.klardrop.common.discovery.TrustedDevicesDirectory
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.discovery.VisibleDevicesImpl
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.CoroutinesImpl
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.PlatformFileSystemImpl
import kotlinx.serialization.protobuf.ProtoBuf

class CommonComponent(
  private val applicationInfo: ApplicationInfo,
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

  private val coroutines: Coroutines by lazy { CoroutinesImpl() }
  private val clock: Clock by lazy { Clock() }
  private val protoBuf = ProtoBuf
  private val currentDeviceProvider by lazy { CurrentDeviceProvider(localProperties) }
  private val platformFileSystem: PlatformFileSystem by lazy { PlatformFileSystemImpl(internalPlatformDependency, coroutines) }
  private val visibleDevices: VisibleDevices by lazy {
    VisibleDevicesImpl(
      coroutines,
      clock,
      currentDeviceProvider = currentDeviceProvider,
    )
  }

  private val communicationModule by lazy {
    CommunicationModule(
      coroutines = coroutines,
      visibleDevices = visibleDevices,
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

  private val discoveryNetwork by lazy {
    DiscoveryNetwork(
      coroutines,
      visibleDevices,
      internalPlatformDependency.serviceDiscoveryMdns(),
      NearbyShareDiscoveryUtils(),
      KlardropDiscoveryUtils(),
      currentDeviceProvider,
      internalPlatformDependency.bleTransport(),
      internalPlatformDependency.networkLifecycleMonitor(),
    )
  }

  private val clipboardManager by lazy {
    ClipboardManager(coroutines, internalPlatformDependency.clipboardReaderWriter())
  }

  private val fileManager: FileManager
    get() = FileManagerImpl(platformFileSystem)

  private val trustedDevicesDirectory: TrustedDevicesDirectory by lazy {
    TrustedDevicesDirectory(
      visibleDevices = visibleDevices,
      knownDevicesRepository = knownDevicesRepository,
      trustStorage = communicationModule.trustStorage(),
      trustChanges = communicationModule.trustManager().trustChanges,
      coroutines = coroutines,
    )
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

  fun applicationInfo() = applicationInfo

  fun discoveryNetwork() = discoveryNetwork
  fun server() = communicationModule.server()
  fun serverPort() = communicationModule.serverPort()
  fun bleServerListener() = communicationModule.bleServerListener()
  fun bleEagerConnector() = communicationModule.bleEagerConnector()
  fun eagerReachabilityConnector() = communicationModule.eagerReachabilityConnector()
  fun coroutines() = coroutines
  fun visibleDevices() = visibleDevices
  fun messenger() = communicationModule.messenger()
  fun messageReceiver() = communicationModule.messageReceiver()
  fun reachability() = communicationModule.reachability()
  fun client(): Client = communicationModule.client()
  fun connectionsPool(): ConnectionsPool = communicationModule.connectionsPool()

  fun platformFileSystem() = platformFileSystem

  fun clipboardManager() = clipboardManager

  fun messageRepository() = messageRepository

  fun fileManager() = fileManager

  fun trustManager() = communicationModule.trustManager()

  fun incomingAuthorizer() = communicationModule.incomingAuthorizer()

  fun pairingProtocolCoordinator() = communicationModule.pairingProtocolCoordinator()

  fun trustStorage() = communicationModule.trustStorage()

  fun trustedDevicesDirectory() = trustedDevicesDirectory

  fun clipboardSyncManager() = communicationModule.clipboardSyncManager()

  fun currentDeviceProvider() = currentDeviceProvider

  fun localPropertiesRepository() = localProperties

  fun connectionInfoJoiner() = internalPlatformDependency.connectionInfoJoiner()

  fun permissionsMonitor() = internalPlatformDependency.permissionsMonitor()

  fun connectivityRestrictionMonitor() = internalPlatformDependency.connectivityRestrictionMonitor()

  fun notifier() = internalPlatformDependency.notifier()

  fun foregroundState() = internalPlatformDependency.foregroundState()

  fun updateChecker() = updateChecker

  /** Hand a URL to the system handler (browser, etc.). Used by the update banner. */
  suspend fun openUrl(url: String) = internalPlatformDependency.openUrl(url)

}
