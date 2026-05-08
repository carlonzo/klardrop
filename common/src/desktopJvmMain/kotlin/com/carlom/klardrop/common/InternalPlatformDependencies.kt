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
import com.carlom.klardrop.common.trust.DesktopTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.downloadDir
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.io.files.Path
import java.io.File


actual class InternalPlatformDependencies(private val applicationInfo: ApplicationInfo) {

  actual fun getDownloadStoragePath(): Path {
    return FileKit.downloadDir.toKotlinxIoPath()
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
    return DriverFactory(FileKit.databasesDir.toKotlinxIoPath(), applicationInfo.disablePersistence)
  }

  actual fun trustStorage(): TrustStorage {
    // Use user's home directory/.klardrop for storing trust data
    val appDir = File(System.getProperty("user.home"), ".klardrop")
    return DesktopTrustStorage(appDir)
  }

  actual suspend fun openFile(filePath: String): Boolean {
    return try {
      val file = File(filePath)
      if (file.exists()) {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
          java.awt.Desktop.getDesktop().open(file)
          true
        } else {
          false
        }
      } else {
        false
      }
    } catch (e: Exception) {
      false
    }
  }

  actual suspend fun openUrl(url: String): Boolean {
    return try {
      if (!java.awt.Desktop.isDesktopSupported()) return false
      val desktop = java.awt.Desktop.getDesktop()
      if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
        desktop.browse(java.net.URI(url))
        true
      } else {
        false
      }
    } catch (e: Exception) {
      false
    }
  }

  // Desktop has no "media gallery" concept; rely on the regular Downloads-folder save.
  actual suspend fun saveMediaToGallery(
    tempPath: Path,
    mimeType: String,
    displayName: String,
  ): String? = null
}
