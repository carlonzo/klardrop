package com.carlom.klardrop.common

import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.communication.platformTransferAnchor
import com.carlom.klardrop.common.di.CommonComponent

import com.klardrop.common.CrashReporter
import com.carlom.klardrop.common.utils.installUnhandledExceptionGuard
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.discovery.DiscoveryNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class Klardrop(
  private val applicationInfo: ApplicationInfo = ApplicationInfo(),
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

  /** How hard discovery works. See [setDiscoveryMode]. */
  enum class DiscoveryMode {
    /** Radios idle: no browse, publish, scan, advertise or re-probe. */
    OFF,
    /** Everything on, at a battery-friendly cadence (low-power BLE, slow re-probe). */
    BACKGROUND,
    /** Everything on at full speed — the user is looking at the app. */
    FOREGROUND,
  }

  private val discoveryMode = MutableStateFlow(DiscoveryMode.FOREGROUND)

  /**
   * Drives every radio-using discovery job: mDNS browse + publish, BLE scan + advertise, and the
   * reachability re-probe. The server socket stays open and in-flight transfers are untouched.
   * Android runs [DiscoveryMode.FOREGROUND] while an Activity is visible, [DiscoveryMode.BACKGROUND]
   * while the opt-in "stay discoverable" service holds the process, and [DiscoveryMode.OFF]
   * otherwise. Other platforms never call this and stay in [DiscoveryMode.FOREGROUND].
   */
  fun setDiscoveryMode(mode: DiscoveryMode) {
    discoveryMode.value = mode
  }

  /**
   * Closes every pooled peer connection, which also stops their heartbeats. The caller must make
   * sure nothing is transferring; peers reconnect on demand (eager probe or the next send).
   */
  suspend fun closeIdleConnections() {
    commonComponent.connectionsPool().closeAllConnections()
  }

  fun init() {
    if (::commonComponent.isInitialized) throw IllegalStateException("Klardrop already initialized")

    // Before anything spawns a coroutine: make an uncaught failure in a scope we don't own
    // (Ktor's selector, most notably) a reported error instead of a process abort.
    installUnhandledExceptionGuard()

    log("Starting Klardrop with ApplicationInfo: $applicationInfo")

    commonComponent =
      CommonComponent(applicationInfo, internalPlatformDependency, transferAnchor)

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
        // The live port, which a future server restart moves — the watchdog below must read
        // this rather than the startup value it published from, or it can never see drift.
        val livePort = commonComponent.serverPort()

        // Port-sync watchdog (T4): the mDNS advertisement must always match the
        // live server port. Re-check every 60s: repair the advertisement if the
        // server port drifted (covers any future server restart path), and warn
        // when nothing listens on the advertised port anymore. delay() is
        // virtual-time friendly; no real sleeps. Both calls are no-ops while
        // publishing is stopped.
        launch {
          while (true) {
            delay(60.seconds)
            discoveryNetwork.republishIfPortChanged(livePort.value)
            discoveryNetwork.checkAdvertisedPortAlive()
          }
        }

        // Publish discovery for both protocols on the same port, while discovery is on. mDNS has
        // no power knob, so BACKGROUND and FOREGROUND publish the same way.
        discoveryMode.map { it != DiscoveryMode.OFF }.distinctUntilChanged().collect { active ->
          if (!active) {
            discoveryNetwork.stopPublishMdns()
            return@collect
          }
          if (applicationInfo.enableKlardropServer) {
            discoveryNetwork.startPublishKlardrop(serverPort)
          }
          if (applicationInfo.enableNearbyServer) {
            discoveryNetwork.startPublishNearbyShare(serverPort)
          }
          discoveryNetwork.republishIfPortChanged(livePort.value)
        }
      }
    }
    
    // start clipboard monitoring
    commonComponent.clipboardSyncManager().startClipboardMonitoring()

    // BLE's GATT server stays up (it's idle unless a peer connects); advertising is what makes us
    // findable and goes on/off with discovery below.
    if (applicationInfo.enableBle) {
      commonComponent.bleServerListener()?.start()
      commonComponent.bleEagerConnector()?.start()
    }

    appScope.launch {
      discoveryMode.collect { mode ->
        if (mode == DiscoveryMode.OFF) stopDiscovery(discoveryNetwork)
        else startDiscovery(discoveryNetwork, lowPower = mode == DiscoveryMode.BACKGROUND)
      }
    }

    // Track the paired devices and snapshot their identity while they're discoverable, so a
    // trusted peer still shows up (offline) once it stops announcing. Touched here rather
    // than left to the first UI read so a pairing accepted with no UI attached — Android's
    // background service — still records the peer's name.
    commonComponent.trustedDevicesDirectory()

    // Check for a newer release, then keep re-checking in the background (desktop
    // only; a no-op where unsupported). A desktop session commonly outlives several
    // releases, so a single check at launch would only ever catch the one published
    // before it started.
    commonComponent.updateChecker().start()

    // Tag crash-reporter events with platform + device identity once the device
    // id is resolved (it's persisted lazily on first read).
    appScope.launch(commonComponent.coroutines().ioDispatcher) {
      runCatching {
        val device = commonComponent.currentDeviceProvider().get()
        CrashReporter.setUser(device.shortDeviceId, device.deviceName, device.osType.name)
      }.onFailure { log("Klardrop", "Failed to set crash-reporter user", it) }
    }
  }

  // Browse only the transports this process is willing to use. Called again on every
  // BACKGROUND <-> FOREGROUND switch: a running mDNS browse is left alone, BLE and the re-probe
  // restart at the new cadence.
  private fun startDiscovery(discoveryNetwork: DiscoveryNetwork, lowPower: Boolean) {
    if (applicationInfo.enableKlardropServer && !discoveryNetwork.isBrowsingKlardrop) {
      discoveryNetwork.discoveryKlardropDevices()
    }
    if (applicationInfo.enableNearbyServer && !discoveryNetwork.isBrowsingNearbyShare) {
      discoveryNetwork.discoveryNearbyShareDevices()
    }

    // BLE is a fallback transport for when peers aren't on the same Wi-Fi.
    // Platform implementations return isSupported()=false when unavailable, so these
    // calls are no-ops on targets that don't have a BLE actual yet. Gated independently
    // of the TCP servers so a test can isolate BLE from Klardrop/Nearby.
    if (applicationInfo.enableBle) {
      discoveryNetwork.startPublishBle(lowPower)
      discoveryNetwork.discoverBleDevices(lowPower)
    }

    // Probe TCP-discovered peers as soon as they're announced so "visible"
    // implies "reachable" — without this the user only finds out at send time
    // that the cached mDNS address is dead.
    commonComponent.eagerReachabilityConnector()?.start(lowPower)
  }

  private fun stopDiscovery(discoveryNetwork: DiscoveryNetwork) {
    log("Klardrop", "Discovery inactive: stopping browse, BLE and reachability probes")
    discoveryNetwork.stopBrowsing()
    discoveryNetwork.stopPublishBle()
    commonComponent.eagerReachabilityConnector()?.stop()
  }

  fun visibleDevices() = commonComponent.visibleDevices()

  fun trustedDevices() = commonComponent.trustedDevicesDirectory().trustedDevices

  /** Update-check result, so the desktop tray can surface it with the window hidden. */
  fun updateStatus() = commonComponent.updateChecker().status

  /** Self-update progress, so the tray can offer the restart that applies it. */
  fun updateInstallProgress() = commonComponent.updateChecker().install

}