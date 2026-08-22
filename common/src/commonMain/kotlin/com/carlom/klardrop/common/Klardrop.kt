package com.carlom.klardrop.common

import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.communication.platformTransferAnchor
import com.carlom.klardrop.common.di.CommonComponent

import com.klardrop.common.CrashReporter
import com.carlom.klardrop.common.utils.UtilsModule
import com.carlom.klardrop.common.utils.installUnhandledExceptionGuard
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.launch

class Klardrop(
  private val applicationInfo: ApplicationInfo = ApplicationInfo(),
  private val utilsModule: UtilsModule = UtilsModule(),
  private val internalPlatformDependency: InternalPlatformDependencies,
  /**
   * Platform hook that keeps this process alive and awake while a file transfer is in flight, in
   * either direction. Android passes a foreground-service-backed anchor because that one needs a
   * manifest-declared service; the Apple targets build their own from `common` (see
   * [platformTransferAnchor]) and desktop doesn't need one.
   */
  private val transferAnchor: TransferAnchor = platformTransferAnchor(),
) {

  lateinit var commonComponent: CommonComponent
  private val appScope by lazy { commonComponent.coroutines().appScope }

  fun init() {
    if (::commonComponent.isInitialized) throw IllegalStateException("Klardrop already initialized")

    // Before anything spawns a coroutine: make an uncaught failure in a scope we don't own
    // (Ktor's selector, most notably) a reported error instead of a process abort.
    installUnhandledExceptionGuard()

    log("Starting Klardrop with ApplicationInfo: $applicationInfo")

    commonComponent =
      CommonComponent(applicationInfo, utilsModule, internalPlatformDependency, transferAnchor)

    // Recover from a prior crash/kill: nothing is actually transferring at boot, so any
    // file_transfers row left as IN_PROGRESS is stale. Without this, those rows render
    // forever as a "0 B of N MB" pending bubble in chat with no terminal state.
    appScope.launch(commonComponent.coroutines().ioDispatcher) {
      runCatching { commonComponent.messageRepository().markStaleInProgressAsFailed() }
        .onFailure { log("Klardrop", "Failed to sweep stale IN_PROGRESS transfers", it) }
    }

    // Same recovery, for outgoing TEXT: a row can only be left SENDING by a crash/kill between
    // Messenger's up-front insert and its single terminal SENT/FAILED flip. Without this a message
    // sent right before a kill would show a permanent "sending…" spinner on next launch.
    appScope.launch(commonComponent.coroutines().ioDispatcher) {
      runCatching { commonComponent.messageRepository().markStaleSendingAsFailed() }
        .onFailure { log("Klardrop", "Failed to sweep stale SENDING messages", it) }
    }

    val discoveryNetwork = commonComponent.discoveryNetwork()

    // start unified server for both protocols
    if (applicationInfo.enableKlardropServer || applicationInfo.enableNearbyServer) {
      appScope.launch(commonComponent.coroutines().ioDispatcher) {

        val serverConfig = commonComponent.server().startServer()
        val serverPort = serverConfig.port

        // Publish discovery for both protocols on the same port
        if (applicationInfo.enableKlardropServer) {
          discoveryNetwork.startPublishKlardrop(serverPort)
        }
        if (applicationInfo.enableNearbyServer) {
          discoveryNetwork.startPublishNearbyShare(serverPort)
        }
      }
    }
    
    // start clipboard monitoring
    commonComponent.clipboardSyncManager().startClipboardMonitoring()

    // start discovery jobs
    discoveryNetwork.discoveryKlardropDevices()
    discoveryNetwork.discoveryNearbyShareDevices()
//    discoveryNetwork.discoverAirdrop()

    // BLE is a fallback transport for when peers aren't on the same Wi-Fi.
    // Platform implementations return isSupported()=false when unavailable, so these
    // calls are no-ops on targets that don't have a BLE actual yet.
    if (applicationInfo.enableKlardropServer) {
      discoveryNetwork.startPublishBle()
      commonComponent.bleServerListener()?.start()
    }
    discoveryNetwork.discoverBleDevices()
    // BLE is one discovery medium alongside mDNS. To populate the friendly
    // identity (name + OS + device type) for BLE-only peers without waiting on
    // user action, the role-selector-picked initiator opens an eager GATT
    // session as soon as a BLE peer is discovered. The other transports
    // (mDNS/Klardrop, Nearby) continue to work in parallel; for transfers, the
    // Client picks the best available transport and falls back to BLE only
    // when no Wi-Fi reachability exists.
    commonComponent.bleEagerConnector()?.start()

    // Probe TCP-discovered peers as soon as they're announced so "visible"
    // implies "reachable" — without this the user only finds out at send time
    // that the cached mDNS address is dead.
    commonComponent.eagerReachabilityConnector()?.start()

    // Track the paired devices and snapshot their identity while they're discoverable, so a
    // trusted peer still shows up (offline) once it stops announcing. Touched here rather
    // than left to the first UI read so a pairing accepted with no UI attached — Android's
    // background service — still records the peer's name.
    commonComponent.trustedDevicesDirectory()

    // Check for a newer release (desktop only; a no-op where unsupported).
    commonComponent.updateChecker().checkNow()

    // Tag crash-reporter events with platform + device identity once the device
    // id is resolved (it's persisted lazily on first read).
    appScope.launch(commonComponent.coroutines().ioDispatcher) {
      runCatching {
        val device = commonComponent.currentDeviceProvider().get()
        CrashReporter.setUser(device.shortDeviceId, device.deviceName, device.osType.name)
      }.onFailure { log("Klardrop", "Failed to set crash-reporter user", it) }
    }
  }

  fun visibleDevices() = commonComponent.visibleDevices()

}