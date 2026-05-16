package com.carlom.klardrop.common

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.features.FallbackClipboardConnectionInfoJoiner
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.notifications.Notifier
import com.carlom.klardrop.common.permissions.PermissionsMonitor
import com.carlom.klardrop.common.trust.AppleTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification

actual class InternalPlatformDependencies(private val applicationInfo: ApplicationInfo) {

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

  actual fun getDownloadStoragePath(): Path {

    val klardropStoragePath = Path(documentsDirectory, "Klardrop")

    if (!SystemFileSystem.exists(klardropStoragePath)) {
      SystemFileSystem.createDirectories(klardropStoragePath, mustCreate = true)
    }

    return klardropStoragePath
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  private val networkLifecycleMonitor by lazy {
    val monitor = NetworkLifecycleMonitor()
    // iOS suspends NSNetService publishes when the app goes inactive (screen lock,
    // backgrounded). They don't auto-resume on becomeActive — DiscoveryNetwork has to
    // re-issue them. Hook the foreground transition into the existing
    // rebuildMdnsState path by emitting a synthetic NetworkChangeEvent.Changed.
    NSNotificationCenter.defaultCenter.addObserverForName(
      name = UIApplicationDidBecomeActiveNotification,
      `object` = null,
      queue = NSOperationQueue.mainQueue,
    ) { _ ->
      monitor.trigger()
    }
    monitor
  }

  actual fun networkLifecycleMonitor(): NetworkLifecycleMonitor = networkLifecycleMonitor

  private val permissionsMonitor by lazy { PermissionsMonitor() }

  actual fun permissionsMonitor(): PermissionsMonitor = permissionsMonitor

  private val notifier by lazy { Notifier() }

  actual fun notifier(): Notifier = notifier

  private val foregroundState by lazy { ForegroundState() }

  actual fun foregroundState(): ForegroundState = foregroundState

  private val bleTransport by lazy { BleTransport() }

  actual fun bleTransport(): BleTransport = bleTransport

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter()
  }

  actual fun connectionInfoJoiner(): ConnectionInfoJoiner {
    // iOS path TODO: NEHotspotConfiguration + NEHotspotConfigurationManager. For now we
    // reuse the clipboard fallback so the UX still surfaces the password cleanly.
    return FallbackClipboardConnectionInfoJoiner(clipboardReaderWriter())
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory()
  }

  actual fun trustStorage(): TrustStorage {
    return AppleTrustStorage()
  }

  actual suspend fun openFile(filePath: String): Boolean {
    // iOS file opening requires UIDocumentInteractionController or similar
    // This is a simplified implementation that always returns false
    // A proper implementation would need platform-specific UI integration
    return false
  }

  actual suspend fun openUrl(url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    val app = UIApplication.sharedApplication
    if (!app.canOpenURL(nsUrl)) return false
    app.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    return true
  }

  // iOS keeps using FileKit.saveImageToGallery (no tracked path); the caller falls back.
  actual suspend fun saveMediaToGallery(
    tempPath: kotlinx.io.files.Path,
    mimeType: String,
    displayName: String,
  ): String? = null
}
