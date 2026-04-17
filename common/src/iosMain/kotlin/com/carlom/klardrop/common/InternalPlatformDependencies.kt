package com.carlom.klardrop.common

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.features.FallbackClipboardConnectionInfoJoiner
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.IosTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

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
    return IosTrustStorage()
  }

  actual suspend fun openFile(filePath: String): Boolean {
    // iOS file opening requires UIDocumentInteractionController or similar
    // This is a simplified implementation that always returns false
    // A proper implementation would need platform-specific UI integration
    return false
  }
}
