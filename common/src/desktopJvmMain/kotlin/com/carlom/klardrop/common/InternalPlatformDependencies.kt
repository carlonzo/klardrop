package com.carlom.klardrop.common

import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.DesktopTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.downloadDir
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.io.files.Path
import java.io.File


actual class InternalPlatformDependencies(private val applicationInfo: ApplicationInfo = ApplicationInfo()) {

  actual fun getDownloadStoragePath(): Path {
    return FileKit.downloadDir.toKotlinxIoPath()
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter()
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
}
