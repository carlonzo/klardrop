package com.carlom.klardrop.common

import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.*

actual class InternalPlatformDependencies {

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

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter()
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory()
  }

  actual suspend fun openFile(filePath: String): Boolean {
    // iOS file opening requires UIDocumentInteractionController or similar
    // This is a simplified implementation that always returns false
    // A proper implementation would need platform-specific UI integration
    return false
  }
}