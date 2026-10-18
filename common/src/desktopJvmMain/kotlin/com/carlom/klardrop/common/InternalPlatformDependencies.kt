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
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor
import com.carlom.klardrop.common.permissions.PermissionsMonitor
import com.carlom.klardrop.common.trust.DesktopTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.downloadDir
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.io.files.Path
import java.io.File


public actual class InternalPlatformDependencies(private val applicationInfo: ApplicationInfo) {

  internal actual fun getDownloadStoragePath(): Path {
    return FileKit.downloadDir.toKotlinxIoPath()
  }

  internal actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  private val networkLifecycleMonitor by lazy { NetworkLifecycleMonitor() }

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
    return ClipboardReaderWriter(useInMemory = applicationInfo.disableSystemClipboard)
  }

  internal actual fun connectionInfoJoiner(): ConnectionInfoJoiner {
    return FallbackClipboardConnectionInfoJoiner(clipboardReaderWriter())
  }

  internal actual fun driverFactory(): DriverFactory {
    return DriverFactory(FileKit.databasesDir.toKotlinxIoPath(), applicationInfo.disablePersistence)
  }

  internal actual fun trustStorage(): TrustStorage {
    // When KLARDROP_HOME env or klardrop.data.dir system property is set (e.g. by the CLI
    // --data-dir flag), root trust storage there so each process gets an isolated identity.
    // Otherwise fall back to the traditional ~/.klardrop directory.
    val dataDirOverride = System.getProperty("klardrop.data.dir")
      ?: System.getenv("KLARDROP_HOME")
    val appDir = if (dataDirOverride != null) {
      File(dataDirOverride, "trust")
    } else {
      File(System.getProperty("user.home"), ".klardrop")
    }
    appDir.mkdirs()
    return DesktopTrustStorage(appDir)
  }

  internal actual suspend fun openFile(filePath: String): Boolean {
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

  internal actual suspend fun openUrl(url: String): Boolean {
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
  internal actual suspend fun saveMediaToGallery(
    tempPath: Path,
    mimeType: String,
    displayName: String,
  ): String? = null
}
