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
import platform.AppKit.NSWorkspace
import platform.Foundation.*

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
    val downloadDirectory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSDownloadsDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    val klardropStoragePath = Path(requireNotNull(downloadDirectory?.path), "Klardrop")

    if (!SystemFileSystem.exists(klardropStoragePath)) {
      SystemFileSystem.createDirectories(klardropStoragePath, mustCreate = true)
    }

    return klardropStoragePath
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  private val networkLifecycleMonitor by lazy { NetworkLifecycleMonitor() }

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
    return FallbackClipboardConnectionInfoJoiner(clipboardReaderWriter())
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory()
  }

  actual fun trustStorage(): TrustStorage {
    // iOS and macOS share the AppleTrustStorage impl from appleMain — peer keys
    // in NSUserDefaults, the device's own private key in the Keychain.
    return AppleTrustStorage()
  }

  actual suspend fun openFile(filePath: String): Boolean {
    // Open the file in the user's default app for its type.
    val fileUrl = NSURL.fileURLWithPath(filePath)
    return NSWorkspace.sharedWorkspace.openURL(fileUrl)
  }

  actual suspend fun openUrl(url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    return NSWorkspace.sharedWorkspace.openURL(nsUrl)
  }

  // Native macOS has no "media gallery" concept distinct from Downloads; rely on the
  // regular Downloads-folder save.
  actual suspend fun saveMediaToGallery(
    tempPath: kotlinx.io.files.Path,
    mimeType: String,
    displayName: String,
  ): String? = null
}