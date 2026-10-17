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
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.di.StorageModule
import com.carlom.klardrop.common.qrshare.LanHttpShareServer
import com.carlom.klardrop.common.qrshare.LanTlsListener

import com.carlom.klardrop.common.qrshare.QrShareSession
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.CoroutinesImpl
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.PlatformFileSystemImpl
import kotlinx.serialization.protobuf.ProtoBuf

public class CommonComponent(
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
      clock = clock,
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

  public fun applicationInfo(): ApplicationInfo = applicationInfo

  internal fun discoveryNetwork() = discoveryNetwork
  public fun server(): com.carlom.klardrop.common.communication.Server = communicationModule.server()
  internal fun serverPort() = communicationModule.serverPort()
  internal fun bleServerListener() = communicationModule.bleServerListener()
  internal fun bleEagerConnector() = communicationModule.bleEagerConnector()
  internal fun eagerReachabilityConnector() = communicationModule.eagerReachabilityConnector()
  public fun coroutines(): Coroutines = coroutines
  public fun visibleDevices(): VisibleDevices = visibleDevices
  public fun messenger(): com.carlom.klardrop.common.communication.Messenger = communicationModule.messenger()
  public fun messageReceiver(): com.carlom.klardrop.common.receiver.MessageReceiver = communicationModule.messageReceiver()
  public fun reachability(): kotlinx.coroutines.flow.StateFlow<Map<String, com.carlom.klardrop.common.communication.Reachability>> = communicationModule.reachability()
  public fun client(): Client = communicationModule.client()
  public fun connectionsPool(): ConnectionsPool = communicationModule.connectionsPool()

  public fun platformFileSystem(): PlatformFileSystem = platformFileSystem

  public fun clipboardManager(): ClipboardManager = clipboardManager

  public fun messageRepository(): MessageRepository = messageRepository

  public fun fileManager(): FileManager = fileManager

  public fun trustManager(): com.carlom.klardrop.common.trust.TrustManager = communicationModule.trustManager()

  public fun incomingAuthorizer(): com.carlom.klardrop.common.communication.router.IncomingAuthorizer = communicationModule.incomingAuthorizer()

  public fun pairingProtocolCoordinator(): com.carlom.klardrop.common.trust.PairingProtocolCoordinator = communicationModule.pairingProtocolCoordinator()

  internal fun trustStorage() = communicationModule.trustStorage()

  public fun trustedDevicesDirectory(): TrustedDevicesDirectory = trustedDevicesDirectory

  internal fun clipboardSyncManager() = communicationModule.clipboardSyncManager()

  public fun currentDeviceProvider(): CurrentDeviceProvider = currentDeviceProvider

  public fun localPropertiesRepository(): LocalPropertiesRepository = localProperties

  public fun connectionInfoJoiner(): ConnectionInfoJoiner = internalPlatformDependency.connectionInfoJoiner()

  public fun permissionsMonitor(): com.carlom.klardrop.common.permissions.PermissionsMonitor = internalPlatformDependency.permissionsMonitor()

  public fun connectivityRestrictionMonitor(): com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor = internalPlatformDependency.connectivityRestrictionMonitor()

  public fun notifier(): com.carlom.klardrop.common.notifications.Notifier = internalPlatformDependency.notifier()

  public fun foregroundState(): com.carlom.klardrop.common.notifications.ForegroundState = internalPlatformDependency.foregroundState()

  public fun updateChecker(): UpdateChecker = updateChecker

  private val qrShareSession: QrShareSession by lazy {
    val server = LanHttpShareServer(
      coroutines = coroutines,
      fileManager = fileManager,
      tls = LanTlsListener(),
      clock = kotlin.time.Clock.System,
    )
    QrShareSession(
      coroutines = coroutines,
      fileManager = fileManager,
      transferAnchor = transferAnchor,
      lanAddressSelector = internalPlatformDependency.lanAddressSelector(),
      clock = kotlin.time.Clock.System,
      server = server,
    )
  }

  public fun qrShareSession(): QrShareSession = qrShareSession

  /** Hand a URL to the system handler (browser, etc.). Used by the update banner. */
  public suspend fun openUrl(url: String): Boolean = internalPlatformDependency.openUrl(url)

}
