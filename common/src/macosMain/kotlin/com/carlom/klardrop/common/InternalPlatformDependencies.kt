package com.carlom.klardrop.common

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.features.FallbackClipboardConnectionInfoJoiner
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.network.NetworkChangeEvent
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.notifications.Notifier
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor
import com.carlom.klardrop.common.permissions.PermissionsMonitor
import com.carlom.klardrop.common.trust.AppleTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import kotlinx.io.files.Path
import platform.AppKit.NSApplicationDidBecomeActiveNotification
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceDidWakeNotification
import platform.Foundation.*

public actual class InternalPlatformDependencies(private val applicationInfo: ApplicationInfo) {

  private val documentsDirectory: Path by lazy {
    val directory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSDocumentDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    Path(requireNotNull(directory?.path))
  }

  internal actual fun getDownloadStoragePath(): Path {
    // Drop received files straight into the user's ~/Downloads (no "Klardrop" subfolder).
    // Downloads always exists, so there's nothing to create — which also avoids the sandbox
    // `mkdir` that the downloads entitlement now permits but we no longer need.
    val downloadDirectory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSDownloadsDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    return Path(requireNotNull(downloadDirectory?.path))
  }

  internal actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  private val networkLifecycleMonitor by lazy {
    val monitor = NetworkLifecycleMonitor()
    // macOS native: NSNetService publishes can stall after display sleep or the app
    // loses foreground. Mirror the iOS UIApplicationDidBecomeActive hook with two
    // macOS-specific lifecycle notifications so DiscoveryNetwork.rebuildMdnsState()
    // is always triggered on recovery:
    //   1. NSApplicationDidBecomeActiveNotification — app regains foreground focus.
    //   2. NSWorkspaceDidWakeNotification — system returns from sleep/display wake.
    NSNotificationCenter.defaultCenter.addObserverForName(
      name = NSApplicationDidBecomeActiveNotification,
      `object` = null,
      queue = NSOperationQueue.mainQueue,
    ) { _ ->
      // Focus only: the network didn't change, so keep live sockets (and pending transfers).
      monitor.trigger(NetworkChangeEvent.Resumed)
    }
    NSWorkspace.sharedWorkspace.notificationCenter.addObserverForName(
      name = NSWorkspaceDidWakeNotification,
      `object` = null,
      queue = NSOperationQueue.mainQueue,
    ) { _ ->
      monitor.trigger()
    }
    monitor
  }

  internal actual fun networkLifecycleMonitor(): NetworkLifecycleMonitor = networkLifecycleMonitor

  internal actual fun lanAddressSelector(): com.carlom.klardrop.common.qrshare.LanAddressSelector =
    com.carlom.klardrop.common.qrshare.PlatformLanAddressSelector()

  private val permissionsMonitor by lazy { PermissionsMonitor() }

  internal actual fun permissionsMonitor(): PermissionsMonitor = permissionsMonitor

  private val connectivityRestrictionMonitor by lazy { ConnectivityRestrictionMonitor() }

  internal actual fun connectivityRestrictionMonitor(): ConnectivityRestrictionMonitor = connectivityRestrictionMonitor

  private val notifier by lazy { Notifier() }

  internal actual fun notifier(): Notifier = notifier

  private val foregroundState by lazy { ForegroundState() }

  internal actual fun foregroundState(): ForegroundState = foregroundState

  private val bleTransport by lazy { BleTransport() }

  internal actual fun bleTransport(): BleTransport = bleTransport

  internal actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter()
  }

  internal actual fun connectionInfoJoiner(): ConnectionInfoJoiner {
    return FallbackClipboardConnectionInfoJoiner(clipboardReaderWriter())
  }

  internal actual fun driverFactory(): DriverFactory {
    return DriverFactory()
  }

  internal actual fun trustStorage(): TrustStorage {
    // iOS and macOS share the AppleTrustStorage impl from appleMain — peer keys
    // in NSUserDefaults, the device's own private key in the Keychain.
    return AppleTrustStorage()
  }

  internal actual suspend fun openFile(filePath: String): Boolean {
    // Open the file in the user's default app for its type.
    val fileUrl = NSURL.fileURLWithPath(filePath)
    return NSWorkspace.sharedWorkspace.openURL(fileUrl)
  }

  internal actual suspend fun openUrl(url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    return NSWorkspace.sharedWorkspace.openURL(nsUrl)
  }

  // Native macOS has no "media gallery" concept distinct from Downloads; rely on the
  // regular Downloads-folder save.
  internal actual suspend fun saveMediaToGallery(
    tempPath: kotlinx.io.files.Path,
    mimeType: String,
    displayName: String,
  ): String? = null
}